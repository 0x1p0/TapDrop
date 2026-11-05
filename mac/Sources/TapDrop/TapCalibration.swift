import AppKit
import Combine
import SwiftUI

// Calibration: how bright each phone's white screen reads on this Mac's notch, so the light key asks for what that
// phone really gives (PROTOCOL.md → "Calibration"). Three holds at the notch, each a full light key that ends in
// key-fail "calibrating", so nothing is sent. The result is that phone's own floor for the rise:
// need = max(screenMin, B + 200), with screenMin = clamp(round(0.5 × median sample), 300, 1000).

/// One phone's calibration on this Mac.
struct TapCalibrationRecord: Codable, Equatable {
    var name: String
    /// The median sample: the phone's white screen at full brightness, pressed on this Mac's notch (lux).
    var contactLux: Double
    /// The rise floor for this phone's taps (lux).
    var screenMin: Double
    var samples: [Double]
    var at: Date
}

/// The calibrations by phone (paired device id), kept in `calibration.json` in the app's folder, and which phone is
/// calibrating now. Read on the notch sensor's queue for every round and written on the main actor, so it all
/// sits behind a lock.
final class TapCalibrations: @unchecked Sendable {
    static let shared = TapCalibrations()

    private let lock = NSLock()
    private var records: [String: TapCalibrationRecord] = [:]
    private var calibrating: String?

    private static var url: URL { Store.dir.appendingPathComponent("calibration.json") }

    private init() {
        if let data = try? Data(contentsOf: Self.url),
           let saved = try? Self.decoder.decode([String: TapCalibrationRecord].self, from: data) {
            records = saved
        }
    }

    func record(for deviceId: String) -> TapCalibrationRecord? { lock.withLock { records[deviceId] } }

    /// The rise floor for this phone's taps, if it was calibrated on this Mac.
    func screenMin(for deviceId: String) -> Double? { lock.withLock { records[deviceId]?.screenMin } }

    var all: [String: TapCalibrationRecord] { lock.withLock { records } }

    /// The phone whose rounds are calibration samples right now.
    var active: String? {
        get { lock.withLock { calibrating } }
        set { lock.withLock { calibrating = newValue } }
    }

    func isCalibrating(_ deviceId: String) -> Bool { lock.withLock { calibrating == deviceId } }

    func save(_ r: TapCalibrationRecord, for deviceId: String) {
        let snapshot = lock.withLock {
            records[deviceId] = r
            return records
        }
        write(snapshot)
    }

    func reset(_ deviceId: String) {
        let snapshot = lock.withLock {
            records[deviceId] = nil
            return records
        }
        write(snapshot)
    }

    private func write(_ snapshot: [String: TapCalibrationRecord]) {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        enc.dateEncodingStrategy = .iso8601
        guard let data = try? enc.encode(snapshot) else { return }
        try? FileManager.default.createDirectory(at: Store.dir, withIntermediateDirectories: true)
        try? data.write(to: Self.url, options: .atomic)
    }

    private static var decoder: JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }
}

/// The calibration as the Settings card shows it. Rounds report their samples here (see `KeyRounds`).
@MainActor
final class TapCalibration: ObservableObject {
    static let shared = TapCalibration()

    enum State: Equatable {
        case idle
        /// Collecting samples from this phone. `note` says why the last hold didn't count, if it didn't.
        case running(deviceId: String, phone: String, samples: [Double], note: String?)
        /// Just finished.
        case done(deviceId: String, record: TapCalibrationRecord)
        /// Stopped before the last sample.
        case stopped(phone: String, reason: String)
    }

    @Published private(set) var state: State = .idle
    /// Every phone's calibration, for the card.
    @Published private(set) var records: [String: TapCalibrationRecord] = TapCalibrations.shared.all

    private var idle: Task<Void, Never>?
    private var leaving: Task<Void, Never>?

    private init() {
        #if DEBUG
        // Screenshots of the card's states (TAPDROP_DEMO_CAL=running|missed|done|stopped, with a settings demo).
        let r = TapCalibrationRecord(name: "Android phone", contactLux: 1480, screenMin: 740, samples: [1480, 1390, 1530], at: Date())
        switch ProcessInfo.processInfo.environment["TAPDROP_DEMO_CAL"] {
        case "running": state = .running(deviceId: "demo", phone: "Android phone", samples: [1480], note: nil)
        case "missed": state = .running(deviceId: "demo", phone: "Android phone", samples: [1480, 1390],
                                        note: TapWords.calibrationNote("not-seen"))
        case "done": state = .done(deviceId: "demo", record: r)
        case "stopped": state = .stopped(phone: "Android phone", reason: "Android phone disconnected. Wake it and try again.")
        default: break
        }
        #endif
    }

    var running: Bool {
        if case .running = state { return true }
        return false
    }

