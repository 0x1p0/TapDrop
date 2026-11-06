import AppKit
import CoreText
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
    // MARK: Brand ("Lumen": light, in the dark)
    //
    // A tap is the phone's screen lighting the notch and going dark, so TapDrop is made of night and light:
    // near-black, bone white, and the cool glow of a screen's light. No accent hue; colour comes from photos.

    static let night = Color(tdHex: 0x08080A)
    static let bone = Color(tdHex: 0xF4F3EF)
    static let ink = Color(tdHex: 0x0B0B0D)
    /// The glow: the cool white of a screen's light.
    static let light = Color(tdHex: 0xD6DEFF)
    /// The one solid fill (primary buttons, progress, selection): bone in dark mode, ink in light.
    static let accent = Color.td(light: 0x0B0B0D, dark: 0xF4F3EF)
    /// Text and icons on `accent`.
    static let onAccent = Color.td(light: 0xF4F3EF, dark: 0x08080A)

    // The old hue names, kept so every view reads the same: all the accent now, or the light.
    static let blue = accent
    static let violet = accent
    static let orchid = light
    static let violetDeep = accent

    /// The accent as a fill (it was a gradient; Lumen has none).
    static let aurora = LinearGradient(colors: [accent, accent], startPoint: .topLeading, endPoint: .bottomTrailing)
    /// The light, for hero glows only.
    static let auroraHero = LinearGradient(colors: [light, .white], startPoint: .topLeading, endPoint: .bottomTrailing)
    /// Rings: the accent, brightening to the light at the top.
    static let auroraRing = AngularGradient(colors: [accent, light, accent], center: .center)

    // MARK: Neutrals (resolve per appearance)

    static let bg = Color.td(light: 0xEFEDE7, dark: 0x08080A)
    /// Cards on solid backgrounds.
    static let surface = Color.td(light: 0xFFFFFF, dark: 0x111114)
    /// Popovers, menus, things that float over a surface (add `tdShadow` in light mode).
    static let surfaceRaised = Color.td(light: 0xFFFFFF, dark: 0x17171A)
    static let hairline = Color.td(light: NSColor(white: 0, alpha: 0.07), dark: NSColor(white: 1, alpha: 0.08))
    static let text = Color.td(light: 0x0B0B0D, dark: 0xF4F3EF)
    static let textSecondary = Color.td(light: 0x5F5E59, dark: 0xA2A2A8)
    static let textTertiary = Color.td(light: 0x8C8A84, dark: 0x6E6E75)

    /// Quiet fill for platters, rows and tiles that sit on glass.
    static let fill = Color.td(light: NSColor(white: 0, alpha: 0.045), dark: NSColor(white: 1, alpha: 0.06))
    /// `fill` under the pointer.
    static let fillHover = Color.td(light: NSColor(white: 0, alpha: 0.075), dark: NSColor(white: 1, alpha: 0.10))
    /// Pressed, selected, or a fill that must read on a busy backdrop.
    static let fillStrong = Color.td(light: NSColor(white: 0, alpha: 0.11), dark: NSColor(white: 1, alpha: 0.15))
    /// The wash `TDGlass.panel` lays under content so large glass surfaces stay legible on any wallpaper.
    static let panelWash = Color.td(light: NSColor(tdHex: 0xEFEDE7, alpha: 0.6), dark: NSColor(tdHex: 0x08080A, alpha: 0.5))

    // MARK: Semantic (system colors, so they adapt to the appearance)

    /// "It worked": bone or ink (with the light's glow where it shows), never green.
    static let success = Color.td(light: 0x0B0B0D, dark: 0xF4F3EF)
    static let warning = Color.orange
    static let danger = Color.red
    /// The hero glow: the light, always heavily blurred, behind hero elements only.
    static let glow = light.opacity(0.45)

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

/// The type scale: Instrument Serif for display and titles, Geist for everything read, Geist Mono for readings.
/// (The fonts ship in Resources/Fonts and are registered at launch; without them it falls back to SF.)
enum TDFont {
    /// Serif 34, −0.6. Window heroes ("Set up your phone").
    case display
    /// Serif 27, −0.4. Pane titles.
    case title
    /// 17 medium, −0.3.
    case title2
    /// 15 medium, −0.2. Card titles, the panel hero line.
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
    /// Mono 10, +1.2. Badges, tiny labels and section eyebrows.
    case micro
    /// Tabular numbers: codes, sizes, percentages.
    case number(CGFloat, Font.Weight = .semibold)

