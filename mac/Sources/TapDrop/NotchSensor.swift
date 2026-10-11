import Foundation
import IOKit

/// Watches the ambient light sensor that sits in the MacBook notch, next to the camera.
///
/// In a lit room, holding a phone over the notch makes the reading collapse (e.g. 57 → 3 lux)
/// and snap back when it's pulled away — that "cover" is one way to tap. (In any light, the phone can
/// also tap a magnet spot on the Mac: it feels the magnet itself and starts the tap.)
final class NotchSensor: @unchecked Sendable {
    static let shared = NotchSensor()

    /// Called on the main queue when the notch gets covered…
    var onCover: (() -> Void)?
    /// …and when it's uncovered again (the phone was pulled away).
    var onUncover: (() -> Void)?
    /// Live readings (lux, normal level) for the "Try it" meter in Settings; main queue, only while testing.
    var onReading: ((Double, Double) -> Void)?

    private typealias CreateFn = @convention(c) (CFAllocator?) -> Unmanaged<AnyObject>?
    private typealias SetMatchingFn = @convention(c) (AnyObject, CFDictionary) -> Void
    private typealias CopyServicesFn = @convention(c) (AnyObject) -> Unmanaged<CFArray>?
    private typealias CopyEventFn = @convention(c) (AnyObject, Int64, Int32, Int64) -> Unmanaged<AnyObject>?
    private typealias GetFloatFn = @convention(c) (AnyObject, Int32) -> Double
    private typealias SetPropFn = @convention(c) (AnyObject, CFString, AnyObject) -> Bool

    private var copyEvent: CopyEventFn?
    private var getFloat: GetFloatFn?
    private var setProp: SetPropFn?
    private var client: AnyObject?
    private var service: AnyObject?
    /// The sensor's own update interval (µs), restored when we stop asking for faster updates.
    private var defaultInterval: Int?

    private let queue = DispatchQueue(label: "app.tapdrop.notch")
    private var timer: DispatchSourceTimer?

    // detection state
    private var baseline: Double = 0
    private var recent: [(t: TimeInterval, lux: Double)] = []
    private var covered = false
    private var lastTrigger: TimeInterval = 0
    private var wanted = false
    private var testing = false
    private let levelLock = NSLock()
    private var level: Double = -1

    /// Darker than this and a phone has nothing to cover (the sensor reads whole lux: 1 → 0 is noise).
    static let minLux: Double = 4

    /// The room's normal light level at the notch, or nil if it hasn't been read yet.
    var normalLevel: Double? {
        levelLock.lock()
        defer { levelLock.unlock() }
        if level >= 0 { return level }
        return nil
    }

    /// Too dark to cover the notch right now (one quick read if the sensor isn't running).
    var tooDark: Bool {
        guard available else { return false }
        let l = normalLevel ?? read() ?? .infinity
        return l < Self.minLux
    }

    private(set) var available = false

    private init() {
        guard let h = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_NOW),
              let c = dlsym(h, "IOHIDEventSystemClientCreate"),
              let m = dlsym(h, "IOHIDEventSystemClientSetMatching"),
              let s = dlsym(h, "IOHIDEventSystemClientCopyServices"),
              let e = dlsym(h, "IOHIDServiceClientCopyEvent"),
              let f = dlsym(h, "IOHIDEventGetFloatValue"),
              let p = dlsym(h, "IOHIDServiceClientSetProperty")
        else { return }
        let create = unsafeBitCast(c, to: CreateFn.self)
        let setMatching = unsafeBitCast(m, to: SetMatchingFn.self)
        let copyServices = unsafeBitCast(s, to: CopyServicesFn.self)
        copyEvent = unsafeBitCast(e, to: CopyEventFn.self)
        getFloat = unsafeBitCast(f, to: GetFloatFn.self)
        setProp = unsafeBitCast(p, to: SetPropFn.self)
        guard let cl = create(kCFAllocatorDefault)?.takeRetainedValue() else { return }
        // Apple vendor page 0xff00, usage 4 = ambient light sensor
        setMatching(cl, ["PrimaryUsagePage": 0xff00, "PrimaryUsage": 4] as CFDictionary)
        client = cl
        service = (copyServices(cl)?.takeRetainedValue() as? [AnyObject])?.first
        available = service != nil && read() != nil
        defaultInterval = Self.driverInterval()
    }

    func read() -> Double? {
        guard let service, let copyEvent, let getFloat,
              let ev = copyEvent(service, 12 /* kIOHIDEventTypeAmbientLightSensor */, 0, 0)?.takeRetainedValue()
        else { return nil }
        return getFloat(ev, Int32(12 << 16) /* kIOHIDEventFieldAmbientLightSensorLevel */)
    }

    /// Polls only while a phone is listening — no point burning cycles otherwise.
    func setActive(_ on: Bool) {
        queue.async { [self] in
            wanted = on
            update()
        }
    }

    /// Settings' "Try it" meter is open: read the sensor even with no phone connected.
    func setTesting(_ on: Bool) {
        queue.async { [self] in
            testing = on
            update()
        }
    }

    private func update() {
        let on = wanted || testing
        if on, timer == nil, available {
            recent.removeAll()
            baseline = 0
            covered = false
            // The sensor normally updates ~5×/s; ~10×/s makes taps land sooner.
            if let service, let setProp { _ = setProp(service, "ReportInterval" as CFString, NSNumber(value: 40_000)) }
            let t = DispatchSource.makeTimerSource(queue: queue)
            t.schedule(deadline: .now(), repeating: .milliseconds(50), leeway: .milliseconds(10))
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            timer = t
        } else if !on, let t = timer {
            t.cancel()
            timer = nil
            if let service, let setProp, let d = defaultInterval {
                _ = setProp(service, "ReportInterval" as CFString, NSNumber(value: d))
            }
        }
    }

    private func tick() {
        guard let lux = read() else { return }
        let now = ProcessInfo.processInfo.systemUptime
        recent.append((now, lux))
        recent.removeAll { now - $0.t > 1.5 }
        if testing, let cb = onReading {
            let b = baseline == 0 ? lux : baseline
            DispatchQueue.main.async { cb(lux, b) }
        }

        if covered {
            // Re-arm once the phone has been pulled away (or after a while).
            if lux > max(baseline * 0.6, 3) || now - lastTrigger > 6 {
                covered = false
                DispatchQueue.main.async { self.onUncover?() }
            }
            return
        }

        // Baseline follows the uncovered light level slowly (lights change; covers don't last).
        if baseline == 0 { baseline = lux }
        if lux >= baseline * 0.7 {
            baseline = baseline * 0.9 + lux * 0.1
        } else {
            baseline = baseline * 0.995 + lux * 0.005
        }
        levelLock.lock()
        level = baseline
        levelLock.unlock()

        // A cover: from near-baseline to under 45% of it within ~1.2 s, in a room bright enough to tell.
        // Fires on the first dark reading so a tap feels instant; the phone decides whether it was
        // really brought up to the Mac. The absolute drop keeps 1-lux flicker from counting.
        let peak = recent.filter { now - $0.t <= 1.2 }.map(\.lux).max() ?? lux
        let bright = baseline >= Self.minLux
        let dropped = lux < baseline * 0.45 && peak >= baseline * 0.75 && peak - lux >= 2.5
        if bright && dropped && now - lastTrigger > 2.5 {
            covered = true
            lastTrigger = now
            NSLog("TapDrop: notch covered (%.0f → %.0f lux)", peak, lux)
            DispatchQueue.main.async { self.onCover?() }
        }
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