    #if DEBUG
    /// Settings demos (`settings:tap:calibrate|calibrating1|calibrating2|calmissed|calibrated|nearmiss`): the card's
    /// states for a live Android phone, with nothing connected.
    func demo(_ which: String, deviceId: String, phone: String) {
        CalibrationStrip.demoPhone = WatchHub.Phone(id: UUID(), deviceId: deviceId, name: phone, key: LightKey.version)
        let r = TapCalibrationRecord(name: phone, contactLux: 1480, screenMin: 740, samples: [1480, 1390, 1530], at: Date())
        switch which {
        case "calibrating1": state = .running(deviceId: deviceId, phone: phone, samples: [], note: nil)
        case "calibrating2": state = .running(deviceId: deviceId, phone: phone, samples: [1480], note: nil)
        case "calmissed": state = .running(deviceId: deviceId, phone: phone, samples: [1480, 1390],
                                           note: TapWords.calibrationNote("not-seen"))
        case "calibrated": state = .done(deviceId: deviceId, record: r)
        case "nearmiss":
            TapStatus.shared.finished(TapStatus.TapResult(time: Date(), phone: phone, trigger: "knock", src: "screen", room: 118,
                                                          pre: 40, peak: 380, need: 600, accepted: false, why: "not-seen"))
        default: break
        }
    }
    #endif

    func start(deviceId: String, phone: String) {
        TapCalibrations.shared.active = deviceId
        withTDAnimation { state = .running(deviceId: deviceId, phone: phone, samples: [], note: nil) }
        TapLog.shared.note("Calibration started · \(phone)")
        armIdle()
    }

    func cancel() {
        guard case .running(_, let phone, _, _) = state else { return }
        stop()
        TapLog.shared.note("Calibration cancelled · \(phone)")
        withTDAnimation { state = .idle }
    }

    /// Forget this phone's calibration: its taps go back to the standard floor.
    func reset(deviceId: String) {
        let name = TapCalibrations.shared.record(for: deviceId)?.name ?? "the phone"
        TapCalibrations.shared.reset(deviceId)
        records = TapCalibrations.shared.all
        TapLog.shared.note("Calibration reset · \(name) · taps need \(Int(LightKey.screenMin)) again")
        withTDAnimation { state = .idle }
    }

    /// Back to the plain card (after a result or a stop has been seen).
    func dismiss() {
        guard !running else { return }
        withTDAnimation { state = .idle }
    }

    // MARK: from the rounds

    /// A good hold: the light came and went like a tap's, and `peak` is its rise.
    func sampled(deviceId: String, peak: Double) {
        guard case .running(let id, let phone, var samples, _) = state, id == deviceId else { return }
        samples.append(peak)
        let n = samples.count
        let total = LightKey.calibrationSamples
        TapLog.shared.note("Calibration \(n) of \(total) · \(phone) reads \(Int(peak.rounded())) lux on the notch", good: true)
        guard n >= total else {
            withTDAnimation { state = .running(deviceId: id, phone: phone, samples: samples, note: nil) }
            armIdle()
            return
        }
        let contact = LightKey.median(samples)
        let record = TapCalibrationRecord(name: phone, contactLux: contact.rounded(), screenMin: LightKey.screenMin(contact: contact),
                                          samples: samples, at: Date())
        TapCalibrations.shared.save(record, for: id)
        stop()
        records = TapCalibrations.shared.all
        TapLog.shared.note("Calibrated \(phone) · \(Int(record.contactLux)) lux on the notch · taps now need \(Int(record.screenMin))",
                           good: true)
        withTDAnimation(TD.Motion.emphasized) { state = .done(deviceId: id, record: record) }
        if !TestMode.headless { TD.haptic(.levelChange) }
    }

    /// A hold that didn't count (the notch didn't see the screen, it didn't go dark, the phone declined…).
    func missed(deviceId: String, why: String) {
        guard case .running(let id, let phone, let samples, _) = state, id == deviceId else { return }
        withTDAnimation { state = .running(deviceId: id, phone: phone, samples: samples, note: TapWords.calibrationNote(why)) }
    }

