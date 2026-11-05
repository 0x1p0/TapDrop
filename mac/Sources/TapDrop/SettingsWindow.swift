import AppKit
import SwiftUI

// The Settings window: a floating Liquid Glass sidebar and grouped panes, following the system appearance.
// Panes live in SettingsPanes.swift (General, Compatibility, About), SettingsTapPane.swift and SettingsPhonesPane.swift;
// the window chrome and shared pieces are in SettingsChrome.swift.

// MARK: - Panes

enum SettingsPane: String, CaseIterable, Identifiable {
    case general, tap, phones, compatibility, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: "General"
        case .tap: "Tap to Send"
        case .phones: "Phones"
        case .compatibility: "Compatibility"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .general: "gearshape.fill"
        case .tap: "hand.tap.fill"
        case .phones: "smartphone"
        case .compatibility: "checkmark.seal.fill"
        case .about: "info.circle.fill"
        }
    }

    var tint: [Color] {
        switch self {
        case .general: TDTint.gray
        case .tap: TDTint.aurora
        case .phones: TDTint.green
        case .compatibility: TDTint.orange
        case .about: TDTint.slate
        }
    }

    /// One line under the pane's title.
    var blurb: String {
        switch self {
        case .general: "Where things land, and how TapDrop starts."
        case .tap: "Send what's on your phone with a touch."
        case .phones: "Phones paired with this Mac."
        case .compatibility: "Which phones and Macs work with TapDrop, and what they need."
        case .about: "TapDrop for Mac."
        }
    }

    /// ⌘1 … ⌘5.
    var number: Int { (Self.allCases.firstIndex(of: self) ?? 0) + 1 }
}

@MainActor
final class SettingsModel: ObservableObject {
    @Published var pane: SettingsPane = .general
    /// Flips on when the window opens, for the staggered entrance.
    @Published var visible = false
    /// Some of the window can be seen (not minimized, covered or on another Space): live things run only then.
    @Published var onScreen = true

    func select(_ p: SettingsPane) {
        guard p != pane else { return }
        withTDAnimation(TD.Motion.standard) { pane = p }
    }

    func step(_ delta: Int) {
        let all = SettingsPane.allCases
        guard let i = all.firstIndex(of: pane) else { return }
        select(all[max(0, min(all.count - 1, i + delta))])
    }
}

// MARK: - Window

@MainActor
final class SettingsWindow: NSObject, NSWindowDelegate {
    static let shared = SettingsWindow()
    static let size = NSSize(width: 840, height: 660)
    static let minSize = NSSize(width: 780, height: 540)
    private var window: NSWindow?
    private var keyMonitor: Any?
    let model = SettingsModel()

    func show(_ pane: SettingsPane? = nil) {
        if let pane { model.pane = pane }
        if window == nil {
            let w = SettingsChrome.makeWindow(
                size: Self.size, minSize: Self.minSize, title: "TapDrop Settings",
                root: SettingsView(model: model))
            w.delegate = self
            window = w
            installKeys()
            model.visible = false
            DispatchQueue.main.async { self.model.visible = true }
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    /// The hosting view clears the window's `minSize` once it lays out, so the limit is kept here.
    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        NSSize(width: max(frameSize.width, Self.minSize.width), height: max(frameSize.height, Self.minSize.height))
    }

    func windowDidChangeOcclusionState(_ notification: Notification) {
        guard let w = window else { return }
        let on = w.occlusionState.contains(.visible)
        if model.onScreen != on { model.onScreen = on }
    }

    func windowWillClose(_ notification: Notification) {
        TapTestModel.shared.stop()
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        keyMonitor = nil
        window = nil
        model.visible = false
    }

    /// A menu bar app has no main menu, so the window's shortcuts are handled here:
    /// ⌘W / Esc close, ⌘Q quits, ⌘1…⌘5 pick a pane, ↑ / ↓ move through the sidebar.
    private func installKeys() {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
            guard let self, let w = self.window, e.window === w else { return e }
            let cmd = e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command
            let editing = w.firstResponder is NSText
            if e.keyCode == 53 && !editing {  // esc
                w.close()
                return nil
            }
            if cmd, let c = e.charactersIgnoringModifiers {
                switch c {
                case "w": w.close(); return nil
                case "q": NSApp.terminate(nil); return nil
                case "1", "2", "3", "4", "5":
                    if let n = Int(c), let p = SettingsPane.allCases.first(where: { $0.number == n }) { self.model.select(p) }
                    return nil
                default: break
                }
            }
            if !editing && e.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty {
                if e.keyCode == 125 { self.model.step(1); return nil }  // ↓
                if e.keyCode == 126 { self.model.step(-1); return nil }  // ↑
            }
            return e
        }
    }
}

