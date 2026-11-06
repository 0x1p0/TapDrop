import Foundation
import IOKit

// MARK: - Light Key numbers

/// Every Light Key threshold, in one place.
///
/// A send needs a *light key* with two edges:
/// 1. Right after the phone says its screen is white at full brightness ("key-on"), the ambient light sensor (ALS)
///    next to the camera in the notch must read a level only a screen pressed on the notch gives.
/// 2. Right after the phone says the screen went black ("key-off", which it does as soon as the Mac says "key-seen"),
///    the sensor must go dark with it — in a lit room, darker than the room: the phone sits on the sensor.
/// A hand or a shadow can darken the sensor but can't make light; a lamp, the sun or a hand leaving the notch can't
/// go dark on cue; a phone a few centimetres away can't reach the level. Only the screen counts: a torch is a point
/// source bright enough to reach the sensor from across a desk. Lux values are what the notch ALS reports.
enum LightKey {
    /// `caps.key` a phone must announce before it gets grabs (3: the light key with its dark echo).
    static let version = 3

    // MARK: room
    // B is the room's level at the notch: a slow follow of the uncovered light, frozen during rounds.

    /// A lit room turns dark when B falls under 5 lux, and a dark one lit again above 8 (hysteresis around 6).
    static let darkBelow = 5.0
    static let litAbove = 8.0
    /// The first reading decides with the plain 6-lux line.
    static let darkLine = 6.0
    /// B follows a similar or brighter level with a 0.5 s time constant, and anything under 70 % of it with
    /// 10 s, so a cover barely moves it while a lamp switched on is followed within a second or two.
    static let followUp = 0.5
    static let followDown = 10.0
    static let darkerThan = 0.7

    // MARK: triggers (each starts a round; one round at a time)

    /// Look-back for the recent peak (DIP) or minimum (RISE).
    static let lookBack = 1.2
    /// Rounds start at least 1.5 s apart.
    static let roundGap = 1.5
    /// DIP, lit room (a phone screen showing dark content, or anything else, over the notch): fire on the first
    /// reading with lux ≤ max(2, 0.30·B) after a peak ≥ 0.75·B within the look-back and a drop of ≥ 3 lux.
    static let dipFloor = 2.0
    static let dipFraction = 0.30
    static let dipPeak = 0.75
    static let dipMinDrop = 3.0
    /// RISE, dark room (a phone screen arriving): lux ≥ max(B + 8, 3·B) and ≥ 8 above the look-back minimum.
    static let risePlus = 8.0
    static let riseTimes = 3.0
    static let riseMinStep = 8.0

    // MARK: the round

    /// P, the pre-light level: the highest reading from 80 ms before the trigger to 40 ms after the grab.
    static let preBefore = 0.080
    static let preAfterGrab = 0.040
    /// The phone must report its light on within 2 s of the grab ("no-light-sent" otherwise). It takes the screenshot
    /// first, which can take a second when the phone is cold, then waits for its white frame to reach the display…
    static let keyOnWithin = 2.0
    /// …and the sensor must see it from grab + 40 ms until 900 ms after key-on ("not-seen" otherwise; the screen's
    /// brightness ramps up to full over a few hundred ms from a dim setting).
    static let seeFromGrab = 0.040
    static let seeWithin = 0.900
    /// A screen still brightening when that window ends (in a dark room Android ramps a dim screen up to full over a
    /// second or more): the window stretches while the readings keep setting new highs, by up to `rampQuiet` past
    /// the last one, until `rampWithin` after key-on. A rise of at least `rampRise` (and 4 %) counts as a new high.
    /// Room light that happens to rise can't pass: the verdict is still the echo (white − black).
    static let rampWithin = 2.5
    static let rampQuiet = 0.35
    static let rampRise = 2.0
    /// Covered: a lit room and P ≤ max(2, 0.30·B) — something sits on the sensor (logged, and how release is judged).
    static let coverFloor = 2.0
    static let coverFraction = 0.30
    /// The phone's light, measured as a difference. While its screen is white the notch reads its light plus whatever
    /// room light gets in around it; once it's black, just that leak. White − black is the phone's own light, whatever
    /// the room — dark, daylight or sun. Light falls off fast with distance, so it must reach `lightFraction` (45 %) of what
    /// this phone gives pressed on the notch (E, learned from its own taps): a phone a few centimetres away can't.
    /// Measured on a MacBook Air M4 with an Android phone, E is 200–460 lux; `defaultContact` stands in until a phone has
    /// passed a few taps on this Mac.
    static let lightFraction = 0.45
    /// A knock (the phone's proximity sensor) comes a centimetre or two before the phone lands: its round starts
    /// this much later, so the screen lights up once it's on the notch.
    static let knockSettle = 0.25
    /// …and only once the notch shows the phone on it: a reading at least max(1 lux, 20 %) away from the room's level
    /// (the phone blocks the room's light, or adds its screen's), within 1 s of the knock: a phone pressed to the notch
    /// always moves it, often before its proximity sensor's knock arrives (so readings from 0.3 s before count), at
    /// times only as it lands. A phone knocking elsewhere (a thumb or a pillow on its proximity sensor) never does.
    static let knockMinStep = 1.0
    static let knockFraction = 0.20
    static let knockWithin = 1.0
    static let knockLookBack = 0.3
    static let defaultContact = 300.0
    static let contactMin = 180.0
    static let contactMax = 1500.0
    /// E is the median of the phone's last 12 passed taps' white − black, once it has 3.
    static let learnKeep = 12
    static let learnMin = 3
    /// key-seen (the phone turns black) once the notch clearly sees light after key-on: a reading ≥ min(0.55·E, P + 0.4·E)
    /// (P: the level just before the light, so a phone showing bright content already counts), at least 40 lux, held
    /// until the readings stop climbing (within 4 % of the one before) or 0.25 s, so the phone is measured at full
    /// brightness. The verdict is the echo below; this only decides when to ask for it.
    static let riseFraction = 0.4
    static let seenFloor = 40.0
    static let seenHold = 0.25