    var font: Font {
        switch self {
        case .display: .serif(34)
        case .title: .serif(27)
        case .title2: .geist(17, .medium)
        case .headline: .geist(15, .medium)
        case .body: .geist(13)
        case .bodyMedium: .geist(13, .medium)
        case .bodySemibold: .geist(13, .semibold)
        case .callout: .geist(12)
        case .caption: .geist(11, .medium)
        case .micro: .geistMono(10)
        case .number(let size, let weight): .geist(size, weight).monospacedDigit()
        }
    }

    var tracking: CGFloat {
        switch self {
        case .display: -0.6
        case .title: -0.4
        case .title2: -0.3
        case .headline: -0.2
        case .micro: 1.2
        default: 0
        }
    }
}

extension Font {
    /// Geist at a weight (its named instances), or SF at that weight if the font isn't registered.
    static func geist(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        TDFonts.available ? .custom("Geist-" + TDFonts.instance(weight), size: size) : .system(size: size, weight: weight)
    }
    /// Geist Mono, for codes and readings.
    static func geistMono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        TDFonts.available ? .custom("GeistMono-" + TDFonts.instance(weight), size: size) : .system(size: size, weight: weight, design: .monospaced)
    }
    /// Instrument Serif (regular or italic), for display lines.
    static func serif(_ size: CGFloat, italic: Bool = false) -> Font {
        TDFonts.available ? .custom(italic ? "InstrumentSerif-Italic" : "InstrumentSerif-Regular", size: size) : .system(size: size, design: .serif)
    }
}

/// Registers the bundled typefaces (Resources/Fonts) for this process, once, at launch.
enum TDFonts {
    private(set) static var available = false

    static func register() {
        guard !available, let dir = Bundle.main.resourceURL?.appendingPathComponent("Fonts") else { return }
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        var ok = 0
        for url in files where url.pathExtension == "ttf" {
            if CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil) { ok += 1 }
        }
        available = ok >= 4 || NSFont(name: "Geist-Regular", size: 12) != nil
    }

    static func instance(_ w: Font.Weight) -> String {
        switch w {
        case .ultraLight, .thin: "Thin"
        case .light: "Light"
        case .medium: "Medium"
        case .semibold: "SemiBold"
        case .bold: "Bold"
        case .heavy: "ExtraBold"
        case .black: "Black"
        default: "Regular"
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
            case .primary: TD.onAccent
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
            } else if role == .primary {
                // Never a flat white pill: the accent, lit.
                content.background { LitCapsule(hover: hover, pressed: pressed) }
            } else if #available(macOS 26.0, *) {
                content
                    .background(Capsule().fill(.white.opacity(wash)))
                    .tdGlass(.regular.interactive(), in: Capsule())
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

/// The primary button's fill: the accent, lit. Bone with a soft top-to-bottom falloff, a rim and a faint glow of the
/// notch's light in dark mode; ink with a sheen and a shadow in light mode.
struct LitCapsule: View {
    var hover = false
    var pressed = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        Capsule()
            .fill(LinearGradient(colors: dark ? [Color(tdHex: 0xFFFFFF), Color(tdHex: 0xE2E0D9)]
                                              : [Color(tdHex: 0x303036), Color(tdHex: 0x0B0B0D)],
                                 startPoint: .top, endPoint: .bottom))
            .overlay(Capsule().strokeBorder(
                LinearGradient(colors: dark ? [.white, .white.opacity(0), .black.opacity(0.12)]
                                            : [.white.opacity(0.26), .white.opacity(0.04), .clear],
                               startPoint: .top, endPoint: .bottom),
                lineWidth: 1))
            .brightness(pressed ? (dark ? -0.06 : 0.06) : (hover ? (dark ? 0.02 : 0.04) : 0))
            .shadow(color: dark ? TD.light.opacity(pressed ? 0.18 : (hover ? 0.5 : 0.32)) : .black.opacity(0.24),
                    radius: dark ? (hover ? 14 : 10) : 6, y: dark ? 0 : 3)
            .shadow(color: .black.opacity(dark ? 0.45 : 0.14), radius: 1.2, y: 1)
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
                .foregroundStyle(style.prominent ? AnyShapeStyle(TD.onAccent) : AnyShapeStyle(hover ? TD.text : TD.textSecondary))
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
                content.background { LitCapsule(hover: hover, pressed: pressed) }
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

/// The app icon, drawn: the Notchline (the notch traced in one line of light) on a night tile, the same in both
/// appearances, so it stays crisp at any size.
struct AppGlyph: View {
    var size: CGFloat
    /// A soft glow of light under it (hero use only).
    var glow = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.23, style: .continuous)
        ZStack {
            shape.fill(Color(tdHex: 0x111114))
            shape.fill(RadialGradient(
                stops: [.init(color: .white.opacity(0.34), location: 0), .init(color: TD.light.opacity(0.10), location: 0.45), .init(color: .clear, location: 1)],
                center: UnitPoint(x: 0.5, y: 0.4), startRadius: 0, endRadius: size * 0.46))
            Notchline()
                .stroke(.white.opacity(0.7), style: StrokeStyle(lineWidth: size * 0.07, lineCap: .round, lineJoin: .round))
                .blur(radius: size * 0.035)
                .frame(width: size * 0.68, height: size * 0.68 * 11.5 / 68)
                .offset(y: size * (0.38 + 0.68 * 11.5 / 68 / 2 - 0.5))
            Notchline()
                .stroke(.white, style: StrokeStyle(lineWidth: size * 0.036, lineCap: .round, lineJoin: .round))
                .frame(width: size * 0.68, height: size * 0.68 * 11.5 / 68)
                .offset(y: size * (0.38 + 0.68 * 11.5 / 68 / 2 - 0.5))
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .overlay(shape.strokeBorder(.white.opacity(0.12), lineWidth: max(0.5, size / 80)))
        .background {
            if glow {
                shape.fill(TD.glow).blur(radius: size * 0.45).scaleEffect(1.1)
            }
        }
        .shadow(color: .black.opacity(0.25), radius: size * 0.1, y: size * 0.04)
        .accessibilityHidden(true)
    }
}

/// The Notchline: the notch's outline as one line (a straight run, a rounded dip, a straight run), filling its
/// frame. The same path as the app icon, the phone app and the website.
struct Notchline: Shape {
    func path(in r: CGRect) -> Path {
        let k = r.width / 68
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + (x - 16) * k, y: r.minY + (y - 38) * k) }
        var path = Path()
        path.move(to: p(16, 38))
        path.addLine(to: p(34, 38))
        path.addCurve(to: p(38.5, 42.5), control1: p(37, 38), control2: p(38.5, 39.5))
        path.addLine(to: p(38.5, 43.5))
        path.addCurve(to: p(44.5, 49.5), control1: p(38.5, 47), control2: p(41, 49.5))
        path.addLine(to: p(55.5, 49.5))
        path.addCurve(to: p(61.5, 43.5), control1: p(59, 49.5), control2: p(61.5, 47))
        path.addLine(to: p(61.5, 42.5))
        path.addCurve(to: p(66, 38), control1: p(61.5, 39.5), control2: p(63, 38))
        path.addLine(to: p(84, 38))
        return path
    }
}