// MARK: - Layout

struct SettingsView: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        HStack(spacing: 0) {
            SettingsSidebar(model: model)
                .frame(width: SettingsChrome.sidebarWidth)
                .padding(SettingsChrome.sidebarInset)
            SettingsDetail(model: model)
        }
        .frame(minWidth: SettingsWindow.minSize.width, maxWidth: .infinity,
               minHeight: SettingsWindow.minSize.height, maxHeight: .infinity)
        .background(SettingsChrome.Backdrop())
        .ignoresSafeArea()
    }
}

/// The scrolling pane. Its title moves into the toolbar row once the pane's own header scrolls away.
private struct SettingsDetail: View {
    @ObservedObject var model: SettingsModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scrolled = false

    var body: some View {
        ZStack(alignment: .top) {
            ScrollView {
                pane
                    .frame(maxWidth: 580, alignment: .leading)
                    .padding(.horizontal, TD.Space.xxl + 4)
                    .padding(.top, SettingsChrome.toolbarHeight - TD.Space.xs)
                    .padding(.bottom, TD.Space.xxxl)
                    .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.automatic)
            .onScrollGeometryChange(for: Bool.self) { g in
                g.contentOffset.y + g.contentInsets.top > 46
            } action: { _, past in
                withTDAnimation(TD.Motion.hover) { scrolled = past }
            }
            .defaultScrollAnchor(SettingsDemo.scrollToEnd ? .bottom : .top)
            .id(model.pane)
            .transition(.asymmetric(
                insertion: .opacity.combined(with: .offset(y: reduceMotion ? 0 : 10)),
                removal: .opacity))

            SettingsChrome.TitleBar(title: model.pane.title, shown: scrolled)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onChange(of: model.pane) { _, _ in scrolled = false }
    }

    @ViewBuilder private var pane: some View {
        switch model.pane {
        case .general: GeneralPane(visible: model.visible)
        case .tap: TapPane(visible: model.visible, onScreen: model.onScreen)
        case .phones: PhonesPane(visible: model.visible)
        case .compatibility: CompatibilityPane(visible: model.visible)
        case .about: AboutPane(visible: model.visible)
        }
    }
}

// MARK: - Sidebar

private struct SettingsSidebar: View {
    @ObservedObject var model: SettingsModel
    @ObservedObject private var store = Store.shared
    @Namespace private var selection

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // the traffic lights sit here, inside the glass
            Color.clear.frame(height: SettingsChrome.toolbarHeight - SettingsChrome.sidebarInset)

            Identity()
                .padding(.horizontal, TD.Space.s)
                .padding(.top, TD.Space.xs)
                .padding(.bottom, TD.Space.l)

            VStack(spacing: 2) {
                ForEach(SettingsPane.allCases) { p in
                    SidebarRow(pane: p, selected: model.pane == p, ns: selection) { model.select(p) }
                }
            }

            Spacer(minLength: TD.Space.l)

            QuitRow()
        }
        .padding(.horizontal, TD.Space.s)
        .padding(.bottom, TD.Space.s)
        .frame(maxHeight: .infinity, alignment: .top)
        .tdGlass(.regular, in: RoundedRectangle(cornerRadius: SettingsChrome.sidebarRadius, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Settings sections")
    }
}

/// The app and, at a glance, how its phone link stands.
private struct Identity: View {
    @ObservedObject private var store = Store.shared