    /// The light this phone's taps need (white − black), for display: `lightFraction` of its contact level.
    static func needed(contact: Double) -> Double {
        (lightFraction * min(max(contact, contactMin), contactMax)).rounded()
    }
    /// For a phone with no taps yet.
    static var screenMin: Double { needed(contact: defaultContact) }

    // MARK: calibration (per phone, see TapCalibration.swift)
    // How bright a phone's white screen reads on this Mac's notch depends on the phone and the Mac. Calibrating
    // measures it: three holds at the notch, each a full light key (rise and fall) that ends in key-fail
    // "calibrating", so nothing is sent.

    /// A calibration round's rise asks only for max(B + 150, 200)…
    static let calibrateOverRoom = 150.0
    static let calibrateFloor = 200.0
    /// …and holds the white until the readings stop climbing (a reading within 4 % of the one before) or for 0.35 s,
    /// so the sample is the screen at full brightness, not a point on its brightness ramp.
    static let calibrateHold = 0.35
    static let calibratePlateau = 1.04
    /// Only readings from 100 ms after key-on count (one sensor integration after the white frame), so whatever the
    /// phone showed before it lit up (bright content can reach the lower calibration level) is never a sample.
    static let calibrateFrom = 0.100
    static let calibrationSamples = 3
    /// Calibration stops after 45 s without a sample.
    static let calibrateIdle = 45.0
    /// A tap the notch didn't see, whose peak still reached 40 % of need, suggests calibrating.
    static let suggestCalibrate = 0.4

    /// What a calibrated phone's taps need (white − black).
    static func screenMin(contact: Double) -> Double { needed(contact: contact) }

    static func median(_ values: [Double]) -> Double {
        let v = values.sorted()
        guard !v.isEmpty else { return 0 }
        return v.count % 2 == 1 ? v[v.count / 2] : (v[v.count / 2 - 1] + v[v.count / 2]) / 2
    }
    /// The echo. After key-seen the phone turns its screen black and says "key-off" within 1 s ("no-key-off"
    /// otherwise: it reports key-off once the black frame is on the display, and a busy phone can take a few hundred
    /// ms to get there). A reading taken after key-off, within 450 ms, must fall far enough that the white − black
    /// difference reaches the phone's needed light (peak − reading ≥ 0.55·E) and, in a lit room, to ≤ max(0.6·B, 8):
    /// the black phone blocks the room's light, so it's on the sensor ("stayed-lit" otherwise: a lamp, the sun, a hand
    /// that just left, or a phone held off the notch). If even a fully dark notch couldn't make the difference, the
    /// phone's light was too weak to be on the notch ("not-seen"). Calibration holds use ≤ peak / 3 instead.
    static let keyOffWithin = 1.0
    static let fallWithin = 0.45
    static let fallFraction = 1.0 / 3
    static let blockFraction = 0.75
    static let blockFloor = 12.0

    // MARK: release (the phone left the notch)

    /// Covered rounds: max(0.6·B, 3) < lux ≤ 1.6·B + 20 (brighter is the phone's screen, still on the notch).
    static let uncoverFraction = 0.6
    static let uncoverFloor = 3.0
    static let roomBackTimes = 1.6
    static let roomBackPlus = 20.0
    /// Open rounds, after key-off: |lux − B| ≤ max(4, 0.5·B) for 2 readings in a row.
    static let settleFloor = 4.0
    static let settleFraction = 0.5
    static let settleReadings = 2
    /// Either way, at the latest 6 s after the verdict.
    static let releaseAfter = 6.0

    // MARK: hints

    /// A gentle notch hint after a failed key, at most once per 30 s.
    static let hintEvery = 30.0

