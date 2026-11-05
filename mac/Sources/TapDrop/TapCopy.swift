import Foundation

/// How TapDrop talks about the tap, in one place, so the panel, the island, Settings, Setup and the install page
/// (and the phone) all say it the same way: plain, calm, second person.
enum TapCopy {
    /// The gesture.
    static let gesture = "Touch the top of your phone's screen to the notch."
    /// How long to stay there.
    static let hold = "Hold it there until your phone buzzes."
    /// Why a tap can't happen by accident.
    static let safety = "TapDrop only sends when your Mac's notch sees your phone's screen light up and go dark right against it. "
        + "A hand, a lamp or a tap anywhere else can't send."
    /// For screen readers: what the gesture drawings show.
    static let drawing = "A phone, seen from the back, with the top of its screen pressed to the notch and its light spilling onto the display"

    struct Hint: Equatable {
        let title: String
        let detail: String
    }

    /// Rounds that end without a send but aren't a failed tap (a calibration sample): no hint, no amber light.
    static func quiet(_ why: String) -> Bool { why == "calibrating" }

    /// What the notch says after a tap that didn't count, by its reason (a light-key failure or the phone's
    /// decline). Nil when there's nothing to say: a calibration sample, or a reason that isn't about the gesture.
    static func hint(_ why: String) -> Hint? {
        if quiet(why) { return nil }
        return switch why {
        case "not-seen", "no-light-sent":
            Hint(title: "Your Mac didn't see your screen", detail: "Press the top of the screen flat on the notch")
        case "stayed-lit", "no-key-off":
            Hint(title: "Keep your phone on the notch", detail: "Hold it there until your phone buzzes")
        case "facing", "screen-only":
            Hint(title: "Turn your phone around", detail: "Its screen goes against the notch")
        case "other-phone", "late", "gone", "busy", "cooldown", "no-light":
            Hint(title: "That tap didn't count", detail: "Try again")
        default:
            nil
        }
    }
}
