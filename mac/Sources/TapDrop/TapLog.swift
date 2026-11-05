import Foundation

/// What happened on each notch tap — shown in Settings → Tap to Send so a tap that "did nothing"
/// always has an answer (no phone connected, phone locked, the light key wasn't seen…).
/// Lines carry the light numbers, for tuning with real taps. Also kept as a small rolling log file.
@MainActor
final class TapLog: ObservableObject {
    static let shared = TapLog()

    struct Event: Identifiable {
        enum Mood { case good, neutral, bad }
        let id = UUID()
        let date = Date()
        let text: String
        let mood: Mood
    }

    @Published private(set) var events: [Event] = []
    /// When the notch was last covered (drives the "Try it" meter's flash).
    @Published private(set) var lastCover: Date?
    /// When a light key last passed (a tap that really counted).
    @Published private(set) var lastKey: Date?

    nonisolated private static var fileURL: URL { Store.dir.appendingPathComponent("taps.log") }

    /// The notch was covered and these phones were asked (older entry point; rounds use `trigger`).
    func covered(phones: [String]) {
        lastCover = Date()
        if phones.isEmpty {
            add("Notch covered — no phone connected", .neutral)
        } else {
            add("Notch covered — asked \(phones.joined(separator: ", "))", .neutral)
        }
    }

    /// A round started.
    /// - DIP: `Covered 57 → 3 lux · asked Android phone`
    /// - RISE: `Light 1 → 40 lux · asked Android phone`
    /// - KNOCK: `Android phone knocked · notch 120 lux`
    func trigger(_ kind: String, from: Double, to: Double, asked: [String], needUpdate: [String] = []) {
        if kind == "dip" { lastCover = Date() }
        var parts: [String]
        switch kind {
        case "dip": parts = ["Covered \(lux(from)) → \(lux(to)) lux"]
        case "rise": parts = ["Light \(lux(from)) → \(lux(to)) lux"]
        default: parts = ["\(asked.first ?? "Phone") knocked · notch \(lux(to)) lux"]
        }
        if kind != "knock" {
            if !asked.isEmpty { parts.append("asked \(asked.joined(separator: ", "))") }
            if !needUpdate.isEmpty { parts.append("\(needUpdate.joined(separator: ", ")) needs the TapDrop update") }
            if asked.isEmpty && needUpdate.isEmpty { parts.append("no phone connected") }
        }
        add(parts.joined(separator: " · "), .neutral)
    }

    /// The light key's verdict. [fall] is the darkest reading after the phone's screen went black.
    /// - `Light key ✓ screen 4 → 1480 lux (needed 600), dark again at 2 lux`
    /// - `Light key ✗ screen 4 → 90 lux (needed 600)`
    /// Calibration holds write their own lines (see `TapCalibration`): `Calibration 1 of 3 · Android phone reads 1480 lux on
    /// the notch`, then `Calibrated Android phone · 1480 lux on the notch · taps now need 740`.
    func key(ok: Bool, phone: String, src: String?, pre: Double, peak: Double, need: Double, why: String?, fall: Double? = nil) {
        let s = src ?? "light"
        if ok {
            lastKey = Date()
            add("Light key ✓ \(s) \(lux(pre)) → \(lux(peak)) lux (needed \(lux(need)))" + (fall.map { ", dark again at \(lux($0)) lux" } ?? ""), .good)
            return
        }
        let text: String
        switch why {
        case "no-light-sent": text = "Light key ✗ \(phone) didn't light up in time"
        case "screen-only": text = "Light key ✗ \(s): only the phone's screen counts"
        case "stayed-lit":
            text = "Light key ✗ \(s) \(lux(pre)) → \(lux(peak)) lux, but the notch stayed lit at \(lux(fall ?? peak)) lux when the phone went dark (moved away, or not its light)"
        case "no-key-off": text = "Light key ✗ \(phone) didn't switch its light off in time"
        case "other-phone": text = "Light key ✗ \(phone): another phone was first"
        case "gone": text = "Light key ✗ \(phone) disconnected"
        case "late", "stopped", "calibrating": return
        default: text = "Light key ✗ \(s) \(lux(pre)) → \(lux(peak)) lux (needed \(lux(need)))"
        }
        add(text, .bad)
    }

    func accepted(_ peer: String, kind: String, spot: Bool = false) {
        if spot { lastCover = Date() }
        let what: String
        switch kind {
        case "pick": what = "is sending (picking what to send)"
        case "link": what = "is sending the page"
        case "inbox": what = "is collecting what's waiting on this Mac"
        default: what = "is sending its screen"
        }
        add(spot ? "\(peer) sent from its old tap spot — update TapDrop on the phone" : "\(peer) \(what)", .good)
    }

    /// The phone said no. Returns a short message worth showing in the notch, if any.
    /// `Android phone declined: facing (dark room, back of phone)`
    @discardableResult
    func declined(_ peer: String, why: String) -> String? {
        let (log, notch): (String?, String?) = {
            switch why {
            case "still": return ("\(peer) declined: still (it wasn't moving — a hand over the notch?)", nil)
            case "locked": return ("\(peer) declined: locked", "Unlock \(peer) to send")
            case "screen-off": return ("\(peer) declined: screen off", "Wake \(peer) to send")
            case "in-call": return ("\(peer) declined: in a call", nil)
            case "cooldown": return ("\(peer) declined: cooldown (it just sent something)", nil)
            case "busy": return ("\(peer) declined: busy with another transfer", nil)
            case "facing": return ("\(peer) declined: facing (its back was to the notch; only the screen's light counts)", nil)
            case "update-mac": return ("\(peer) declined: update-mac (TapDrop on this Mac is older than the phone's)",
                                       "Update TapDrop on this Mac")
            case "no-light", "calibrating": return (nil, nil)  // the light key (or calibration) line already says why
            case "self": return ("TapDrop was open on \(peer) — open what you want to send, then tap",
                                 "Open what you want to send on \(peer)")
            case "off": return ("Sending is turned off for that app on \(peer)", nil)
            case "secure": return ("That app on \(peer) blocks screenshots", nil)
            case "no-screenshot": return ("\(peer) couldn't capture its screen", nil)
            default: return ("\(peer) declined: \(why.isEmpty ? "no reason given" : why)", nil)
            }
        }()
        if let log { add(log, .bad) }
        return notch
    }

    /// A free-form line (e.g. a phone that needs the update).
    func note(_ text: String, good: Bool = false) {
        add(text, good ? .good : .neutral)
    }

    private func lux(_ v: Double) -> String {
        v < 10 && v.rounded() != v ? String(format: "%.1f", v) : String(format: "%.0f", v)
    }

    private func add(_ text: String, _ mood: Event.Mood) {
        events.insert(Event(text: text, mood: mood), at: 0)
        if events.count > 30 { events.removeLast(events.count - 30) }
        if TestMode.on { NSLog("TapDrop[test]: log: %@", text) }
        let line = "\(ISO8601DateFormatter().string(from: Date()))  \(text)\n"
        DispatchQueue.global(qos: .utility).async { Self.append(line) }
    }

    nonisolated private static func append(_ line: String) {
        let url = fileURL
        let size = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int) ?? 0
        if size > 64 * 1024, let d = try? Data(contentsOf: url) {
            try? d.suffix(32 * 1024).write(to: url, options: .atomic)  // keep the newest half
        }
        if let h = try? FileHandle(forWritingTo: url) {
            defer { try? h.close() }
            _ = try? h.seekToEnd()
            try? h.write(contentsOf: Data(line.utf8))
        } else {
            try? Data(line.utf8).write(to: url)
        }
    }
}
