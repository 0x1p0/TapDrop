import AppKit
import Foundation

/// Phones that hold a live "watch" connection while their screen is on.
/// Lets the Mac push events instantly: "grab" (a light-key round), "room", "lux", "release", "inbox".
final class WatchHub: @unchecked Sendable {
    static let shared = WatchHub()

    /// A live phone, as rounds see it.
    struct Phone {
        let id: UUID
        /// The paired phone (its calibration is kept by this id).
        let deviceId: String
        let name: String
        /// The light-key version from the phone's `caps` (0: a phone that predates the light key).
        let key: Int
    }

    private final class Watcher {
        let deviceId: String
        let name: String
        let channel: SecureChannel
        var key = 0
        var diag = false
        var luxCredit = 1.0
        var luxAt: TimeInterval = 0
        var toldUpdate = false

        init(deviceId: String, name: String, channel: SecureChannel) {
            self.deviceId = deviceId
            self.name = name
            self.channel = channel
        }
    }

    private let lock = NSLock()
    private var watchers: [UUID: Watcher] = [:]

    /// Call after the session's "watching" went out (so it stays the first message the phone reads).
    ///
    /// A phone holds one live link. A new one from the same phone replaces any older one, which is closed: after a
    /// network blip the old link can look open for minutes, and grabs sent down it would reach nobody (and make the
    /// next cover ask "2 phones").
    func add(deviceId: String, name: String, channel: SecureChannel) -> UUID {
        let id = UUID()
        let replaced: [(UUID, Watcher)] = lock.withLock {
            let old = watchers.filter { $0.value.deviceId == deviceId }.map { ($0.key, $0.value) }
            for (oldId, _) in old { watchers.removeValue(forKey: oldId) }
            watchers[id] = Watcher(deviceId: deviceId, name: name, channel: channel)
            return old
        }
        for (oldId, w) in replaced {
            NSLog("TapDrop: %@ opened a new live link; closing its old one", name)
            KeyRounds.shared.gone(oldId)
            w.channel.conn.close()
        }
        changed()
        KeyRounds.shared.welcome(id)
        return id
    }

    /// Whether this session is still the phone's live link (a newer one replaces it).
    func isLive(_ id: UUID) -> Bool { lock.withLock { watchers[id] != nil } }

    func remove(_ id: UUID) {
        let gone = lock.withLock { watchers.removeValue(forKey: id) }
        KeyRounds.shared.gone(id)
        changed()
        if let device = gone?.deviceId {
            Task { @MainActor in TapCalibration.shared.phoneLeft(deviceId: device) }
        }
    }

    /// The phone announced `{"t":"caps","key":n}`.
    func setCaps(_ id: UUID, key: Int) {
        let w = lock.withLock {
            watchers[id]?.key = key
            return watchers[id]
        }
        if TestMode.on { NSLog("TapDrop[test]: caps key=%d from %@", key, name(id) ?? "?") }
        if TestHooks.on, key >= LightKey.version, let w {
            let (device, phone) = (w.deviceId, w.name)
            Task { @MainActor in TapCalibration.shared.testStartIfAsked(deviceId: device, phone: phone) }
        }
    }

    /// `{"t":"diag","on":bool}`: stream `lux` readings to this phone (its "Test your tap" screen).
    func setDiag(_ id: UUID, on: Bool) {
        lock.withLock {
            watchers[id]?.diag = on
            watchers[id]?.luxCredit = 1
        }
    }

    func phones() -> [Phone] {
        lock.withLock { watchers.map { Phone(id: $0.key, deviceId: $0.value.deviceId, name: $0.value.name, key: $0.value.key) } }
    }

    func name(_ id: UUID) -> String? { lock.withLock { watchers[id]?.name } }

    func key(of id: UUID) -> Int { lock.withLock { watchers[id]?.key ?? 0 } }

    /// True the first time it's asked for this session (the "update TapDrop" notice shows once per session).
    func firstUpdateNotice(_ id: UUID) -> Bool {
        lock.withLock {
            guard let w = watchers[id], !w.toldUpdate else { return false }
            w.toldUpdate = true
            return true
        }
    }

    @discardableResult
    func send(_ id: UUID, _ msg: JSON) -> Bool {
        guard let ch = lock.withLock({ watchers[id]?.channel }) else { return false }
        ch.sendJSONNow(msg)
        return true
    }

    func broadcast(_ msg: JSON) {
        let targets = lock.withLock { watchers.values.map(\.channel) }
        for ch in targets { ch.sendJSONNow(msg) }
    }

    /// One reading for phones in diag mode, at most 8 a second each (readings arrive ~10 a second).
    func streamLux(_ v: Double, room b: Double, at t: TimeInterval) {
        let targets: [SecureChannel] = lock.withLock {
            watchers.values.compactMap { w in
                guard w.diag else { return nil }
                w.luxCredit = min(1.5, w.luxCredit + max(0, t - w.luxAt) * 8)
                w.luxAt = t
                guard w.luxCredit >= 1 else { return nil }
                w.luxCredit -= 1
                return w.channel
            }
        }
        guard !targets.isEmpty else { return }
        let msg: JSON = ["t": "lux", "v": (v * 10).rounded() / 10, "b": (b * 10).rounded() / 10]
        for ch in targets { ch.sendJSONNow(msg) }
    }

