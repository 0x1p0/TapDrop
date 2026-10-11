import AppKit
import CryptoKit
import Foundation
import Network
import UniformTypeIdentifiers

/// Something the Mac sends to the phone.
enum OutItem {
    case file(url: URL, name: String, clip: Bool)
    case text(String, clip: Bool)
}

/// The TapDrop listener: Bonjour-advertised TCP server that phones pair with and talk to.
final class Server: @unchecked Sendable {
    static let shared = Server()
    static let preferredPort: UInt16 = 47913
    static let serviceType = "_tapdrop._tcp"

    private let queue = DispatchQueue(label: "app.tapdrop.net")
    private var listener: NWListener?
    private var macId = ""
    private var macName = ""
    private let autoPair = ProcessInfo.processInfo.environment["TAPDROP_TEST_AUTOPAIR"] == "1"

    @MainActor func start() {
        macId = Store.shared.macId
        macName = Store.computerName
        let port = ProcessInfo.processInfo.environment["TAPDROP_PORT"].flatMap(UInt16.init) ?? Self.preferredPort
        queue.async { self.listen(on: port) }
    }

    private func listen(on port: UInt16?) {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            tcp.noDelay = true
            // Notice vanished phones (Wi-Fi dropped, phone asleep) on long-lived watch connections.
            tcp.enableKeepalive = true
            tcp.keepaliveIdle = 10
            tcp.keepaliveInterval = 5
            tcp.keepaliveCount = 3
        }
        let l: NWListener
        do {
            if let port, let p = NWEndpoint.Port(rawValue: port) {
                l = try NWListener(using: params, on: p)
            } else {
                l = try NWListener(using: params)
            }
        } catch {
            NSLog("TapDrop: listener init failed: \(error)")
            if port != nil { listen(on: nil) }
            return
        }
        var txt = NWTXTRecord()
        txt["id"] = macId
        txt["v"] = "1"
        l.service = NWListener.Service(name: macName, type: Self.serviceType, domain: nil, txtRecord: txt)
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self else { return }
            switch state {
            case .ready:
                let p = l?.port?.rawValue ?? 0
                NSLog("TapDrop: listening on port \(p)")
                Task { @MainActor in
                    Store.shared.port = p
                    Store.shared.status = "Ready"
                }
            case .failed(let err):
                NSLog("TapDrop: listener failed: \(err)")
                l?.cancel()
                Task { @MainActor in Store.shared.status = "Restarting…" }
                // Preferred port busy → take any port (Bonjour still advertises it).
                self.queue.asyncAfter(deadline: .now() + (port == nil ? 3 : 0.2)) { self.listen(on: nil) }
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] nw in
            guard let self else { nw.cancel(); return }
            nw.start(queue: self.queue)
            let conn = Conn(nw)
            Task { await self.handle(conn) }
        }
        l.start(queue: queue)
        listener = l
    }

    private func handle(_ conn: Conn) async {
        defer { conn.close() }
        do {
            let first = try await conn.readJSON()
            switch first.type {
            case "pair": try await handlePair(conn, first)
            case "hello": try await handleSession(conn, first)
            default: throw TDError.proto("unknown opener \(first.type)")
            }
        } catch {
            NSLog("TapDrop: connection from \(conn.remoteHost) ended: \(error.localizedDescription)")
        }
    }

    // MARK: pairing

    private func handlePair(_ conn: Conn, _ m: JSON) async throws {
        guard let deviceId = m.str("device"), let name = m.str("name"),
              let pubB64 = m.str("pub"), let phonePub = Data(base64Encoded: pubB64)
        else { throw TDError.proto("bad pair request") }

        let peer = try P256.KeyAgreement.PublicKey(derRepresentation: phonePub)
        let mine = P256.KeyAgreement.PrivateKey()
        let shared = try mine.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        let macPub = mine.publicKey.derRepresentation
        let secret = PairingSecret(shared: shared, phonePub: phonePub, macPub: macPub)

        try await conn.writeJSON([
            "t": "pair-wait", "v": 1, "mac": macId, "name": macName, "pub": macPub.base64EncodedString(),
        ])
        NSLog("TapDrop: pairing request from \(name), code \(secret.code)")

        let approved: Bool
        if autoPair {
            approved = true
        } else {
            approved = await PairPrompt.ask(device: name, code: secret.code, conn: conn)
        }
        guard approved else {
            try? await conn.writeJSON(["t": "pair-no"])
            return
        }
        await MainActor.run { Store.shared.addDevice(id: deviceId, name: name, key: secret.key) }
        try await conn.writeJSON(["t": "pair-ok"])
        await MainActor.run {
            Activity.shared.notice(
                .paired, title: "Paired with \(name)", detail: "Share anything to “\(macName)” from your phone",
                peer: name, hideAfter: 5)
        }
    }

    // MARK: sessions

    private func handleSession(_ conn: Conn, _ m: JSON) async throws {
        guard let deviceId = m.str("device"), let nb = m.str("nonce"),
              let phoneNonce = Data(base64Encoded: nb), phoneNonce.count == 16
        else { throw TDError.proto("bad hello") }

        guard let dev = await MainActor.run(body: { Store.shared.device(deviceId) }) else {
            try await conn.writeJSON(["t": "error", "code": "unpaired"])
            return
        }
        let macNonce = TDCrypto.random(16)
        try await conn.writeJSON([
            "t": "hello", "v": 1, "mac": macId, "name": macName, "nonce": macNonce.base64EncodedString(),
        ])
        let ch = SecureChannel(
            conn: conn,
            key: TDCrypto.sessionKey(pairKey: dev.key, phoneNonce: phoneNonce, macNonce: macNonce),
            sendDir: SecureChannel.dirMacToPhone,
            recvDir: SecureChannel.dirPhoneToMac)

        let req = try await ch.recvJSON()  // throws .decrypt if the key is wrong
        await MainActor.run { Store.shared.touch(deviceId) }

        switch req.type {
        case "ping":
            let pending = await MainActor.run { Store.shared.outbox.count }
            try await ch.sendJSON(["t": "pong", "name": macName, "pending": pending])
        case "send":
            try await receiveBatch(ch, header: req, from: dev.name)
        case "getclip":
            try await sendClipboard(ch, to: dev.name)
        case "inbox":
            try await sendInbox(ch, to: dev.name)
        case "watch":
            try await runWatch(ch, device: dev)
        default:
            throw TDError.proto("unknown request \(req.type)")
        }
    }

    // MARK: live link

    private func runWatch(_ ch: SecureChannel, device: PairedDevice) async throws {
        let id = WatchHub.shared.add(deviceId: device.id, name: device.name, channel: ch)
        defer { WatchHub.shared.remove(id) }
        let pending = await MainActor.run { Store.shared.outbox.count }
        ch.sendJSONNow(["t": "watching", "pending": pending, "notch": NotchSensor.shared.available])
        while true {
            let m = try await ch.recvJSON()
            switch m.type {
            case "ka":
                ch.sendJSONNow(["t": "ka"])
                await MainActor.run { Store.shared.touch(device.id) }
            case "grab-ack" where !m.bool("ok"):
                if TestMode.on { NSLog("TapDrop[test]: grab declined by phone (%@)", m.str("why") ?? "?") }
                let why = m.str("why") ?? ""
                await MainActor.run {
                    if let say = TapLog.shared.declined(device.name, why: why) {
                        Activity.shared.notice(.queued, title: say, detail: "Then hold it over the notch again", sound: false, hideAfter: 2.6)
                    }
                }
            case "grab-ack" where m.bool("ok"):
                // The phone is on its way — open the island right away so the tap feels instant.
                let kind = m.str("kind")
                let bySpot = m.bool("spot")
                await MainActor.run {
                    TapLog.shared.accepted(device.name, kind: kind ?? "", spot: bySpot)
                    if kind == "inbox" {
                        // The phone is collecting what's queued here; that transfer shows its own progress.
                    } else if kind == "pick" {
                        Activity.shared.beginPick(peer: device.name)
                    } else {
                        Activity.shared.begin(
                            incoming: true, peer: device.name,
                            title: kind == "link" ? "Opening from \(device.name)" : "Receiving from \(device.name)")
                    }
                }
            case "grab-cancel":
                await MainActor.run { Activity.shared.dismiss() }

            default:
                break
            }
        }
    }

    // MARK: phone → Mac

    private func receiveBatch(_ ch: SecureChannel, header: JSON, from peer: String) async throws {
        let total = header.int("total") ?? -1
        let count = Int(header.int("count") ?? 0)
        let folder = await MainActor.run { Store.shared.saveFolder }
        var hudShown = false

        var saved: [URL] = []
        var ocrImages: [URL] = []
        var texts: [(text: String, clip: Bool, otp: String?)] = []
        var clipImage: URL?
        var received: Int64 = 0
        let throttle = ProgressThrottle()
        var partial: IncomingFile?

        do {
            batch: while true {
                let msg = try await ch.recvJSON()
                switch msg.type {
                case "file":
                    let name = FileNames.sanitize(msg.str("name") ?? "file")
                    let ocr = msg.bool("ocr")
                    if !hudShown {
                        // Only files get a progress island; texts/codes go straight to their result.
                        hudShown = true
                        let mime = msg.str("mime") ?? ""
                        await MainActor.run {
                            if ocr { Activity.shared.reading(peer: peer) }
                            else { Activity.shared.arriving(name: name, mime: mime, peer: peer, count: count) }
                        }
                    }
                    let f = try IncomingFile(folder: ocr ? OutgoingFiles.tempDir() : folder, name: name)
                    partial = f
                    file: while true {
                        switch try await ch.recv() {
                        case .data(let d):
                            try f.write(d)
                            received += Int64(d.count)
                            let done = received
                            if !ocr { throttle.tick { Activity.shared.update(detail: "From \(peer)", done: done, total: total) } }
                        case .json(let j):
                            guard j.type == "eof" else { throw TDError.proto("expected eof, got \(j.type)") }
                            break file
                        }
                    }
                    let url = try f.finish()
                    partial = nil
                    if ocr {
                        ocrImages.append(url)
                    } else {
                        saved.append(url)
                        if msg.bool("clip") { clipImage = url }
                    }
                case "text":
                    texts.append((msg.str("text") ?? "", msg.bool("clip"), msg.str("otp")))
                case "done":
                    break batch
                default:
                    throw TDError.proto("unexpected \(msg.type)")
                }
            }
            try await ch.sendJSON(["t": "ok", "saved": saved.count + texts.count + ocrImages.count])
        } catch {
            partial?.abort()
            await MainActor.run { Activity.shared.fail(error.localizedDescription) }
            throw error
        }

        var textPending = false
        if !ocrImages.isEmpty {
            await MainActor.run { Activity.shared.reading(peer: peer) }
            let reads = ocrImages.map(OCR.start)
            var parts: [String] = []
            var ready = true
            for r in reads {
                guard let t = await OCR.wait(r, timeout: 5) else { ready = false; break }
                if !t.isEmpty { parts.append(t) }
            }
            let text = parts.joined(separator: "\n\n")
            if ready, !text.isEmpty {
                ocrImages.forEach { try? FileManager.default.removeItem(at: $0) }
                await MainActor.run { Delivery.deliverOCR(text, from: peer) }
                return
            }
            // Nothing readable (it's a picture), or the text model is still loading:
            // hand over the picture now rather than leave the notch waiting.
            for img in ocrImages {
                let dest = FileNames.unique(in: folder, name: img.lastPathComponent)
                if (try? FileManager.default.copyItem(at: img, to: dest)) != nil {
                    saved.append(dest)
                    clipImage = dest
                }
            }
            let temps = ocrImages
            if ready {
                temps.forEach { try? FileManager.default.removeItem(at: $0) }
            } else {
                textPending = true
                // …and the words follow to the clipboard once they're read.
                Task.detached {
                    var parts: [String] = []
                    for r in reads { let t = await r.value; if !t.isEmpty { parts.append(t) } }
                    temps.forEach { try? FileManager.default.removeItem(at: $0) }
                    let text = parts.joined(separator: "\n\n")
                    if !text.isEmpty { await MainActor.run { Delivery.deliverOCR(text, from: peer, late: true) } }
                }
            }
        }
        let files = saved, txts = texts, clip = clipImage, pending = textPending
        await MainActor.run { Delivery.deliver(files: files, texts: txts, clipImage: clip, from: peer, textPending: pending) }
    }

    // MARK: Mac → phone

    private func sendInbox(_ ch: SecureChannel, to peer: String) async throws {
        let queued = await MainActor.run { Store.shared.outbox }
        var items: [OutItem] = []
        for q in queued {
            if let path = q.path {
                if let item = try? OutgoingFiles.item(for: URL(fileURLWithPath: path), clip: false) { items.append(item) }
            } else if let t = q.text {
                items.append(.text(t, clip: true))
            }
        }
        let title = items.count == 1 ? "Sending to \(peer)" : "Sending \(items.count) items to \(peer)"
        try await sendBatch(ch, items: items, to: peer, title: title, quiet: items.isEmpty)
        let ids = Set(queued.map(\.id))
        let n = items.count
        await MainActor.run {
            Store.shared.dequeue(ids)
            if n > 0 {
                Activity.shared.finish(title: n == 1 ? "Sent to \(peer)" : "Sent \(n) items to \(peer)")
                Store.shared.addRecent(RecentEvent(
                    incoming: false, peer: peer, summary: n == 1 ? queued[0].displayName : "\(n) items",
                    files: queued.compactMap { $0.path.map { URL(fileURLWithPath: $0) } },
                    text: queued.first(where: { $0.text != nil })?.text))
            }
        }
    }

    private func sendClipboard(_ ch: SecureChannel, to peer: String) async throws {
        let snap = await MainActor.run { ClipboardSnapshot.read() }
        var items: [OutItem] = []
        var summary = "Clipboard"
        switch snap {
        case .files(let urls):
            items = urls.compactMap { try? OutgoingFiles.item(for: $0, clip: false) }
            summary = urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files"
        case .image(let png):
            let url = OutgoingFiles.tempDir().appendingPathComponent("Mac Clipboard.png")
            try png.write(to: url)
            items = [.file(url: url, name: url.lastPathComponent, clip: true)]
            summary = "Image"
        case .text(let s):
            items = [.text(s, clip: true)]
            summary = String(s.prefix(60))
        case .empty:
            break
        }
        try await sendBatch(ch, items: items, to: peer, title: "Sending clipboard to \(peer)", quiet: true)
        if !items.isEmpty {
            let s = summary
            await MainActor.run {
                Activity.shared.notice(.clipboard, title: "Clipboard sent to \(peer)", detail: s, peer: peer)
            }
        }
    }

    private func sendBatch(_ ch: SecureChannel, items: [OutItem], to peer: String, title: String, quiet: Bool) async throws {
        var total: Int64 = 0
        for case .file(let url, _, _) in items { total += FileNames.size(of: url) }
        try await ch.sendJSON(["t": "send", "count": items.count, "total": total, "from": macName])
        if !quiet {
            await MainActor.run { Activity.shared.begin(incoming: false, peer: peer, title: title) }
        }
        var sent: Int64 = 0
        let throttle = ProgressThrottle()
        do {
            for item in items {
                switch item {
                case .file(let url, let name, let clip):
                    try await ch.sendJSON([
                        "t": "file", "name": name, "size": FileNames.size(of: url),
                        "mime": FileNames.mime(for: url), "clip": clip,
                    ])
                    let h = try FileHandle(forReadingFrom: url)
                    defer { try? h.close() }
                    while let d = try h.read(upToCount: SecureChannel.chunk), !d.isEmpty {
                        try await ch.sendData(d)
                        sent += Int64(d.count)
                        let done = sent
                        if !quiet { throttle.tick { Activity.shared.update(detail: name, done: done, total: total) } }
                    }
                    try await ch.sendJSON(["t": "eof"])
                case .text(let s, let clip):
                    try await ch.sendJSON(["t": "text", "text": s, "clip": clip])
                }
            }
            try await ch.sendJSON(["t": "done"])
            let ack = try await ch.recvJSON()
            guard ack.type == "ok" else { throw TDError.proto("phone did not acknowledge") }
        } catch {
            if !quiet { await MainActor.run { Activity.shared.fail(error.localizedDescription) } }
            throw error
        }
    }
}

