import AppKit
import SwiftUI

// Building blocks shared by every TapDrop window. Tokens and glass are in DesignSystem.swift.

// MARK: - Tints

/// Fills for chips and marks. Lumen has no hues: every "colour" is the accent (bone in dark, ink in light) or a
/// neutral grey; only `red` (something needs fixing) and `orange` (a warning) keep their colour.
enum TDTint {
    static let aurora = [TD.accent, TD.accent]
    static let green = [TD.accent, TD.accent]
    static let orange = [Color(red: 1.00, green: 0.72, blue: 0.29), Color(red: 1.00, green: 0.50, blue: 0.22)]
    static let red = [Color(red: 1.00, green: 0.45, blue: 0.47), Color(red: 0.90, green: 0.22, blue: 0.30)]
    static let pink = [TD.accent, TD.accent]
    static let blue = [TD.accent, TD.accent]
    static let gray = [Color(white: 0.62), Color(white: 0.44)]
    static let slate = [Color(white: 0.55), Color(white: 0.4)]
}

// MARK: - GlassCard

/// The standard container.
///
/// - `.glass`: Liquid Glass, for cards that float over arbitrary content.
/// - `.surface`: solid card for opaque windows (Settings panes); soft shadow in light mode.
/// - `.inset`: a quiet platter for grouping content that already sits on glass (the menu bar panel).
///
///     GlassCard(.inset) { … }
///     GlassCard(.surface, padding: 0) { SettingsRow… }
/// How a `GlassCard` (and a `SettingsGroup`) is drawn.
enum TDCardStyle { case glass, surface, inset }

struct GlassCard<Content: View>: View {
    typealias Style = TDCardStyle

    var style: Style
    var padding: CGFloat
    var radius: CGFloat
    @ViewBuilder var content: Content

    init(_ style: Style = .glass, padding: CGFloat = TD.Space.l, radius: CGFloat = TD.Radius.card,
         @ViewBuilder content: () -> Content) {
        self.style = style
        self.padding = padding
        self.radius = radius
        self.content = content()
    }

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let inner = content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
        switch style {
        case .glass:
            inner.tdGlass(.regular, in: shape)
        case .surface:
            inner
                .background(shape.fill(TD.surface))
                .overlay(shape.strokeBorder(TD.hairline, lineWidth: 1))
                .shadow(color: .black.opacity(scheme == .dark ? 0 : 0.06), radius: 10, y: 3)
        case .inset:
            inner
                .background(shape.fill(TD.fill))
                .overlay(shape.strokeBorder(TD.hairline, lineWidth: 1))
        }
    }
}

// MARK: - StatusPill

/// Link / health state, shared by StatusPill, DeviceHero and the island.
enum TDStatus: Equatable {
    /// Connected and ready: green, soft pulse.
    case live
    /// Starting, reconnecting, waiting for the phone: amber, with a turning arc.
    case searching
    /// Paired but not around: gray.
    case offline
    /// Needs attention: red.
    case error

    var color: Color {
        switch self {
        case .live: TD.success
        case .searching: TD.warning
        case .offline: Color(nsColor: .tertiaryLabelColor)
        case .error: TD.danger
        }
    }
}

/// A live dot plus a short label: `StatusPill(.live, "Ready")`.
struct StatusPill: View {
    var status: TDStatus
    var label: String
    /// Dot only (the label is still read by VoiceOver).
    var compact = false

    init(_ status: TDStatus, _ label: String, compact: Bool = false) {
        self.status = status
        self.label = label
        self.compact = compact
    }

    var body: some View {
        HStack(spacing: 6) {
            StatusDot(status: status)
            if !compact {
                Text(label)
                    .tdFont(.caption)
                    .foregroundStyle(TD.text.opacity(status == .offline ? 0.7 : 0.92))
                    .lineLimit(1)
                    .contentTransition(.interpolate)
            }
        }
        .padding(.leading, compact ? 6 : 8)
        .padding(.trailing, compact ? 6 : 9)
        .frame(height: 22)
        .background(Capsule().fill(status.color.opacity(status == .offline ? 0.10 : 0.13)))
        .overlay(Capsule().strokeBorder(status.color.opacity(0.18), lineWidth: 0.5))
        .tdAnimation(TD.Motion.standard, value: status)
        .tdAnimation(TD.Motion.standard, value: label)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
    }
}