    var body: some View {
        HStack(spacing: TD.Space.s + 2) {
            AppGlyph(size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text("TapDrop")
                    .tdFont(.headline)
                    .foregroundStyle(TD.text)
                let link = PhoneLink.current(store)
                HStack(spacing: 5) {
                    SidebarDot(status: link.status)
                    HStack(spacing: 0) {
                        if let phone = link.phone {
                            Text(phone)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .layoutPriority(0)
                            Text(" · ")
                                .fixedSize()
                        }
                        Text(link.state)
                            .lineLimit(1)
                            .fixedSize()
                            .layoutPriority(1)
                    }
                    .tdFont(.caption)
                    .foregroundStyle(TD.textSecondary)
                    .contentTransition(.interpolate)
                }
                .tdAnimation(TD.Motion.standard, value: link)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("TapDrop, \(PhoneLink.current(store).spoken)")
    }
}

private struct SidebarRow: View {
    let pane: SettingsPane
    let selected: Bool
    let ns: Namespace.ID
    let action: () -> Void
    @State private var hover = false
    @ObservedObject private var store = Store.shared
    @ObservedObject private var tap = TapStatus.shared

    var body: some View {
        Button(action: action) {
            HStack(spacing: TD.Space.s + 2) {
                IconChip(pane.symbol, tint: pane.tint, size: 22)
                Text(pane.title)
                    .tdFont(.bodyMedium)
                    .foregroundStyle(selected ? TD.text : TD.text.opacity(0.86))
                    .lineLimit(1)
                Spacer(minLength: TD.Space.xs)
                accessory
            }
            .padding(.horizontal, TD.Space.s - 1)
            .frame(height: 34)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(SettingsChrome.selectionFill)
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .strokeBorder(SettingsChrome.selectionStroke, lineWidth: 1))
                        .matchedGeometryEffect(id: "selection", in: ns)
                } else if hover {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(TD.fill)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
        .help("\(pane.title)  ⌘\(pane.number)")
        .accessibilityLabel(pane.title)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }

    /// A quiet hint of each pane's state (tap readiness, paired phone count).
    @ViewBuilder private var accessory: some View {
        switch pane {
        case .tap:
            let r = TapReadiness.current(store)
            if r == .off {
                Text("Off").tdFont(.caption).foregroundStyle(TD.textTertiary)
            } else if r == .ready {
                SidebarDot(status: .live).accessibilityHidden(true)
            }
        case .phones:
            if !store.devices.isEmpty {
                Text("\(store.devices.count)")
                    .tdFont(.number(11, .semibold))
                    .foregroundStyle(TD.textSecondary)
                    .padding(.horizontal, 6)
                    .frame(minWidth: 20, minHeight: 18)
                    .background(Capsule().fill(TD.fill))
                    .contentTransition(.numericText(value: Double(store.devices.count)))
                    .accessibilityLabel("\(store.devices.count) paired")
            }
        default:
            EmptyView()
        }
    }
}

/// A still status dot for the sidebar. The panes' own pills carry the live pulse; chrome that's always on
/// screen stays still, so an open Settings window costs next to nothing while idle.
private struct SidebarDot: View {
    let status: TDStatus

    var body: some View {
        Circle()
            .fill(status.color)
            .frame(width: 6, height: 6)
            .shadow(color: status == .live ? status.color.opacity(0.6) : .clear, radius: 2.5)
            .accessibilityHidden(true)
    }
}

private struct QuitRow: View {
    @State private var hover = false

    var body: some View {
        Button {
            NSApp.terminate(nil)
        } label: {
            HStack(spacing: TD.Space.s + 2) {
                Image(systemName: "power")
                    .font(.system(size: 11, weight: .bold))
                    .frame(width: 22, height: 22)
                Text("Quit TapDrop")
                    .tdFont(.bodyMedium)
                Spacer(minLength: TD.Space.xs)
                if hover { KeyHint("⌘Q").transition(.opacity) }
            }
            .foregroundStyle(hover ? TD.text : TD.textSecondary)
            .padding(.horizontal, TD.Space.s - 1)
            .frame(height: 34)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(hover ? TD.fill : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
        .help("Quit TapDrop  ⌘Q")
    }
}
