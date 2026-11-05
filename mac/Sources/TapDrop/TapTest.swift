import AppKit
import Combine
import SwiftUI

// "Test your tap": live light readings from the notch sensor, the moments that matter (covered, the light key
// seen or not, sent), and plain words for what happened. Plus the recent taps, grouped from TapLog.

@MainActor
final class TapTestModel: ObservableObject {
    static let shared = TapTestModel()

    struct Sample: Identifiable, Equatable {
        let id: Int
        let t: Date
        let lux: Double
    }

    enum MarkKind: Equatable {
        /// The notch went dark (lit room): a round started.
        case cover
        /// Light arrived at the notch (dark room) or the phone touched it (knock): a round started.
        case touch
        /// The notch saw the phone's light.
        case keyOK
        /// It didn't (or the phone said no).
        case keyFail
        /// The phone is sending.
        case sent
    }

    struct Mark: Identifiable, Equatable {
        let id = UUID()
        let t: Date
        let kind: MarkKind
        /// Where it sits on the chart (lux); nil places it from the readings around `t`.
        var lux: Double?
    }

    /// The level the last light key had to reach, drawn around its moment.
    struct Need: Equatable {
        let lux: Double
        let at: Date
    }

    /// What just happened, for the words under the chart.
    enum Moment: Equatable {
        case trigger(MarkKind, phones: Bool, at: Date)
        case result(TapStatus.TapResult)
        case sent(phone: String, at: Date)
    }

    /// Seconds of history on the chart.
    static let span: TimeInterval = 12
    /// How long a moment's words stay up before going back to the prompt.
    static let momentLife: TimeInterval = 9

    /// The readings and moments on the chart. They change about ten times a second, so they live in their own
    /// object: only the chart and the readout redraw with them, not the whole pane.
    let trace = TapTrace()

    @Published private(set) var moment: Moment?
    @Published private(set) var running = false
    /// No reading for a while although the sensor should be streaming.
    @Published private(set) var stalled = false
    /// Enough readings to draw a line.
    @Published private(set) var hasReadings = false
    /// The room at the notch is dark (B under the dark line), from the test's own readings; nil before the first.
    @Published private(set) var roomDark: Bool?

    private var seq = 0
    private var bag = Set<AnyCancellable>()
    private var seen = Set<UUID>()
    private var watchdog: Timer?
    private var lastReading: Date?
    private var startedAt = Date()
    /// Demo: readings come from the demo script, not the sensor.
    var demoFeed = false

    func start() {
        guard !running else { return }
        running = true
        stalled = false
        startedAt = Date()
        if !demoFeed {
            trace.reset()
            hasReadings = false
            roomDark = nil
            moment = nil
            lastReading = nil
        }
        seen = Set(TapLog.shared.events.map(\.id))

        if !demoFeed {
            NotchSensor.shared.onReading = { [weak self] lux, b in
                MainActor.assumeIsolated { self?.add(lux: lux, room: b) }
            }
            NotchSensor.shared.setTesting(true)
        }

        TapLog.shared.$events
            .receive(on: RunLoop.main)
            .sink { [weak self] events in self?.logChanged(events) }
            .store(in: &bag)
        TapStatus.shared.$last
            .compactMap { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self] r in self?.finished(r) }
            .store(in: &bag)

