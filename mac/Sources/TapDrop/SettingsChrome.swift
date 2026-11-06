import AppKit
import SwiftUI

// Chrome shared by the Settings and Setup windows: the window itself, the backdrop, the toolbar-row title,
// pane headers and small status pieces. Built only from the design system (DesignSystem.swift, DS*.swift).

@MainActor
enum SettingsChrome {
    /// The unified toolbar row the traffic lights are centred in.
    static let toolbarHeight: CGFloat = 52
    static let sidebarWidth: CGFloat = 214
    /// The sidebar floats this far from the window's edges (macOS 26 style).
    static let sidebarInset: CGFloat = 8
    /// Concentric with the window's corners.
    static let sidebarRadius: CGFloat = 18

    /// The selected sidebar row: a violet wash, quiet enough to sit inside glass.
    static let selectionFill = Color.td(light: NSColor(white: 0, alpha: 0.07), dark: NSColor(white: 1, alpha: 0.10))
    static let selectionStroke = Color.td(light: NSColor(white: 0, alpha: 0.08), dark: NSColor(white: 1, alpha: 0.12))

    /// The window background (TD.bg as an NSColor that follows the appearance).
    static let windowColor = NSColor(name: nil) { a in
        a.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(tdHex: 0x08080A) : NSColor(tdHex: 0xEFEDE7)
    }

    /// A titled window with a transparent unified toolbar (the traffic lights sit in the toolbar row, inside a
    /// floating sidebar when there is one) that follows the system appearance.
    static func makeWindow<V: View>(size: NSSize, minSize: NSSize? = nil, title: String, root: V) -> NSWindow {
        var style: NSWindow.StyleMask = [.titled, .closable, .miniaturizable, .fullSizeContentView]
        if minSize != nil { style.insert(.resizable) }
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size), styleMask: style, backing: .buffered, defer: false)
        w.title = title
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.titlebarSeparatorStyle = .none
        let toolbar = NSToolbar(identifier: "app.tapdrop.\(title.filter(\.isLetter))")
        toolbar.allowsUserCustomization = false
        toolbar.displayMode = .iconOnly
        w.toolbar = toolbar
        w.toolbarStyle = .unified
        w.isMovableByWindowBackground = true
        w.isReleasedWhenClosed = false
        w.backgroundColor = windowColor
        w.tabbingMode = .disallowed
        let host = NSHostingView(rootView: root)
        // The window sets its own limits; SwiftUI's would count the toolbar's safe area again.
        host.sizingOptions = []
        w.contentView = host
        // The content runs under the title bar, so `size` is the whole window.
        w.setFrame(NSRect(origin: .zero, size: size), display: false)
        if let minSize { w.minSize = minSize }
        w.center()
        return w
    }

    /// TD.bg with a faint brand glow in the top-left corner, so the glass sidebar has some light to bend.
    struct Backdrop: View {
        @Environment(\.colorScheme) private var scheme

        var body: some View {
            ZStack(alignment: .topLeading) {
                TD.bg
                Ellipse()
                    .fill(LinearGradient(colors: [TD.blue, TD.violet], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .frame(width: 300, height: 260)
                    .blur(radius: 90)
                    .opacity(scheme == .dark ? 0.22 : 0.16)
                    .offset(x: -70, y: -60)
                Ellipse()
                    .fill(TD.orchid)
                    .frame(width: 200, height: 160)
                    .blur(radius: 90)
                    .opacity(scheme == .dark ? 0.08 : 0.06)
                    .offset(x: 40, y: 380)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }

    /// The toolbar row over the pane: content scrolling under it fades out, and the pane's title appears
    /// in it once the pane's own header has scrolled away (`shown`).
    struct TitleBar: View {
        let title: String
        var shown: Bool

        var body: some View {
            HStack {
                Text(title)
                    .tdFont(.headline)
                    .foregroundStyle(TD.text)
                    .opacity(shown ? 1 : 0)
                    .offset(y: shown ? 0 : 4)
                    .accessibilityHidden(!shown)
                Spacer()
            }
            .padding(.horizontal, TD.Space.xxl + 4)
            .frame(height: SettingsChrome.toolbarHeight)
            .frame(maxWidth: .infinity)
            .background {
                LinearGradient(stops: [.init(color: TD.bg, location: 0), .init(color: TD.bg, location: 0.6),
                                       .init(color: TD.bg.opacity(0), location: 1)],
                               startPoint: .top, endPoint: .bottom)
                    .padding(.bottom, -16)
                    .allowsHitTesting(false)
            }
        }
    }
}

// MARK: - Pane header

/// The top of a pane: the pane's icon chip, its title and one line about it.
struct SettingsPaneHeader<Trailing: View>: View {
    let pane: SettingsPane
    var blurb: String?
    @ViewBuilder var trailing: Trailing

    init(_ pane: SettingsPane, blurb: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.pane = pane
        self.blurb = blurb
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.m + 2) {
            IconChip(pane.symbol, tint: pane.tint, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(pane.title)
                    .tdFont(.title)
                    .foregroundStyle(TD.text)
                    .accessibilityAddTraits(.isHeader)
                Text(blurb ?? pane.blurb)
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: TD.Space.m)
            trailing
        }
    }
}

extension SettingsPaneHeader where Trailing == EmptyView {
    init(_ pane: SettingsPane, blurb: String? = nil) {
        self.init(pane, blurb: blurb) { EmptyView() }
    }
}

// MARK: - Small pieces

/// A check, warning or cross for a requirement row.
struct CheckBadge: View {
    enum State { case ok, warning, missing, neutral }
    let state: State
    var label: String?

    var body: some View {
        HStack(spacing: 5) {
            if let label {
                Text(label)
                    .tdFont(.caption)
                    .foregroundStyle(TD.textSecondary)
            }
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(state == .ok ? TD.onAccent : .white, color)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label ?? spoken)
    }

    private var symbol: String {
        switch state {
        case .ok: "checkmark.circle.fill"
        case .warning: "exclamationmark.circle.fill"
        case .missing: "xmark.circle.fill"
        case .neutral: "minus.circle.fill"
        }
    }

    private var color: Color {
        switch state {
        case .ok: TD.success
        case .warning: TD.warning
        case .missing: TD.danger
        case .neutral: Color(nsColor: .tertiaryLabelColor)
        }
    }

    private var spoken: String {
        switch state {
        case .ok: "Yes"
        case .warning: "Needs attention"
        case .missing: "No"
        case .neutral: "Not applicable"
        }
    }
}

/// A capsule with a short fact ("Paired Oct 11", "Ready for taps").
struct FactChip: View {
    let text: String
    var symbol: String?
    var tint: Color?

    var body: some View {
        HStack(spacing: 4) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 9.5, weight: .bold))
                    .foregroundStyle(tint ?? TD.textSecondary)
            }
            Text(text)
                .tdFont(.caption)
                .foregroundStyle(tint ?? TD.textSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 8)
        .frame(height: 22)
        .background(Capsule().fill((tint ?? TD.textSecondary).opacity(tint == nil ? 0.08 : 0.12)))
        .overlay(Capsule().strokeBorder((tint ?? TD.textSecondary).opacity(0.12), lineWidth: 0.5))
    }
}

