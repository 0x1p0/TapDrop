import AppKit
import Combine
import Foundation

/// Live state of whatever TapDrop is doing right now; drives the notch island (and the panel's transfer row).
@MainActor
final class Activity: ObservableObject {
    static let shared = Activity()

    enum Phase { case idle, active, done, failed }
    enum Kind { case transfer, paired, queued, clipboard, otp, pick }

    /// What a one-shot notice is about, which picks its picture in the island. Callers that don't say get it
    /// inferred from the kind and the copy (see `NoticeStyle.infer`).
    enum NoticeStyle: Equatable {
        /// Things waiting on this Mac for the phone ("Ready to send 2 items").
        case ready
        /// A light-key hint: shows the gesture (the top of the phone's screen touching the notch).
        case gesture
        /// The phone is locked.
        case locked
        /// The phone's screen is off.
        case asleep
        /// A TapDrop update is needed, on the phone or on this Mac.
        case update
        /// A new TapDrop for this Mac is out (once per version; "Details" opens Settings → About).
        case appUpdate
        /// TapDrop itself was open on the phone.
        case openApp
        case paired
        /// The Mac's clipboard went to the phone.
        case clipboard
        /// Text read off the phone's screen landed on the clipboard.
        case text
        /// Nothing readable was found.
        case noText
        case info
    }

    @Published private(set) var phase: Phase = .idle {
        didSet {
            if TestMode.on && phase != oldValue { NSLog("TapDrop[test]: island %@ → %@ (%@)", "\(oldValue)", "\(phase)", title) }
            if phase != .active { stopLanding() }
        }
    }
    @Published private(set) var kind: Kind = .transfer
    @Published private(set) var incoming = true
    @Published private(set) var peer = ""
    @Published private(set) var title = ""
    @Published private(set) var detail = ""
    @Published private(set) var progress: Double?
    @Published private(set) var bytesDone: Int64 = 0
    @Published private(set) var bytesTotal: Int64 = 0
    @Published private(set) var files: [URL] = []
    @Published private(set) var link: URL?
    @Published var hovering = false
    /// SF Symbol for what's arriving (video, photo, document…), shown inside the progress ring.
    @Published private(set) var symbol = "arrow.down"

    // MARK: island extras (additions; the panel ignores them)

    /// Bumps whenever the island should present something new (a new transfer, its result, a notice, a code,
    /// a failure). Progress updates within one transfer keep it.
    @Published private(set) var serial = 0
    /// For `.queued` / `.paired` / `.clipboard` notices: what the notice is about.
    @Published private(set) var noticeStyle: NoticeStyle = .info
    /// For a one-time code: who sent it ("HDFC Bank"). The code itself is `title`.
    @Published private(set) var codeSource = ""
    /// Files of the running batch that have already landed, newest last (live thumbnails while the rest arrive).
    @Published private(set) var arrived: [URL] = []
    /// What's going out to the phone (the queue), for thumbnails while sending and in "Ready to send".
    @Published private(set) var outgoing: [OutboxItem] = []
    /// When the running transfer started (the island tucks long transfers into the notch's sides).
    @Published private(set) var startedAt = Date()

    private var hideTask: Task<Void, Never>?
    private var lightSub: AnyCancellable?
    /// The save folder, watched while files arrive (see `watchLanding`).
    private var landing: DispatchSourceFileSystemObject?
    private var landingKnown: Set<String> = []

    private init() {
        // The island lights the notch while the Mac looks for the phone's light. Activity is created at launch
        // (the status item observes it), so the island hears every round.
        lightSub = TapStatus.shared.$phase
            .removeDuplicates()
            .sink { HUD.shared.light($0) }
    }

    func begin(incoming: Bool, peer: String, title: String) {
        hideTask?.cancel()
        kind = .transfer
        symbol = incoming ? "arrow.down" : "arrow.up"
        self.incoming = incoming
        self.peer = peer
        self.title = title
        detail = "Connecting…"
        progress = 0
        bytesDone = 0
        bytesTotal = 0
        files = []
        link = nil
        arrived = []
        // Sending is always the queue going out (the clipboard goes quietly), so its files are the outbox's.
        outgoing = incoming ? [] : Store.shared.outbox
        startedAt = Date()
        serial += 1
        phase = .active
        if incoming { watchLanding() }
        HUD.shared.show()
        touch()
        announce(title)
    }

    // MARK: landed files (live thumbnails)

