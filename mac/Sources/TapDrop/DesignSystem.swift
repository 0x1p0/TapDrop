import AppKit
import SwiftUI

// TapDrop design system for the Mac app (the spec lives in docs/DESIGN.md).
//
// Everything a screen needs comes from here, so no screen has to pick a color, a radius, a spring or
// branch on the OS version itself:
//
//   TD.*                tokens: brand + neutral + semantic colors, gradients, radii, spacing, springs
//   .tdFont(_:)         type scale (SF Pro, negative tracking on titles, rounded digits for numbers)
//   .tdGlass(_:in:)     Liquid Glass on macOS 26+, material + highlight + shadow on macOS 15
//   TDGlassGroup        GlassEffectContainer on 26+, a plain stack before
//   .buttonStyle(.tdPrimary / .tdSecondary / .tdDestructive / .tdQuiet / .tdIcon)
//   AppGlyph            the app icon, drawn
//
// Components (GlassCard, StatusPill, IconChip, SectionHeader, SettingsGroup/Row, EmptyState, Shimmer…)
// are in DSComponents.swift; illustrations and motion pieces (AuroraBackdrop, ProgressRing, CheckBurst,
// TransferGlyph, DeviceHero) in DSVisuals.swift; thumbnails and file dragging in DSThumbnail.swift.

// MARK: - Tokens

enum TD {
    // MARK: Brand ("Aurora"); exact values, they match the app icon.

    static let blue = Color(tdHex: 0x409EFF)
    static let violet = Color(tdHex: 0x8561FF)
    static let orchid = Color(tdHex: 0xF26BD9)
    /// Tint for violet glass: glass lightens its tint, so this lands on the brand violet.
    static let violetDeep = Color(tdHex: 0x6B44F2)

    /// blue → violet. The accent gradient (primary fills, hero glyphs, progress). At most one per view.
    static let aurora = LinearGradient(colors: [blue, violet], startPoint: .topLeading, endPoint: .bottomTrailing)
    /// blue → violet → orchid. Hero glow only.
    static let auroraHero = LinearGradient(colors: [blue, violet, orchid], startPoint: .topLeading, endPoint: .bottomTrailing)
    /// The aurora around a circle, for rings (starts at 12 o'clock when the ring is rotated −90°).
    static let auroraRing = AngularGradient(colors: [blue, violet, orchid, violet, blue], center: .center)

    // MARK: Neutrals (resolve per appearance)

    static let bg = Color.td(light: 0xF4F4F8, dark: 0x0A0A0F)
    /// Cards on solid backgrounds.
    static let surface = Color.td(light: 0xFFFFFF, dark: 0x15151C)
    /// Popovers, menus, things that float over a surface (add `tdShadow` in light mode).
    static let surfaceRaised = Color.td(light: 0xFFFFFF, dark: 0x1C1C25)
    static let hairline = Color.td(light: NSColor(white: 0, alpha: 0.07), dark: NSColor(white: 1, alpha: 0.08))
    static let text = Color.td(light: 0x111118, dark: 0xF5F5FA)
    static let textSecondary = Color.td(light: 0x5E6173, dark: 0xA0A2B4)
    static let textTertiary = Color.td(light: 0x8E90A2, dark: 0x6B6D80)

    /// Quiet fill for platters, rows and tiles that sit on glass.
    static let fill = Color.td(light: NSColor(white: 0, alpha: 0.045), dark: NSColor(white: 1, alpha: 0.06))
    /// `fill` under the pointer.
    static let fillHover = Color.td(light: NSColor(white: 0, alpha: 0.075), dark: NSColor(white: 1, alpha: 0.10))
    /// Pressed, selected, or a fill that must read on a busy backdrop.
    static let fillStrong = Color.td(light: NSColor(white: 0, alpha: 0.11), dark: NSColor(white: 1, alpha: 0.15))
    /// The wash `TDGlass.panel` lays under content so large glass surfaces stay legible on any wallpaper.
    static let panelWash = Color.td(light: NSColor(tdHex: 0xF4F4F8, alpha: 0.58), dark: NSColor(tdHex: 0x0A0A0F, alpha: 0.46))

    // MARK: Semantic (system colors, so they adapt to the appearance)

