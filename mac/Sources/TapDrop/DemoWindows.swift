import AppKit
import SwiftUI

/// Demo modes for the Settings and Setup windows (TAPDROP_DEMO=<mode>), for visual QA.
///
///     settings[:general|tap|phones|compatibility|about]
///     settings:general:problems  the save folder is missing, Open at Login waits for approval
///     settings:compatibility:nosensor
///     settings:tap:<state>      sent (default), fail, ignored, off, nophone, waiting, nosensor, loading, stalled,
///                               details, sim; calibration: calibrate, calibrating1, calibrating2, calmissed,
///                               calibrated, nearmiss
///     settings:phones:<state>   empty, forget, update
///     setup[:<step>]            install (default), visited, downloading, downloaded, dlfailed, portbusy, noapk, nowifi,
///                               pair, paired, tap, tapready, tapped, nosensor, done, live (nothing staged)
///     qrcheck                   decodes the rendered setup QR code and prints the result
///     presence                  opens Settings, then closes it: prints whether TapDrop is in the Dock and ⌘-Tab
extension Demo {
    /// Returns true when `what` is one of this file's modes.
    @MainActor static func windows(_ what: String, files: [URL]) -> Bool {
        let parts = what.split(separator: ":").map(String.init)
        switch parts.first {
        case "settings":
            DemoWindows.settings(parts.count > 1 ? parts[1] : "general", state: parts.count > 2 ? parts[2] : "")
            return true
        case "setup":
            DemoWindows.setup(parts.count > 1 ? parts[1] : "install")
            return true
        case "qrcheck":
            DemoWindows.qrCheck()
            return true
        case "presence":
            let policy = { NSApp.activationPolicy() == .regular ? "in the Dock and ⌘-Tab" : "menu bar only" }
            print("TapDrop[demo]: presence at launch: \(policy())")
            SettingsWindow.shared.show()
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                print("TapDrop[demo]: presence with Settings open: \(policy()), active: \(NSApp.isActive)")
                NSApp.windows.first { AppPresence.isAppWindow($0) && $0.isVisible }?.performClose(nil)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    print("TapDrop[demo]: presence after closing it: \(policy()), still running, active: \(NSApp.isActive)")
                    fflush(stdout)
                }
            }
            return true
        case "installpage":
            // The served install page, written out for a browser check (with and without the APK).
            let dir = ProcessInfo.processInfo.environment["TAPDROP_SNAP"] ?? NSTemporaryDirectory()
            try? InstallPage.html(macName: "MacBook Air", apkSize: 9_437_184)
                .write(toFile: "\(dir)/install_page.html", atomically: true, encoding: .utf8)
            try? InstallPage.html(macName: "Studio’s <MacBook> & \"Air\"", apkSize: nil)
                .write(toFile: "\(dir)/install_page_noapk.html", atomically: true, encoding: .utf8)
            print("installpage: wrote \(dir)/install_page.html")
            exit(0)
        default:
            return false
        }
    }
}

@MainActor
private enum DemoWindows {
    static let pixel = "demo-pixel"
    static let galaxy = "demo-galaxy"

    // MARK: Settings

    static func settings(_ paneName: String, state: String) {
        let pane = SettingsPane(rawValue: paneName) ?? .general
        let s = Store.shared
        s.settings.notchTap = true
        phones(pane == .phones && state == "empty" || pane == .tap && state == "nophone" ? [] : [pixel, galaxy])
        let live = !(pane == .tap && ["waiting", "nophone"].contains(state)) && !(pane == .phones && state == "empty")
        s.liveDevices = live ? [pixel] : []
        s.status = "Ready"

        if state == "nosensor" { SettingsDemo.noSensor = true }
        switch pane {
        case .general:
            if state == "problems" { SettingsDemo.generalProblems = true }
        case .tap:
            tapDemo(state)
        case .phones:
            if state == "forget" { SettingsDemo.forgetFirst = true }
            if state == "update" { SettingsDemo.outdatedPhones = ["Android phone"] }
            s.clearOutbox()
            if state != "empty" { s.enqueue(text: "Gate B32 · boarding at 7:40") }
        default:
            break
        }
        let env = ProcessInfo.processInfo.environment
        SettingsDemo.scrollToEnd = env["TAPDROP_DEMO_END"] == "1"
        SettingsWindow.shared.show(pane)
        if env["TAPDROP_DEMO_TALL"] == "1" { tall("TapDrop Settings") }
        if let small = env["TAPDROP_DEMO_SMALL"], small != "0" { smallest("TapDrop Settings", widthOnly: small == "w") }
    }