        watchdog = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        bag.removeAll()
        watchdog?.invalidate()
        watchdog = nil
        if !demoFeed {
            NotchSensor.shared.setTesting(false)
            NotchSensor.shared.onReading = nil
        }
    }

    /// Stop and start again (after the sensor stalled).
    func restart() {
        stop()
        start()
    }

    /// A reading (lux) and the room level behind it.
    func add(lux: Double, room b: Double, at t: Date = Date()) {
        seq += 1
        lastReading = t
        if stalled { stalled = false }
        trace.append(Sample(id: seq, t: t, lux: max(0, lux)), room: b)
        if !hasReadings && trace.samples.count >= 2 { hasReadings = true }
        let dark = b < LightKey.darkLine
        if roomDark != dark { roomDark = dark }
    }

    // MARK: events

    private func logChanged(_ events: [TapLog.Event]) {
        // newest first; take what's new since the test started, oldest first
        let fresh = events.filter { !seen.contains($0.id) }.reversed()
        for e in fresh {
            seen.insert(e.id)
            switch TapRecords.kind(of: e.text) {
            case .cover:
                trace.mark(.cover, at: e.date, lux: trace.latest?.lux)
                moment = .trigger(.cover, phones: !e.text.contains("no phone connected"), at: e.date)
            case .rise, .knock:
                trace.mark(.touch, at: e.date, lux: trace.latest?.lux)
                moment = .trigger(.touch, phones: !e.text.contains("no phone connected"), at: e.date)
            case .sent:
                trace.mark(.sent, at: e.date, lux: nil)
                moment = .sent(phone: TapRecords.phone(in: e.text) ?? "Your phone", at: e.date)
            default:
                break
            }
        }
    }

    private func finished(_ r: TapStatus.TapResult) {
        guard r.time >= startedAt.addingTimeInterval(-1) else { return }
        if r.src != nil {
            trace.mark(r.accepted ? .keyOK : .keyFail, at: r.time, lux: r.peak)
            if r.need > 0 { trace.need = Need(lux: r.need, at: r.time) }
        } else {
            trace.mark(.keyFail, at: r.time, lux: nil)
        }
        moment = .result(r)
    }

    /// A calibration hold: the chart marks the light the notch saw (and the level it was measured against), and
    /// the words go back to the prompt, which is what to do for the next hold.
    func calibrationSample(peak: Double, need: Double) {
        guard running else { return }
        let now = Date()
        trace.mark(.keyOK, at: now, lux: peak)
        if need > 0 { trace.need = Need(lux: need, at: now) }
        moment = nil
    }

    private func tick() {
        let now = Date()
        if let m = moment, now.timeIntervalSince(m.at) > Self.momentLife { moment = nil }
        guard !demoFeed, NotchSensor.shared.available else { return }
        let silent = now.timeIntervalSince(lastReading ?? startedAt)
        if silent > 3, !stalled { stalled = true }
    }

    // MARK: demo

    /// Demo: the sensor stopped answering.
    func demoStall() {
        stalled = true
    }

    /// Demo: replace everything with a scripted trace ending now.
    func demoLoad(samples s: [(TimeInterval, Double)], room b: Double, marks m: [(TimeInterval, MarkKind, Double?)],
                  need n: (TimeInterval, Double)?, moment mo: Moment?) {
        let now = Date()
        trace.samples = s.enumerated().map { Sample(id: $0.offset, t: now.addingTimeInterval($0.element.0), lux: $0.element.1) }
        seq = trace.samples.count
        trace.room = b
        trace.marks = m.map { Mark(t: now.addingTimeInterval($0.0), kind: $0.1, lux: $0.2) }
        trace.need = n.map { Need(lux: $0.1, at: now.addingTimeInterval($0.0)) }
        hasReadings = trace.samples.count >= 2
        roomDark = b < LightKey.darkLine
        moment = mo
        lastReading = now
    }
}

/// The light at the notch over the last `TapTestModel.span` seconds, and the moments on it.
@MainActor
final class TapTrace: ObservableObject {
    @Published fileprivate(set) var samples: [TapTestModel.Sample] = []
    /// B, the room level at the notch.
    @Published fileprivate(set) var room: Double?
    @Published fileprivate(set) var marks: [TapTestModel.Mark] = []
    @Published fileprivate(set) var need: TapTestModel.Need?

    var latest: TapTestModel.Sample? { samples.last }

    /// The reading closest to `t`.
    func lux(near t: Date) -> Double? {
        samples.min { abs($0.t.timeIntervalSince(t)) < abs($1.t.timeIntervalSince(t)) }?.lux
    }

    fileprivate func reset() {
        samples = []
        marks = []
        need = nil
        room = nil
    }

    fileprivate func append(_ s: TapTestModel.Sample, room b: Double) {
        samples.append(s)
        let cutoff = s.t.addingTimeInterval(-TapTestModel.span - 1)
        if let i = samples.firstIndex(where: { $0.t >= cutoff }), i > 0 { samples.removeFirst(i) }
        if marks.contains(where: { $0.t < cutoff }) { marks.removeAll { $0.t < cutoff } }
        if let n = need, n.at < cutoff { need = nil }
        let r = (b * 10).rounded() / 10
        if room != r { room = r }
    }

    fileprivate func mark(_ kind: TapTestModel.MarkKind, at t: Date, lux: Double?) {
        marks.append(TapTestModel.Mark(t: t, kind: kind, lux: lux))
    }
}

extension TapTestModel.Moment {
    var at: Date {
        switch self {
        case .trigger(_, _, let t): t
        case .result(let r): r.time
        case .sent(_, let t): t
        }
    }
}

// MARK: - Words

/// Plain words for the tap (the light key's verdicts and the phone's own reasons), the same on both platforms:
///
/// - Gesture: "Touch the top of your phone's screen to the notch." Hold: "Hold it there until your phone buzzes."
/// - Didn't see it (`not-seen`, `no-light-sent`): "Your Mac didn't see your screen. Press the top of the screen
///   flat on the notch."
/// - Didn't go dark with it (`stayed-lit`, `no-key-off`): "Keep your phone on the notch until it buzzes."
/// - The back faced the notch (`facing`, `screen-only`): "Turn your phone around: its screen goes against the notch."
/// - Anything else that can't count (`other-phone`, `late`, `gone`, `busy`, `cooldown`): "That tap didn't count.
///   Try again."
/// - `calibrating` isn't a failure: it's one of the calibration holds.
enum TapWords {
    static let gesture = "Touch the top of your phone's screen to the notch."
    static let hold = "Hold it there until your phone buzzes."
    static let safety = "TapDrop only sends when your Mac's notch sees your phone's screen light up and go dark right against it. A hand, a lamp or a tap anywhere else can't send."