    static let success = Color.green
    static let warning = Color.orange
    static let danger = Color.red
    /// The hero glow: violet, 35–45 % alpha, always heavily blurred, behind hero elements only.
    static let glow = violet.opacity(0.4)

    // MARK: Shape

    /// Continuous-corner radii.
    enum Radius {
        static let control: CGFloat = 8
        static let row: CGFloat = 12
        static let card: CGFloat = 18
        static let panel: CGFloat = 26
    }

    /// The 4-pt grid. Standard paddings are s/m/l/xl/xxl/xxxl.
    enum Space {
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 24
        static let xxxl: CGFloat = 32
    }

    // MARK: Motion

    enum Motion {
        /// Most state changes.
        static let standard = Animation.spring(response: 0.38, dampingFraction: 0.86)
        /// Arrivals and celebrations: a little overshoot.
        static let emphasized = Animation.spring(response: 0.5, dampingFraction: 0.72)
        /// The notch island growing and shrinking.
        static let island = Animation.spring(response: 0.42, dampingFraction: 0.78)
        /// Hover and pressed feedback.
        static let hover = Animation.easeOut(duration: 0.14)
        /// What every spring becomes when Reduce Motion is on.
        static let reduced = Animation.easeInOut(duration: 0.2)

        /// The system "Reduce motion" setting (for code outside a view; views read the environment).
        static var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

        /// `animation`, or a quick fade when Reduce Motion is on.
        static func resolved(_ animation: Animation, reduceMotion: Bool = Motion.reduceMotion) -> Animation {
            reduceMotion ? reduced : animation
        }
    }

    /// A light haptic tick on Force Touch trackpads (accept / confirm moments).
    static func haptic(_ pattern: NSHapticFeedbackManager.FeedbackPattern = .alignment) {
        NSHapticFeedbackManager.defaultPerformer.perform(pattern, performanceTime: .now)
    }
}

/// `withAnimation`, honouring Reduce Motion (springs become a short fade).
@discardableResult
func withTDAnimation<Result>(_ animation: Animation = TD.Motion.standard, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(TD.Motion.resolved(animation), body)
}

extension View {
    /// `.animation(_:value:)` that turns springs into a short fade when Reduce Motion is on.
    func tdAnimation<V: Equatable>(_ animation: Animation = TD.Motion.standard, value: V) -> some View {
        modifier(TDAnimationModifier(animation: animation, value: value))
    }
}

private struct TDAnimationModifier<V: Equatable>: ViewModifier {
    let animation: Animation
    let value: V
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? TD.Motion.reduced : animation, value: value)
    }
}

// MARK: - Color helpers

extension NSColor {
    convenience init(tdHex hex: UInt32, alpha: CGFloat = 1) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

extension Color {
    init(tdHex hex: UInt32, opacity: Double = 1) {
        self.init(nsColor: NSColor(tdHex: hex, alpha: opacity))
    }

    /// A color that resolves per appearance (follows the window's light/dark appearance live).
    static func td(light: NSColor, dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
        })
    }

    static func td(light: UInt32, dark: UInt32) -> Color {
        td(light: NSColor(tdHex: light), dark: NSColor(tdHex: dark))
    }
}

/// `.foregroundStyle(.tdSecondary)` etc.
extension ShapeStyle where Self == Color {
    static var tdText: Color { TD.text }
    static var tdSecondary: Color { TD.textSecondary }
    static var tdTertiary: Color { TD.textTertiary }
    static var tdHairline: Color { TD.hairline }
    static var tdFill: Color { TD.fill }
    static var tdViolet: Color { TD.violet }
}

/// Legacy names (Island, Setup, Settings and the pair prompt still use them). Prefer `TD`.
enum Brand {
    static let a = TD.blue
    static let b = TD.violet
    static let c = TD.orchid
    static var gradient: LinearGradient { TD.aurora }
}

// MARK: - Type

/// The type scale. SF Pro throughout; titles are semibold with slightly negative tracking.
enum TDFont {
    /// 28 semibold, −0.5. Window heroes ("Set up your phone").
    case display
    /// 20 semibold, −0.3. Pane titles.
    case title
    /// 17 semibold, −0.2.
    case title2
    /// 15 semibold, −0.15. Card titles, the panel hero line.
    case headline
    /// 13 regular. The macOS body size.
    case body
    /// 13 medium. Row titles.
    case bodyMedium
    /// 13 semibold. Buttons, emphasized labels.
    case bodySemibold
    /// 12 regular. Secondary lines.
    case callout
    /// 11 medium. Captions, footnotes, row subtitles.
    case caption
    /// 10 semibold, +0.2. Badges and tiny labels.
    case micro
    /// Rounded, semibold, monospaced digits: numbers, codes, sizes, percentages.
    case number(CGFloat, Font.Weight = .semibold)

