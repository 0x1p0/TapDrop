import AppKit
import Combine
import ServiceManagement
import SwiftUI

@main
enum TapDropMain {
    static func main() {
        let app = NSApplication.shared
        let delegate = AppDelegate()
        app.delegate = delegate
        app.setActivationPolicy(.accessory)
        withExtendedLifetime(delegate) { app.run() }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var status: StatusController?
    private let services = ServiceProvider()
    private var testSignal: DispatchSourceSignal?
    private var testRelease: DispatchSourceSignal?

    func applicationDidFinishLaunching(_ notification: Notification) {
        TDFonts.register()
        Store.applyAppearance(Store.shared.settings.appearance)
        if TestHooks.on, ProcessInfo.processInfo.environment["TAPDROP_TEST_SELFTEST"] == "1" {
            // The light-key trigger logic against synthetic light traces, then quit.
            let failures = NotchDetector.selfTest()
            print(failures.isEmpty ? "ALL PASSED" : "\(failures.count) FAILED: \(failures.joined(separator: ", "))")
            exit(failures.isEmpty ? 0 : 1)
        }
        // Headless integration tests (TAPDROP_TEST without a demo) stay out of the user's menu bar and notch.
        if !TestMode.headless && ProcessInfo.processInfo.environment["TAPDROP_NO_STATUS"] == nil { status = StatusController() }
        // Notch taps: the light sensor's triggers start light-key rounds; only a key the notch sees sends.
        KeyRounds.shared.attach()
        Server.shared.start()
        #if DEBUG
        // Captures: serve the phone's download page without opening the Setup window.
        if TestHooks.on, ProcessInfo.processInfo.environment["TAPDROP_TEST_INSTALL"] == "1" { InstallServer.shared.start() }
        #endif
        if TestHooks.on {
            // Integration tests (debug builds only) run on a simulated light sensor (a 120-lux room).
            // `kill -USR1 <pid>` covers the notch: a DIP round with B = 120, P = 4. Each key-on is answered per
            // TAPDROP_TEST_KEY (pass: 900 lux after 120 ms, dark again at key-off; stay: it stays lit; fail: 6 lux
            // until the window ends; uncover: the room's light comes back).
            signal(SIGUSR1, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
            src.setEventHandler { KeyRounds.shared.testCover() }
            // `kill -USR2 <pid>` takes the phone away from the notch (the round releases its phone).
            signal(SIGUSR2, SIG_IGN)
            let rel = DispatchSource.makeSignalSource(signal: SIGUSR2, queue: .main)
            rel.setEventHandler { KeyRounds.shared.testLeave() }
            rel.resume()
            testRelease = rel
            src.resume()
            testSignal = src
        }

        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
        NSApp.mainMenu = MainMenu.make()

        let store = Store.shared
        if !store.settings.didFirstLaunch {
            store.settings.didFirstLaunch = true
            if Bundle.main.bundlePath.hasPrefix("/Applications") {
                try? SMAppService.mainApp.register()
            }
            if store.devices.isEmpty {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { SetupWindow.shared.show() }
            }
        }
        // Once a day: is there a newer TapDrop? (Not in demos or test runs, unless a debug build has a test feed.)
        Updater.shared.start()
        if let demo = ProcessInfo.processInfo.environment["TAPDROP_DEMO"] {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { Demo.run(demo, status: self.status) }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // The Dock icon (there while a window is open) brings that window back; opening TapDrop again otherwise
        // shows the panel.
        if AppPresence.hasWindows { return true }
        status?.openPanel()
        return false
    }
}

/// Menu bar icon. Click → glass panel. Drag files over it → panel springs open as a drop target.
@MainActor
final class StatusController: NSObject, NSWindowDelegate, NSDraggingDestination {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    let panel = PanelController()
    private var bag = Set<AnyCancellable>()
    private var waveTimer: Timer?
    private var wavePhase = 0

    override init() {
        super.init()
        if let button = item.button {
            button.image = Self.icon(variable: nil)
            button.imagePosition = .imageLeading
            button.action = #selector(toggle)
            button.target = self
            button.window?.registerForDraggedTypes([.fileURL])
            button.window?.delegate = self
            button.setAccessibilityLabel("TapDrop")
        }

        Store.shared.$outbox
            .map(\.count)
            .removeDuplicates()
            .sink { [weak self] n in
                guard let button = self?.item.button else { return }
                button.attributedTitle = n > 0
                    ? NSAttributedString(string: " \(n)", attributes: [
                        .font: NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .semibold),
                    ])
                    : NSAttributedString(string: "")
            }
            .store(in: &bag)

        Activity.shared.$phase
            .removeDuplicates()
            .sink { [weak self] phase in self?.animateWaves(phase == .active) }
            .store(in: &bag)
    }

    /// The radio waves in the menu bar ripple while a transfer runs, like AirDrop.
    private func animateWaves(_ on: Bool) {
        waveTimer?.invalidate()
        waveTimer = nil
        guard on else {
            item.button?.image = Self.icon(variable: nil)
            return
        }
        waveTimer = Timer.scheduledTimer(withTimeInterval: 0.16, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.wavePhase = (self.wavePhase + 1) % 4
                self.item.button?.image = Self.icon(variable: Double(self.wavePhase) / 3)
            }
        }
    }

    private static func icon(variable: Double?) -> NSImage? {
        let base: NSImage?
        if let variable {
            base = NSImage(systemSymbolName: "dot.radiowaves.left.and.right", variableValue: variable, accessibilityDescription: "TapDrop")
        } else {
            base = NSImage(systemSymbolName: "dot.radiowaves.left.and.right", accessibilityDescription: "TapDrop")
        }
        let img = base?.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: 14, weight: .semibold))
        img?.isTemplate = true
        return img
    }

    @objc func toggle() {
        panel.toggle(below: item.button)
    }

    func openPanel() {
        if !panel.isShown { panel.show(below: item.button) }
    }

    // NSWindow forwards dragging-destination calls to its delegate.
    func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        item.button?.highlight(true)
        openPanel()  // spring-load: hovering a drag over the icon opens the drop panel
        return .copy
    }

    func draggingExited(_ sender: NSDraggingInfo?) {
        item.button?.highlight(panel.isShown)
    }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        item.button?.highlight(panel.isShown)
        guard let urls = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty
        else { return false }
        ServiceProvider.queue(urls)
        return true
    }
}