// MARK: - Lumen pieces

/// The Notchline lit: in dark mode a white line in its own glow; in light mode an ink line with a white halo behind
/// it, as if lit. `glow` 0…1 dims or brightens the light.
struct NotchlineGlow: View {
    var glow: Double = 1
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let line = max(1.2, w / 68 * 1.1)
            ZStack {
                if scheme == .dark {
                    Notchline().stroke(.white.opacity(0.75 * glow), style: StrokeStyle(lineWidth: line * 4, lineCap: .round, lineJoin: .round))
                        .blur(radius: line * 2.4)
                    Notchline().stroke(.white.opacity(0.6 * glow), style: StrokeStyle(lineWidth: line * 2, lineCap: .round, lineJoin: .round))
                        .blur(radius: line * 0.9)
                    Notchline().stroke(.white.opacity(0.35 + 0.65 * glow), style: StrokeStyle(lineWidth: line, lineCap: .round, lineJoin: .round))
                } else {
                    Notchline().stroke(.white.opacity(glow), style: StrokeStyle(lineWidth: line * 7, lineCap: .round, lineJoin: .round))
                        .blur(radius: line * 3.5)
                    Notchline().stroke(TD.ink, style: StrokeStyle(lineWidth: line, lineCap: .round, lineJoin: .round))
                }
            }
            .frame(width: w, height: w * 11.5 / 68)
            .frame(maxHeight: .infinity)
        }
        .accessibilityHidden(true)
    }
}

/// "TapDrop": Tap in Geist, Drop in the serif's italic.
struct Wordmark: View {
    var size: CGFloat = 16
    var body: some View {
        (Text("Tap").font(.geist(size, .semibold)) + Text("Drop").font(.serif(size * 1.12, italic: true)))
            .tracking(-0.3)
            .foregroundStyle(TD.text)
            .fixedSize()
            .accessibilityLabel("TapDrop")
    }
}
