import AppKit

/// TapDrop lives in the menu bar. While one of its windows (Settings, Set Up a Phone) is open it is also a regular
/// app, in the Dock and ⌘-Tab, so the window can always be switched back to. Closing the last window returns it to
/// the menu bar only; it keeps running there.
@MainActor
enum AppPresence {
    private static var observing = false

    /// Call when a TapDrop window opens: becomes a regular app and comes to the front.
    static func windowOpened() {
        observe()
        if NSApp.activationPolicy() != .regular { NSApp.setActivationPolicy(.regular) }
        NSApp.activate(ignoringOtherApps: true)
    }

    private static func observe() {
        guard !observing else { return }
        observing = true
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { _ in
            // The closing window is gone by the next turn of the run loop.
            DispatchQueue.main.async { MainActor.assumeIsolated { update() } }
        }
    }

    /// A window of TapDrop's own (Settings, Setup), as opposed to the panel, the island or a file picker.
    static func isAppWindow(_ w: NSWindow) -> Bool {
        w.styleMask.contains(.titled) && !(w is NSPanel)
    }

    static var hasWindows: Bool {
        NSApp.windows.contains { isAppWindow($0) && ($0.isVisible || $0.isMiniaturized) }
    }

    private static func update() {
        guard !hasWindows, NSApp.activationPolicy() != .accessory else { return }
        NSApp.setActivationPolicy(.accessory)
        // Hand the menu bar back to the app that was in use before.
        if NSApp.isActive { NSApp.deactivate() }
    }
}

/// The menu bar while a TapDrop window is in front (a menu bar app has none otherwise).
@MainActor
enum MainMenu {
    private final class Actions: NSObject {
        @objc func settings(_ sender: Any?) {
            MainActor.assumeIsolated { SettingsWindow.shared.show() }
        }
    }

    private static let actions = Actions()

    static func make() -> NSMenu {
        let main = NSMenu()

        let app = NSMenu(title: "TapDrop")
        app.addItem(withTitle: "About TapDrop", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: "")
        app.addItem(.separator())
        let settings = app.addItem(withTitle: "Settings…", action: #selector(Actions.settings(_:)), keyEquivalent: ",")
        settings.target = actions
        app.addItem(.separator())
        app.addItem(withTitle: "Hide TapDrop", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        let others = app.addItem(withTitle: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        others.keyEquivalentModifierMask = [.command, .option]
        app.addItem(withTitle: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: "")
        app.addItem(.separator())
        app.addItem(withTitle: "Quit TapDrop", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        main.addItem(withTitle: "TapDrop", action: nil, keyEquivalent: "").submenu = app

        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        let redo = edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        main.addItem(withTitle: "Edit", action: nil, keyEquivalent: "").submenu = edit

        let window = NSMenu(title: "Window")
        window.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        window.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        main.addItem(withTitle: "Window", action: nil, keyEquivalent: "").submenu = window
        NSApp.windowsMenu = window

        return main
    }
}