    var font: Font {
        switch self {
        case .display: .system(size: 28, weight: .semibold)
        case .title: .system(size: 20, weight: .semibold)
        case .title2: .system(size: 17, weight: .semibold)
        case .headline: .system(size: 15, weight: .semibold)
        case .body: .system(size: 13)
        case .bodyMedium: .system(size: 13, weight: .medium)
        case .bodySemibold: .system(size: 13, weight: .semibold)
        case .callout: .system(size: 12)
        case .caption: .system(size: 11, weight: .medium)
        case .micro: .system(size: 10, weight: .semibold)
        case .number(let size, let weight): .system(size: size, weight: weight, design: .rounded).monospacedDigit()
        }
    }

    var tracking: CGFloat {
        switch self {
        case .display: -0.5
        case .title: -0.3
        case .title2: -0.2
        case .headline: -0.15
        case .micro: 0.2
        default: 0
        }
    }
}

extension View {
    /// Applies a step of the type scale (font + tracking).
    func tdFont(_ style: TDFont) -> some View {
        font(style.font).tracking(style.tracking)
    }
}

// MARK: - Glass

/// What `.tdGlass` draws. Mirrors SwiftUI's `Glass`, plus a `panel` variant for big surfaces.
struct TDGlass: Equatable {
    enum Variant: Equatable { case regular, clear, panel }

    var variant: Variant = .regular
    var tint: Color?
    var isInteractive = false

    /// The standard glass for controls and floating elements.
    static var regular: TDGlass { TDGlass() }
    /// More transparent; for glass over photos and media.
    static var clear: TDGlass { TDGlass(variant: .clear) }
    /// Window-sized containers (the menu bar panel, HUD windows): glass with a deeper wash under the
    /// content so dense text stays legible on any wallpaper. On macOS 15 this uses a behind-window blur
    /// (a SwiftUI material can't see through a transparent window); pass a RoundedRectangle shape.
    static var panel: TDGlass { TDGlass(variant: .panel) }

    /// Tints the glass (prominent elements). Use the brand violet or a semantic color.
    func tint(_ color: Color?) -> TDGlass {
        var g = self
        g.tint = color
        return g
    }

    /// Reacts to the pointer (macOS 26+): use on custom clickable glass.
    func interactive(_ on: Bool = true) -> TDGlass {
        var g = self
        g.isInteractive = on
        return g
    }

    @available(macOS 26.0, *)
    var system: Glass {
        var g: Glass = variant == .clear ? .clear : .regular
        if let tint { g = g.tint(tint) }
        if isInteractive { g = g.interactive() }
        return g
    }
}

extension View {
    /// Liquid Glass behind this view, in `shape`. macOS 26+: `glassEffect`. macOS 15: ultra-thin
    /// material, a 1 px top-edge highlight and a soft shadow. Screens never branch on the OS themselves.
    func tdGlass<S: Shape>(_ glass: TDGlass = .regular, in shape: S) -> some View {
        modifier(TDGlassModifier(glass: glass, shape: shape))
    }

    /// Liquid Glass in a capsule.
    func tdGlass(_ glass: TDGlass = .regular) -> some View {
        tdGlass(glass, in: Capsule())
    }

    /// Ties this glass to others in the same `TDGlassGroup` so it morphs between states (no-op before 26).
    @ViewBuilder
    func tdGlassID<ID: Hashable & Sendable>(_ id: ID, in namespace: Namespace.ID) -> some View {
        if #available(macOS 26.0, *) {
            glassEffectID(id, in: namespace)
        } else {
            self
        }
    }

    /// Merges this glass shape with siblings that share `id` inside a `TDGlassGroup` (no-op before 26).
    @ViewBuilder
    func tdGlassUnion<ID: Hashable & Sendable>(_ id: ID, in namespace: Namespace.ID) -> some View {
        if #available(macOS 26.0, *) {
            glassEffectUnion(id: id, namespace: namespace)
        } else {
            self
        }
    }
}