    /// While files arrive, watches the save folder: the receiver writes each file to a hidden part file and renames
    /// it into place when it's complete, so a new name there is a file of this batch that has landed. Its thumbnail
    /// shows in the island's ring while the rest come in. Stops when the transfer ends (see `phase`).
    private func watchLanding() {
        stopLanding()
        let folder = Store.shared.saveFolder
        let fd = open(folder.path, O_EVTONLY)
        guard fd >= 0 else { return }
        landingKnown = Set((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [])
        let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: .write, queue: .main)
        source.setEventHandler { [weak self] in
            MainActor.assumeIsolated { self?.scanLanding(folder) }
        }
        source.setCancelHandler { close(fd) }
        source.resume()
        landing = source
    }

    private func scanLanding(_ folder: URL) {
        guard phase == .active, incoming else { return stopLanding() }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        let fresh = names.filter { !$0.hasPrefix(".") && !landingKnown.contains($0) }
        guard !fresh.isEmpty else { return }
        landingKnown.formUnion(fresh)
        for name in fresh.sorted() { arrived(folder.appendingPathComponent(name)) }
    }

    private func stopLanding() {
        landing?.cancel()
        landing = nil
        landingKnown = []
    }

    /// Text is being read off the phone's screen (OCR).
    func reading(peer: String) {
        let title = "Reading text from \(peer)"
        // Begun with its real title, so VoiceOver announces it (begin announces the title it's given).
        if phase != .active { begin(incoming: true, peer: peer, title: title) }
        kind = .transfer
        self.title = title
        detail = "Copying it to your clipboard"
        symbol = "text.viewfinder"
        progress = nil
        bytesTotal = 0
        touch()
    }

    // MARK: stall watchdog — the island must never sit on a transfer forever

    private var lastActivity = Date()
    private var watchdog: Task<Void, Never>?

    private func touch() {
        lastActivity = Date()
        guard watchdog == nil else { return }
        watchdog = Task { [weak self] in
            while let self, self.phase == .active {
                try? await Task.sleep(for: .seconds(5))
                if self.phase == .active, Date().timeIntervalSince(self.lastActivity) > 25 {
                    self.fail("This took too long — try again")
                }
            }
            self?.watchdog = nil
        }
    }

    /// The phone is showing its picker: the user may tap something specific, or wait.
    func beginPick(peer: String) {
        begin(incoming: true, peer: peer, title: "Sending from \(peer)…")
        kind = .pick
        symbol = "hand.tap.fill"
        detail = "Tap something on your phone to send just that"
        progress = nil
    }

    /// A file started arriving: show its name and what kind of thing it is.
    func arriving(name: String, mime: String, peer: String, count: Int) {
        hideTask?.cancel()
        let title = count == 1 ? name : "Receiving \(count) items"
        if phase != .active { begin(incoming: true, peer: peer, title: title) }
        kind = .transfer
        self.title = title
        symbol = mime.hasPrefix("video/") ? "play.fill"
            : mime.hasPrefix("image/") ? "photo.fill"
            : mime.hasPrefix("audio/") ? "waveform"
            : mime == "application/pdf" ? "doc.richtext.fill"
            : "doc.fill"
        touch()
    }

    /// One file of the running batch has landed (its thumbnail shows in the ring while the rest arrive).
    func arrived(_ url: URL) {
        guard phase == .active else { return }
        if TestMode.on { NSLog("TapDrop[test]: landed %@", url.lastPathComponent) }
        arrived.append(url)
        touch()
    }

    func update(detail: String, done: Int64, total: Int64) {
        guard phase == .active else { return }
        lastActivity = Date()
        self.detail = detail
        bytesDone = done
        bytesTotal = total
        progress = total > 0 ? min(1, Double(done) / Double(total)) : nil
    }

    func finish(title: String, detail: String = "", files: [URL] = [], link: URL? = nil) {
        kind = .transfer
        // "Sent to Android phone" (the queue went out) vs. anything that arrived (text batches never call `begin`).
        incoming = !(files.isEmpty && link == nil && title.hasPrefix("Sent"))
        self.title = title
        self.detail = detail
        self.files = files
        self.link = link
        progress = 1
        serial += 1
        phase = .done
        HUD.shared.show()
        haptic()
        if Store.shared.settings.playSound { NSSound(named: "Glass")?.play() }
        announce(detail.isEmpty ? title : "\(title). \(detail)")
        scheduleHide(after: files.isEmpty && link == nil ? 3.5 : 6)
    }