    // MARK: sensor rates
    //
    // Measured on this MacBook Air M4 (Mac16,12, macOS 27.2), 5 s per setting, room at ~40 lux:
    //
    //   ReportInterval (µs)   pushed events/s   polled distinct readings/s
    //   197 380 (default)          4.8               5.0
    //   100 000                    9.8               8.8
    //    40 000                    9.8               7.4
    //    20 000                    9.8               8.6
    //    10 000                    9.8               8.6
    //     5 000                    9.8               8.6
    //
    // The sensor tops out at ~10 readings/s (a ~100 ms integration, which also cancels 50/60 Hz flicker): asked
    // for anything faster, the driver runs at 97 300 µs (I/O Registry). So asking for less than ~100 ms gains
    // nothing, and rounds and watching use the same interval. ReportInterval is a per-client request: the event
    // system runs the driver at the fastest one asked for, pushes readings only to clients that asked, and drops
    // a request with its client. Readings are pushed to us as they happen (IOHIDEventSystemClient event
    // callback; ~0.04 % CPU), so there's no polling delay. Polling remains as a fallback if nothing is pushed.
    static let watchInterval = 40_000
    static let roundInterval = 40_000
    /// Fallback polling periods (s).
    static let pollWatch = 0.050
    static let pollRound = 0.020
}

/// Test hooks — the simulated light sensor and its simulated light keys, the USR1/USR2 signals, the self-test and
/// auto-pairing — exist only in debug builds. A release build ignores TAPDROP_TEST*: a simulated sensor answers every
/// key-on with light, and auto-pairing accepts anyone on the network, so neither may ever be on in the app people use.
enum TestHooks {
    #if DEBUG
    static let on = TestMode.on
    #else
    static let on = false
    #endif
}

// MARK: - Detection logic (no hardware)

struct LightSample {
    let t: TimeInterval  // ProcessInfo.systemUptime
    let lux: Double
}

/// The room baseline B, the dark flag and the DIP/RISE triggers, with no hardware attached, so the same code
/// runs on real readings, simulated ones (test mode) and synthetic ones (self-test).
struct NotchDetector {
    enum Trigger: String {
        case dip, rise, knock
    }

    struct Fired {
        let trigger: Trigger
        let from: Double  // DIP: the recent peak; RISE: the recent minimum
        let to: Double  // the reading that fired
        let at: TimeInterval
    }

    private(set) var baseline: Double?
    private(set) var dark: Bool?
    /// B stops following while a round runs and until the phone has left.
    var frozen = false
    /// Readings before this don't count towards a trigger (set when a round ends).
    private(set) var armedAt: TimeInterval = -.infinity
    private(set) var recent: [LightSample] = []
    private var lastRound: TimeInterval = -.infinity
    private var lastT: TimeInterval?

    mutating func rearm(at t: TimeInterval) { armedAt = t }
    mutating func roundStarted(at t: TimeInterval) { lastRound = t }

    /// Feeds one reading. Returns whether the dark flag changed (or became known), and a trigger if one fired.
    mutating func feed(_ s: LightSample, detect: Bool = true) -> (roomChanged: Bool, fired: Fired?) {
        recent.append(s)
        if let first = recent.first, s.t - first.t > 4 { recent.removeAll { s.t - $0.t > 4 } }

        // Triggers are judged against B as it was before this reading.
        var fired: Fired?
        if detect, !frozen, let b = baseline, let isDark = dark, s.t - lastRound >= LightKey.roundGap {
            let from = max(s.t - LightKey.lookBack, armedAt)
            let window = recent.filter { $0.t >= from }.map(\.lux)
            if !isDark {
                let peak = window.max() ?? s.lux
                if s.lux <= max(LightKey.dipFloor, LightKey.dipFraction * b),
                   peak >= LightKey.dipPeak * b, peak - s.lux >= LightKey.dipMinDrop {
                    fired = Fired(trigger: .dip, from: peak, to: s.lux, at: s.t)
                }
            } else {
                let low = window.min() ?? s.lux
                if s.lux >= max(b + LightKey.risePlus, LightKey.riseTimes * b), s.lux - low >= LightKey.riseMinStep {
                    fired = Fired(trigger: .rise, from: low, to: s.lux, at: s.t)
                }
            }
            if fired != nil { lastRound = s.t }
        }

        var roomChanged = false
        if !frozen && fired == nil {
            if let b = baseline {
                let dt = min(1, max(0, s.t - (lastT ?? s.t)))
                let tau = s.lux >= LightKey.darkerThan * b ? LightKey.followUp : LightKey.followDown
                baseline = b + (1 - exp(-dt / tau)) * (s.lux - b)
            } else {
                baseline = s.lux
            }
            let b = baseline ?? s.lux
            let was = dark
            switch dark {
            case nil: dark = b < LightKey.darkLine
            case true?: if b > LightKey.litAbove { dark = false }
            case false?: if b < LightKey.darkBelow { dark = true }
            }
            roomChanged = was != dark
        }
        lastT = s.t
        return (roomChanged, fired)
    }
}

// MARK: - The sensor

/// Watches the ambient light sensor that sits in the MacBook notch, next to the camera.
///
/// While a phone is live it streams readings (~10 a second, pushed by the HID event system), keeps the room
/// baseline, and fires DIP (lit room, the notch suddenly covered) or RISE (dark room, light arriving) triggers.
/// A trigger alone never sends anything: it starts a light-key round (see `KeyRounds`).
final class NotchSensor: @unchecked Sendable {
    static let shared = NotchSensor()