    /// A phone's link went away. If it was the one calibrating and doesn't come back within a few seconds (a link
    /// can drop and reopen), calibration stops.
    func phoneLeft(deviceId: String) {
        guard case .running(let id, _, _, _) = state, id == deviceId else { return }
        leaving?.cancel()
        leaving = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard let self, !Task.isCancelled, case .running(let id, let phone, _, _) = self.state, id == deviceId else { return }
            guard !WatchHub.shared.phones().contains(where: { $0.deviceId == deviceId }) else { return }
            self.halt(phone: phone, reason: "\(phone) disconnected. Wake it and try again.")
        }
    }

    /// The notch sensor stopped: Tap to Send was switched off, or no phone is connected any more.
    func sensorStopped() {
        guard case .running(let id, let phone, _, _) = state else { return }
        if Store.shared.settings.notchTap {
            phoneLeft(deviceId: id)  // the phone went away (it may be right back)
        } else {
            halt(phone: phone, reason: "Tap to Send is off. Turn it on, then try again.")
        }
    }

    private func halt(phone: String, reason: String) {
        stop()
        TapLog.shared.note("Calibration stopped · \(phone) · \(reason)")
        withTDAnimation { state = .stopped(phone: phone, reason: reason) }
    }

    private func stop() {
        TapCalibrations.shared.active = nil
        idle?.cancel()
        idle = nil
        leaving?.cancel()
        leaving = nil
    }

    private func armIdle() {
        idle?.cancel()
        idle = Task { [weak self] in
            try? await Task.sleep(for: .seconds(LightKey.calibrateIdle))
            guard let self, !Task.isCancelled, case .running(_, let phone, _, _) = self.state else { return }
            self.halt(phone: phone, reason: "No hold for a while. Try again when you're ready.")
        }
    }

    // MARK: test hook

    /// Debug builds with TAPDROP_TEST_CALIBRATE=1: the first light-key phone that connects is calibrated (keyCheck).
    func testStartIfAsked(deviceId: String, phone: String) {
        guard TestHooks.on, ProcessInfo.processInfo.environment["TAPDROP_TEST_CALIBRATE"] == "1", !running,
              TapCalibrations.shared.record(for: deviceId) == nil else { return }
        if case .stopped = state { return }
        start(deviceId: deviceId, phone: phone)
        NSLog("TapDrop[test]: calibrating %@", phone)
    }
}

// MARK: - The card

/// Calibration, at the foot of the "Test your tap" card: an offer (or a suggestion after a near miss), the three
/// holds while it runs, and the phone's numbers once it's done, with Reset.
struct CalibrationStrip: View {
    #if DEBUG
    /// Settings demos: the live phone to show the card for.
    nonisolated(unsafe) static var demoPhone: WatchHub.Phone?
    #endif

    @ObservedObject private var cal = TapCalibration.shared
    @ObservedObject private var store = Store.shared
    @ObservedObject private var status = TapStatus.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TDDivider()
            content
                .padding(.horizontal, TD.Space.l)
                .padding(.vertical, TD.Space.m + 2)
        }
        .tdAnimation(TD.Motion.standard, value: cal.state)
    }

    /// The live phone that can calibrate (the first with the current light key).
    private var phone: WatchHub.Phone? {
        _ = store.liveDevices  // re-evaluated as phones come and go
        #if DEBUG
        if let p = Self.demoPhone { return p }
        #endif
        return WatchHub.shared.phones().filter { $0.key >= LightKey.version }.min { $0.name < $1.name }
    }

    @ViewBuilder private var content: some View {
        switch cal.state {
        case .running(_, let name, let samples, let note):
            RunningRow(phone: name, samples: samples, note: note) { cal.cancel() }
        case .done(let id, let record):
            ResultRow(record: record, fresh: true, reset: { cal.reset(deviceId: id) }, again: again(id, record.name))
        case .stopped(let name, let reason):
            OfferRow(symbol: "exclamationmark.triangle.fill", tint: TDTint.orange, title: "Calibration stopped",
                     detail: reason, action: "Try Again", primary: false, enabled: phone != nil) {
                if let p = phone { cal.start(deviceId: p.deviceId, phone: p.name) } else { cal.dismiss() }
            }
            .accessibilityHint("Calibrates \(name) again")
        case .idle:
            idle
        }
    }

    @ViewBuilder private var idle: some View {
        if let p = phone, let record = cal.records[p.deviceId] {
            if nearMiss {
                OfferRow(symbol: "scope", tint: TDTint.aurora, title: "\(p.name)'s light was close",
                         detail: "It reads \(lux(record.contactLux)) lux here when calibrated. Calibrate again if its case or screen changed.",
                         action: "Calibrate", primary: true, enabled: true) {
                    cal.start(deviceId: p.deviceId, phone: p.name)
                }
            } else {
                ResultRow(record: record, fresh: false, reset: { cal.reset(deviceId: p.deviceId) }, again: again(p.deviceId, p.name))
            }
        } else if let p = phone {
            if nearMiss {
                OfferRow(symbol: "scope", tint: TDTint.aurora, title: "Your Mac almost saw \(p.name)",
                         detail: "Calibrate so taps fit \(p.name)'s screen and this notch.",
                         action: "Calibrate", primary: true, enabled: true) {
                    cal.start(deviceId: p.deviceId, phone: p.name)
                }
            } else {
                OfferRow(symbol: "scope", tint: TDTint.aurora, title: "Calibrate for \(p.name)",
                         detail: "Three holds at the notch tune taps to its screen.",
                         action: "Calibrate", primary: false, enabled: true) {
                    cal.start(deviceId: p.deviceId, phone: p.name)
                }
            }
        } else {
            OfferRow(symbol: "scope", tint: TDTint.gray, title: "Calibrate taps",
                     detail: "Wake your phone with TapDrop open to calibrate it for this Mac.",
                     action: "Calibrate", primary: false, enabled: false) {}
        }
    }

    private func again(_ id: String, _ name: String) -> (() -> Void)? {
        guard let p = phone, p.deviceId == id else { return nil }
        return { cal.start(deviceId: p.deviceId, phone: name) }
    }

    /// The last tap: the notch didn't see the screen, but the peak reached 40 % of what it needed (in the last
    /// minute).
    private var nearMiss: Bool {
        guard let r = status.last, !r.accepted, r.why == "not-seen", r.need > 0, r.peak >= LightKey.suggestCalibrate * r.need,
              Date().timeIntervalSince(r.time) < 60 else { return false }
        return true
    }
}

