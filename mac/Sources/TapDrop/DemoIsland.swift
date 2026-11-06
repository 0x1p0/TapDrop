import AppKit
import Foundation

/// Demo modes for the notch island and the pairing prompt (TAPDROP_DEMO=<mode>), for visual QA.
///
/// Island: `island:<state>`, where state is one of
///   keying, keying@<0…1>, accepted, key-failed        the light key at the notch (keying@ freezes the sweep)
///   connecting, opening, receiving, receiving-live, compact, picking, reading, sending
///   received, received-one, received-long, received-pdf, screenshot, link, text, sent, hover
///   queued, code, code-long, code-settled (shoot after 6 s), paired, clipboard, copied-text, no-text
///   hint-<reason>: a tap that didn't count (not-seen, no-light-sent, stayed-lit, no-key-off, facing, screen-only,
///     other-phone, late, gone, busy, cooldown; calibrating says nothing)
///   locked, asleep, update-phone, update-mac, open-app, long-name   (the phone's declines and other notices)
///   app-update   a new TapDrop for this Mac is out
///   failed, sequence (a tap from the light key to the result, live), dismiss, handover
/// Every island mode draws a stand-in for the hardware notch, so screenshots show the island in place.
/// Prefix a state with `nonotch-` (island:nonotch-received) to see it as on a Mac without a notch, and set
/// TAPDROP_REDUCE_MOTION=1 for Reduce Motion, TAPDROP_ISLAND_SLOWMO=<n> to photograph morphs mid-way.
/// TAPDROP_DEMO_AT=<s> puts the island up at rest first and stages the state that many seconds later (with
/// TAPDROP_SNAP_BURST, to watch it grow out of the notch).
///
/// Pairing: `pair` (in App.swift), `pair:approved`, `pair:withdrawn`, `pair:expired`, `pair:long`.
extension Demo {
    /// Returns true when `what` is one of this file's modes.
    @MainActor static func island(_ what: String, files: [URL]) -> Bool {
        if what.hasPrefix("pair:") {
            PairPrompt.demo(String(what.dropFirst("pair:".count)))
            return true
        }
        guard what.hasPrefix("island:") else { return false }
        var mode = String(what.dropFirst("island:".count))
        let hud = HUD.shared
        hud.state.standIn = true
        if mode.hasPrefix("nonotch-") {
            // as on a Mac without a notch (or an external display): it hangs from the top edge
            hud.state.demoNoNotch = true
            mode = String(mode.dropFirst("nonotch-".count))
        }
        Store.shared.clearOutbox()
        let at = ProcessInfo.processInfo.environment["TAPDROP_DEMO_AT"].flatMap(Double.init) ?? 0
        if at > 0 {
            hud.demoAtRest()
            after(at) { [mode] in stage(mode, files: files) }
        } else {
            stage(mode, files: files)
        }
        return true
    }