    /// Kept for older callers; covers now start rounds through `onTrigger`.
    var onCover: (() -> Void)?
    var onUncover: (() -> Void)?
    /// Live readings (lux, room level) for Settings' meter; main queue, only while testing. Set on the main thread
    /// and read on `queue`, so it lives behind a lock.
    var onReading: ((Double, Double) -> Void)? {
        get { readingLock.withLock { _onReading } }
        set { readingLock.withLock { _onReading = newValue } }
    }
    private var _onReading: ((Double, Double) -> Void)?
    private let readingLock = NSLock()
    /// A DIP or RISE fired. Called on `queue`.
    var onTrigger: ((NotchDetector.Fired) -> Void)?
    /// Every reading while active. Called on `queue`.
    var onSample: ((LightSample) -> Void)?
    /// The room's dark flag changed or became known (dark, B). Called on `queue`.
    var onRoom: ((Bool, Double) -> Void)?
    /// Streaming stopped (no phone live any more). Called on `queue`.
    var onStop: (() -> Void)?

    /// All detection state lives on this queue.
    let queue = DispatchQueue(label: "app.tapdrop.notch", qos: .userInteractive)

    private typealias CreateFn = @convention(c) (CFAllocator?) -> Unmanaged<AnyObject>?
    private typealias SetMatchingFn = @convention(c) (AnyObject, CFDictionary) -> Void
    private typealias CopyServicesFn = @convention(c) (AnyObject) -> Unmanaged<CFArray>?
    private typealias CopyEventFn = @convention(c) (AnyObject, Int64, Int32, Int64) -> Unmanaged<AnyObject>?
    private typealias GetFloatFn = @convention(c) (AnyObject, Int32) -> Double
    private typealias SetPropFn = @convention(c) (AnyObject, CFString, AnyObject) -> Bool
    private typealias EventCallback = @convention(c) (
        UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeMutableRawPointer?, UnsafeMutableRawPointer?) -> Void
    private typealias RegisterFn = @convention(c) (AnyObject, EventCallback, UnsafeMutableRawPointer?, UnsafeMutableRawPointer?) -> Void
    private typealias SetQueueFn = @convention(c) (AnyObject, DispatchQueue) -> Void
    private typealias ClientFn = @convention(c) (AnyObject) -> Void
    private typealias CancelHandlerFn = @convention(c) (AnyObject, @escaping @convention(block) () -> Void) -> Void
    private typealias EventTypeFn = @convention(c) (UnsafeMutableRawPointer) -> UInt32
    private typealias EventFloatFn = @convention(c) (UnsafeMutableRawPointer, Int32) -> Double

    private static let alsMatching = ["PrimaryUsagePage": 0xff00, "PrimaryUsage": 4] as CFDictionary  // Apple vendor page, ALS
    private static let alsEvent: Int64 = 12  // kIOHIDEventTypeAmbientLightSensor
    private static let alsLevel = Int32(12 << 16)  // kIOHIDEventFieldAmbientLightSensorLevel

    private var create: CreateFn?
    private var setMatching: SetMatchingFn?
    private var copyServices: CopyServicesFn?
    private var copyEvent: CopyEventFn?
    private var getFloat: GetFloatFn?
    private var setProp: SetPropFn?
    private var register: RegisterFn?
    private var setQueue: SetQueueFn?
    private var activate: ClientFn?
    private var cancel: ClientFn?
    private var setCancelHandler: CancelHandlerFn?
    private var eventType: EventTypeFn?
    private var eventFloat: EventFloatFn?
    /// The long-lived client: one-off reads, and the polling fallback. Never cancelled.
    private var client: AnyObject?
    private var service: AnyObject?
    /// The sensor driver's own report interval (µs): what the long-lived client asks for again once it stops polling.
    private var defaultInterval: Int?

    // queue-confined
    private(set) var detector = NotchDetector()
    private var wanted = false
    private var testing = false
    private var inRound = false
    private var streaming = false
    private var push: AnyObject?  // a fresh client per stream; never touched after it's cancelled
    private var pushKeeper: Keeper?  // keeps `push` alive through its cancellation
    private var pushService: AnyObject?  // its ALS service object (valid only while `push` is)
    private var lastPush: TimeInterval = 0
    private var slowGaps = 0
    private var lastReassert: TimeInterval = 0
    private var pollTimer: DispatchSourceTimer?
    private var watchdog: DispatchSourceTimer?
    private var simTimer: DispatchSourceTimer?

    // Test mode (debug builds only, see `TestHooks`): a simulated sensor (no hardware), driven by `simulate(_:)`.
    // The room reads 120 lux, or TAPDROP_TEST_ROOM. TAPDROP_TEST_ALS=real keeps the hardware sensor in test mode
    // (e.g. to measure CPU with a phone live).
    let simulated = TestHooks.on && ProcessInfo.processInfo.environment["TAPDROP_TEST_ALS"] != "real"
    static let simulatedRoom = ProcessInfo.processInfo.environment["TAPDROP_TEST_ROOM"].flatMap(Double.init) ?? 120.0
    private(set) var simLevel = NotchSensor.simulatedRoom