    static func why(_ reason: String, phone: String) -> (title: String, detail: String) {
        switch reason {
        case "not-seen", "no-light-sent", "no-light", "":
            ("Your Mac didn't see your screen", "Press the top of the screen flat on the notch.")
        case "stayed-lit", "no-key-off":
            ("Keep your phone on the notch until it buzzes", "Lift it after the buzz, not before.")
        case "facing", "screen-only":
            ("Turn your phone around", "Its screen goes against the notch.")
        case "calibrating":
            ("Calibration hold saved", "Lift your phone, then touch the notch again.")
        case "still":
            ("\(phone) wasn't moving", "So the tap was ignored: a hand over the notch can't send.")
        case "locked":
            ("\(phone) is locked", "Unlock it, then touch the top of its screen to the notch.")
        case "screen-off":
            ("\(phone)'s screen is off", "Wake it, then touch the top of its screen to the notch.")
        case "in-call":
            ("\(phone) is in a call", "Taps don't count during calls. Try again when it ends.")
        case "update-mac":
            ("Update TapDrop on this Mac", "\(phone) has a newer TapDrop than this Mac.")
        case "timeout":
            ("No answer from \(phone)", "Check that it's on the same Wi-Fi as this Mac.")
        default:  // other-phone, late, gone, busy, cooldown
            ("That tap didn't count", "Try again.")
        }
    }

    /// The short notch hint after a failed tap (title, detail), for the reasons worth a hint.
    static func notchHint(_ reason: String) -> (String, String) {
        switch reason {
        case "stayed-lit", "no-key-off": ("Keep your phone on the notch", "Hold it there until your phone buzzes.")
        case "facing", "screen-only": ("Turn your phone around", "Its screen goes against the notch.")
        case "not-seen", "no-light-sent": ("Your Mac didn't see your screen", "Press the top of the screen flat on the notch.")
        default: ("That tap didn't count", "Try again.")
        }
    }

    /// What the calibration card says after a hold that didn't count.
    static func calibrationNote(_ reason: String) -> String {
        switch reason {
        case "not-seen", "no-light-sent", "no-light": "Your Mac didn't see your screen. Press the top of the screen flat on the notch."
        case "stayed-lit", "no-key-off": "Keep your phone on the notch until it buzzes."
        case "facing", "screen-only": "Turn your phone around: its screen goes against the notch."
        case "locked": "Unlock your phone, then try again."
        case "screen-off": "Wake your phone, then try again."
        default: "That hold didn't count. Try again."
        }
    }

    static func light(_ src: String?) -> String { "its screen" }
}

// MARK: - Recent taps

/// One tap (a round), from the TapLog lines it wrote.
struct TapRecord: Identifiable, Equatable {
    enum Outcome { case sent, failed, ignored, nothing, other }

    let id: UUID
    let date: Date
    let outcome: Outcome
    let title: String
    let detail: String?
    /// The log lines, oldest first, with their numbers.
    let lines: [(Date, String)]

    static func == (a: TapRecord, b: TapRecord) -> Bool { a.id == b.id && a.lines.count == b.lines.count }
}

enum TapRecords {
    enum LineKind { case cover, rise, knock, keyOK, keyFail, sent, declined, calibration, other }

    /// What a TapLog line is (TapLog's formats are in its comments).
    static func kind(of text: String) -> LineKind {
        if text.hasPrefix("Calibration ") || text.hasPrefix("Calibrated ") { return .calibration }
        if text.hasPrefix("Light key ✓") { return .keyOK }
        if text.hasPrefix("Light key ✗") { return .keyFail }
        if text.hasPrefix("Covered ") || text.hasPrefix("Notch covered") { return .cover }
        if text.hasPrefix("Light ") { return .rise }
        if text.contains(" knocked") { return .knock }
        if text.contains(" declined: ") || text.hasPrefix("TapDrop was open on") { return .declined }
        if text.contains(" is sending") || text.contains(" is collecting") || text.contains(" sent from ") { return .sent }
        return .other
    }

    /// The phone a line names, if any.
    static func phone(in text: String) -> String? {
        for marker in [" is sending", " is collecting", " declined: ", " knocked", " sent from "] {
            if let r = text.range(of: marker) {
                let name = String(text[..<r.lowerBound])
                return name.isEmpty || name.hasPrefix("Light key") ? nil : name
            }
        }
        if let r = text.range(of: "asked ") {
            return text[r.upperBound...].components(separatedBy: " · ").first?.components(separatedBy: ", ").first
        }
        if let r = text.range(of: "TapDrop was open on ") {
            return text[r.upperBound...].components(separatedBy: " — ").first
        }
        return nil
    }