// MARK: - helpers

final class IncomingFile {
    private let folder: URL
    private let name: String
    private let tmp: URL
    private let handle: FileHandle

    init(folder: URL, name: String) throws {
        self.folder = folder
        self.name = name
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        tmp = folder.appendingPathComponent(".tapdrop-\(UUID().uuidString).part")
        guard FileManager.default.createFile(atPath: tmp.path, contents: nil) else {
            throw CocoaError(.fileWriteNoPermission)
        }
        handle = try FileHandle(forWritingTo: tmp)
    }

    func write(_ d: Data) throws { try handle.write(contentsOf: d) }

    func finish() throws -> URL {
        try handle.close()
        let dest = FileNames.unique(in: folder, name: name)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }

    func abort() {
        try? handle.close()
        try? FileManager.default.removeItem(at: tmp)
    }
}

enum FileNames {
    static func sanitize(_ raw: String) -> String {
        var s = raw.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        s = String(s.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) })
        while s.hasPrefix(".") || s.hasPrefix(" ") { s.removeFirst() }
        if s.isEmpty { s = "file" }
        if s.count > 200 {
            let ext = (s as NSString).pathExtension
            s = String(s.prefix(180)) + (ext.isEmpty ? "" : ".\(ext)")
        }
        return s
    }

    static func unique(in folder: URL, name: String) -> URL {
        var url = folder.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var i = 1
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent(ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)")
            i += 1
        }
        return url
    }

    static func size(of url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    static func mime(for url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}

enum OutgoingFiles {
    static func tempDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("TapDrop-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Files go as-is; folders are zipped first.
    static func item(for url: URL, clip: Bool) throws -> OutItem {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) else {
            throw CocoaError(.fileNoSuchFile)
        }
        if !isDir.boolValue {
            return .file(url: url, name: url.lastPathComponent, clip: clip)
        }
        let out = tempDir().appendingPathComponent(url.lastPathComponent + ".zip")
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        p.arguments = ["-c", "-k", "--norsrc", "--noextattr", "--noacl", "--keepParent", url.path, out.path]
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw CocoaError(.fileWriteUnknown) }
        return .file(url: out, name: out.lastPathComponent, clip: clip)
    }
}

