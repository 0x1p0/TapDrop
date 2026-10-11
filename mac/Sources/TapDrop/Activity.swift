import AppKit
import Foundation

/// Live state of whatever TapDrop is doing right now; drives the notch island.
@MainActor
final class Activity: ObservableObject {
    static let shared = Activity()

    enum Phase { case idle, active, done, failed }
    enum Kind { case transfer, paired, queued, clipboard, otp, pick }

    @Published private(set) var phase: Phase = .idle {
        didSet { if TestMode.on && phase != oldValue { NSLog("TapDrop[test]: island %@ → %@ (%@)", "\(oldValue)", "\(phase)", title) } }
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

    private var hideTask: Task<Void, Never>?

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
        phase = .active
        HUD.shared.show()
        touch()
    }

    /// Text is being read off the phone's screen (OCR).
    func reading(peer: String) {
        if phase != .active { begin(incoming: true, peer: peer, title: "") }
        kind = .transfer
        title = "Reading text from \(peer)"
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
        detail = "Tap something on your phone to send just that"
        progress = nil
    }

    /// A file started arriving: show its name and what kind of thing it is.
    func arriving(name: String, mime: String, peer: String, count: Int) {
        hideTask?.cancel()
        if phase != .active { begin(incoming: true, peer: peer, title: "") }
        kind = .transfer
        title = count == 1 ? name : "Receiving \(count) items"
        symbol = mime.hasPrefix("video/") ? "play.fill"
            : mime.hasPrefix("image/") ? "photo.fill"
            : mime.hasPrefix("audio/") ? "waveform"
            : mime == "application/pdf" ? "doc.richtext.fill"
            : "doc.fill"
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
        self.title = title
        self.detail = detail
        self.files = files
        self.link = link
        progress = 1
        phase = .done
        HUD.shared.show()
        if Store.shared.settings.playSound { NSSound(named: "Glass")?.play() }
        scheduleHide(after: files.isEmpty ? 3.5 : 6)
    }

    /// One-shot message (paired, queued, clipboard sent) without a progress phase.
    func notice(_ kind: Kind, title: String, detail: String, peer: String = "", sound: Bool = true, hideAfter: Double = 4) {
        hideTask?.cancel()
        self.kind = kind
        self.peer = peer
        self.title = title
        self.detail = detail
        files = []
        link = nil
        progress = 1
        phase = .done
        HUD.shared.show()
        if sound && Store.shared.settings.playSound { NSSound(named: "Glass")?.play() }
        scheduleHide(after: hideAfter)
    }

    /// A one-time code from the phone, shown big in the notch.
    func showCode(_ code: String, source: String, peer: String) {
        hideTask?.cancel()
        kind = .otp
        self.peer = peer
        title = code
        detail = "\(source) · Copied — ⌘V to paste"
        files = []
        link = nil
        progress = 1
        phase = .done
        HUD.shared.show()
        if Store.shared.settings.playSound { NSSound(named: "Pop")?.play() }
        scheduleHide(after: 10)
    }

    func fail(_ message: String) {
        title = "Transfer failed"
        detail = message
        progress = nil
        phase = .failed
        HUD.shared.show()
        NSSound(named: "Basso")?.play()
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