// MARK: - Link state

/// How the phone link stands, in a few words (the sidebar).
struct PhoneLink: Equatable {
    var status: TDStatus
    /// The connected phone ("Android phone", "Android phone +1"); it gives way first when space is short.
    var phone: String?
    /// "Connected", "Not connected", "No phone yet", "Starting…".
    var state: String

    var spoken: String { [phone, state].compactMap { $0 }.joined(separator: ", ") }

    @MainActor static func current(_ store: Store) -> PhoneLink {
        let live = store.devices.filter { store.liveDevices.contains($0.id) }
        if let p = live.first {
            return PhoneLink(status: .live, phone: live.count > 1 ? "\(p.name) +\(live.count - 1)" : p.name, state: "Connected")
        }
        if store.devices.isEmpty { return PhoneLink(status: .offline, phone: nil, state: "No phone yet") }
        if store.status != "Ready" { return PhoneLink(status: .searching, phone: nil, state: "Starting…") }
        return PhoneLink(status: .offline, phone: nil, state: "Not connected")
    }
}

/// Whether a tap can send right now.
enum TapReadiness: Equatable {
    /// No light sensor TapDrop can use.
    case unavailable
    /// Tap to Send is switched off.
    case off
    /// No phone paired.
    case noPhone
    /// Paired, but no phone holds a live link.
    case waiting
    case ready

    @MainActor static func current(_ store: Store) -> TapReadiness {
        if !NotchSensor.shared.available || SettingsDemo.noSensor { return .unavailable }
        if !store.settings.notchTap { return .off }
        if store.devices.isEmpty { return .noPhone }
        if store.liveDevices.isEmpty { return .waiting }
        return .ready
    }
}

/// Demo / QA switches for these windows (set by DemoWindows.swift only).
@MainActor
enum SettingsDemo {
    /// Pretend this Mac has no usable light sensor.
    static var noSensor = false
    /// Open the newest recent tap's details.
    static var expandFirstTap = false
    /// Ask to forget the first phone as the Phones pane opens.
    static var forgetFirst = false
    /// Open panes scrolled to the bottom.
    static var scrollToEnd = false
    /// Phones to show as running a TapDrop too old to tap.
    static var outdatedPhones: Set<String> = []
    /// Pretend the save folder is gone and Open at Login waits for approval.
    static var generalProblems = false
}