    private let levelLock = NSLock()
    private var level: Double = -1
    private var darkNow: Bool?
    private var simShadow = NotchSensor.simulatedRoom

    /// Below this the room counts as dark (B < 6 lux): covering can't be seen, so taps use the phone's screen light.
    static let minLux: Double = LightKey.darkLine

    /// The room's normal light level at the notch (B), or nil if it hasn't been read yet.
    var normalLevel: Double? {
        levelLock.withLock { level >= 0 ? level : nil }
    }

    /// The room is dark right now (one quick read if the sensor isn't running).
    var tooDark: Bool {
        guard available else { return false }
        if let d = levelLock.withLock({ darkNow }) { return d }
        return (read() ?? .infinity) < LightKey.darkLine
    }

    private(set) var available = false

    private init() {
        if simulated {
            available = true
            return
        }
        guard let h = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_NOW),
              let c = dlsym(h, "IOHIDEventSystemClientCreate"),
              let m = dlsym(h, "IOHIDEventSystemClientSetMatching"),
              let s = dlsym(h, "IOHIDEventSystemClientCopyServices"),
              let e = dlsym(h, "IOHIDServiceClientCopyEvent"),
              let f = dlsym(h, "IOHIDEventGetFloatValue"),
              let p = dlsym(h, "IOHIDServiceClientSetProperty")
        else { return }
        create = unsafeBitCast(c, to: CreateFn.self)
        setMatching = unsafeBitCast(m, to: SetMatchingFn.self)
        copyServices = unsafeBitCast(s, to: CopyServicesFn.self)
        copyEvent = unsafeBitCast(e, to: CopyEventFn.self)
        getFloat = unsafeBitCast(f, to: GetFloatFn.self)
        setProp = unsafeBitCast(p, to: SetPropFn.self)
        eventFloat = unsafeBitCast(f, to: EventFloatFn.self)
        // Pushed events (optional; polling otherwise).
        if let r = dlsym(h, "IOHIDEventSystemClientRegisterEventCallback"),
           let q = dlsym(h, "IOHIDEventSystemClientSetDispatchQueue"),
           let a = dlsym(h, "IOHIDEventSystemClientActivate"),
           let x = dlsym(h, "IOHIDEventSystemClientCancel"),
           let ch = dlsym(h, "IOHIDEventSystemClientSetCancelHandler"),
           let t = dlsym(h, "IOHIDEventGetType") {
            register = unsafeBitCast(r, to: RegisterFn.self)
            setQueue = unsafeBitCast(q, to: SetQueueFn.self)
            activate = unsafeBitCast(a, to: ClientFn.self)
            cancel = unsafeBitCast(x, to: ClientFn.self)
            setCancelHandler = unsafeBitCast(ch, to: CancelHandlerFn.self)
            eventType = unsafeBitCast(t, to: EventTypeFn.self)
        }
        guard let create, let setMatching, let copyServices,
              let cl = create(kCFAllocatorDefault)?.takeRetainedValue() else { return }
        setMatching(cl, Self.alsMatching)
        client = cl
        service = (copyServices(cl)?.takeRetainedValue() as? [AnyObject])?.first
        available = service != nil && read() != nil
        // The driver's interval right now. Under 100 ms some other client is asking for fast readings; the
        // driver's own pace (when nobody asks) is ~5 Hz.
        if let d = Self.driverInterval() { defaultInterval = d >= 100_000 ? d : 197_380 }
    }

    func read() -> Double? {
        if simulated { return levelLock.withLock { simShadow } }
        guard let service, let copyEvent, let getFloat,
              let ev = copyEvent(service, Self.alsEvent, 0, 0)?.takeRetainedValue()
        else { return nil }
        return getFloat(ev, Self.alsLevel)
    }

    /// Streams only while a phone is listening — no point burning cycles otherwise.
    func setActive(_ on: Bool) {
        queue.async { [self] in
            wanted = on
            update()
        }
    }

    /// Settings' meter is open: read the sensor even with no phone connected.
    func setTesting(_ on: Bool) {
        queue.async { [self] in
            testing = on
            update()
        }
    }

    // MARK: rounds (call on `queue`)

    /// A round started: freeze the baseline and ask for the round's report interval.
    func beginRound(at t: TimeInterval) {
        dispatchPrecondition(condition: .onQueue(queue))
        inRound = true
        detector.frozen = true
        detector.roundStarted(at: t)
        setInterval(LightKey.roundInterval)
        if pollTimer != nil { startPolling() }
    }

    /// The round is over and the phone has left: follow the room again; only readings from now on can trigger.
    func endRound(at t: TimeInterval) {
        dispatchPrecondition(condition: .onQueue(queue))
        inRound = false
        detector.frozen = false
        detector.rearm(at: t)
        if streaming { setInterval(LightKey.watchInterval) }
        if pollTimer != nil { startPolling() }
    }

    /// Readings with from ≤ t ≤ to.
    func samples(from: TimeInterval, to: TimeInterval) -> [LightSample] {
        dispatchPrecondition(condition: .onQueue(queue))
        return detector.recent.filter { $0.t >= from && $0.t <= to }
    }

    func latest(atOrBefore t: TimeInterval) -> LightSample? {
        dispatchPrecondition(condition: .onQueue(queue))
        return detector.recent.last { $0.t <= t }
    }

    var baseline: Double? {
        dispatchPrecondition(condition: .onQueue(queue))
        return detector.baseline
    }

    var isDark: Bool {
        dispatchPrecondition(condition: .onQueue(queue))
        return detector.dark ?? false
    }

    /// Test mode: the simulated sensor now reads `lux`; one reading goes through at once and the next ones follow
    /// every 100 ms from there, like the hardware (call on `queue`). Returns the reading, if streaming.
    /// Simulated readings never fire DIP/RISE themselves: test rounds are started explicitly (USR1, knocks).
    @discardableResult
    func simulate(_ lux: Double) -> LightSample? {
        dispatchPrecondition(condition: .onQueue(queue))
        simLevel = lux
        levelLock.withLock { simShadow = lux }
        guard streaming else { return nil }
        simTimer?.schedule(deadline: .now() + .milliseconds(100), repeating: .milliseconds(100), leeway: .milliseconds(5))
        return deliver(lux, detect: false)
    }

    // MARK: streaming

    private func update() {
        let on = (wanted || testing) && available
        if on && !streaming {
            streaming = true
            detector = NotchDetector()
            if simulated {
                // Like the hardware: a reading every ~100 ms.
                let t = DispatchSource.makeTimerSource(queue: queue)
                t.schedule(deadline: .now(), repeating: .milliseconds(100), leeway: .milliseconds(5))
                t.setEventHandler { [weak self] in
                    guard let self else { return }
                    self.deliver(self.simLevel, detect: false)
                }
                t.resume()
                simTimer = t
                return
            }
            if !startPush() { startPolling() }
        } else if !on && streaming {
            streaming = false
            simTimer?.cancel()
            simTimer = nil
            stopPush()
            stopPolling()
            inRound = false
            levelLock.withLock { darkNow = nil }
            onStop?()
        }
    }

    /// Asks for a report interval. It's a per-client request: the HID event system runs the driver at the
    /// fastest interval any client asks for (here it bottoms out at 97 300 µs) and drops a client's request with
    /// the client. The push client asks while it lives; the long-lived probe client only while it polls.
    private func setInterval(_ us: Int) {
        guard !simulated, let setProp else { return }
        if let pushService {
            _ = setProp(pushService, "ReportInterval" as CFString, NSNumber(value: us))
        } else if pollTimer != nil, let service {
            _ = setProp(service, "ReportInterval" as CFString, NSNumber(value: us))
        }
    }

    /// Readings pushed by the HID event system as the sensor reports them (no polling delay, ~10 wakeups a second).
    private func startPush() -> Bool {
        guard let create, let setMatching, let copyServices, let setProp, let register, let setQueue, let activate,
              let setCancelHandler, let c = create(kCFAllocatorDefault)?.takeRetainedValue()
        else { return false }
        setMatching(c, Self.alsMatching)
        // Events are only pushed to a client that asked for a report interval on its own service.
        guard let s = (copyServices(c)?.takeRetainedValue() as? [AnyObject])?.first else { return false }
        _ = setProp(s, "ReportInterval" as CFString, NSNumber(value: inRound ? LightKey.roundInterval : LightKey.watchInterval))
        setQueue(c, queue)
        register(c, Self.pushed, Unmanaged.passUnretained(self).toOpaque(), nil)
        // A client must not be freed before its cancel handler has run (cancelling finishes asynchronously on the
        // queue). `stopPush` hands the client to this keeper, and the handler lets go of it.
        let keeper = Keeper()
        setCancelHandler(c) { keeper.client = nil }
        activate(c)
        push = c
        pushKeeper = keeper
        pushService = s
        lastPush = ProcessInfo.processInfo.systemUptime
        // If nothing arrives (an older macOS that doesn't push to us), poll instead.
        let w = DispatchSource.makeTimerSource(queue: queue)
        w.schedule(deadline: .now() + 1.2, repeating: 2, leeway: .milliseconds(500))
        w.setEventHandler { [weak self] in
            guard let self, self.push != nil else { return }
            if ProcessInfo.processInfo.systemUptime - self.lastPush > 1.0 {
                NSLog("TapDrop: no pushed light readings; polling the notch sensor instead")
                self.stopPush()
                self.startPolling()
            }
        }
        w.resume()
        watchdog = w
        return true
    }

    private func stopPush() {
        watchdog?.cancel()
        watchdog = nil
        // Cancelling also drops this client's interval request. Its objects are never touched again; the keeper
        // holds the client until its cancel handler has run.
        pushService = nil
        if let c = push, let cancel {
            pushKeeper?.client = c
            cancel(c)
        }
        push = nil
        pushKeeper = nil
    }

    /// Holds a cancelled HID client until its cancel handler runs.
    private final class Keeper: @unchecked Sendable {
        var client: AnyObject?
    }

    private static let pushed: EventCallback = { target, _, _, event in
        guard let target, let event else { return }
        Unmanaged<NotchSensor>.fromOpaque(target).takeUnretainedValue().handlePushed(event)
    }

    private func handlePushed(_ event: UnsafeMutableRawPointer) {
        guard push != nil, let eventType, let eventFloat, eventType(event) == UInt32(Self.alsEvent) else { return }
        let now = ProcessInfo.processInfo.systemUptime
        // Something else set the sensor back to ~5 readings/s: ask again (at most every 3 s).
        slowGaps = now - lastPush > 0.16 ? slowGaps + 1 : 0
        lastPush = now
        if slowGaps >= 2, now - lastReassert > 3 {
            lastReassert = now
            setInterval(inRound ? LightKey.roundInterval : LightKey.watchInterval)
        }
        deliver(eventFloat(event, Self.alsLevel), detect: true)
    }

    /// The fallback: poll the long-lived client (also used to switch the period when a round starts or ends).
    private func startPolling() {
        let period = inRound ? LightKey.pollRound : LightKey.pollWatch
        if let t = pollTimer {
            t.schedule(deadline: .now() + period, repeating: period, leeway: .milliseconds(5))
        } else {
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now(), repeating: period, leeway: .milliseconds(5))
            t.setEventHandler { [weak self] in
                guard let self, let lux = self.read() else { return }
                self.deliver(lux, detect: true)
            }
            t.resume()
            pollTimer = t
        }
        setInterval(inRound ? LightKey.roundInterval : LightKey.watchInterval)
    }

    private func stopPolling() {
        guard let t = pollTimer else { return }
        t.cancel()
        pollTimer = nil
        // The long-lived client stays, so give its request back (the driver's own interval, read at launch).
        if let setProp, let service, let d = defaultInterval {
            _ = setProp(service, "ReportInterval" as CFString, NSNumber(value: d))
        }
    }

    private var statCount = 0
    private var statSince: TimeInterval = 0

    @discardableResult
    private func deliver(_ lux: Double, detect: Bool) -> LightSample {
        let s = LightSample(t: ProcessInfo.processInfo.systemUptime, lux: max(0, lux))
        if TestMode.on {
            statCount += 1
            if statSince == 0 { statSince = s.t }
            if s.t - statSince >= 10 {
                let how = simulated ? "simulated" : (push != nil ? "pushed" : "polled")
                NSLog("TapDrop[test]: notch sensor: %d readings in %.1f s (%@), now %.1f lux", statCount, s.t - statSince, how, s.lux)
                statCount = 0
                statSince = s.t
            }
        }
        let (roomChanged, fired) = detector.feed(s, detect: detect)
        if let b = detector.baseline {
            let d = detector.dark
            levelLock.withLock {
                level = b
                darkNow = d
            }
            if roomChanged, let d { onRoom?(d, b) }
        }
        onSample?(s)
        if testing, let cb = onReading {
            let b = detector.baseline ?? s.lux
            DispatchQueue.main.async { cb(s.lux, b) }
        }
        if let fired {
            NSLog("TapDrop: notch %@ (%.0f → %.0f lux, room %.0f)", fired.trigger.rawValue, fired.from, fired.to, detector.baseline ?? 0)
            onTrigger?(fired)
            if fired.trigger == .dip, let cb = onCover { DispatchQueue.main.async { cb() } }
        }
        return s
    }

    /// The light sensor driver's own report interval, read from the I/O Registry.
    private static func driverInterval() -> Int? {
        var iter: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, IOServiceMatching("AppleSPUHIDDriver"), &iter) == KERN_SUCCESS
        else { return nil }
        defer { IOObjectRelease(iter) }
        while true {
            let s = IOIteratorNext(iter)
            if s == 0 { return nil }
            defer { IOObjectRelease(s) }
            let product = IORegistryEntryCreateCFProperty(s, "Product" as CFString, kCFAllocatorDefault, 0)?
                .takeRetainedValue() as? String
            if product == "als",
               let v = IORegistryEntryCreateCFProperty(s, "ReportInterval" as CFString, kCFAllocatorDefault, 0)?
                   .takeRetainedValue() as? Int, v > 0 {
                return v
            }
        }
    }
}