enum ClipboardSnapshot {
    case files([URL])
    case image(Data)
    case text(String)
    case empty

    @MainActor static func read() -> ClipboardSnapshot {
        let pb = NSPasteboard.general
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL],
           !urls.isEmpty {
            return .files(urls)
        }
        let string = pb.string(forType: .string)
        let looksLikeLink = string.map { URL(string: $0.trimmingCharacters(in: .whitespacesAndNewlines))?.scheme != nil } ?? true
        if string == nil || looksLikeLink, let png = imagePNG(pb) {
            return .image(png)
        }
        if let s = string, !s.isEmpty { return .text(s) }
        if let png = imagePNG(pb) { return .image(png) }
        return .empty
    }

    @MainActor private static func imagePNG(_ pb: NSPasteboard) -> Data? {
        if let png = pb.data(forType: .png) { return png }
        if let tiff = pb.data(forType: .tiff), let rep = NSBitmapImageRep(data: tiff) {
            return rep.representation(using: .png, properties: [:])
        }
        return nil
    }
}

/// What happens on the Mac once a batch from the phone has landed.
@MainActor
enum Delivery {
    static func deliverOCR(_ text: String, from peer: String, late: Bool = false) {
        guard !text.isEmpty else {
            Activity.shared.notice(.clipboard, title: "No text found", detail: "Nothing readable on \(peer)'s screen", peer: peer, sound: false)
            return
        }
        if TestMode.on { NSLog("TapDrop[test]: OCR text: %@", text) }
        SecureClipboard.copy(text)
        let first = text.split(separator: "\n").first.map(String.init) ?? text
        let words = text.split(whereSeparator: { $0.isWhitespace }).count
        Activity.shared.notice(.clipboard, title: late ? "Text ready · copied \(words) words" : "Copied \(words) words from \(peer)", detail: first, peer: peer)
        Store.shared.addRecent(RecentEvent(incoming: true, peer: peer, summary: String(first.prefix(40)), files: [], text: text))
    }

