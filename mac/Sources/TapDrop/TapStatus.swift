import Foundation

/// Live state of tap detection for the UI ("Test your tap", the Settings tap pane, the island).
/// Updated on the main actor by the sensor and the light-key rounds; cheap to observe (no per-reading updates).
@MainActor
final class TapStatus: ObservableObject {
    static let shared = TapStatus()

    enum Phase: Equatable {
        /// Waiting for a tap.
        case idle
        /// A phone reported its light on (`src`: "screen"); the notch sensor is looking for it, then for the dark
        /// when the phone switches it off.
        case keying(src: String)
        /// The notch saw the phone's light come and go: the tap counts and the phone is sending.
        case accepted
        /// The round ended without a send. `reason` is a light-key failure ("not-seen", "no-light-sent",
        /// "stayed-lit", "no-key-off", "screen-only", "other-phone", "late", "gone") or the phone's decline ("still",
        /// "locked", "facing"…). A calibration hold isn't a failure: it goes back to `idle` (see `calibrated()`).
        case failed(reason: String)
    }

    /// One finished round, with the numbers behind it.
    struct TapResult: Equatable {
        let time: Date
        let phone: String
        /// What started the round: "dip" (lit room, notch covered), "rise" (dark room, light arrived) or "knock"
        /// (the phone's proximity sensor touched something while it was being moved).
        let trigger: String
        /// The light the phone used ("screen"), nil if it declined before lighting up.
        let src: String?
        /// The room level B at the notch (lux).
        let room: Double
        /// P, the level just before the phone lit up (lux).
        let pre: Double
        /// The brightest reading while the phone's light was on (lux).
        let peak: Double
        /// The level the light had to reach (lux); 0 if the phone declined first.
        let need: Double
        let accepted: Bool
        /// Why it didn't count (see `Phase.failed`), nil when accepted.
        let why: String?
    }

    /// B, the room's level at the notch (lux); nil until the sensor has run (it runs while a phone is live).
    @Published private(set) var roomLux: Double?
    /// The room counts as dark (B under 5 lux, lit again above 8): taps need the phone's screen on the notch.
    @Published private(set) var dark = false
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var last: TapResult?

    /// How far each phone (by name) is with fingerprinting its videos, so a tap can send the original video.
    struct VideoIndex: Equatable {
        let done: Int
        let total: Int
        var ready: Bool { done >= total }
    }
    @Published private(set) var videos: [String: VideoIndex] = [:]

    func videoIndex(_ phone: String, done: Int, total: Int) {
        let v = VideoIndex(done: min(done, total), total: total)
        if videos[phone] != v { videos[phone] = v }
    }

    private var idleTask: Task<Void, Never>?

    /// Called with meaningful changes only (≥ 5 % or a dark flip, at most twice a second; see `KeyRounds`).
    func room(lux: Double?, dark: Bool) {
        if self.dark != dark { self.dark = dark }
        let r = lux.map { ($0 * 10).rounded() / 10 }
        if roomLux != r { roomLux = r }
    }

    func keying(src: String) {
        idleTask?.cancel()
        phase = .keying(src: src)
    }

    /// A calibration hold ended well: nothing was sent and nothing failed (the calibration card shows the sample).
    func calibrated() {
        idleTask?.cancel()
        phase = .idle
    }

    func finished(_ r: TapResult) {
        last = r
        phase = r.accepted ? .accepted : .failed(reason: r.why ?? "")
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            self?.phase = .idle
        }
    }
}