    /// QA: the smallest the window can be (or only its narrowest), to catch clipped and crowded text.
    private static func smallest(_ title: String, widthOnly: Bool) {
        guard let w = NSApp.windows.first(where: { $0.title == title }) else { return }
        let size = NSSize(width: SettingsWindow.minSize.width, height: widthOnly ? w.frame.height : SettingsWindow.minSize.height)
        w.setFrame(NSRect(origin: w.frame.origin, size: size), display: true)
        w.center()
    }

    /// QA: as tall as the screen allows, so a whole pane fits in one shot.
    private static func tall(_ title: String) {
        guard let w = NSApp.windows.first(where: { $0.title == title }), let screen = w.screen ?? NSScreen.main else { return }
        let h = min(screen.visibleFrame.height - 24, 1600)
        w.setFrame(NSRect(x: w.frame.minX, y: w.frame.minY, width: w.frame.width, height: h), display: true)
        w.center()
    }

    /// Paired demo phones (Android phone first), nothing else.
    private static func phones(_ ids: [String]) {
        let s = Store.shared
        for d in s.devices where !ids.contains(d.id) { s.removeDevice(d.id) }
        if ids.contains(pixel), s.device(pixel) == nil { s.addDevice(id: pixel, name: "Android phone", key: Data(count: 32)) }
        if ids.contains(galaxy), s.device(galaxy) == nil { s.addDevice(id: galaxy, name: "Galaxy S24", key: Data(count: 32)) }
    }

