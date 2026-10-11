import Foundation

/// Phones that hold a live "watch" connection while their screen is on.
/// Lets the Mac push events instantly: "grab" (notch covered) and "inbox" (files queued).
final class WatchHub: @unchecked Sendable {
    static let shared = WatchHub()

    private struct Watcher {
        let deviceId: String
        let name: String
        let channel: SecureChannel
    }

    private let lock = NSLock()
    private var watchers: [UUID: Watcher] = [:]
    private var seq = 0

    func add(deviceId: String, name: String, channel: SecureChannel) -> UUID {
        let id = UUID()
        lock.withLock { watchers[id] = Watcher(deviceId: deviceId, name: name, channel: channel) }
        changed()
        return id
    }

    func remove(_ id: UUID) {
        lock.withLock { _ = watchers.removeValue(forKey: id) }
        changed()
    }

    /// The notch was covered: ask every live phone to send what's on its screen.
    /// (Phones also start a tap themselves when they touch their magnet spot on the Mac.)
    func grab() {
        let targets = lock.withLock { () -> [Watcher] in
            seq += 1
            return Array(watchers.values)
        }
        let n = lock.withLock { seq }
        if TestMode.on { NSLog("TapDrop[test]: grab → %d phone(s)", targets.count) }
        for w in targets { w.channel.sendJSONNow(["t": "grab", "id": n]) }
        let names = targets.map(\.name)
        Task { @MainActor in TapLog.shared.covered(phones: names) }
    }

    /// The phone left the notch: its pick countdown can start.
    func release() {
        let targets = lock.withLock { Array(watchers.values) }
        for w in targets { w.channel.sendJSONNow(["t": "release"]) }
    }

    func pushInbox(pending: Int) {
        guard pending > 0 else { return }
        let targets = lock.withLock { Array(watchers.values) }
        for w in targets { w.channel.sendJSONNow(["t": "inbox", "pending": pending]) }
    }

    private func changed() {
        let ids = lock.withLock { Set(watchers.values.map(\.deviceId)) }
        Task { @MainActor in
            Store.shared.liveDevices = ids
            NotchSensor.shared.setActive(!ids.isEmpty && Store.shared.settings.notchTap)
            if !ids.isEmpty { OCR.prewarm() }
        }
    }

    @MainActor func settingsChanged() {
        NotchSensor.shared.setActive(!Store.shared.liveDevices.isEmpty && Store.shared.settings.notchTap)
    }
}