// MARK: - Self-test

extension NotchDetector {
    /// Synthetic light traces through the real detector (TAPDROP_TEST_SELFTEST=1). Returns failures.
    static func selfTest() -> [String] {
        var failures: [String] = []
        func run(_ name: String, _ trace: [Double], dt: Double = 0.1, expect: Trigger?, expectAt: Int? = nil,
                 settle: Int = 40, settleLux: Double? = nil) {
            var d = NotchDetector()
            var t = 100.0
            // settle on the room first
            for _ in 0..<settle {
                _ = d.feed(LightSample(t: t, lux: settleLux ?? trace.first ?? 0))
                t += dt
            }
            var got: (Trigger, Int)?
            for (i, lux) in trace.enumerated() {
                if let f = d.feed(LightSample(t: t, lux: lux)).fired, got == nil { got = (f.trigger, i) }
                t += dt
            }
            let ok = got?.0 == expect && (expectAt == nil || got?.1 == expectAt)
            let desc = got.map { "\($0.0.rawValue) at #\($0.1)" } ?? "nothing"
            print(String(format: "  %@  %@ → %@", ok ? "PASS" : "FAIL", name, desc))
            if !ok { failures.append(name) }
        }
        print("Light key detector self-test")
        run("lit 57: phone covers the notch (57 → 30 → 3)", [57, 57, 30, 3, 3, 3], expect: .dip, expectAt: 3)
        run("lit 57: full cover in one reading", [57, 57, 4, 4], expect: .dip, expectAt: 2)
        run("lit 120: dark screen content over the notch (120 → 20)", [120, 120, 20, 20], expect: .dip, expectAt: 2)
        run("lit 57: lights dimmed over 5 s", (0..<50).map { 57 - Double($0) * 0.95 }, expect: nil)
        run("lit 57: shadow (57 → 35)", [57, 57, 35, 35, 57], expect: nil)
        run("lit 7: flicker 7 → 5 → 7", [7, 7, 5, 7, 7], expect: nil, settleLux: 7)
        run("dark 1: phone screen arriving (1 → 3 → 12 → 40)", [1, 1, 3, 12, 40, 120], expect: .rise, expectAt: 3)
        run("dark 1: small flicker (1 → 4 → 1)", [1, 1, 4, 1, 1], expect: nil)
        run("dark 0: phone 30 cm away (0 → 6)", [0, 0, 3, 6, 6], expect: nil)
        run("dark 2: lamp switched on (2 → 180)", [2, 2, 180, 180], expect: .rise, expectAt: 2)
        // hysteresis: a room fading from 10 to 5.5 stays lit, below 5 turns dark, back to 7.5 stays dark, 8+ lit
        var d = NotchDetector()
        var t = 0.0
        func hold(_ lux: Double, _ secs: Double) {
            for _ in 0..<Int(secs * 10) {
                _ = d.feed(LightSample(t: t, lux: lux))
                t += 0.1
            }
        }
        hold(10, 2)
        let lit0 = d.dark == false
        hold(5.5, 90)
        let lit1 = d.dark == false
        hold(4.5, 90)
        let dark1 = d.dark == true
        hold(7.5, 10)
        let dark2 = d.dark == true
        hold(8.6, 10)
        let lit2 = d.dark == false
        let hyst = lit0 && lit1 && dark1 && dark2 && lit2
        print("  \(hyst ? "PASS" : "FAIL")  room hysteresis 10 lit → 5.5 lit → 4.5 dark → 7.5 dark → 8.6 lit")
        if !hyst { failures.append("hysteresis") }
        // frozen baseline + re-arm: no trigger from readings before the re-arm
        var r = NotchDetector()
        t = 0
        for _ in 0..<30 {
            _ = r.feed(LightSample(t: t, lux: 57))
            t += 0.1
        }
        r.frozen = true
        for _ in 0..<10 {
            _ = r.feed(LightSample(t: t, lux: 900))
            t += 0.1
        }
        r.frozen = false
        r.rearm(at: t)
        r.roundStarted(at: t - 2)
        let after = r.feed(LightSample(t: t, lux: 3)).fired
        let frozeOK = after == nil && abs((r.baseline ?? 0) - 57) < 1
        print("  \(frozeOK ? "PASS" : "FAIL")  light-key spike while frozen leaves B at \(String(format: "%.1f", r.baseline ?? 0)), no DIP when it ends")
        if !frozeOK { failures.append("freeze/re-arm") }
        // rounds at least 1.5 s apart
        var g = NotchDetector()
        t = 0
        for _ in 0..<30 {
            _ = g.feed(LightSample(t: t, lux: 57))
            t += 0.1
        }
        g.roundStarted(at: t - 1.0)
        let tooSoon = g.feed(LightSample(t: t, lux: 3)).fired
        let gapOK = tooSoon == nil
        print("  \(gapOK ? "PASS" : "FAIL")  no trigger within 1.5 s of the last round")
        if !gapOK { failures.append("round gap") }
        // the needed light: 0.45 × the contact level (median), which counts within 180…1 500 lux
        let cal: [(String, [Double], Double)] = [
            ("1480, 1390, 1530 → contact 1480 → taps need 666", [1480, 1390, 1530], 666),
            ("2600, 2900, 2400 → 2600, capped at 1 500 → 675", [2600, 2900, 2400], 675),
            ("300, 260, 330 → 300 → 135", [300, 260, 330], 135),
            ("120, 140, 130 → 130, raised to 180 → 81", [120, 140, 130], 81),
            ("one stray sample doesn't move it: 300, 40, 320 → 135", [300, 40, 320], 135),
        ]
        for (name, samples, want) in cal {
            let got = LightKey.screenMin(contact: LightKey.median(samples))
            let ok = got == want
            print("  \(ok ? "PASS" : "FAIL")  calibration \(name) (got \(Int(got)))")
            if !ok { failures.append("calibration \(name)") }
        }
        return failures
    }
}