    private static func tapDemo(_ state: String) {
        let s = Store.shared
        let model = TapTestModel.shared
        let log = TapLog.shared
        switch state {
        case "off": s.settings.notchTap = false
        case "nosensor": SettingsDemo.noSensor = true
        case "details": SettingsDemo.expandFirstTap = true
        default: break
        }
        if state != "sim" { model.demoFeed = true }
        TapStatus.shared.room(lux: 118, dark: false)
        let calibration = ["calibrate", "calibrating1", "calibrating2", "calmissed", "calibrated", "nearmiss"]
        #if DEBUG
        if calibration.contains(state) { TapCalibration.shared.demo(state, deviceId: pixel, phone: "Android phone") }
        #endif

        // The recent taps, oldest first (each starts with its trigger line, as the rounds write them).
        if state != "nophone" && state != "nosensor" {
            log.trigger("dip", from: 121, to: 3, asked: [])
            log.trigger("dip", from: 119, to: 4, asked: ["Android phone"])
            log.declined("Android phone", why: "still")
            log.trigger("dip", from: 118, to: 5, asked: ["Android phone"])
            log.key(ok: false, phone: "Android phone", src: "screen", pre: 5, peak: 380, need: 600, why: "not-seen")
            log.trigger("dip", from: 120, to: 3.5, asked: ["Android phone"])
            log.key(ok: true, phone: "Android phone", src: "screen", pre: 3.5, peak: 1480, need: 600, why: nil, fall: 2)
            log.accepted("Android phone", kind: "screen")
        }

        if state == "stalled" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { model.demoStall() }
            return
        }
        guard ["", "sent", "fail", "ignored", "details", "loading", "waiting", "nophone"].contains(state) || calibration.contains(state) else {
            if state == "sim" { simulate() }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            if state == "loading" { return }
            let trace = demoTrace(fail: state == "fail", ignored: state == "ignored", noPhone: ["waiting", "nophone"].contains(state))
            let failResult = TapStatus.TapResult(time: Date().addingTimeInterval(-1.9), phone: "Android phone", trigger: "dip", src: "screen",
                                                 room: 118, pre: 5, peak: 9, need: 20, accepted: false, why: "not-seen")
            let ignoredResult = TapStatus.TapResult(time: Date().addingTimeInterval(-2.0), phone: "Android phone", trigger: "dip", src: nil,
                                                    room: 118, pre: 4, peak: 0, need: 0, accepted: false, why: "still")
            let moment: TapTestModel.Moment? = switch state {
            case "fail": .result(failResult)
            case "ignored": .result(ignoredResult)
            case "waiting", "nophone": .trigger(.cover, phones: false, at: Date().addingTimeInterval(-2.2))
            default: .sent(phone: "Android phone", at: Date().addingTimeInterval(-3.35))
            }
            model.demoLoad(samples: trace.samples, room: 118, marks: trace.marks, need: trace.need, moment: moment)
        }
    }

    /// Twelve seconds of light at the notch: a tap the notch didn't see, then one it did. With no phone around,
    /// two hands over the notch and nothing else.
    private static func demoTrace(fail: Bool, ignored: Bool, noPhone: Bool = false)
        -> (samples: [(TimeInterval, Double)], marks: [(TimeInterval, TapTestModel.MarkKind, Double?)], need: (TimeInterval, Double)?) {
        var g = SystemRandomNumberGenerator()
        func room() -> Double { 118 + Double.random(in: -2.5...2.5, using: &g) }
        var samples: [(TimeInterval, Double)] = []
        var marks: [(TimeInterval, TapTestModel.MarkKind, Double?)] = []
        var need: (TimeInterval, Double)?

        // a round the notch didn't see: covered, the phone's light too weak
        let a = -8.6
        // the second round: seen and sent (or, for fail / ignored, not)
        let b = fail || ignored ? -2.6 : -4.1
        var t = -12.4
        while t <= 0.05 {
            let v: Double
            switch t {
            case a..<(a + 0.3): v = 5 + Double.random(in: -0.6...0.6, using: &g)
            case (a + 0.3)..<(a + 0.75): v = noPhone ? 4.5 : 9 + Double.random(in: -0.8...0.8, using: &g)
            case (a + 0.75)..<(a + 1.0): v = 6
            case b..<(b + 0.35): v = 3.5 + Double.random(in: -0.4...0.4, using: &g)
            case (b + 0.35)..<(b + 0.75):
                v = fail ? 9 + Double.random(in: -0.8...0.8, using: &g)
                    : (ignored || noPhone ? 4 : 860 + Double.random(in: -60...70, using: &g))
            case (b + 0.75)..<(b + 1.0): v = fail || ignored ? 5 : 3.5
            default: v = room()
            }
            samples.append((t, v))
            t += 0.1
        }
        marks.append((a, .cover, 5))
        if noPhone {
            marks.append((b, .cover, 3.5))
            return (samples, marks, nil)
        }
        marks.append((a + 0.6, .keyFail, 9))
        marks.append((b, .cover, 3.5))
        if fail {
            marks.append((b + 0.6, .keyFail, 9))
            need = (b + 0.6, 20)
        } else if ignored {
            marks.append((b + 0.5, .keyFail, nil))
        } else {
            marks.append((b + 0.5, .keyOK, 930))
            marks.append((b + 0.75, .sent, nil))
            need = (b + 0.5, 18.5)
        }
        return (samples, marks, need)
    }

    /// The real path, on the simulated sensor (TAPDROP_TEST=1): room light, a cover, the phone's light, sent.
    private static func simulate() {
        let sensor = NotchSensor.shared
        func at(_ delay: Double, _ lux: Double) {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                sensor.queue.async { sensor.simulate(lux) }
            }
        }
        at(0.5, 118)
        at(2.0, 4)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
            TapLog.shared.trigger("dip", from: 118, to: 4, asked: ["Android phone"])
            TapStatus.shared.keying(src: "screen")
        }
        at(2.4, 880)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.55) {
            TapLog.shared.key(ok: true, phone: "Android phone", src: "screen", pre: 4, peak: 880, need: 19, why: nil)
            TapStatus.shared.finished(.init(time: Date(), phone: "Android phone", trigger: "dip", src: "screen", room: 118, pre: 4,
                                            peak: 880, need: 19, accepted: true, why: nil))
        }
        at(2.8, 4)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.85) { TapLog.shared.accepted("Android phone", kind: "screen") }
        at(3.2, 119)
    }

    // MARK: Setup

    static func setup(_ step: String) {
        let s = Store.shared
        let install = InstallStatus.shared
        if step == "live" {
            // The real flow: nothing staged, nothing frozen (pair a test phone, fetch the page, tap…).
            phones([])
            s.status = "Ready"
            SetupWindow.shared.show()
            return
        }
        phones(["tap", "tapready", "tapped", "done", "nosensor"].contains(step) ? [pixel] : [])
        s.liveDevices = ["tapready", "tapped", "done"].contains(step) ? [pixel] : []
        s.status = "Ready"
        s.settings.notchTap = true
        SetupDemo.frozen = true
        if step == "nowifi" { SetupDemo.noNetwork = true }
        if step == "noapk" { InstallServer.demoNoAPK = true }
        if step == "nosensor" { SettingsDemo.noSensor = true }

        SetupWindow.shared.show()
        let model = SetupWindow.shared.model
        let phone = s.device(pixel)
        switch step {
        case "visited":
            install.visits = 1
            install.lastVisit = Date()
            model.demo(step: .install, phone: nil, tapped: false)
        case "downloading":
            install.visits = 1
            install.download = .init(sent: 6_100_000, total: 9_400_000)
            model.demo(step: .install, phone: nil, tapped: false)
        case "downloaded":
            install.visits = 1
            install.download = .init(sent: 9_400_000, total: 9_400_000, done: true)
            model.demo(step: .install, phone: nil, tapped: false)
        case "dlfailed":
            install.visits = 1
            install.download = .init(sent: 3_200_000, total: 9_400_000, failed: true)
            model.demo(step: .install, phone: nil, tapped: false)
        case "portbusy":
            model.demo(step: .install, phone: nil, tapped: false)
            // after the real server has reported in
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                install.server = .failed("Port \(InstallServer.port) is busy")
            }
        case "pair":
            model.demo(step: .pair, phone: nil, tapped: false)
        case "paired":
            model.demo(step: .pair, phone: PairedDevice(id: pixel, name: "Android phone", key: Data(count: 32), pairedAt: Date(), lastSeen: Date()),
                       tapped: false)
        case "tap", "tapready", "nosensor":
            model.demo(step: .tap, phone: phone, tapped: false)
        case "tapped":
            model.demo(step: .tap, phone: phone, tapped: true)
        case "done":
            model.demo(step: .done, phone: phone, tapped: true)
        default:
            model.demo(step: .install, phone: nil, tapped: false)
        }
    }

    // MARK: QR check

    /// Renders the setup QR code as the window draws it and decodes it with Core Image.
    static func qrCheck() {
        let urls = ["http://192.168.1.4:47914/", "http://10.0.0.123:47914/", "http://255.255.255.255:47914/"]
        var failures = 0
        for url in urls {
            let view = QRCodeView(text: url).frame(width: 220, height: 220).padding(22).background(Color.white)
            let r = ImageRenderer(content: view)
            r.scale = 2
            guard let cg = r.cgImage else {
                print("qrcheck: \(url) → no image")
                failures += 1
                continue
            }
            let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
            let found = detector?.features(in: CIImage(cgImage: cg)).compactMap { ($0 as? CIQRCodeFeature)?.messageString } ?? []
            let ok = found.contains(url)
            if !ok { failures += 1 }
            print("qrcheck: \(url) → \(found) \(ok ? "OK" : "FAIL") (\(QR.matrix(for: url)?.count ?? 0) modules)")
            if let dir = ProcessInfo.processInfo.environment["TAPDROP_SNAP"],
               let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) {
                try? png.write(to: URL(fileURLWithPath: "\(dir)/qrcheck_\(url.filter(\.isNumber).prefix(12)).png"))
            }
        }
        print(failures == 0 ? "qrcheck: ALL OK" : "qrcheck: \(failures) FAILED")
        exit(failures == 0 ? 0 : 1)
    }
}