private struct TDGlassModifier<S: Shape>: ViewModifier {
    let glass: TDGlass
    let shape: S
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        if #available(macOS 26.0, *) {
            content
                .background {
                    if glass.variant == .panel { shape.fill(TD.panelWash) }
                }
                .glassEffect(glass.system, in: shape)
        } else {
            content
                .background { fallback }
                .overlay {
                    // 1 px highlight along the top edge, as if lit from above.
                    shape.stroke(
                        LinearGradient(colors: [.white.opacity(scheme == .dark ? 0.14 : 0.5), .white.opacity(0)],
                                       startPoint: .top, endPoint: .center),
                        lineWidth: 1)
                }
                .shadow(color: .black.opacity(scheme == .dark ? 0.35 : 0.12), radius: glass.variant == .panel ? 22 : 10,
                        y: glass.variant == .panel ? 12 : 4)
        }
    }

    @ViewBuilder private var fallback: some View {
        if glass.variant == .panel, let r = (shape as? RoundedRectangle)?.cornerSize.width {
            ZStack {
                TDBehindWindowBlur(cornerRadius: r)
                shape.fill(TD.panelWash)
            }
        } else {
            ZStack {
                shape.fill(glass.variant == .clear ? AnyShapeStyle(.ultraThinMaterial) : AnyShapeStyle(.regularMaterial))
                if let tint = glass.tint { shape.fill(tint.opacity(0.85)) }
            }
        }
    }
}

/// `GlassEffectContainer` on macOS 26+ (glass shapes inside blend and morph together); a plain
/// pass-through before. Wrap groups of `.tdGlass` controls that sit close together.
struct TDGlassGroup<Content: View>: View {
    var spacing: CGFloat?
    @ViewBuilder var content: Content