/// Finder → right-click → Quick Actions/Services → "Send to Android Phone".
final class ServiceProvider: NSObject {
    @MainActor static func queue(_ urls: [URL]) {
        Store.shared.enqueue(urls: urls)
        announce(urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) items")
    }

    /// The island once something's queued: with the phone connected it goes straight away (the phone fetches it);
    /// otherwise it waits on this Mac until the phone is back on the Wi‑Fi.
    @MainActor static func announce(_ what: String) {
        let live = !Store.shared.liveDevices.isEmpty
        Activity.shared.notice(
            .queued,
            title: live ? "Sending \(what) to your phone" : "Ready to send \(what)",
            detail: live ? "It arrives in a moment" : "It goes as soon as your phone is on this Wi‑Fi",
            sound: false, hideAfter: 3.5)
    }

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        let text = pboard.string(forType: .string)
        DispatchQueue.main.async {
            if !urls.isEmpty {
                ServiceProvider.queue(urls)
            } else if let text, !text.isEmpty {
                Store.shared.enqueue(text: text)
                ServiceProvider.announce("text")
            }
        }
    }
}

/// Screens on demand for visual QA (TAPDROP_DEMO=panel[:state]|receiving|received|pair|setup|queued|failed|otp|settings:<pane>).
/// Panel states: live (= plain `panel`), new, empty, waiting, away, starting, offline, transfer, done, failed,
/// queued, drop, hover, longname, update (a new TapDrop is out: the row at the foot of the panel).
/// TAPDROP_APPEARANCE=dark|light forces the appearance; TAPDROP_SNAP=<dir> makes the run photograph its windows and quit
/// (see DSSnapshot).
@MainActor
enum Demo {
    static func run(_ what: String, status: StatusController?) {
        if let look = ProcessInfo.processInfo.environment["TAPDROP_APPEARANCE"] {
            NSApp.appearance = NSAppearance(named: look == "light" ? .aqua : .darkAqua)
        }
        defer { DSSnapshot.scheduleIfRequested(mode: what) }
        let a = Activity.shared
        let files = (ProcessInfo.processInfo.environment["TAPDROP_DEMO_FILES"] ?? "")
            .split(separator: ":").map { URL(fileURLWithPath: String($0)) }
        if what.hasPrefix("panel") {
            panel(String(what.dropFirst("panel".count).drop { $0 == ":" }), files: files, status: status)
            return
        }
        if Store.shared.recent.isEmpty, files.count >= 3 { addRecents(files) }
        // Screen-specific demo modes live next to their screens (DemoIsland.swift, DemoWindows.swift).
        if island(what, files: files) || windows(what, files: files) { return }
        switch what {
        case "receiving":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving 3 items from Android phone")
            var done: Int64 = 0
            Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { t in
                Task { @MainActor in
                    done += 900_000
                    a.update(detail: "IMG_20261011_141203.jpg", done: min(done, 48_000_000), total: 48_000_000)
                    if done > 30_000_000 { t.invalidate() }
                }
            }
        case "received":
            let files = Array(files.prefix(3))
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: files.count == 1 ? files[0].lastPathComponent : "Received \(files.count) photos",
                     detail: "From Android phone · Downloads", files: files)
        case "queued":
            a.notice(.queued, title: "Ready to send 2 items", detail: "Touch the top of your phone's screen to the notch", sound: false,
                     hideAfter: 30)
        case "otp":
            a.showCode("482915", source: "Lumen Bank", peer: "Android phone")
        case "failed":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving")
            a.fail("The connection was lost")
        case "pair":
            Task { _ = await PairPrompt.askDemo() }
        case "setup":
            SetupWindow.shared.show()
        case "gallery":
            DSGallery.show()
        case let w where w.hasPrefix("settings"):
            let pane = SettingsPane(rawValue: String(w.dropFirst("settings".count).drop { $0 == ":" })) ?? .general
            SettingsWindow.shared.show(pane)
        default:
            break
        }
    }

    private static func addRecents(_ files: [URL]) {
        let s = Store.shared
        s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: "Gate B32", files: [], text: "Gate B32 · boarding at 7:40"))
        s.addRecent(RecentEvent(incoming: false, peer: "Android phone", summary: files.last!.lastPathComponent, files: [files.last!]))
        s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: "3 files", files: Array(files.prefix(3))))
        s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: "apple.com", files: [],
                                text: "https://www.apple.com/macbook-air/"))
        s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: files[1].lastPathComponent, files: [files[1]]))
    }

    /// Sets the throwaway demo home up for one panel state, then opens the panel.
    private static func panel(_ mode: String, files: [URL], status: StatusController?) {
        let s = Store.shared
        let a = Activity.shared
        let id = "demo-pixel"
        let state = status?.panel.state
        s.clearOutbox()
        s.recent = []
        // `longname`: a phone with a long name, live, with things waiting for it
        let name = mode == "longname" ? "Galaxy S24 Ultra (Work phone)" : "Android phone"
        for d in s.devices where mode == "new" || d.id != id || d.name != name { s.removeDevice(d.id) }
        if mode != "new", s.device(id) == nil { s.addDevice(id: id, name: name, key: Data(count: 32)) }
        let away = ["waiting", "away", "starting", "offline", "new"].contains(mode)
        s.liveDevices = away ? [] : [id]
        if !["new", "empty"].contains(mode), files.count >= 3 { addRecents(files) }
        state?.demoOffline = mode == "offline"
        state?.demoHoverFirst = mode == "hover"
        state?.demoLink = mode == "away" ? .away : nil
        state?.demoPinned = true
        switch mode {
        case "starting":
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { s.status = "Restarting…" }
        case "transfer":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving 3 photos from Android phone")
            a.arriving(name: "IMG_20261104_181203.jpg", mime: "image/jpeg", peer: "Android phone", count: 3)
            var done: Int64 = 0
            Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { t in
                Task { @MainActor in
                    done += 1_400_000
                    a.update(detail: "IMG_20261104_181203.jpg", done: min(done, 48_000_000), total: 48_000_000)
                    if done > 29_000_000 { t.invalidate() }
                }
            }
        case "done":
            a.begin(incoming: true, peer: "Android phone", title: "")
            a.finish(title: "Received 3 photos", detail: "From Android phone · Downloads", files: Array(files.prefix(3)))
        case "failed":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving")
            a.fail("The connection was lost")
        case "queued", "longname":
            s.enqueue(urls: Array(files.prefix(3)) + [files.last!])
            s.enqueue(text: "Gate B32 · boarding at 7:40")
        case "update":
            Updater.shared.demo("available")
        default:
            break
        }
        status?.openPanel()
        if mode == "drop" {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { withTDAnimation { state?.dropTargeted = true } }
        }
    }
}
