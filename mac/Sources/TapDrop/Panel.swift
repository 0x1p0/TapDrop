import AppKit
import SwiftUI

// A borderless glass panel that springs down from the menu bar icon
// (replaces NSPopover, which looks like a stock settings sheet).

final class KeyPanel: NSPanel {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
}

@MainActor
final class PanelState: ObservableObject {
    @Published var visible = false
    @Published var dropTargeted = false
}

@MainActor
final class PanelController: NSObject, NSWindowDelegate {
    static let contentWidth: CGFloat = 360
    static let margin: CGFloat = 26
    static let windowHeight: CGFloat = 720

    let state = PanelState()
    private var panel: KeyPanel?
    private var globalMonitor: Any?
    private var keyMonitor: Any?
    private var hideWork: DispatchWorkItem?

    var isShown: Bool { panel?.isVisible == true && state.visible }

    func toggle(below button: NSStatusBarButton?) {
        if isShown { hide() } else { show(below: button) }
    }

    func show(below button: NSStatusBarButton?) {
        hideWork?.cancel()
        let p = ensurePanel()
        if let bw = button?.window, let screen = bw.screen ?? NSScreen.main {
            let w = Self.contentWidth + Self.margin * 2
            let vf = screen.visibleFrame
            var x = bw.frame.midX - w / 2
            x = min(max(x, vf.minX + 6 - Self.margin), vf.maxX - w + Self.margin - 6)
            let top = bw.frame.minY - 2
            p.setFrame(NSRect(x: x, y: top - Self.windowHeight, width: w, height: Self.windowHeight), display: false)
        }
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
        DispatchQueue.main.async {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) { self.state.visible = true }
        }
        installMonitors()
    }

    func hide() {
        guard state.visible else { return }
        withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { state.visible = false }
        removeMonitors()
        let work = DispatchWorkItem { [weak self] in self?.panel?.orderOut(nil) }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.28, execute: work)
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
        p.appearance = NSAppearance(named: .darkAqua)
        p.delegate = self
        let root = PanelRoot(state: state, close: { [weak self] in self?.hide() })
        let host = NSHostingView(rootView: root)
        host.frame = p.contentLayoutRect
        host.autoresizingMask = [.width, .height]
        p.contentView = host
        panel = p
        return p
    }

    func windowDidResignKey(_ notification: Notification) {
        // Losing key to our own sheets/menus is fine; losing it to another app closes us.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            guard let self, self.isShown else { return }
            if NSApp.keyWindow == nil || !NSApp.isActive { self.hide() }
        }
    }

    private func installMonitors() {
        removeMonitors()
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            Task { @MainActor in self?.hide() }
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
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        globalMonitor = nil
        keyMonitor = nil
    }
}

struct PanelRoot: View {
    @ObservedObject var state: PanelState
    let close: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            PanelView(state: state, close: close)
                .frame(width: PanelController.contentWidth)
                .background(GlassBackground(cornerRadius: 24))
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .strokeBorder(
                            LinearGradient(colors: [.white.opacity(0.22), .white.opacity(0.06)],
                                           startPoint: .top, endPoint: .bottom),
                            lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .shadow(color: .black.opacity(0.45), radius: 24, y: 14)
                .scaleEffect(state.visible ? 1 : 0.92, anchor: .top)
                .offset(y: state.visible ? 0 : -10)
                .opacity(state.visible ? 1 : 0)
                .blur(radius: state.visible ? 0 : 4)
            Spacer(minLength: 0)
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .environment(\.colorScheme, .dark)
    }
}

/// Behind-window blur with real rounded corners (SwiftUI clipping alone leaves square edges on the blur).
struct GlassBackground: NSViewRepresentable {
    var cornerRadius: CGFloat
    var material: NSVisualEffectView.Material = .hudWindow

    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = material
        v.blendingMode = .behindWindow
        v.state = .active
        v.maskImage = Self.mask(cornerRadius)
        return v
    }

    func updateNSView(_ v: NSVisualEffectView, context: Context) {
        v.material = material
    }

    static func mask(_ r: CGFloat) -> NSImage {
        let size = NSSize(width: r * 2 + 1, height: r * 2 + 1)
        let img = NSImage(size: size, flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: r, yRadius: r).fill()
            return true
        }
        img.capInsets = NSEdgeInsets(top: r, left: r, bottom: r, right: r)
        img.resizingMode = .stretch
        return img
    }
}

/// Capsule button used across the panel.
struct GlassButtonStyle: ButtonStyle {
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        GlassButtonBody(configuration: configuration, prominent: prominent)
    }

    private struct GlassButtonBody: View {
        let configuration: ButtonStyleConfiguration
        let prominent: Bool
        @State private var hover = false
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            configuration.label
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(enabled ? 1 : 0.4))
                .padding(.horizontal, 14)
                .frame(height: 34)
                .frame(maxWidth: .infinity)
                .background {
                    if prominent {
                        Capsule().fill(Brand.gradient)
                            .overlay(Capsule().fill(.white.opacity(hover ? 0.12 : 0)))
                            .shadow(color: Brand.b.opacity(hover ? 0.55 : 0.3), radius: hover ? 12 : 6, y: 3)
                    } else {
                        Capsule().fill(.white.opacity(hover ? 0.14 : 0.08))
                            .overlay(Capsule().strokeBorder(.white.opacity(0.1), lineWidth: 1))
                    }
                }
                .scaleEffect(configuration.isPressed ? 0.96 : 1)
                .animation(.spring(response: 0.25, dampingFraction: 0.65), value: configuration.isPressed)
                .animation(.easeOut(duration: 0.15), value: hover)
                .onHover { hover = $0 }
                .contentShape(Capsule())
        }
    }
}

/// Small circular icon button (header "more" menu, close buttons).
struct CircleIconLabel: View {
    let systemName: String
    @State private var hover = false

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(.white.opacity(hover ? 0.95 : 0.6))
            .frame(width: 26, height: 26)
            .background(Circle().fill(.white.opacity(hover ? 0.14 : 0.07)))
            .onHover { h in withAnimation(.easeOut(duration: 0.12)) { hover = h } }
    }
}