private func lux(_ v: Double) -> String { Int(v.rounded()).formatted() }

/// An offer: icon, words, one button.
private struct OfferRow: View {
    let symbol: String
    let tint: [Color]
    let title: String
    let detail: String
    let action: String
    let primary: Bool
    let enabled: Bool
    let run: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.m) {
            IconChip(symbol, tint: tint, size: 30, style: .tinted)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail)
                    .tdFont(.callout)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: TD.Space.m)
            Button(action, action: run)
                .buttonStyle(.td(primary ? .primary : .secondary, size: .small))
                .disabled(!enabled)
        }
        .accessibilityElement(children: .contain)
    }
}

/// While calibrating: what to do, the three holds, and Cancel.
private struct RunningRow: View {
    let phone: String
    let samples: [Double]
    let note: String?
    let cancel: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.m) {
            IconChip("scope", tint: TDTint.aurora, size: 30, style: .tinted)
                .symbolEffect(.pulse, options: .repeating)
            VStack(alignment: .leading, spacing: 2) {
                Text(TapCopy.gesture)
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(TapCopy.hold)
                    .tdFont(.callout)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: TD.Space.s) {
                    Pips(done: samples.count, total: LightKey.calibrationSamples)
                    Text(caption)
                        .tdFont(.callout)
                        .foregroundStyle(note == nil ? TD.textSecondary : TD.warning)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.interpolate)
                }
                .padding(.top, 6)
            }
            Spacer(minLength: TD.Space.m)
            Button("Cancel", action: cancel)
                .buttonStyle(.tdQuiet)
                .keyboardShortcut(.cancelAction)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Calibrating \(phone). \(caption)")
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var caption: String {
        if let note { return note }
        let n = samples.count
        if n == 0 { return "Hold 1 of \(LightKey.calibrationSamples) · \(phone)" }
        return "Hold \(n + 1) of \(LightKey.calibrationSamples) · lift it, then touch again"
    }
}

/// The holds so far: done, current (breathing) and to come.
private struct Pips: View {
    let done: Int
    let total: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathe = false

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<total, id: \.self) { i in
                Capsule()
                    .fill(i < done ? AnyShapeStyle(TD.aurora) : AnyShapeStyle(TD.fillStrong))
                    .overlay {
                        if i == done {
                            Capsule().fill(TD.violet.opacity(breathe ? 0.55 : 0.18))
                        }
                    }
                    .frame(width: i < done ? 18 : 12, height: 6)
            }
        }
        .tdAnimation(TD.Motion.emphasized, value: done)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breathe = true }
        }
        .accessibilityHidden(true)
    }
}

/// A phone's numbers: what its screen reads on the notch and what taps need now.
private struct ResultRow: View {
    let record: TapCalibrationRecord
    /// Just finished (a check that lands), or the saved result.
    let fresh: Bool
    let reset: () -> Void
    let again: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.m) {
            IconChip(fresh ? "checkmark" : "scope", tint: fresh ? TDTint.green : TDTint.aurora, size: 30, style: .tinted)
                .symbolEffect(.bounce, value: fresh)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(record.name) reads \(lux(record.contactLux)) lux on the notch.")
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Taps now need \(lux(record.screenMin)).")
                    .tdFont(.callout)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: TD.Space.m)
            if let again {
                Button("Calibrate Again", action: again)
                    .buttonStyle(.tdQuiet)
            }
            Button("Reset", action: reset)
                .buttonStyle(.td(.secondary, size: .small))
                .help("Forget this calibration: taps from \(record.name) need \(Int(LightKey.screenMin)) lux again")
        }
        .accessibilityElement(children: .contain)
    }
}