/// The indicator inside StatusPill, usable on its own (8 pt).
struct StatusDot: View {
    var status: TDStatus
    var size: CGFloat = 7
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            switch status {
            case .live:
                if !reduceMotion {
                    LivePulse(color: status.color)
                }
                Circle().fill(status.color)
                    .shadow(color: status.color.opacity(0.7), radius: 3)
            case .searching:
                Circle().fill(status.color).frame(width: size * 0.5, height: size * 0.5)
                Circle().stroke(status.color.opacity(0.28), lineWidth: 1.4)
                    .frame(width: size + 4, height: size + 4)
                SearchingArc(color: status.color, animate: !reduceMotion)
                    .frame(width: size + 4, height: size + 4)
            case .offline, .error:
                Circle().fill(status.color)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The live dot's pulse: a ring that grows to 2.6× and fades over 1.5 s, then rests 0.6 s. Core Animation runs it in
/// the render server, so a pulsing dot on screen costs TapDrop nothing per frame. (As a SwiftUI animation it redrew
/// the whole window it sat in, glass and all, about 60 times a second: around 9 % CPU for one dot.)
private struct LivePulse: NSViewRepresentable {
    let color: Color

    func makeNSView(context: Context) -> LivePulseView { LivePulseView() }
    func updateNSView(_ view: LivePulseView, context: Context) { view.color = NSColor(color) }
}

private final class LivePulseView: NSView {
    private let ring = CALayer()
    var color: NSColor = .white {
        didSet { if color != oldValue { applyColor() } }
    }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.masksToBounds = false
        ring.opacity = 0  // only the animation shows it
        layer?.addSublayer(ring)
    }

    required init?(coder: NSCoder) { nil }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        ring.bounds = bounds
        ring.position = CGPoint(x: bounds.midX, y: bounds.midY)
        ring.cornerRadius = min(bounds.width, bounds.height) / 2
        CATransaction.commit()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        applyColor()
        ring.removeAllAnimations()
        guard window != nil else { return }
        let grow = CABasicAnimation(keyPath: "transform.scale")
        grow.fromValue = 1
        grow.toValue = 2.6
        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.8
        fade.toValue = 0
        for a in [grow, fade] {
            a.duration = 1.5
            a.timingFunction = CAMediaTimingFunction(name: .easeOut)
        }
        let pulse = CAAnimationGroup()
        pulse.animations = [grow, fade]
        pulse.duration = 2.1
        pulse.repeatCount = .infinity
        ring.add(pulse, forKey: "pulse")
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        applyColor()
    }

    private func applyColor() {
        // The status colours follow the appearance: resolve for this view's.
        effectiveAppearance.performAsCurrentDrawingAppearance {
            ring.backgroundColor = color.withAlphaComponent(0.55).cgColor
        }
    }
}

private struct SearchingArc: View {
    let color: Color
    let animate: Bool
    @State private var spin = false

    var body: some View {
        Circle()
            .trim(from: 0, to: 0.3)
            .stroke(color, style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            .rotationEffect(.degrees(spin ? 360 : 0))
            .animation(animate ? .linear(duration: 1.1).repeatForever(autoreverses: false) : nil, value: spin)
            .onAppear { spin = true }
    }
}

// MARK: - IconChip

/// A 28–36 pt rounded square holding a symbol.
///
/// - `.gradient`: white symbol on a two-stop fill (settings rows, sidebar).
/// - `.tinted`: tinted symbol on a 15 % wash of the tint (quieter; lists on glass).
/// - `.plain`: secondary symbol on the neutral fill (inactive things).
struct IconChip: View {
    enum Style { case gradient, tinted, plain }

    var symbol: String
    var tint: [Color] = TDTint.aurora
    var size: CGFloat = 28
    var style: Style = .gradient

    init(_ symbol: String, tint: [Color] = TDTint.aurora, size: CGFloat = 28, style: Style = .gradient) {
        self.symbol = symbol
        self.tint = tint
        self.size = size
        self.style = style
    }

    var body: some View {
        // Lumen: a plain glyph in the text colour in a quiet round well, whatever tint a caller passes (no rainbow,
        // no gradients); only a red tint (something needs fixing) keeps its colour.
        let danger = tint.first == TDTint.red.first
        Image(systemName: symbol)
            .font(.geist(size * 0.46, .medium))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(danger ? TD.danger : TD.text)
            .frame(width: size, height: size)
            .background(Circle().fill(danger ? TD.danger.opacity(0.14) : TD.fill))
            .overlay(Circle().strokeBorder(TD.hairline, lineWidth: 0.5))
            .accessibilityHidden(true)
    }

    private var foreground: AnyShapeStyle {
        switch style {
        case .gradient: AnyShapeStyle(.white)
        case .tinted: AnyShapeStyle(LinearGradient(colors: tint, startPoint: .top, endPoint: .bottom))
        case .plain: AnyShapeStyle(TD.textSecondary)
        }
    }
}

// MARK: - SectionHeader

/// A section title with an optional trailing accessory (a quiet button, a count).
///
///     SectionHeader("Recent") { Button("Downloads", systemImage: "arrow.up.right") { … }.buttonStyle(.tdQuiet) }
struct SectionHeader<Trailing: View>: View {
    var title: String
    var subtitle: String?
    @ViewBuilder var trailing: Trailing