    /// Sets one island state up.
    @MainActor private static func stage(_ mode: String, files: [URL]) {
        let a = Activity.shared
        let hud = HUD.shared
        let photos = files.filter { ["jpg", "jpeg", "png", "heic", "mp4", "mov"].contains($0.pathExtension.lowercased()) }
        let pdf = files.first { $0.pathExtension.lowercased() == "pdf" }
        let s = Store.shared

        switch mode {
        // MARK: the light key
        case "keying":
            TapStatus.shared.keying(src: "screen")
        case let m where m.hasPrefix("keying@"):
            // a frozen point in the sweep, e.g. keying@0.4
            hud.state.demoSweep = Double(m.dropFirst("keying@".count)) ?? 0.5
            TapStatus.shared.keying(src: "screen")
        case "accepted":
            hud.state.demoHoldLight = true
            TapStatus.shared.keying(src: "screen")
            after(0.6) { hud.light(.accepted) }
        case "key-failed":
            hud.state.demoHoldLight = true
            TapStatus.shared.keying(src: "screen")
            after(0.6) { hud.light(.failed(reason: "not-seen")) }

        // MARK: transfers in flight
        case "connecting":
            // the tap counted; nothing has arrived yet (the ring spins until bytes flow)
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
        case "opening":
            a.begin(incoming: true, peer: "Android phone", title: "Opening from Android phone")
        case "receiving":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.arriving(name: "IMG_20261104_181203.jpg", mime: "image/jpeg", peer: "Android phone", count: 1)
            run(to: 0.63, total: 4_800_000, peer: "Android phone")
        case "receiving-live":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.arriving(name: "Sunset.jpg", mime: "image/jpeg", peer: "Android phone", count: 3)
            if let first = photos.first { after(0.3) { a.arrived(first) } }
            run(to: 0.41, total: 14_200_000, peer: "Android phone")
        case "compact":
            hud.state.demoCompact = true
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.arriving(name: "IMG_20261104_VID.mp4", mime: "video/mp4", peer: "Android phone", count: 1)
            run(to: 0.58, total: 248_000_000, peer: "Android phone")
        case "picking":
            a.beginPick(peer: "Android phone")
        case "reading":
            a.reading(peer: "Android phone")
        case "sending":
            s.enqueue(urls: Array(photos.prefix(2)))
            a.begin(incoming: false, peer: "Android phone", title: "Sending 2 items to Android phone")
            run(to: 0.72, total: 9_600_000, peer: "Android phone", detail: photos.first?.lastPathComponent)

        // MARK: results
        case "received":
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(photos.prefix(3)))
        case "received-one":
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: photos.first?.lastPathComponent ?? "Sunset.jpg", detail: "From Android phone · Downloads",
                     files: Array(photos.prefix(1)))
        case "received-long":
            // a long name keeps its start and its extension
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Quarterly planning — design review notes (final, v3).pdf", detail: "From Android phone · Downloads",
                     files: pdf.map { [$0] } ?? [])
        case "received-pdf":
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: pdf?.lastPathComponent ?? "Boarding pass.pdf", detail: "From Android phone · Downloads",
                     files: pdf.map { [$0] } ?? [])
        case "screenshot":
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Screenshot from Android phone", detail: "Copied to clipboard · Downloads",
                     files: Array(photos.dropFirst(1).prefix(1)))
        case "link":
            a.finish(title: "Opened link from Android phone", detail: "stories.design",
                     link: URL(string: "https://stories.design/the-quiet-web"))
        case "text":
            a.finish(title: "Copied text from Android phone",
                     detail: "Gate B32 · boarding at 7:40. Seat 14A, window. Bags go to belt 6.")
        case "sent":
            s.enqueue(urls: Array(photos.prefix(2)))
            a.begin(incoming: false, peer: "Android phone", title: "Sending 2 items to Android phone")
            s.dequeue(Set(s.outbox.map(\.id)))
            a.finish(title: "Sent 2 items to Android phone")
        case "hover":
            hud.state.demoHover = true
            hud.state.hover = true
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(photos.prefix(3)))

        // MARK: notices
        case "queued":
            ServiceProvider.queue(Array(photos.prefix(2)))
            keep()
        case "code":
            a.showCode("482915", source: "Lumen Bank", peer: "Android phone")
        case "code-long":
            a.showCode("48291537", source: "Google", peer: "Android phone")
        case "code-settled":
            // after a few seconds the confirmation settles into a Copy button
            a.showCode("482915", source: "Lumen Bank", peer: "Android phone")
        case "paired":
            a.notice(.paired, title: "Paired with Android phone", detail: "Share anything to “\(Store.computerName)” from your phone",
                     peer: "Android phone", sound: false, hideAfter: 60)
        case "clipboard":
            a.notice(.clipboard, title: "Clipboard sent to Android phone", detail: "Gate B32 · boarding at 7:40", peer: "Android phone",
                     sound: false, hideAfter: 60)
        case "copied-text":
            a.notice(.clipboard, title: "Copied 42 words from Android phone", detail: "Your table is ready at 8:30 — show this at the door",
                     peer: "Android phone", sound: false, hideAfter: 60)
        case "no-text":
            a.notice(.clipboard, title: "No text found", detail: "Nothing readable on Android phone's screen", peer: "Android phone",
                     sound: false, hideAfter: 60)

        // MARK: a tap that didn't count, by reason (as a round ends: its result, then the hint)
        case let m where m.hasPrefix("hint-"):
            let why = String(m.dropFirst("hint-".count))
            TapStatus.shared.finished(TapStatus.TapResult(time: Date(), phone: "Android phone", trigger: "knock", src: "screen",
                                                          room: 120, pre: 120, peak: 380, need: 600, accepted: false, why: why))
            a.keyHint(why)
            keepUp()

        // MARK: the phone's declines and other notices (the copy Server and WatchHub use)
        case "locked":
            hint("Unlock Android phone to send", "Then touch the top of its screen to the notch")
        case "asleep":
            hint("Wake Android phone to send", "Then touch the top of its screen to the notch")
        case "update-phone":
            hint("Update TapDrop on Android phone", "It needs the new version to keep tapping")
        case "update-mac":
            hint("Update TapDrop on this Mac", "Then touch the top of its screen to the notch")
        case "app-update":
            // a new TapDrop for this Mac is out (Updater says so once per version)
            a.notice(.queued, title: "TapDrop 2.1.1 is available", detail: "See what's new in Settings", sound: false, hideAfter: 60,
                     style: .appUpdate)
        case "open-app":
            hint("Open what you want to send on Android phone", "Then touch the top of its screen to the notch")
        case "long-name":
            // a long phone name wraps rather than losing the end of the sentence
            hint("Unlock Galaxy S24 Ultra (Work phone) to send", "Then touch the top of its screen to the notch")

        // MARK: failure
        case "failed":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.fail("The connection was lost")
        case "sequence":
            sequence(photos: photos)
        case "dismiss":
            // a result that tucks back into the notch after TAPDROP_DEMO_FINISH_AT seconds (default 1)
            let at = ProcessInfo.processInfo.environment["TAPDROP_DEMO_FINISH_AT"].flatMap(Double.init) ?? 1
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(photos.prefix(3)))
            after(at) { a.dismiss() }
        case "handover":
            // a transfer that finishes after TAPDROP_DEMO_FINISH_AT seconds (default 1; with TAPDROP_ISLAND_SLOWMO,
            // to watch the content hand over)
            let at = ProcessInfo.processInfo.environment["TAPDROP_DEMO_FINISH_AT"].flatMap(Double.init) ?? 1
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.arriving(name: "Sunset.jpg", mime: "image/jpeg", peer: "Android phone", count: 3)
            run(to: 0.9, total: 14_200_000, peer: "Android phone")
            after(at) {
                a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(photos.prefix(3)))
            }
        default:
            NSLog("TapDrop: no island demo called %@", mode)
        }
    }

    // MARK: helpers

    @MainActor private static func after(_ seconds: Double, _ body: @escaping @MainActor () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { MainActor.assumeIsolated(body) }
    }

    /// Notices hide after a few seconds; demos keep them up for the camera.
    @MainActor private static func keep() {
        let a = Activity.shared
        a.notice(.queued, title: a.title, detail: a.detail, sound: false, hideAfter: 60)
    }

    /// Keeps the hint that's up on screen for the camera (hints go after a couple of seconds).
    @MainActor private static func keepUp() {
        let a = Activity.shared
        guard a.phase == .done else { return }
        a.notice(.queued, title: a.title, detail: a.detail, sound: false, hideAfter: 60, style: a.noticeStyle)
    }

    @MainActor private static func hint(_ title: String, _ detail: String) {
        Activity.shared.notice(.queued, title: title, detail: detail, sound: false, hideAfter: 60)
    }

    /// Bytes flow until `fraction`, then hold (so a screenshot catches the transfer mid-way).
    @MainActor private static func run(to fraction: Double, total: Int64, peer: String, detail: String? = nil) {
        let a = Activity.shared
        var done: Int64 = 0
        let step = max(1, Int64(Double(total) * fraction / 24))
        Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { t in
            MainActor.assumeIsolated {
                done = min(done + step, Int64(Double(total) * fraction))
                a.update(detail: detail ?? "From \(peer)", done: done, total: total)
                if done >= Int64(Double(total) * fraction) { t.invalidate() }
            }
        }
    }

    /// A whole tap: the light key at the notch, the flare, three photos arriving, the result.
    @MainActor private static func sequence(photos: [URL]) {
        let a = Activity.shared
        TapStatus.shared.keying(src: "screen")
        after(1.4) { HUD.shared.light(.accepted) }
        after(1.6) {
            a.begin(incoming: true, peer: "Android phone", title: "Receiving from Android phone")
            a.arriving(name: "Sunset.jpg", mime: "image/jpeg", peer: "Android phone", count: 3)
            run(to: 1, total: 14_200_000, peer: "Android phone")
        }
        if let first = photos.first { after(2.0) { a.arrived(first) } }
        if photos.count > 1 { after(2.5) { a.arrived(photos[1]) } }
        after(3.2) {
            a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(photos.prefix(3)))
        }
    }
}