    /// One-shot message (paired, queued, clipboard sent, a hint) without a progress phase.
    /// `style` says what it's about; left out, it's inferred from the kind and the copy.
    func notice(_ kind: Kind, title: String, detail: String, peer: String = "", sound: Bool = true, hideAfter: Double = 4,
                style: NoticeStyle? = nil) {
        hideTask?.cancel()
        self.kind = kind
        self.peer = peer
        self.title = title
        noticeStyle = style ?? NoticeStyle.infer(kind: kind, title: title)
        // Phone declines all come with "Then touch the top of its screen to the notch"; under "Update TapDrop on
        // this Mac" that reads as if the Mac had the screen, and there's nothing to retry until it's updated.
        self.detail = noticeStyle == .update && title.hasSuffix("this Mac") && detail.hasPrefix("Then touch")
            ? "Your phone has a newer version" : detail
        files = []
        link = nil
        outgoing = noticeStyle == .ready ? Store.shared.outbox : []
        progress = 1
        serial += 1
        phase = .done
        HUD.shared.show()
        if noticeStyle == .paired { haptic() }
        if sound && Store.shared.settings.playSound { NSSound(named: "Glass")?.play() }
        announce("\(title). \(self.detail)")
        scheduleHide(after: hideAfter)
    }

    /// A tap that didn't count: a gentle hint in the notch, by its reason (see `TapCopy.hint`). Some reasons (a
    /// calibration sample) say nothing.
    func keyHint(_ why: String) {
        guard let h = TapCopy.hint(why) else { return }
        notice(.queued, title: h.title, detail: h.detail, sound: false, hideAfter: 2.6, style: .gesture)
    }

    /// A one-time code from the phone, shown big in the notch (it's already on the clipboard).
    func showCode(_ code: String, source: String, peer: String) {
        hideTask?.cancel()
        kind = .otp
        self.peer = peer
        title = code
        codeSource = source
        detail = "\(source) · Copied — ⌘V to paste"
        files = []
        link = nil
        progress = 1
        serial += 1
        phase = .done
        HUD.shared.show()
        haptic()
        if Store.shared.settings.playSound { NSSound(named: "Pop")?.play() }
        announce("Code from \(source) copied")
        scheduleHide(after: 10)
    }

    func fail(_ message: String) {
        title = "Transfer failed"
        detail = message
        progress = nil
        serial += 1
        phase = .failed
        HUD.shared.show()
        NSSound(named: "Basso")?.play()
        announce("Transfer failed. \(message)")
        scheduleHide(after: 5)
    }

    func dismiss() {
        hideTask?.cancel()
        HUD.shared.hide { [weak self] in self?.phase = .idle }
    }

    private func scheduleHide(after seconds: Double) {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            // Stay open while the pointer is over the island.
            while self?.hovering == true, !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(300))
            }
            guard !Task.isCancelled else { return }
            self?.dismiss()
        }
    }

    // MARK: feedback

    /// A tick on Force Touch trackpads when something lands (the light key's own tick comes from the rounds).
    private func haptic() {
        if !TestMode.headless { TD.haptic() }
    }

    /// The island never takes focus, so VoiceOver hears what it shows as an announcement.
    private func announce(_ text: String) {
        guard !TestMode.headless, !text.isEmpty else { return }
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested, userInfo: [
            .announcement: text,
            .priority: NSAccessibilityPriorityLevel.medium.rawValue,
        ])
    }
}

extension Activity.NoticeStyle {
    /// Reads the notice's copy (the callers predate styles): "Ready to send…", "Unlock Android phone to send"…
    static func infer(kind: Activity.Kind, title: String) -> Self {
        switch kind {
        case .paired:
            return .paired
        case .clipboard:
            if title.hasPrefix("Clipboard sent") { return .clipboard }
            if title.hasPrefix("No text") { return .noText }
            return .text
        case .queued:
            if title.hasPrefix("Ready to send") { return .ready }
            if title.hasPrefix("Unlock") { return .locked }
            if title.hasPrefix("Wake") { return .asleep }
            if title.hasPrefix("Update TapDrop") { return .update }
            if title.hasPrefix("Open what you want") { return .openApp }
            let t = title.replacingOccurrences(of: "\u{2019}", with: "'")
            if ["Your Mac didn't see", "Keep your phone on the notch", "Turn your phone around", "That tap didn't count"]
                .contains(where: t.hasPrefix) { return .gesture }
            return .info
        default:
            return .info
        }
    }
}

/// Coalesces progress callbacks so the UI isn't flooded from the network thread.
final class ProgressThrottle: @unchecked Sendable {
    private var last = Date.distantPast

    func tick(_ body: @escaping @MainActor () -> Void) {
        let now = Date()
        guard now.timeIntervalSince(last) > 0.05 else { return }
        last = now
        Task { @MainActor in body() }
    }
}

extension Int64 {
    var byteString: String { ByteCountFormatter.string(fromByteCount: self, countStyle: .file) }
}