    init(spacing: CGFloat? = nil, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        if #available(macOS 26.0, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

/// Behind-window blur with real rounded corners (SwiftUI clipping leaves square corners on the blur).
/// The macOS 15 path of `TDGlass.panel`; use it directly only for window backgrounds.
struct TDBehindWindowBlur: NSViewRepresentable {
    var cornerRadius: CGFloat
    var material: NSVisualEffectView.Material = .popover

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

// MARK: - Buttons

/// Capsule buttons, all the same metrics. macOS 26+: Liquid Glass (violet-tinted glass for primary,
/// clear glass for secondary). macOS 15: drawn equivalents (aurora gradient / quiet fill + hairline).
///
/// (The system `.glassProminent` style draws a small-radius rectangle on macOS 26/27 and ignores
/// `buttonBorderShape`, so the prominent capsule is drawn with the same tinted, interactive glass.)
///
///     Button("Send Files", systemImage: "plus") { … }.buttonStyle(.tdPrimary)
///     Button("Cancel") { … }.buttonStyle(.td(.secondary, size: .small))
///     Button("Unpair") { … }.buttonStyle(.tdDestructive)
struct TDButtonStyle: PrimitiveButtonStyle {
    enum Role { case primary, secondary, destructive, quiet }

    enum Size {
        case small, regular, large

        var font: Font {
            switch self {
            case .small: .system(size: 12, weight: .semibold)
            case .regular: .system(size: 13, weight: .semibold)
            case .large: .system(size: 14, weight: .semibold)
            }
        }

        var height: CGFloat {
            switch self {
            case .small: 26
            case .regular: 32
            case .large: 38
            }
        }

        var hPadding: CGFloat {
            switch self {
            case .small: 11
            case .regular: 15
            case .large: 20
            }
        }
    }

    var role: Role = .secondary
    var size: Size = .regular
    /// Stretch to the available width (buttons in a row share it equally).
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        Button(role: configuration.role, action: configuration.trigger) {
            configuration.label
                .lineLimit(1)
                .frame(maxWidth: fullWidth ? .infinity : nil)
        }
        .buttonStyle(TDCapsuleButtonStyle(role: role, size: size))
    }
}

extension PrimitiveButtonStyle where Self == TDButtonStyle {
    /// The one main action of a view: violet glass.
    static var tdPrimary: TDButtonStyle { TDButtonStyle(role: .primary) }
    /// Everything else: clear glass.
    static var tdSecondary: TDButtonStyle { TDButtonStyle(role: .secondary) }
    /// Unpair, remove, clear: glass with a red label.
    static var tdDestructive: TDButtonStyle { TDButtonStyle(role: .destructive) }
    /// Text-only (with a hover fill): links inside cards, "Clear", "Show all".
    static var tdQuiet: TDButtonStyle { TDButtonStyle(role: .quiet, size: .small) }

    static func td(_ role: TDButtonStyle.Role, size: TDButtonStyle.Size = .regular, fullWidth: Bool = false) -> TDButtonStyle {
        TDButtonStyle(role: role, size: size, fullWidth: fullWidth)
    }
}

private struct TDCapsuleButtonStyle: ButtonStyle {
    let role: TDButtonStyle.Role
    let size: TDButtonStyle.Size

    func makeBody(configuration: Configuration) -> some View {
        CapsuleBody(configuration: configuration, role: role, size: size)
    }

    private struct CapsuleBody: View {
        let configuration: ButtonStyleConfiguration
        let role: TDButtonStyle.Role
        let size: TDButtonStyle.Size
        @State private var hover = false
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            configuration.label
                .font(size.font)
                .foregroundStyle(foreground)
                .padding(.horizontal, role == .quiet ? 8 : size.hPadding)
                .frame(height: role == .quiet ? size.height - 4 : size.height)
                .modifier(CapsuleFill(role: role, wash: wash, hover: hover, pressed: configuration.isPressed))
                .contentShape(Capsule())
                .opacity(enabled ? 1 : 0.45)
                .scaleEffect(configuration.isPressed && role != .quiet ? 0.97 : 1)
                .animation(TD.Motion.hover, value: hover)
                .animation(TD.Motion.hover, value: configuration.isPressed)
                .onHover { hover = $0 && enabled }
        }

        private var foreground: Color {
            switch role {
            case .primary: .white
            case .destructive: TD.danger
            case .quiet: hover ? TD.text : TD.textSecondary
            case .secondary: TD.text
            }
        }

        /// White wash for hover / press feedback on top of glass.
        private var wash: Double { configuration.isPressed ? 0.16 : (hover ? 0.09 : 0) }
    }

    /// Glass is applied to the label itself (so the label is the glass's content, drawn crisp on top
    /// even inside a GlassEffectContainer); the hover wash sits between them.
    private struct CapsuleFill: ViewModifier {
        let role: TDButtonStyle.Role
        let wash: Double
        let hover: Bool
        let pressed: Bool

        func body(content: Content) -> some View {
            if role == .quiet {
                content.background(Capsule().fill(pressed ? TD.fillStrong : (hover ? TD.fill : .clear)))
            } else if #available(macOS 26.0, *) {
                content
                    .background(Capsule().fill(.white.opacity(wash)))
                    .tdGlass(role == .primary ? .regular.tint(TD.violetDeep).interactive() : .regular.interactive(), in: Capsule())
            } else {
                content.background { drawn }
            }
        }

        @ViewBuilder private var drawn: some View {
            switch role {
            case .primary:
                Capsule().fill(TD.aurora)
                    .overlay(Capsule().fill(.white.opacity(wash)))
                    .overlay(Capsule().strokeBorder(.white.opacity(0.22), lineWidth: 0.5))
                    .shadow(color: TD.violet.opacity(hover ? 0.45 : 0.28), radius: hover ? 10 : 6, y: 3)
            default:
                Capsule().fill(pressed ? TD.fillStrong : (hover ? TD.fillHover : TD.fill))
                    .overlay(Capsule().strokeBorder(TD.hairline, lineWidth: 1))
            }
        }
    }
}

/// Round icon buttons (gear, close, row actions). Glass on macOS 26+.
///
///     Button { … } label: { Image(systemName: "gearshape") }.buttonStyle(.tdIcon)
///     .buttonStyle(.tdIcon(size: 24, prominent: true))
struct TDIconButtonStyle: PrimitiveButtonStyle {
    var size: CGFloat = 28
    /// Violet tinted (the main action of a row).
    var prominent = false
    /// No glass at rest; a fill appears on hover (for dense rows).
    var quiet = false

    func makeBody(configuration: Configuration) -> some View {
        TDIconButtonBody(configuration: configuration, style: self)
    }
}