    static func deliver(files: [URL], texts: [(text: String, clip: Bool, otp: String?)], clipImage: URL?, from peer: String, textPending: Bool = false) {
        let store = Store.shared
        var link: URL?

        // A one-time code: straight to the clipboard (privately) and big in the notch.
        if files.isEmpty, let code = texts.first(where: { $0.otp != nil }) {
            if TestMode.on { NSLog("TapDrop[test]: code %@ from %@", code.text, code.otp ?? "") }
            SecureClipboard.set(code.text)
            Activity.shared.showCode(code.text, source: code.otp ?? peer, peer: peer)
            store.addRecent(RecentEvent(incoming: true, peer: peer, summary: "Code · \(code.otp ?? peer)", files: [], text: nil))
            return
        }

        if let clipImage, !TestMode.on, let image = NSImage(contentsOf: clipImage) {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.writeObjects([image])
        }
        if TestMode.on, let last = texts.last { NSLog("TapDrop[test]: received text: %@", last.text) }
        if let last = texts.last, !TestMode.on {
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(last.text, forType: .string)
            let trimmed = last.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.contains(where: \.isWhitespace), let u = URL(string: trimmed),
               let scheme = u.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                link = u
                if store.settings.openLinks { NSWorkspace.shared.open(u) }
            }
        }