    /// A notch cover (kept for older callers): in test mode the simulated cover, otherwise nothing to do —
    /// real covers reach `KeyRounds` straight from the sensor.
    func grab() {
        if TestHooks.on { KeyRounds.shared.testCover() }
    }

    /// The phone left the notch: its pick countdown can start. (Rounds release their own phone.)
    func release() {
        broadcast(["t": "release"])
    }

    func pushInbox(pending: Int) {
        guard pending > 0 else { return }
        broadcast(["t": "inbox", "pending": pending])
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

// MARK: - Light-key rounds

/// Every send needs a light key (see `LightKey`). A trigger — DIP or RISE from the notch sensor, or a phone's
/// KNOCK — starts a round:
/// 1. The Mac asks the phone(s) to light up ("grab" with "key"). A phone turns its screen white at full brightness
///    and says "key-on".
/// 2. The rise: a reading at a level only a screen on the notch gives → "key-seen" to that phone.
/// 3. The phone turns its screen black and says "key-off".
/// 4. The fall: a reading right after key-off must go dark with it (in a lit room, darker than the room).
/// Only then "key-ok" (the phone sends as usual); anything else is "key-fail" (nothing is sent).
///
/// While a phone is being calibrated (`TapCalibrations.active`), its rounds are calibration holds: a lower rise that
/// waits for the brightest reading, the usual fall, then key-fail "calibrating" with the sample (never key-ok). A
/// calibrated phone's taps need max(its own floor, B + 120) instead of max(180, B + 120).
///
/// One round at a time, ≥ 1.5 s apart, and one phone's echo at a time. All state lives on the sensor's queue.
final class KeyRounds: @unchecked Sendable {
    static let shared = KeyRounds()

    private let sensor = NotchSensor.shared
    private var q: DispatchQueue { sensor.queue }

    private enum Verdict {
        case ok
        case failed(String)
        case declined(String)
    }

    private struct Phone {
        let name: String
        let deviceId: String
        /// This round is one of the phone's calibration holds: a lower rise that waits for the brightest reading,
        /// the usual fall, and key-fail "calibrating" instead of key-ok (nothing is sent).
        let calibrating: Bool
        /// Calibration: when the first reading reached the provisional need; the reading before the current one.
        var overAt: TimeInterval?
        var lastLux = 0.0
        var keyOn: TimeInterval?
        var src: String?
        /// The reading that triggers key-seen.
        var need: Double?
        /// The light this phone's tap needs: white − black ≥ this (0.55 × its contact level).
        var light: Double?
        /// The brightest reading while its light was on (grab + 40 ms until key-off).
        var peak = 0.0
        /// When the notch saw its light ("key-seen" went out).
        var seenAt: TimeInterval?
        var keyOff: TimeInterval?
        /// The level the notch must fall to after key-off (fixed at key-off, once seen).
        var fall: Double?
        /// The darkest reading after key-off.
        var low: Double?
        var verdict: Verdict?
        var deadline: DispatchWorkItem?
    }

    private final class Round {
        let id: Int
        let trigger: NotchDetector.Trigger
        let t0: TimeInterval
        let room: Double  // B, frozen for the round
        let dark: Bool
        var tGrab: TimeInterval = 0
        var pre = 0.0  // P
        var preReady = false
        var covered = false
        var phones: [UUID: Phone] = [:]
        /// The phone whose echo is being checked (seen, not yet decided). Only one at a time: only one phone can
        /// sit on the notch, and the fall has to be its own.
        var candidate: UUID?
        var winner: UUID?
        var decidedAt: TimeInterval?
        var settled = 0
        var work: [DispatchWorkItem] = []

        init(id: Int, trigger: NotchDetector.Trigger, t0: TimeInterval, room: Double, dark: Bool) {
            self.id = id
            self.trigger = trigger
            self.t0 = t0
            self.room = room
            self.dark = dark
        }

        var lightsOff: Bool { phones.values.allSatisfy { $0.keyOn == nil || $0.keyOff != nil } }
        var lightsOffAt: TimeInterval { phones.values.compactMap(\.keyOff).max() ?? 0 }
    }

    private var round: Round?
    private var lastStart: TimeInterval = -.infinity
    private var seq = 0
    private var lastHint: TimeInterval = -.infinity
    /// The room as last sent to phones (dark flag, B), for sessions that join later.
    private var room: (dark: Bool, lux: Double)?
    private var published: (dark: Bool, lux: Double, at: TimeInterval)?

    /// Sessions whose light key passed, and for which round (read from the network tasks).
    private let passLock = NSLock()
    private var passes: [UUID: (round: Int, at: TimeInterval)] = [:]

    // Test mode (debug builds, TAPDROP_TEST=1): the simulated sensor answers each key-on per TAPDROP_TEST_KEY, at
    // TAPDROP_TEST_PEAK lux if set (a phone brighter or dimmer than usual, for calibration).
    private let testKey = ProcessInfo.processInfo.environment["TAPDROP_TEST_KEY"] ?? "pass"
    private let testPeak = TestHooks.on ? ProcessInfo.processInfo.environment["TAPDROP_TEST_PEAK"].flatMap(Double.init) : nil
    private let testDelay = TestHooks.on
        ? (ProcessInfo.processInfo.environment["TAPDROP_TEST_KEY_DELAY_MS"].flatMap(Double.init) ?? 0) / 1000 : 0
    private var simPhoneAtNotch = false

    private static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    /// Connects the notch sensor to the rounds (call once at launch).
    func attach() {
        sensor.onTrigger = { [unowned self] in self.triggered($0) }
        sensor.onSample = { [unowned self] in self.sample($0) }
        sensor.onRoom = { [unowned self] dark, b in self.roomFlipped(dark, b) }
        sensor.onStop = { [unowned self] in self.sensorStopped() }
    }

    // MARK: phone messages (any thread)

    func welcome(_ id: UUID) {
        q.async { [self] in
            if let room { WatchHub.shared.send(id, ["t": "room", "dark": room.dark, "lux": Self.r1(room.lux)]) }
        }
    }

    func gone(_ id: UUID) {
        q.async { [self] in
            passLock.withLock { _ = passes.removeValue(forKey: id) }
            guard let r = round, var p = r.phones[id] else { return }
            // Whatever its light is doing, it's no longer the notch's business (and mustn't hold up the release).
            if p.keyOn != nil, p.keyOff == nil { p.keyOff = Self.now }
            r.phones[id] = p
            fail(r, id, "gone")  // also ends the island's "looking for the light"
        }
    }

    /// `{"t":"knock"}`: the phone's proximity sensor went near while it was moving and upright.
    func knock(from id: UUID, at t: TimeInterval) {
        q.async { [self] in
            guard let p = WatchHub.shared.phones().first(where: { $0.id == id }), p.key >= LightKey.version else { return }
            guard sensor.baseline != nil else {
                if TestMode.on { NSLog("TapDrop[test]: knock from %@ ignored: the notch sensor isn't running", p.name) }
                return
            }
            guard canStart(at: t) else {
                if TestMode.on { NSLog("TapDrop[test]: knock from %@ ignored: a round is running or just ran", p.name) }
                return
            }
            start(.knock, phones: [p], t0: t)
            if sensor.simulated {
                // Simulated: the phone's screen content on the notch (an open notch: brighter than 0.3·B). Read once
                // the round has frozen B, and inside P's window.
                simPhoneAtNotch = true
                sensor.simulate(300)
            }
            let level = sensor.latest(atOrBefore: Self.now)?.lux ?? sensor.baseline ?? 0
            let name = p.name
            DispatchQueue.main.async { TapLog.shared.trigger("knock", from: level, to: level, asked: [name]) }
        }
    }

    /// `{"t":"key-on","id":n,"src":…}`: the phone's screen is white at full brightness.
    func keyOn(from id: UUID, round n: Int, src: String, at t: TimeInterval) {
        q.async { [self] in
            guard let r = round, r.id == n, var p = r.phones[id], p.keyOn == nil, p.verdict == nil else {
                // Unknown, stale or repeated: answer at once so the phone switches its light off.
                WatchHub.shared.send(id, ["t": "key-fail", "id": n, "peak": 0, "need": 0, "why": "late"])
                return
            }
            p.keyOn = t
            p.src = src
            r.phones[id] = p
            if TestMode.on { NSLog("TapDrop[test]: key-on %d src=%@ from %@ (+%.0f ms after grab)", n, src, p.name, (t - r.tGrab) * 1000) }
            if r.winner != nil {
                fail(r, id, "other-phone")
                return
            }
            DispatchQueue.main.async { TapStatus.shared.keying(src: src) }
            if sensor.simulated { simulateLight(r, id) }
            if r.preReady { judge(r, id) }
        }
    }

    /// `{"t":"key-off","id":n}`: the phone's screen is black (or its light gave up). For the phone the notch saw,
    /// this starts the fall: the notch must go dark with it.
    func keyOff(from id: UUID, round n: Int, at t: TimeInterval) {
        q.async { [self] in
            guard let r = round, r.id == n, var p = r.phones[id], p.keyOn != nil, p.keyOff == nil else { return }
            p.keyOff = t
            if p.verdict == nil, p.seenAt != nil {
                // White − black must reach the phone's needed light, and in a lit room the black phone must block the
                // room (it's on the sensor). A calibration hold: ≤ peak / 3 and the block.
                let block = r.dark ? Double.infinity : max(LightKey.blockFraction * r.room, LightKey.blockFloor)
                p.fall = p.calibrating
                    ? min(LightKey.fallFraction * p.peak, block)
                    : min(p.peak - (p.light ?? 0), block)
                p.deadline?.cancel()
                p.deadline = after(r, at: t + LightKey.fallWithin) { [weak self] in
                    guard let self, self.round === r, r.phones[id]?.verdict == nil else { return }
                    self.fail(r, id, "stayed-lit")
                }
            }
            r.phones[id] = p
            if TestMode.on { NSLog("TapDrop[test]: key-off %d from %@%@", n, p.name, p.fall.map { String(format: " (must fall to %.0f lux)", $0) } ?? "") }
            // Its light was too weak for even a fully dark notch to make the difference: not on the notch.
            if p.verdict == nil, let f = p.fall, f < 0 { fail(r, id, "not-seen") }
            if sensor.simulated { simulateDark(r, id) }
        }
    }

    /// `grab-ack` with ok=false: the phone declined before (or instead of) lighting up, or gave up.
    func declined(from id: UUID, round n: Int?, why: String) {
        q.async { [self] in
            guard let r = round, n == nil || n == r.id, var p = r.phones[id], p.verdict == nil else { return }
            p.verdict = .declined(why)
            p.deadline?.cancel()
            if p.keyOn != nil, p.keyOff == nil { p.keyOff = Self.now }
            r.phones[id] = p
            if r.candidate == id { r.candidate = nil }
            let calibrating = p.calibrating
            // Only the screen's light counts: with its back to the notch, the phone is told to turn around.
            let hint = why == "facing" && wantsHint(why, calibrating: calibrating)
            let result = TapStatus.TapResult(
                time: Date(), phone: p.name, trigger: r.trigger.rawValue, src: nil, room: r.room, pre: r.pre,
                peak: 0, need: 0, accepted: false, why: why)
            let winner = r.winner != nil
            let device = p.deviceId
            checkDecided(r)
            DispatchQueue.main.async {
                if calibrating { TapCalibration.shared.missed(deviceId: device, why: why) }
                if !winner { TapStatus.shared.finished(result) }
                if hint { Activity.shared.keyHint(why) }
            }
        }
    }

    /// Whether this session's light key passed (for `round`, or within the last 15 s if the phone didn't say).
    func passed(session id: UUID, round n: Int?) -> Bool {
        passLock.withLock {
            guard let p = passes[id] else { return false }
            if let n { return p.round == n }
            return Self.now - p.at < 15
        }
    }

    // MARK: test mode

    /// `kill -USR1`: the notch is covered (a DIP round with B = the simulated room, P = 4).
    func testCover() {
        q.async { [self] in
            guard sensor.simulated else {
                NSLog("TapDrop[test]: cover ignored: the real light sensor is in use (TAPDROP_TEST_ALS=real)")
                return
            }
            if let r = round {
                guard r.decidedAt != nil else {
                    NSLog("TapDrop[test]: cover ignored: round %d is still running", r.id)
                    return
                }
                release(r, why: "test: next cover")
            }
            simPhoneAtNotch = true
            let b = sensor.baseline ?? NotchSensor.simulatedRoom
            let t = sensor.simulate(4)?.t ?? Self.now
            guard canStart(at: t) else {
                NSLog("TapDrop[test]: cover ignored: rounds start at least %.1f s apart", LightKey.roundGap)
                return
            }
            triggered(NotchDetector.Fired(trigger: .dip, from: b, to: 4, at: t))
        }
    }

    /// `kill -USR2`: the phone left the notch.
    func testLeave() {
        q.async { [self] in
            simPhoneAtNotch = false
            if sensor.simulated { sensor.simulate(NotchSensor.simulatedRoom) }
            // A round in its release wait sees that reading and releases its phone; otherwise, as before.
            if round == nil { WatchHub.shared.release() }
        }
    }

    // MARK: the round (on the sensor queue)

    private func canStart(at t: TimeInterval) -> Bool {
        round == nil && t - lastStart >= LightKey.roundGap - 0.001
    }

    private func triggered(_ f: NotchDetector.Fired) {
        guard canStart(at: f.at) else { return }
        let phones = WatchHub.shared.phones()
        let keyed = phones.filter { $0.key >= LightKey.version }
        let old = phones.filter { $0.key < LightKey.version }
        let fresh = old.filter { WatchHub.shared.firstUpdateNotice($0.id) }.map(\.name)
        let asked = keyed.map(\.name)
        let needUpdate = old.map(\.name)
        DispatchQueue.main.async {
            TapLog.shared.trigger(f.trigger.rawValue, from: f.from, to: f.to, asked: asked, needUpdate: needUpdate)
            if let name = fresh.first, keyed.isEmpty {
                Activity.shared.notice(.queued, title: "Update TapDrop on \(name)", detail: "It needs the new version to keep tapping",
                                       sound: false, hideAfter: 3.5)
            }
        }
        if TestMode.on && keyed.isEmpty { NSLog("TapDrop[test]: %@ → no light-key phone (%d older)", f.trigger.rawValue, old.count) }
        guard !keyed.isEmpty else { return }
        start(f.trigger, phones: keyed, t0: f.at)
    }

    private func start(_ trigger: NotchDetector.Trigger, phones: [WatchHub.Phone], t0: TimeInterval) {
        guard let b = sensor.baseline else { return }
        seq += 1
        let r = Round(id: seq, trigger: trigger, t0: t0, room: b, dark: sensor.isDark)
        for p in phones {
            r.phones[p.id] = Phone(name: p.name, deviceId: p.deviceId, calibrating: TapCalibrations.shared.isCalibrating(p.deviceId))
        }
        round = r
        lastStart = t0
        sensor.beginRound(at: t0)
        for id in r.phones.keys {
            WatchHub.shared.send(id, ["t": "grab", "id": r.id, "key": LightKey.version, "dark": r.dark])
        }
        r.tGrab = Self.now
        if TestMode.on {
            NSLog("TapDrop[test]: grab %d (%@, room %.0f lux, %@) → %d phone(s)", r.id, trigger.rawValue, b,
                  r.dark ? "dark" : "lit", phones.count)
        }
        after(r, at: r.tGrab + LightKey.preAfterGrab) { [weak self] in self?.fixPre(r) }
        after(r, at: r.tGrab + LightKey.keyOnWithin) { [weak self] in self?.keyOnDeadline(r) }
    }

    /// P is known 40 ms after the grab.
    private func fixPre(_ r: Round) {
        guard round === r else { return }
        // DIP and RISE: from the reading that fired. (Readings come ~100 ms apart, so "80 ms before" holds no
        // older one on the pushed stream; a polled or simulated one could still show the room before the cover.)
        // KNOCK has no reading of its own: 80 ms before the knock arrived.
        let from = r.trigger == .knock ? r.t0 - LightKey.preBefore : r.t0
        let window = sensor.samples(from: from, to: r.tGrab + LightKey.preAfterGrab).map(\.lux)
        r.pre = window.max() ?? sensor.latest(atOrBefore: r.tGrab + LightKey.preAfterGrab)?.lux ?? r.room
        r.covered = !r.dark && r.pre <= max(LightKey.coverFloor, LightKey.coverFraction * r.room)
        r.preReady = true
        if TestMode.on { NSLog("TapDrop[test]: round %d P=%.0f B=%.0f %@", r.id, r.pre, r.room, r.covered ? "covered" : "open") }
        for (id, p) in r.phones where p.keyOn != nil && p.need == nil && p.verdict == nil { judge(r, id) }
    }

    private func keyOnDeadline(_ r: Round) {
        guard round === r else { return }
        for (id, p) in r.phones where p.keyOn == nil && p.verdict == nil && r.winner == nil {
            fail(r, id, "no-light-sent")
        }
        checkDecided(r)
    }

    /// Sets `need` for this phone's light and checks the readings so far.
    private func judge(_ r: Round, _ id: UUID) {
        guard var p = r.phones[id], let on = p.keyOn, p.need == nil, p.verdict == nil else { return }
        guard p.src == "screen" else {
            // A torch is a point source: from across a desk it can light a sensor a hand half covers, or one the
            // phone shades, as brightly as it does on contact. Only a screen's light tells contact from distance.
            fail(r, id, "screen-only")
            return
        }
        // E: how bright this phone's screen reads on the notch (learned from its taps). Its tap needs white − black
        // ≥ 0.55·E; key-seen comes once the notch clearly sees light: ≥ min(0.55·E, P + 0.4·E). A calibration hold
        // asks for a set level instead: it's there to measure the phone.
        let e = TapCalibrations.shared.contact(for: p.deviceId)
        p.light = p.calibrating ? 0 : LightKey.needed(contact: e)
        p.need = p.calibrating
            ? max(r.room + LightKey.calibrateOverRoom, LightKey.calibrateFloor)
            : max(LightKey.seenFloor, min(LightKey.lightFraction * e, r.pre + LightKey.riseFraction * e))
        r.phones[id] = p
        // The light may already be in readings taken while key-on was on its way.
        for s in sensor.samples(from: r.tGrab + LightKey.seeFromGrab, to: Self.now) where see(r, id, s) {
            return
        }
        r.phones[id]?.deadline = after(r, at: on + LightKey.seeWithin) { [weak self] in
            guard let self, self.round === r, let p = r.phones[id], p.verdict == nil, p.seenAt == nil else { return }
            // A light that reached its level and was still climbing when the window ended counts as seen.
            if p.overAt != nil, r.winner == nil, r.candidate == nil {
                self.seen(r, id, p.peak)
                return
            }
            self.fail(r, id, r.winner == nil ? "not-seen" : "other-phone")
        }
    }

    /// The rise: one reading against one phone's window. True if it made this phone the candidate.
    @discardableResult
    private func see(_ r: Round, _ id: UUID, _ s: LightSample) -> Bool {
        guard var p = r.phones[id], p.verdict == nil, p.seenAt == nil, p.keyOff == nil, let on = p.keyOn,
              let need = p.need, s.t >= r.tGrab + LightKey.seeFromGrab, s.t <= on + LightKey.seeWithin else { return false }
        // A calibration hold measures the white screen itself: what the phone showed before (bright content on the
        // notch can reach the lower calibration level) isn't a sample.
        if p.calibrating, s.t < on + LightKey.calibrateFrom { return false }
        let before = p.lastLux
        p.lastLux = s.lux
        p.peak = max(p.peak, s.lux)
        r.phones[id] = p
        guard s.lux >= need, r.winner == nil, r.candidate == nil else { return false }
        // Hold the white until the readings stop climbing, so the phone is measured at full brightness (not on its
        // brightness ramp): key-seen at the first reading within 4 % of the one before, or after a short hold.
        let first = p.overAt == nil
        if first { r.phones[id]?.overAt = s.t }
        let climbing = first || s.lux > before * LightKey.calibratePlateau
        let hold = p.calibrating ? LightKey.calibrateHold : LightKey.seenHold
        if climbing, s.t - (p.overAt ?? s.t) < hold { return false }
        seen(r, id, s.lux)
        return true
    }

    /// The notch sees this phone's light: "key-seen", and the phone must turn its screen black and say so.
    private func seen(_ r: Round, _ id: UUID, _ lux: Double) {
        guard var p = r.phones[id] else { return }
        r.candidate = id
        p.seenAt = Self.now
        p.deadline?.cancel()
        p.deadline = after(r, at: Self.now + LightKey.keyOffWithin) { [weak self] in
            guard let self, self.round === r, let p = r.phones[id], p.verdict == nil, p.keyOff == nil else { return }
            self.fail(r, id, "no-key-off")
        }
        r.phones[id] = p
        if TestMode.on { NSLog("TapDrop[test]: key-seen %d %@ at %.0f lux (need %.0f)", r.id, p.name, lux, p.need ?? 0) }
        WatchHub.shared.send(id, ["t": "key-seen", "id": r.id, "peak": Self.r1(lux), "need": Self.r1(p.need ?? 0)])
    }

    /// The fall: a reading for the candidate. Until key-off it's still its light; after key-off the notch must go
    /// dark with it.
    private func echo(_ r: Round, _ id: UUID, _ s: LightSample) {
        guard var p = r.phones[id], p.verdict == nil, p.seenAt != nil else { return }
        guard let off = p.keyOff, let fall = p.fall else {
            p.peak = max(p.peak, s.lux)
            r.phones[id] = p
            return
        }
        guard s.t > off else { return }
        p.low = min(p.low ?? s.lux, s.lux)
        r.phones[id] = p
        if s.lux <= fall { accept(r, id) }
    }

    private func sample(_ s: LightSample) {
        publishRoom(at: s.t)
        WatchHub.shared.streamLux(s.lux, room: sensor.baseline ?? s.lux, at: s.t)
        guard let r = round else { return }
        if r.winner == nil, let c = r.candidate { echo(r, c, s) }
        if r.winner == nil {
            for id in r.phones.keys where id != r.candidate { see(r, id, s) }
        }
        if let decided = r.decidedAt, s.t > decided { checkRelease(r, s) }
    }

    private func accept(_ r: Round, _ id: UUID) {
        guard var p = r.phones[id] else { return }
        if p.calibrating {
            sampled(r, id)
            return
        }
        r.winner = id
        r.candidate = nil
        p.verdict = .ok
        p.deadline?.cancel()
        r.phones[id] = p
        let (need, peak, low) = (p.light ?? p.need ?? 0, p.peak, p.low ?? 0)
        let round = r.id
        passLock.withLock { passes[id] = (round, Self.now) }
        // This tap's light (white − black) keeps the phone's contact level current.
        TapCalibrations.shared.learn(p.deviceId, light: peak - low)
        verdict(r, id, ["t": "key-ok", "id": r.id, "peak": Self.r1(peak), "need": Self.r1(need), "fall": Self.r1(low)])
        for (other, o) in r.phones where other != id && o.keyOn != nil && o.verdict == nil { fail(r, other, "other-phone") }
        checkDecided(r)
        let result = TapStatus.TapResult(
            time: Date(), phone: p.name, trigger: r.trigger.rawValue, src: p.src, room: r.room, pre: r.pre,
            peak: peak, need: need, accepted: true, why: nil)
        let (name, src, pre) = (p.name, p.src, r.pre)
        DispatchQueue.main.async {
            TapLog.shared.key(ok: true, phone: name, src: src, pre: pre, peak: peak, need: need, why: nil, fall: low)
            TapStatus.shared.finished(result)
            if !TestMode.headless { NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now) }
        }
    }

    /// A calibration hold: the light came and went with the phone, like a tap's, so its rise peak is how bright this
    /// phone's screen reads on the notch. The phone hears "calibrating" (nothing is sent, no error), and the card
    /// counts the sample.
    private func sampled(_ r: Round, _ id: UUID) {
        guard var p = r.phones[id] else { return }
        r.candidate = nil
        p.verdict = .failed("calibrating")
        p.deadline?.cancel()
        r.phones[id] = p
        let (need, peak, low) = (p.need ?? 0, p.peak, p.low ?? 0)
        verdict(r, id, ["t": "key-fail", "id": r.id, "peak": Self.r1(peak), "need": Self.r1(need), "fall": Self.r1(low),
                        "why": "calibrating"])
        for (other, o) in r.phones where other != id && o.keyOn != nil && o.verdict == nil { fail(r, other, "other-phone") }
        checkDecided(r)
        let device = p.deviceId
        DispatchQueue.main.async {
            TapStatus.shared.calibrated()
            TapTestModel.shared.calibrationSample(peak: peak, need: need)
            TapCalibration.shared.sampled(deviceId: device, peak: peak - low)
        }
    }

    private func fail(_ r: Round, _ id: UUID, _ why: String) {
        guard var p = r.phones[id], p.verdict == nil else { return }
        p.verdict = .failed(why)
        p.deadline?.cancel()
        r.phones[id] = p
        if r.candidate == id { r.candidate = nil }
        let need = (p.calibrating ? p.need : p.light ?? p.need) ?? 0
        verdict(r, id, ["t": "key-fail", "id": r.id, "peak": Self.r1(p.peak), "need": Self.r1(need), "why": why])
        // A gentle hint when the phone did light up and the notch saw some of it. A round where the light never
        // reached the notch (a hand over it, a phone in a pocket) stays quiet. While calibrating, the card says it.
        let emitted = p.keyOn != nil && !["other-phone", "late", "gone", "stopped"].contains(why)
        let nearby = p.peak >= max(1.5 * r.pre, r.pre + 20) || why == "screen-only"
        let calibrating = p.calibrating
        let hint = emitted && nearby && wantsHint(why, calibrating: calibrating)
        let device = p.deviceId
        let result = TapStatus.TapResult(
            time: Date(), phone: p.name, trigger: r.trigger.rawValue, src: p.src, room: r.room, pre: r.pre,
            peak: p.peak, need: need, accepted: false, why: why)
        let (name, src, pre, peak, low) = (p.name, p.src, r.pre, p.peak, p.low)
        // A phone that drops off before it ever lit up was never part of what the notch showed.
        let quiet = why == "gone" && p.keyOn == nil
        checkDecided(r)
        DispatchQueue.main.async {
            if calibrating { TapCalibration.shared.missed(deviceId: device, why: why) }
            guard !quiet else { return }
            TapLog.shared.key(ok: false, phone: name, src: src, pre: pre, peak: peak, need: need, why: why, fall: low)
            if why != "other-phone" { TapStatus.shared.finished(result) }
            if hint { Activity.shared.keyHint(why) }
        }
    }

    /// key-ok / key-fail. Test runs may hold the answer open (TAPDROP_TEST_KEY_DELAY_MS, from key-on).
    private func verdict(_ r: Round, _ id: UUID, _ msg: JSON) {
        if TestMode.on { NSLog("TapDrop[test]: %@ %d peak=%@ need=%@ %@", msg.type, r.id, "\(msg["peak"] ?? "")", "\(msg["need"] ?? "")", msg.str("why") ?? "") }
        if testDelay > 0, let on = r.phones[id]?.keyOn, on + testDelay > Self.now {
            q.asyncAfter(deadline: .now() + (on + testDelay - Self.now)) { WatchHub.shared.send(id, msg) }
            return
        }
        WatchHub.shared.send(id, msg)
    }

    private func checkDecided(_ r: Round) {
        guard r.decidedAt == nil, round === r else { return }
        guard r.winner != nil || r.phones.values.allSatisfy({ $0.verdict != nil }) else { return }
        let t = Self.now
        r.decidedAt = t
        after(r, at: t + LightKey.releaseAfter) { [weak self] in
            guard let self, self.round === r else { return }
            self.release(r, why: "timeout")
        }
    }

    /// The phone has left the notch once its light is off and the sensor reads the room again.
    private func checkRelease(_ r: Round, _ s: LightSample) {
        // Readings that may still hold the phone's light (a reading integrates ~100 ms) don't count.
        guard r.lightsOff, s.t >= r.lightsOffAt + 0.15 else {
            r.settled = 0
            return
        }
        if r.covered {
            // The room's light is back. Much brighter than the room is the phone's own screen, still on the notch:
            // releasing then would let B follow that screen and skew the next taps.
            if s.lux > max(LightKey.uncoverFraction * r.room, LightKey.uncoverFloor),
               s.lux <= LightKey.roomBackTimes * r.room + LightKey.roomBackPlus {
                release(r, why: "uncovered")
            }
        } else if abs(s.lux - r.room) <= max(LightKey.settleFloor, LightKey.settleFraction * r.room) {
            r.settled += 1
            if r.settled >= LightKey.settleReadings { release(r, why: "settled") }
        } else {
            r.settled = 0
        }
    }

    private func release(_ r: Round, why: String) {
        guard round === r else { return }
        r.work.forEach { $0.cancel() }
        r.phones.values.forEach { $0.deadline?.cancel() }
        round = nil
        if sensor.simulated, sensor.simLevel != NotchSensor.simulatedRoom {
            // Test mode: the round is over, so the simulated phone has left the notch. Left at its black screen's
            // 1 lux (or a lamp's 900), B would drift there and every later round would run in the wrong room.
            simPhoneAtNotch = false
            sensor.simulate(NotchSensor.simulatedRoom)
        }
        sensor.endRound(at: Self.now)
        if let w = r.winner { WatchHub.shared.send(w, ["t": "release", "id": r.id]) }
        if TestMode.on { NSLog("TapDrop[test]: round %d released (%@)", r.id, why) }
    }

    /// Whether to show the notch hint for this reason now: once per 30 s, and only for a tap that has something to
    /// say. A calibration hold (or anything else without words) never uses the allowance up.
    private func wantsHint(_ why: String, calibrating: Bool) -> Bool {
        guard !calibrating, !TapCopy.quiet(why), TapCopy.hint(why) != nil else { return false }
        let t = Self.now
        guard t - lastHint >= LightKey.hintEvery else { return false }
        lastHint = t
        return true
    }

    @discardableResult
    private func after(_ r: Round, at t: TimeInterval, _ body: @escaping () -> Void) -> DispatchWorkItem {
        let item = DispatchWorkItem(block: body)
        r.work.append(item)
        q.asyncAfter(deadline: .now() + max(0, t - Self.now), execute: item)
        return item
    }

    /// Test mode: the simulated sensor answers the light (TAPDROP_TEST_KEY). Never before P is fixed (a test
    /// client may answer within a millisecond; a real phone's light can't be on that soon).
    /// - pass: a bright reading after 120 ms (900 lux, or 1.2 × need when that's higher), and dark (1 lux, the black
    ///   phone on the notch) as soon as that phone says key-off.
    /// - stay: the bright reading, but it stays when the phone goes dark (a lamp, not the phone).
    /// - fail: 6-lux readings, so the window runs out.
    /// - uncover: the notch reads the room again (the hand that covered it left, as the phone lit up elsewhere).
    private func simulateLight(_ r: Round, _ id: UUID) {
        let first = max(Self.now, r.tGrab + LightKey.preAfterGrab + 0.002)
        switch testKey {
        case "fail":
            after(r, at: first) { [weak self] in
                guard let self, self.round === r else { return }
                self.sensor.simulate(6)
            }
        case "uncover":
            after(r, at: first) { [weak self] in
                guard let self, self.round === r else { return }
                self.simPhoneAtNotch = false
                self.sensor.simulate(NotchSensor.simulatedRoom)
            }
        default:
            after(r, at: max(first, Self.now + 0.12)) { [weak self] in
                // Only while that phone's light is on and still being judged (like a real one).
                guard let self, self.round === r, let p = r.phones[id], p.keyOff == nil, p.verdict == nil else { return }
                self.sensor.simulate(self.testPeak ?? max(900, ((p.need ?? LightKey.screenMin) * 1.2).rounded(.up)))
            }
        }
    }

    /// Test mode: a phone's light went off.
    private func simulateDark(_ r: Round, _ id: UUID) {
        guard testKey != "stay" else { return }
        if r.candidate == id || r.winner == id {
            // The phone the notch saw is the one on it: its black screen leaves the sensor (almost) dark.
            sensor.simulate(simPhoneAtNotch ? 1 : NotchSensor.simulatedRoom)
        } else if r.candidate == nil, r.lightsOff {
            // Nothing lit any more; the simulated phone stays on the notch until USR2.
            sensor.simulate(simPhoneAtNotch ? min(r.pre, 4) : NotchSensor.simulatedRoom)
        }
    }

    // MARK: room

    private func roomFlipped(_ dark: Bool, _ b: Double) {
        room = (dark, b)
        WatchHub.shared.broadcast(["t": "room", "dark": dark, "lux": Self.r1(b)])
        if TestMode.on { NSLog("TapDrop[test]: room %@ (%.0f lux)", dark ? "dark" : "lit", b) }
        publishRoom(at: Self.now, force: true)
    }

    /// TapStatus gets meaningful changes only: a dark flip, or ≥ 5 % (≥ 1 lux), at most twice a second.
    private func publishRoom(at t: TimeInterval, force: Bool = false) {
        guard let b = sensor.baseline else { return }
        let d = sensor.isDark
        if room != nil { room = (d, b) }
        if let p = published, !force {
            guard t - p.at >= 0.5, p.dark != d || abs(p.lux - b) >= max(1, 0.05 * p.lux) else { return }
        }
        published = (d, b, t)
        DispatchQueue.main.async { TapStatus.shared.room(lux: b, dark: d) }
    }

    private func sensorStopped() {
        if let r = round {
            // Tap to Send was switched off mid-round: whoever is still lit switches off now, and nothing passes.
            for id in r.phones.keys { fail(r, id, "stopped") }
            release(r, why: "sensor stopped")
        }
        room = nil
        published = nil
        DispatchQueue.main.async {
            TapStatus.shared.room(lux: nil, dark: false)
            TapCalibration.shared.sensorStopped()
        }
    }

    private static func r1(_ v: Double) -> Double { (v * 10).rounded() / 10 }
}