extension PrimitiveButtonStyle where Self == TDIconButtonStyle {
    static var tdIcon: TDIconButtonStyle { TDIconButtonStyle() }
    static func tdIcon(size: CGFloat = 28, prominent: Bool = false, quiet: Bool = false) -> TDIconButtonStyle {
        TDIconButtonStyle(size: size, prominent: prominent, quiet: quiet)
    }
}

private struct TDIconButtonBody: View {
    let configuration: PrimitiveButtonStyleConfiguration
    let style: TDIconButtonStyle

    var body: some View {
        Button(role: configuration.role, action: configuration.trigger) { configuration.label }
            .buttonStyle(TDIconDrawnStyle(style: style))
    }
}

private struct TDIconDrawnStyle: ButtonStyle {
    let style: TDIconButtonStyle

    func makeBody(configuration: Configuration) -> some View {
        DrawnBody(configuration: configuration, style: style)
    }

    private struct DrawnBody: View {
        let configuration: ButtonStyleConfiguration
        let style: TDIconButtonStyle
        @State private var hover = false
        @Environment(\.isEnabled) private var enabled

        var body: some View {
            configuration.label
                .labelStyle(.iconOnly)
                .font(.system(size: (style.size * 0.43).rounded(), weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(style.prominent ? AnyShapeStyle(.white) : AnyShapeStyle(hover ? TD.text : TD.textSecondary))
                .frame(width: style.size, height: style.size)
                .contentShape(Circle())
                .modifier(CircleFill(style: style, hover: hover, pressed: configuration.isPressed))
                .scaleEffect(configuration.isPressed ? 0.92 : 1)
                .opacity(enabled ? 1 : 0.4)
                .animation(TD.Motion.hover, value: hover)
                .animation(TD.Motion.hover, value: configuration.isPressed)
                .onHover { hover = $0 && enabled }
        }
    }

    private struct CircleFill: ViewModifier {
        let style: TDIconButtonStyle
        let hover: Bool
        let pressed: Bool

        func body(content: Content) -> some View {
            if style.quiet {
                content.background(Circle().fill(pressed ? TD.fillStrong : (hover ? TD.fillHover : .clear)))
            } else if style.prominent {
                content.tdGlass(.regular.tint(TD.violetDeep).interactive(), in: Circle())
            } else {
                content
                    .background(Circle().fill(pressed ? TD.fillHover : (hover ? TD.fill : .clear)))
                    .tdGlass(.regular.interactive(), in: Circle())
            }
        }
    }
}

/// Legacy capsule style (the pair prompt uses it). Prefer `.tdPrimary` / `.tdSecondary`.
struct GlassButtonStyle: PrimitiveButtonStyle {
    var prominent = false

    func makeBody(configuration: Configuration) -> some View {
        TDButtonStyle(role: prominent ? .primary : .secondary, size: .large, fullWidth: true)
            .makeBody(configuration: configuration)
    }
}

// MARK: - App glyph

/// The app icon, drawn (aurora squircle with the radio-waves mark) so it stays crisp at any size.
struct AppGlyph: View {
    var size: CGFloat
    /// Soft violet glow under the glyph (hero use only).
    var glow = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.27, style: .continuous)
        ZStack {
            shape.fill(LinearGradient(
                stops: [.init(color: Color(red: 0.20, green: 0.66, blue: 1.0), location: 0),
                        .init(color: Color(red: 0.40, green: 0.45, blue: 1.0), location: 0.55),
                        .init(color: Color(red: 0.60, green: 0.32, blue: 0.98), location: 1)],
                startPoint: .topLeading, endPoint: .bottomTrailing))
            shape.fill(LinearGradient(colors: [.white.opacity(0.28), .white.opacity(0)], startPoint: .top, endPoint: .center))
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: size * 0.46, weight: .bold))
                .foregroundStyle(.white)
                .shadow(color: Color(red: 0.15, green: 0.1, blue: 0.5).opacity(0.35), radius: size * 0.03, y: size * 0.01)
        }
        .frame(width: size, height: size)
        .overlay(shape.strokeBorder(.white.opacity(0.18), lineWidth: max(0.5, size / 64)))
        .background {
            if glow {
                shape.fill(TD.glow).blur(radius: size * 0.45).scaleEffect(1.1)
            }
        }
        .shadow(color: TD.violet.opacity(0.28), radius: size * 0.12, y: size * 0.05)
        .accessibilityHidden(true)
    }
}