    init(_ title: String, subtitle: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.s) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                if let subtitle {
                    Text(subtitle).tdFont(.caption).foregroundStyle(TD.textTertiary)
                }
            }
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: TD.Space.s)
            trailing
        }
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil) {
        self.init(title, subtitle: subtitle) { EmptyView() }
    }
}

// MARK: - Settings rows

/// A titled group of `SettingsRow`s on a card; hairline dividers are inserted between rows.
///
///     SettingsGroup("Received files", footer: "…") {
///         SettingsRow("folder.fill", tint: TDTint.blue, title: "Save to", subtitle: "~/Downloads") { Button… }
///         SettingsRow("link", title: "Open links automatically") { Toggle("", isOn: $on).tdSwitch() }
///     }
struct SettingsGroup<Content: View>: View {
    var title: String?
    var footer: String?
    var style: TDCardStyle
    @ViewBuilder var content: Content

    init(_ title: String? = nil, footer: String? = nil, style: TDCardStyle = .surface,
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.footer = footer
        self.style = style
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s) {
            if let title {
                Text(title)
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                    .padding(.leading, TD.Space.xs)
                    .accessibilityAddTraits(.isHeader)
            }
            GlassCard(style, padding: 0, radius: TD.Radius.row + 2) {
                VStack(spacing: 0) {
                    Group(subviews: content) { rows in
                        ForEach(rows) { row in
                            row
                            if row.id != rows.last?.id {
                                Rectangle().fill(TD.hairline).frame(height: 1)
                                    .padding(.leading, row.containerValues.tdRowInset)
                            }
                        }
                    }
                }
            }
            if let footer {
                Text(footer)
                    .tdFont(.caption)
                    .foregroundStyle(TD.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, TD.Space.xs)
            }
        }
    }
}

private struct TDRowInsetKey: ContainerValueKey {
    static let defaultValue: CGFloat = TD.Space.m
}

extension ContainerValues {
    /// Where the divider under a SettingsGroup row starts (aligned with the row's title).
    var tdRowInset: CGFloat {
        get { self[TDRowInsetKey.self] }
        set { self[TDRowInsetKey.self] = newValue }
    }
}

/// Icon chip, title, optional subtitle, and a trailing control. Pass `action` to make the whole row
/// clickable (a chevron is added when there's no trailing control).
struct SettingsRow<Trailing: View>: View {
    var symbol: String?
    var tint: [Color]
    var title: String
    var subtitle: String?
    var action: (() -> Void)?
    @ViewBuilder var trailing: Trailing
    @State private var hover = false

    init(_ symbol: String? = nil, tint: [Color] = TDTint.aurora, title: String, subtitle: String? = nil,
         action: (() -> Void)? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.symbol = symbol
        self.tint = tint
        self.title = title
        self.subtitle = subtitle
        self.action = action
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: TD.Space.m) {
            if let symbol { IconChip(symbol, tint: tint, size: 26) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .tdFont(.bodyMedium)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                if let subtitle {
                    Text(subtitle)
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: TD.Space.m)
            trailing
            if action != nil && Trailing.self == EmptyView.self {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(TD.textTertiary)
            }
        }
        .padding(.horizontal, TD.Space.m)
        .padding(.vertical, subtitle == nil ? 9 : 10)
        .frame(minHeight: 44)
        .background(action != nil && hover ? TD.fill : .clear)
        .contentShape(Rectangle())
        .onHover { h in if action != nil { hover = h } }
        .onTapGesture { action?() }
        .animation(TD.Motion.hover, value: hover)
        .containerValue(\.tdRowInset, symbol == nil ? TD.Space.m : TD.Space.m + 26 + TD.Space.m)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(action != nil ? .isButton : [])
    }
}

extension SettingsRow where Trailing == EmptyView {
    init(_ symbol: String? = nil, tint: [Color] = TDTint.aurora, title: String, subtitle: String? = nil,
         action: (() -> Void)? = nil) {
        self.init(symbol, tint: tint, title: title, subtitle: subtitle, action: action) { EmptyView() }
    }
}

extension View {
    /// The settings switch: small, violet.
    func tdSwitch() -> some View {
        // The switch's knob is always white, so "on" is ink in light mode and a mid grey in dark (bone would hide it).
        toggleStyle(.switch).labelsHidden().controlSize(.small).tint(Color.td(light: 0x0B0B0D, dark: 0x8D8D95))
    }
}

// MARK: - EmptyState

