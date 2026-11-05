import AppKit
import Combine
import CoreImage
import CoreImage.CIFilterBuiltins
import Network
import SwiftUI

// Phone setup: a tiny web server that hands the Android app to the phone (scan the QR code → download →
// install), and the Setup window that walks through installing, pairing and turning on Tap to Send
// (the flow's views are in SetupFlow.swift, the served page in InstallPage.swift).

/// Tiny HTTP server that hands the Android APK to the phone.
final class InstallServer: @unchecked Sendable {
    static let shared = InstallServer()
    /// TAPDROP_INSTALL_PORT moves it (tests and demos, so they never collide with a running TapDrop).
    static let port: UInt16 = ProcessInfo.processInfo.environment["TAPDROP_INSTALL_PORT"].flatMap(UInt16.init) ?? 47914

    private let queue = DispatchQueue(label: "app.tapdrop.install")
    private var listener: NWListener?

    /// Demo: act as a copy of TapDrop without the Android app.
    nonisolated(unsafe) static var demoNoAPK = false

    /// The Android app to hand out: the one bundled with this Mac app, or a newer one placed in TapDrop's folder
    /// (TapDrop.apk with TapDrop.apk.json beside it), so the phone app can be updated without reinstalling the Mac app.
    var apkURL: URL? { apk?.url }

    /// The Android app's version ("2.0.6"), from the .json beside the APK.
    var apkVersion: String? { apk?.version }

    private var apk: (url: URL, version: String?)? {
        if Self.demoNoAPK { return nil }
        if let p = ProcessInfo.processInfo.environment["TAPDROP_APK"] { return (URL(fileURLWithPath: p), nil) }
        func info(_ url: URL?) -> (url: URL, version: String?, code: Int)? {
            guard let url, FileManager.default.fileExists(atPath: url.path) else { return nil }
            let side = url.appendingPathExtension("json")
            let json = (try? Data(contentsOf: side)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            return (url, json?["version"] as? String, json?["code"] as? Int ?? 0)
        }
        let bundled = info(Bundle.main.url(forResource: "TapDrop", withExtension: "apk"))
        let placed = info(Store.dir.appendingPathComponent("TapDrop.apk"))
        let pick = [bundled, placed].compactMap { $0 }.max { $0.code < $1.code }
        return pick.map { ($0.url, $0.version) }
    }

    var pageURL: String? { Net.lanIPv4().map { "http://\($0):\(Self.port)/" } }

    func start() {
        Self.report { $0.server = .starting }
        queue.async { [self] in
            guard listener == nil else {
                Self.report { $0.server = .ready }
                return
            }
            guard let port = NWEndpoint.Port(rawValue: Self.port) else { return }
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let l: NWListener
            do {
                l = try NWListener(using: params, on: port)
            } catch {
                Self.report { $0.server = .failed("Port \(Self.port) is busy") }
                return
            }
            l.newConnectionHandler = { [weak self] nw in
                guard let self else { return }
                nw.start(queue: self.queue)
                let c = Conn(nw)
                Task { await self.serve(c) }
            }
            l.stateUpdateHandler = { [weak self] s in
                switch s {
                case .ready:
                    Self.report { $0.server = .ready }
                case .failed(let e):
                    self?.queue.async { self?.listener = nil }
                    Self.report { $0.server = .failed(Self.describe(e)) }
                default:
                    break
                }
            }
            l.start(queue: queue)
            listener = l
        }
    }

    func stop() {
        queue.async { [self] in
            listener?.cancel()
            listener = nil
        }
        Self.report { $0.server = .stopped }
    }

    private static func describe(_ e: NWError) -> String {
        if case .posix(let code) = e, code == .EADDRINUSE { return "Port \(port) is busy" }
        return "The network said no (\(e.localizedDescription))"
    }

    /// One line per request in install.log (in TapDrop's folder), for when a phone can't open the page.
    private static func log(_ who: String, _ what: String) {
        let url = Store.dir.appendingPathComponent("install.log")
        let line = "\(ISO8601DateFormatter().string(from: Date()))  \(who)  \(what.prefix(160))\n"
        if (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int).flatMap({ $0 }) ?? 0 > 64 * 1024 {
            try? FileManager.default.removeItem(at: url)
        }
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile()
            h.write(Data(line.utf8))
            try? h.close()
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }

    private static func report(_ change: @escaping @MainActor (InstallStatus) -> Void) {
        DispatchQueue.main.async { MainActor.assumeIsolated { change(InstallStatus.shared) } }
    }

    private func serve(_ c: Conn) async {
        defer { c.close() }
        let who = c.remoteHost
        // A browser that tries https:// first (Chrome often does) or a connection that never asks for anything must
        // not hang: close it, so the browser falls back to plain http at once.
        let idle = DispatchWorkItem { c.close() }
        queue.asyncAfter(deadline: .now() + 20, execute: idle)
        var buf = Data()
        let end = Data("\r\n\r\n".utf8)
        while buf.range(of: end) == nil {
            guard buf.count < 32 * 1024, let chunk = try? await c.readSome() else {
                idle.cancel()
                Self.log(who, buf.isEmpty ? "closed without a request" : "closed mid-request")
                return
            }
            buf.append(chunk)
            if let first = buf.first, !(65...90).contains(first) {
                // Not an HTTP request (0x16 is a TLS handshake: the browser trying https).
                idle.cancel()
                Self.log(who, first == 0x16 ? "https attempt, closed so the browser uses http" : "not http, closed")
                return
            }
        }
        idle.cancel()
        let requestLine = String(decoding: buf.prefix { $0 != 13 }, as: UTF8.self)
        Self.log(who, requestLine)
        let parts = requestLine.split(separator: " ")
        let path = parts.count > 1 ? String(parts[1].split(separator: "?").first ?? "/") : "/"

        if path.hasPrefix("/TapDrop.apk"), let apk = apkURL, let h = try? FileHandle(forReadingFrom: apk) {
            defer { try? h.close() }
            let size = Int64(FileNames.size(of: apk))
            let head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                + "Content-Length: \(size)\r\nContent-Disposition: attachment; filename=\"TapDrop.apk\"\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            Self.report { $0.download = .init(sent: 0, total: size) }
            var sent: Int64 = 0
            var reported = Date.distantPast
            do {
                try await c.write(Data(head.utf8))
                while let d = try h.read(upToCount: 256 * 1024), !d.isEmpty {
                    try await c.write(d)
                    sent += Int64(d.count)
                    if Date().timeIntervalSince(reported) > 0.1 {
                        reported = Date()
                        let s = sent
                        Self.report { $0.download?.sent = s }
                    }
                }
                let s = sent
                Self.report {
                    $0.download?.sent = s
                    $0.download?.done = true
                }
            } catch {
                Self.report { $0.download?.failed = true }
            }
            return
        }
        guard path == "/" || path == "/index.html" else {
            try? await c.write(Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8))
            return
        }
        Self.report {
            $0.visits += 1
            $0.lastVisit = Date()
        }
        let apkSize = apkURL.map { Int64(FileNames.size(of: $0)) }
        let body = Data(InstallPage.html(macName: Store.computerName, apkSize: apkSize, version: apkVersion).utf8)
        let head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.count)\r\n"
            + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        try? await c.write(Data(head.utf8) + body)
    }
}