    /// Groups the log (newest first) into taps, newest first.
    static func build(_ events: [TapLog.Event], limit: Int = 6) -> [TapRecord] {
        var groups: [[TapLog.Event]] = []
        for e in events.reversed() {
            let k = kind(of: e.text)
            // A calibration hold ("Calibration 2 of 3 · …", then "Calibrated …") belongs to its round; starting,
            // cancelling, stopping or resetting a calibration is a line of its own.
            let hold = e.text.hasPrefix("Calibrated ") || (e.text.hasPrefix("Calibration ") && e.text.dropFirst(12).first?.isNumber == true)
            let starts = k == .cover || k == .rise || k == .knock || (k == .calibration && !hold)
            if let last = groups.last?.last, !starts, e.date.timeIntervalSince(last.date) < 6 {
                groups[groups.count - 1].append(e)
            } else {
                groups.append([e])
            }
        }
        return groups.reversed().prefix(limit).map(record)
    }

    private static func record(_ g: [TapLog.Event]) -> TapRecord {
        let texts = g.map(\.text)
        let kinds = texts.map(kind(of:))
        let phone = texts.lazy.compactMap(phone(in:)).first ?? "Your phone"
        let lines = g.map { ($0.date, $0.text) }
        func make(_ o: TapRecord.Outcome, _ title: String, _ detail: String?) -> TapRecord {
            TapRecord(id: g[0].id, date: g[0].date, outcome: o, title: title, detail: detail, lines: lines)
        }

        if let i = kinds.lastIndex(of: .calibration) {
            // "Calibration 2 of 3 · Android phone reads 1480 lux on the notch", "Calibrated Android phone · 1480 lux on the
            // notch · taps now need 740", "Calibration stopped · Android phone · …"
            let parts = texts[i].components(separatedBy: " · ")
            let detail = parts.dropFirst().joined(separator: ", ")
            let done = texts[i].hasPrefix("Calibrated ")
            return make(done ? .sent : .other, parts.first ?? texts[i], detail.isEmpty ? nil : detail.prefix(1).uppercased() + detail.dropFirst())
        }
        if let i = kinds.firstIndex(of: .sent) {
            let t = texts[i]
            if t.contains(" is collecting") { return make(.sent, "\(phone) picked up what was waiting", nil) }
            if t.contains(" sent from ") {
                // an older TapDrop on the phone sent by itself, without the light key
                return make(.sent, "Sent from \(phone)", "Its TapDrop is out of date. Update it so only a touch at the notch sends.")
            }
            let what = t.contains("the page") ? "The page it was on"
                : t.contains("picking") ? "Chosen on the phone" : "What was on its screen"
            return make(.sent, "Sent from \(phone)", what)
        }
        if let i = kinds.firstIndex(of: .keyFail) {
            let t = texts[i]
            let why = t.contains("didn't light up") ? "no-light-sent"
                : t.contains("stayed lit") ? "stayed-lit"
                : t.contains("switch its light off") ? "no-key-off"
                : t.contains("only the phone's screen counts") ? "screen-only"
                : t.contains("another phone") ? "other-phone"
                : t.contains("disconnected") ? "gone" : "not-seen"
            let w = TapWords.why(why, phone: phone)
            return make(.failed, "Didn't send", w.title)
        }
        if let i = kinds.firstIndex(of: .declined) {
            let t = texts[i]
            if t.hasPrefix("TapDrop was open on") {
                return make(.ignored, "TapDrop was open on \(phone)", "Open what you want to send, then tap.")
            }
            let reason = t.components(separatedBy: " declined: ").last?.components(separatedBy: " ").first ?? ""
            let w = TapWords.why(reason, phone: phone)
            return make(.ignored, w.title, w.detail)
        }
        if kinds.contains(.keyOK) { return make(.sent, "The notch saw \(phone)'s light", nil) }
        if let first = texts.first, first.contains("no phone connected") {
            return make(.nothing, kinds.first == .rise ? "Light at the notch" : "Notch covered",
                        "No phone was connected, so nothing was sent.")
        }
        if let first = texts.first, first.contains("needs the TapDrop update") {
            return make(.ignored, "Update TapDrop on \(phone)", "It needs the latest TapDrop to tap.")
        }
        if kinds.first == .cover || kinds.first == .rise || kinds.first == .knock {
            return make(.nothing, kinds.first == .knock ? "\(phone) touched the notch" : "Notch covered", "Waiting for \(phone)…")
        }
        return make(.other, texts.first ?? "", nil)
    }
}