/// Nothing here yet: a symbol, a title, a line of guidance and optional actions.
///
///     EmptyState("tray", title: "Nothing yet", message: "Things you send and receive show up here.")
struct EmptyState<Actions: View>: View {
    var symbol: String
    var title: String
    var message: String?
    var compact: Bool
    @ViewBuilder var actions: Actions

    init(_ symbol: String, title: String, message: String? = nil, compact: Bool = false,
         @ViewBuilder actions: () -> Actions) {
        self.symbol = symbol
        self.title = title
        self.message = message
        self.compact = compact
        self.actions = actions()
    }

    var body: some View {
        VStack(spacing: compact ? TD.Space.s : TD.Space.m) {
            Image(systemName: symbol)
                .font(.system(size: compact ? 17 : 22, weight: .medium))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(TD.textSecondary)
                .frame(width: compact ? 38 : 52, height: compact ? 38 : 52)
                .background(Circle().fill(TD.fill))
                .overlay(Circle().strokeBorder(TD.hairline, lineWidth: 1))
            VStack(spacing: 3) {
                Text(title)
                    .tdFont(compact ? .bodySemibold : .headline)
                    .foregroundStyle(TD.text)
                if let message {
                    Text(message)
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            actions
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }
}

extension EmptyState where Actions == EmptyView {
    init(_ symbol: String, title: String, message: String? = nil, compact: Bool = false) {
        self.init(symbol, title: title, message: message, compact: compact) { EmptyView() }
    }
}

// MARK: - Shimmer

extension View {
    /// A soft highlight sweeping across the view, for loading placeholders. Static under Reduce Motion.
    func tdShimmer(_ active: Bool = true) -> some View {
        modifier(ShimmerModifier(active: active))
    }
}

/// A rounded placeholder block that shimmers (text lines, thumbnails still loading).
struct Shimmer: View {
    var radius: CGFloat = TD.Radius.control

    var body: some View {
        RoundedRectangle(cornerRadius: radius, style: .continuous)
            .fill(TD.fillStrong)
            .tdShimmer()
            .accessibilityLabel("Loading")
    }
}

private struct ShimmerModifier: ViewModifier {
    let active: Bool
    @State private var phase: CGFloat = -1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        content
            .overlay {
                if active && !reduceMotion {
                    GeometryReader { g in
                        LinearGradient(colors: [.clear, .white.opacity(scheme == .dark ? 0.10 : 0.45), .clear],
                                       startPoint: .leading, endPoint: .trailing)
                            .frame(width: g.size.width * 0.8)
                            .offset(x: phase * g.size.width * 1.4)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    }
                    .mask(content)
                    .allowsHitTesting(false)
                    .onAppear {
                        withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: false)) { phase = 1 }
                    }
                }
            }
    }
}

// MARK: - Small pieces

/// A hairline divider (inset from the leading edge).
struct TDDivider: View {
    var inset: CGFloat = 0

    var body: some View {
        Rectangle().fill(TD.hairline).frame(height: 1).padding(.leading, inset)
    }
}

/// A keyboard shortcut drawn as key caps ("⌘O").
struct KeyHint: View {
    var keys: String

    init(_ keys: String) { self.keys = keys }

    var body: some View {
        Text(keys)
            .font(.system(size: 10.5, weight: .medium, design: .rounded))
            .foregroundStyle(TD.textTertiary)
            .padding(.horizontal, 5)
            .frame(height: 17)
            .background(RoundedRectangle(cornerRadius: 5, style: .continuous).fill(TD.fill))
            .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
            .accessibilityHidden(true)
    }
}

/// "just now", "4 min ago", "2 h ago", "yesterday", "Oct 11".
enum ShortTime {
    static func string(_ d: Date, now: Date = Date()) -> String {
        let s = now.timeIntervalSince(d)
        if s < 60 { return "just now" }
        if s < 3600 { return "\(Int(s / 60)) min ago" }
        if s < 86400 { return "\(Int(s / 3600)) h ago" }
        if s < 172_800 { return "yesterday" }
        return d.formatted(.dateTime.month(.abbreviated).day())
    }
}

// MARK: - Entrance

extension View {
    /// Staggered entrance each time a panel or window opens: fades and rises in order of `index`.
    func tdReveal(_ index: Int, _ visible: Bool) -> some View {
        modifier(RevealModifier(index: index, visible: visible))
    }
}

private struct RevealModifier: ViewModifier {
    let index: Int
    let visible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(visible ? 1 : 0)
            .offset(y: visible || reduceMotion ? 0 : 8)
            .animation(
                reduceMotion ? TD.Motion.reduced
                    : TD.Motion.standard.delay(visible ? 0.04 + Double(index) * 0.04 : 0),
                value: visible)
    }
}
