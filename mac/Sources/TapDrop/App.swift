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
        // Headless integration tests (TAPDROP_TEST without a demo) stay out of the user's menu bar and notch.
        if !TestMode.headless && ProcessInfo.processInfo.environment["TAPDROP_NO_STATUS"] == nil { status = StatusController() }
        Server.shared.start()
        if !TestMode.on {
            NotchSensor.shared.onCover = { WatchHub.shared.grab() }
            NotchSensor.shared.onUncover = { WatchHub.shared.release() }
        }
        if TestMode.on {
            // Integration tests: `kill -USR1 <pid>` simulates covering the notch.
            signal(SIGUSR1, SIG_IGN)
            let src = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
            src.setEventHandler { WatchHub.shared.grab() }
            // `kill -USR2 <pid>` simulates pulling the phone away from the notch.
            signal(SIGUSR2, SIG_IGN)
            let rel = DispatchSource.makeSignalSource(signal: SIGUSR2, queue: .main)
            rel.setEventHandler { WatchHub.shared.release() }
            rel.resume()
            testRelease = rel
            src.resume()
            testSignal = src
        }

        NSApp.servicesProvider = services
        NSUpdateDynamicServices()

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
        if let demo = ProcessInfo.processInfo.environment["TAPDROP_DEMO"] {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { Demo.run(demo, status: self.status) }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
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
        item.button?.highlight(false)
    }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        item.button?.highlight(false)
        guard let urls = sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty
        else { return false }
        ServiceProvider.queue(urls)
        return true
    }
}

/// Finder → right-click → Quick Actions/Services → "Send to Phone with TapDrop".
final class ServiceProvider: NSObject {
    @MainActor static func queue(_ urls: [URL]) {
        Store.shared.enqueue(urls: urls)
        Activity.shared.notice(
            .queued,
            title: urls.count == 1 ? "Ready to send \(urls[0].lastPathComponent)" : "Ready to send \(urls.count) items",
            detail: "Tap your phone on the Mac to receive", sound: false, hideAfter: 3.5)
    }

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        let text = pboard.string(forType: .string)
        DispatchQueue.main.async {
            if !urls.isEmpty {
                ServiceProvider.queue(urls)
            } else if let text, !text.isEmpty {
                Store.shared.enqueue(text: text)
                Activity.shared.notice(.queued, title: "Ready to send text", detail: "Tap your phone on the Mac to receive",
                                       sound: false, hideAfter: 3.5)
            }
        }
    }
}

/// Screens on demand for visual QA (TAPDROP_DEMO=panel|receiving|received|pair|setup|queued|failed).
@MainActor
enum Demo {
    static func run(_ what: String, status: StatusController?) {
        let a = Activity.shared
        let files = (ProcessInfo.processInfo.environment["TAPDROP_DEMO_FILES"] ?? "")
            .split(separator: ":").map { URL(fileURLWithPath: String($0)) }
        if Store.shared.recent.isEmpty, files.count >= 3 {
            let s = Store.shared
            s.addRecent(RecentEvent(incoming: false, peer: "Android phone", summary: "Keynote draft.pdf", files: [files.last!]))
            s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: "github.com", files: [], text: "https://github.com"))
            s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: "3 files", files: Array(files.prefix(3))))
            s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: files[1].lastPathComponent, files: [files[1]]))
            s.addRecent(RecentEvent(incoming: true, peer: "Android phone", summary: files[0].lastPathComponent, files: [files[0]]))
        }
        switch what {
        case "panel":
            status?.openPanel()
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
            a.notice(.queued, title: "Ready to send 2 items", detail: "Tap your phone on the Mac to receive", sound: false, hideAfter: 30)
        case "otp":
            a.showCode("482915", source: "HDFC Bank", peer: "Android phone")
        case "failed":
            a.begin(incoming: true, peer: "Android phone", title: "Receiving")
            a.fail("The connection was lost")
        case "pair":
            Task { _ = await PairPrompt.askDemo() }
        case "setup":
            SetupWindow.shared.show()
        case let w where w.hasPrefix("settings"):
            let pane = SettingsPane(rawValue: String(w.dropFirst("settings".count).drop { $0 == ":" })) ?? .general
            SettingsWindow.shared.show(pane)
        default:
            break
        }
    }
}
