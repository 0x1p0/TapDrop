import Foundation

/// What happened on each notch tap — shown in Settings → Tap to Send so a tap that "did nothing"
/// always has an answer (no phone connected, phone locked, phone wasn't moving…).
/// Also kept as a small rolling log file next to the app's state.
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

    nonisolated private static var fileURL: URL { Store.dir.appendingPathComponent("taps.log") }

    func covered(phones: [String]) {
        lastCover = Date()
        if phones.isEmpty {
            add("Notch covered — no phone connected", .neutral)
        } else {
            add("Notch covered — asked \(phones.joined(separator: ", "))", .neutral)
        }
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
        add(spot ? "\(peer) tapped its spot on this Mac and \(what.replacingOccurrences(of: "is ", with: "is now "))" : "\(peer) \(what)", .good)
    }

    /// The phone said no. Returns a short message worth showing in the notch, if any.
    @discardableResult
    func declined(_ peer: String, why: String) -> String? {
        let (log, notch): (String, String?) = {
            switch why {
            case "still": return ("\(peer) ignored it: the phone wasn't moving (a hand over the notch?)", nil)
            case "locked": return ("\(peer) is locked — unlock it, then tap", "Unlock \(peer) to send")
            case "screen-off": return ("\(peer)'s screen is off — wake it, then tap", "Wake \(peer) to send")
            case "cooldown": return ("Ignored: \(peer) just sent something", nil)
            case "busy": return ("\(peer) is busy with another transfer", nil)
            case "self": return ("TapDrop was open on \(peer) — open what you want to send, then tap",
                                 "Open what you want to send on \(peer)")
            case "off": return ("Sending is turned off for that app on \(peer)", nil)
            case "secure": return ("That app on \(peer) blocks screenshots", nil)
            case "no-screenshot": return ("\(peer) couldn't capture its screen", nil)
            default: return ("\(peer) didn't send", nil)
            }
        }()
        add(log, .bad)
        return notch
    }

    private func add(_ text: String, _ mood: Event.Mood) {
        events.insert(Event(text: text, mood: mood), at: 0)
        if events.count > 30 { events.removeLast(events.count - 30) }
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