/// What the install server is up to, for the Setup window.
@MainActor
final class InstallStatus: ObservableObject {
    static let shared = InstallStatus()

    enum Server: Equatable {
        case stopped, starting, ready
        case failed(String)
    }

    struct Download: Equatable {
        var sent: Int64
        var total: Int64
        var done = false
        var failed = false

        var fraction: Double { total > 0 ? min(1, Double(sent) / Double(total)) : 0 }
    }

    @Published var server: Server = .stopped
    /// Times the page was opened (on the phone, normally).
    @Published var visits = 0
    @Published var lastVisit: Date?
    /// The latest APK download.
    @Published var download: Download?

    func reset() {
        visits = 0
        lastVisit = nil
        download = nil
    }
}

enum QR {
    /// The code's modules, row by row from the top (true = dark), without the quiet zone.
    static func matrix(for string: String, correction: String = "Q") -> [[Bool]]? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(string.utf8)
        f.correctionLevel = correction
        guard let img = f.outputImage else { return nil }
        let w = Int(img.extent.width), h = Int(img.extent.height)
        guard w > 0, h > 0 else { return nil }
        let ctx = CIContext(options: [.workingColorSpace: NSNull(), .outputColorSpace: NSNull()])
        guard let cg = ctx.createCGImage(img, from: img.extent) else { return nil }
        var px = [UInt8](repeating: 255, count: w * h)
        let ok = px.withUnsafeMutableBytes { buf -> Bool in
            guard let bc = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                     space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
            else { return false }
            bc.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        // CGContext memory starts at the top row.
        var rows: [[Bool]] = (0..<h).map { y in (0..<w).map { x in px[y * w + x] < 128 } }
        // trim the quiet zone
        let dark = rows.indices.filter { rows[$0].contains(true) }
        guard let top = dark.first, let bottom = dark.last else { return nil }
        let cols = (0..<w).filter { x in rows.contains { $0[x] } }
        guard let left = cols.first, let right = cols.last else { return nil }
        rows = rows[top...bottom].map { Array($0[left...right]) }
        return rows
    }

    static func image(for string: String, size: CGFloat) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(string.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let ctx = CIContext()
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: size, height: size))
    }
}

@MainActor
final class SetupWindow: NSObject, NSWindowDelegate {
    static let shared = SetupWindow()
    static let size = NSSize(width: 800, height: 548)
    private var window: NSWindow?
    private var keyMonitor: Any?
    let model = SetupModel()

    func show() {
        InstallServer.shared.start()
        if let window {
            AppPresence.windowOpened()
            window.makeKeyAndOrderFront(nil)
            return
        }
        InstallStatus.shared.reset()
        model.begin()
        let w = SettingsChrome.makeWindow(size: Self.size, title: "Set Up a Phone", root: SetupView(model: model))
        w.delegate = self
        window = w
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self, let w = self.window, e.window === w else { return e }
            let cmd = e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command
            if e.keyCode == 53 || (cmd && e.charactersIgnoringModifiers == "w") {
                w.close()
                return nil
            }
            if cmd && e.charactersIgnoringModifiers == "q" {
                NSApp.terminate(nil)
                return nil
            }
            return e
        }
        AppPresence.windowOpened()
        w.makeKeyAndOrderFront(nil)
    }

    func close() {
        window?.close()
    }

    func windowWillClose(_ notification: Notification) {
        InstallServer.shared.stop()
        model.end()
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        keyMonitor = nil
        window = nil
    }
}