        let folderName = store.saveFolder.lastPathComponent
        var title: String
        var detail = ""
        if !files.isEmpty {
            title = files.count == 1 ? files[0].lastPathComponent : "Received \(files.count) files"
            detail = "From \(peer) · \(folderName)"
            if clipImage != nil {
                // A notch tap / clipboard image: lead with what it is, not the long file name.
                title = "Screenshot from \(peer)"
                detail = textPending ? "Reading the text… it'll be copied in a moment" : "Copied to clipboard · \(folderName)"
            }
        } else if let link {
            title = store.settings.openLinks ? "Opened link from \(peer)" : "Link copied from \(peer)"
            detail = link.absoluteString
        } else if let t = texts.last {
            title = "Copied text from \(peer)"
            detail = String(t.text.prefix(120))
        } else {
            title = "Nothing received"
        }
        Activity.shared.finish(title: title, detail: detail, files: files, link: link)

        let summary: String
        if files.count == 1 { summary = files[0].lastPathComponent }
        else if !files.isEmpty { summary = "\(files.count) files" }
        else if let link { summary = link.host ?? link.absoluteString }
        else { summary = texts.last.map { String($0.text.prefix(40)) } ?? "Text" }
        store.addRecent(RecentEvent(incoming: true, peer: peer, summary: summary, files: files, text: texts.last?.text))
    }
}
