import AppKit
import SwiftUI

// A borderless Liquid Glass panel that springs down from the menu bar icon
// (replaces NSPopover, which looks like a stock settings sheet).

final class KeyPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

@MainActor
final class PanelState: ObservableObject {
    @Published var visible = false
    @Published var dropTargeted = false
    /// The Mac has a network address phones can reach (checked each time the panel opens).
    @Published var macOnline = true
    /// Demo/QA: show the first recent item as if hovered.
    @Published var demoHoverFirst = false
    /// Demo/QA: pretend the Mac is offline.
    var demoOffline = false
    /// Demo/QA: force a link state the demo home can't produce (a phone last seen long ago).
    @Published var demoLink: PanelLink?
    /// Demo/QA: stay open even though a background-launched demo can't become the active app.
    var demoPinned = false
}

@MainActor
final class PanelController: NSObject, NSWindowDelegate {
    static let contentWidth: CGFloat = 360
    static let margin: CGFloat = 28
    static let windowHeight: CGFloat = 780

    let state = PanelState()
    private var panel: KeyPanel?
    private var globalMonitor: Any?
    private var clickMonitor: Any?
    private var keyMonitor: Any?
    private var hideWork: DispatchWorkItem?
    /// When the panel last started closing. A click on the menu bar icon first takes focus from the panel (which
    /// closes it), then reaches the icon's toggle: that same click must not open it again.
    private var hiddenAt = Date.distantPast
    private weak var button: NSStatusBarButton?

    var isShown: Bool { panel?.isVisible == true && state.visible }

    func toggle(below button: NSStatusBarButton?) {
        if isShown {
            hide()
        } else if Date().timeIntervalSince(hiddenAt) > 0.4 {
            show(below: button)
        }
    }

    func show(below button: NSStatusBarButton?) {
        hideWork?.cancel()
        self.button = button
        button?.highlight(true)
        state.macOnline = !state.demoOffline && Net.lanIPv4() != nil
        let p = ensurePanel()
        if let bw = button?.window, let screen = bw.screen ?? NSScreen.main {
            let w = Self.contentWidth + Self.margin * 2
            let vf = screen.visibleFrame
            var x = bw.frame.midX - w / 2
            x = min(max(x, vf.minX + 6 - Self.margin), vf.maxX - w + Self.margin - 6)
            let top = bw.frame.minY - 2
            p.setFrame(NSRect(x: x, y: top - Self.windowHeight, width: w, height: Self.windowHeight), display: false)
        }
        // A non-activating panel takes the keyboard without activating TapDrop, so other TapDrop windows (Setup,
        // Settings) stay where they are instead of jumping to the front with it.
        p.makeKeyAndOrderFront(nil)
        DispatchQueue.main.async {
            withTDAnimation(TD.Motion.island) { self.state.visible = true }
        }
        installMonitors()
    }

    func hide() {
        guard state.visible else { return }
        hiddenAt = Date()
        button?.highlight(false)
        withTDAnimation(.spring(response: 0.3, dampingFraction: 0.92)) { state.visible = false }
        removeMonitors()
        // The card stays built while hidden (rebuilding it on every open could leave the glass empty); nothing in it
        // animates in SwiftUI while it's ordered out (the live dot pulses in Core Animation), so it costs nothing.
        let work = DispatchWorkItem { [weak self] in self?.panel?.orderOut(nil) }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.26, execute: work)
    }

    private func ensurePanel() -> KeyPanel {
        if let panel { return panel }
        let p = KeyPanel(
            contentRect: NSRect(x: 0, y: 0, width: Self.contentWidth + Self.margin * 2, height: Self.windowHeight),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.level = .popUpMenu
        p.isFloatingPanel = true
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient]
        p.delegate = self
        p.setAccessibilityLabel("TapDrop")
        let root = PanelRoot(state: state, close: { [weak self] in self?.hide() })
        let host = NSHostingView(rootView: root)
        host.frame = p.contentLayoutRect
        host.autoresizingMask = [.width, .height]
        p.contentView = host
        panel = p
        return p
    }

    func windowDidResignKey(_ notification: Notification) {
        // Losing key to anything but a file picker the panel opened (another app, or another TapDrop window such as
        // Settings or Setup) closes it, like a menu.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            guard let self, self.isShown, !self.state.demoPinned else { return }
            if let key = NSApp.keyWindow, key === self.panel || key is NSSavePanel { return }
            self.hide()
        }
    }

    private func installMonitors() {
        removeMonitors()
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.state.demoPinned else { return }
                self.hide()
            }
        }
        // A click in any other TapDrop window (Settings, Setup) closes it too; the click still goes through.
        clickMonitor = NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] e in
            guard let self, self.isShown, !self.state.demoPinned, e.window !== self.panel, !(e.window is NSSavePanel) else { return e }
            self.hide()
            return e
        }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self, self.isShown, e.window === self.panel else { return e }
            if e.keyCode == 53 { self.hide(); return nil }  // esc
            if e.modifierFlags.contains(.command) {
                switch e.charactersIgnoringModifiers {
                case "o": PanelActions.chooseFiles(close: { self.hide() }); return nil
                case "v": PanelActions.queueClipboard(); return nil
                case "q": NSApp.terminate(nil); return nil
                case ",": self.hide(); SettingsWindow.shared.show(); return nil
                default: break
                }
            }
            return e
        }
    }

    private func removeMonitors() {
        if let m = globalMonitor { NSEvent.removeMonitor(m) }
        if let m = clickMonitor { NSEvent.removeMonitor(m) }
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        globalMonitor = nil
        clickMonitor = nil
        keyMonitor = nil
    }
}

struct PanelRoot: View {
    @ObservedObject var state: PanelState
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .top) {
            // The panel's window is taller and wider than the card: a click in that empty space is a click outside.
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { close() }
                .accessibilityHidden(true)
            card
        }
    }

    private var card: some View {
        VStack(spacing: 0) {
            PanelView(state: state, close: close)
                .frame(width: PanelController.contentWidth)
                .tdGlass(.panel, in: RoundedRectangle(cornerRadius: TD.Radius.panel, style: .continuous))
                .scaleEffect(state.visible || reduceMotion ? 1 : 0.94, anchor: .top)
                .offset(y: state.visible || reduceMotion ? 0 : -8)
                .opacity(state.visible ? 1 : 0)
            Spacer(minLength: 0)
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}
