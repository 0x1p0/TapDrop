import AppKit
import SwiftUI

// The notch island: a Dynamic-Island style surface that grows out of the MacBook's notch (or out of the top
// edge of the screen on Macs without one). At rest it is exactly the notch, so it reads as the hardware
// itself; it springs out to show a transfer, a result, a code or a hint, and tucks back in.
//
//   HUD            the borderless panel above the menu bar, placement, click-through, hover, the light key
//   IslandView     the black shape (morphs between notch, peek, compact and expanded) and its content
//   IslandShape    the notch outline with concave ears, continuous bottom corners, animatable
//   IslandRim      the light key: light running along the island's edge while the notch looks for the phone
//
// Content for each state is in IslandContent.swift; demo modes in DemoIsland.swift.

/// The light key as the island shows it (from `TapStatus.phase`).
enum IslandLight: Equatable {
    case off
    /// The phone's light is on; the notch is looking for it: light sweeps along the notch.
    case keying
    /// The notch saw it: the rim flares, then the island opens for what's coming.
    case accepted
    /// It didn't count: the rim dims amber and goes out.
    case failed
}

@MainActor
final class IslandState: ObservableObject {
    /// The island is showing `Activity` (false while tucked into the notch).
    @Published var expanded = false
    /// The hardware notch (points). Without a notch: its width, and height 0.
    @Published var notch = CGSize(width: 190, height: 32)
    @Published var hasNotch = true
    @Published var light: IslandLight = .off
    /// The pointer is over the island (it stays open, and its close button shows).
    @Published var hover = false
    /// The island's current body size (for click-through and hover), set by the view.
    var size: CGSize = .zero
    /// Demo/QA: draw a stand-in for the hardware notch under the island (screenshots don't have one).
    @Published var standIn = false
    /// Demo/QA: tuck a running transfer into the notch's sides right away.
    @Published var demoCompact = false
    /// Demo/QA: draw the island as if the pointer were on it.
    var demoHover = false
    /// Demo/QA: keep the light key's flare or failure up (it normally settles in about a second).
    var demoHoldLight = false
    /// Demo/QA: freeze the light key's sweep at this point along the rim (0…1).
    var demoSweep: Double?
    /// Demo/QA: lay the island out as on a Mac without a notch.
    var demoNoNotch = false
}

/// Demo/QA: TAPDROP_REDUCE_MOTION=1 draws the island and the pairing prompt as they look with Reduce Motion on
/// (their own motion; the system setting itself can't be overridden from inside the app).
enum DemoReduceMotion {
    static let on = ProcessInfo.processInfo.environment["TAPDROP_REDUCE_MOTION"] == "1"
}

/// Demo/QA: the hardware notch, drawn where the real one is (screenshots don't include it). It sits on top
/// of the island, as the real one does: black, with the camera in the middle.
private struct StandInNotch: View {
    let size: CGSize

    var body: some View {
        ZStack {
            NotchShape(ear: 4, radius: 9).fill(Color.black)
            Circle()
                .fill(RadialGradient(colors: [Color(tdHex: 0x2A3350), Color(tdHex: 0x10131D)], center: .init(x: 0.4, y: 0.35),
                                     startRadius: 0, endRadius: 4))
                .frame(width: 7, height: 7)
                .overlay(Circle().fill(Color(tdHex: 0x4A5A8A).opacity(0.5)).frame(width: 1.6, height: 1.6).offset(x: -1, y: -1))
        }
        .frame(width: size.width + 8, height: size.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Demo/QA: the menu bar beside the notch (screenshots don't include the real one), so the island is judged in
/// place: macOS 26's clear bar, the last menus left of the notch and a few status items right of it.
private struct StandInMenuBar: View {
    let notch: CGSize
    private let dark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua

    var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: 19) {
                Text("View")
                Text("Window")
                Text("Help")
            }
            .font(.system(size: 13))
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.trailing, 34)
            Color.clear.frame(width: notch.width)
            HStack(spacing: 17) {
                Image(systemName: "dot.radiowaves.left.and.right")
                Image(systemName: "wifi")
                Image(systemName: "battery.75percent")
                Text("9:41")
            }
            .font(.system(size: 13, weight: .medium))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 28)
        }
        .foregroundStyle(dark ? Color.white.opacity(0.94) : Color.black.opacity(0.86))
        .frame(width: HUD.canvas.width, height: notch.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

@MainActor
final class HUD {
    static let shared = HUD()
    /// The panel: room for the widest island, its shadow and the light key's glow.
    static let canvas = CGSize(width: 640, height: 300)

    let state = IslandState()
    private var panel: IslandPanel?
    private var generation = 0
    private var monitors: [Any] = []
    private var lightWork: DispatchWorkItem?
    /// The light the island should show once it's on screen (applied late when the panel was just ordered in).
    private var wantedLight: IslandLight = .off

    /// Shows the island for whatever `Activity` holds now.
    func show() {
        guard !TestMode.headless else { return }
        generation += 1
        // The light key's flare hands over to what it unlocked: it fades off the rim as the island opens.
        if wantedLight == .accepted { settleLight(after: 0.15) }
        present {
            guard !self.state.expanded else { return }
            withTDAnimation(TD.Motion.island) { self.state.expanded = true }
        }
    }

    /// Orders the panel in and applies `change`. A panel that was away first draws one frame at rest (the
    /// notch itself), so the island visibly grows out of the hardware rather than appearing grown.
    private func present(_ change: @escaping @MainActor @Sendable () -> Void) {
        let panel = ensurePanel()
        let wasHidden = !panel.isVisible
        if wasHidden { place(panel) }
        panel.orderFrontRegardless()
        startTracking()
        if wasHidden {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0 / 30, execute: change)
        } else {
            change()
        }
    }

    /// Demo/QA: puts the island on screen at rest (exactly the notch), so a shot can watch it grow.
    func demoAtRest() {
        present {}
    }

    func hide(completion: @escaping () -> Void) {
        let gen = generation
        withTDAnimation(IslandMetrics.tuck) { state.expanded = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5 * IslandMetrics.slowmo) {
            guard gen == self.generation else { return }  // re-opened meanwhile
            completion()
            if self.state.light == .off { self.orderOut() }
        }
    }

    // MARK: the light key

    /// Follows the light-key round: light runs along the notch while it looks, flares when it counts.
    func light(_ phase: TapStatus.Phase) {
        guard !TestMode.headless else { return }
        switch phase {
        case .keying:
            setLight(.keying)
        case .accepted:
            setLight(.accepted)
            settleLight(after: 1.5)
        case .failed(let reason):
            // A phone that declined before lighting up (locked, still…) never lit the notch: stay quiet.
            guard wantedLight == .keying else { return }
            // A calibration sample isn't a failure: the light just goes out.
            if TapCopy.quiet(reason) { return setLight(.off) }
            setLight(.failed)
            settleLight(after: 0.9)
        case .idle:
            if wantedLight == .keying { setLight(.off) }
        }
    }

    private func setLight(_ l: IslandLight) {
        lightWork?.cancel()
        guard l != wantedLight else { return }
        wantedLight = l
        if l == .off {
            withTDAnimation(.easeOut(duration: 0.45)) { state.light = .off }
            orderOutIfIdle(after: 0.5)
            return
        }
        if panel?.isVisible != true { generation += 1 }  // cancels a pending order-out
        present {
            // the latest wish wins (keying → accepted can both land before the first frame)
            guard self.state.light != self.wantedLight else { return }
            withTDAnimation(TD.Motion.island) { self.state.light = self.wantedLight }
        }
    }

    private func settleLight(after seconds: Double) {
        guard !state.demoHoldLight else { return }
        let work = DispatchWorkItem { [weak self] in self?.setLight(.off) }
        lightWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: work)
    }

    private func orderOutIfIdle(after seconds: Double) {
        let gen = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            guard gen == self.generation, !self.state.expanded, self.state.light == .off else { return }
            self.orderOut()
        }
    }

    private func orderOut() {
        panel?.orderOut(nil)
        stopTracking()
        if state.hover { state.hover = false }
        Activity.shared.hovering = false
    }

    // MARK: window

    private func ensurePanel() -> IslandPanel {
        if let panel { return panel }
        let p = IslandPanel(
            contentRect: NSRect(origin: .zero, size: Self.canvas),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        // Above the menu bar, like the hardware it extends.
        p.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        p.isMovable = false
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        p.acceptsMouseMovedEvents = true
        // Clicks fall through to the menu bar except over the island itself (see `track`).
        p.ignoresMouseEvents = true
        // Pure black hardware in both appearances: its content is always drawn for a dark surface.
        p.appearance = NSAppearance(named: .darkAqua)
        p.setAccessibilityLabel("TapDrop")
        let host = NSHostingView(rootView: IslandView(state: state, activity: Activity.shared))
        host.frame = NSRect(origin: .zero, size: Self.canvas)
        p.contentView = host
        panel = p
        return p
    }

    /// Centers the canvas on the notch of the built-in display (where the phone taps), else the main screen.
    private func place(_ p: NSPanel) {
        let notched = NSScreen.screens.first { $0.safeAreaInsets.top > 0 && $0.auxiliaryTopLeftArea != nil }
        guard let screen = notched ?? NSScreen.main ?? NSScreen.screens.first else { return }
        let f = screen.frame
        var midX = f.midX
        if !state.demoNoNotch, screen.safeAreaInsets.top > 0, let l = screen.auxiliaryTopLeftArea,
           let r = screen.auxiliaryTopRightArea {
            state.notch = CGSize(width: r.minX - l.maxX, height: screen.safeAreaInsets.top)
            state.hasNotch = true
            midX = (l.maxX + r.minX) / 2
        } else {
            state.notch = CGSize(width: 200, height: 0)
            state.hasNotch = false
        }
        p.setFrame(NSRect(x: (midX - Self.canvas.width / 2).rounded(), y: f.maxY - Self.canvas.height,
                          width: Self.canvas.width, height: Self.canvas.height), display: false)
    }

    // MARK: pointer

    /// The island's body on screen (the window is much bigger than the island; the rest is click-through).
    private var islandRect: NSRect {
        guard let p = panel else { return .zero }
        let s = state.size
        return NSRect(x: p.frame.midX - s.width / 2, y: p.frame.maxY - s.height, width: s.width, height: s.height)
    }

    /// Mouse-moved monitors only while the island is up (nothing runs while it's away).
    private func startTracking() {
        guard monitors.isEmpty else { return }
        let mask: NSEvent.EventTypeMask = [.mouseMoved, .leftMouseDragged, .mouseEntered, .mouseExited]
        if let g = NSEvent.addGlobalMonitorForEvents(matching: mask, handler: { [weak self] _ in
            MainActor.assumeIsolated { self?.track() }
        }) { monitors.append(g) }
        if let l = NSEvent.addLocalMonitorForEvents(matching: mask, handler: { [weak self] e in
            MainActor.assumeIsolated { self?.track() }
            return e
        }) { monitors.append(l) }
        DispatchQueue.main.async { self.track() }
    }

    private func stopTracking() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors = []
    }

    private func track() {
        guard let panel, panel.isVisible else { return }
        let inside = state.demoHover
            || state.size.width > 0 && islandRect.insetBy(dx: -2, dy: -2).contains(NSEvent.mouseLocation)
        if panel.ignoresMouseEvents == inside { panel.ignoresMouseEvents = !inside }
        if state.hover != inside {
            withTDAnimation(TD.Motion.standard) { state.hover = inside }
            Activity.shared.hovering = inside
        }
    }
}

/// Never key, never main: the island must not take focus from what the user is doing.
final class IslandPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
    // Stays flush with the top of the screen, over the menu bar.
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect { frameRect }
}

// MARK: - Geometry

enum IslandMetrics {
    /// Expanded body width (the bottom edge; the ears add to it at the top).
    static func width(notch: CGSize) -> CGFloat { max(440, notch.width + 2 * 112) }
    /// The narrowest expanded island: shoulders of at least 64 pt beside the notch.
    static func minWidth(notch: CGSize) -> CGFloat { min(width(notch: notch), notch.width + 2 * 64) }
    /// The widest an island that hugs its text may grow (a long notice keeps to one line rather than wrap).
    static func maxWidth(notch: CGSize) -> CGFloat { width(notch: notch) + 40 }
    /// Content inset from the island's sides and bottom.
    static let padding: CGFloat = 18
    /// Expanded bottom corners. Content 18 pt in sits on concentric 12-pt corners.
    static let radius: CGFloat = 30
    /// Concave fillets into the top edge, expanded.
    static let ear: CGFloat = 12
    /// Compact: room each side of the notch for a ring and a number.
    static let wing: CGFloat = 52
    /// The leading visual of an expanded row (ring, thumbnail, tile or chip).
    static let glyph: CGFloat = 46
    /// Collapsing back into the notch: firm, no bounce.
    static let tuck = Animation.spring(response: 0.36, dampingFraction: 0.92)
    /// The notch saw the phone's light: a quick, springy swell, felt more than seen.
    static let pop = Animation.spring(response: 0.3, dampingFraction: 0.58)

    /// Demo/QA: TAPDROP_ISLAND_SLOWMO=10 plays the island's morphs ten times slower, to photograph them mid-way.
    static let slowmo = ProcessInfo.processInfo.environment["TAPDROP_ISLAND_SLOWMO"].flatMap(Double.init) ?? 1

    static func paced(_ a: Animation) -> Animation { slowmo == 1 ? a : a.speed(1 / slowmo) }
}

/// What the island is doing, size-wise.
enum IslandScene: Equatable {
    /// Exactly the notch (hidden in it).
    case rest
    /// A little larger than the notch, for the light key.
    case peek
    /// The light key passed: the peek swells for a moment.
    case flare
    /// Notch height, with a ring and a number on its sides (a long transfer).
    case compact
    /// Grown down for content.
    case expanded
}

struct IslandFrame: Equatable {
    /// The body: width along the bottom edge, height from the top of the screen.
    var size: CGSize
    var radius: CGFloat
    var ear: CGFloat

    static func make(_ scene: IslandScene, notch: CGSize, hasNotch: Bool, content: CGFloat, width: CGFloat) -> IslandFrame {
        let n = CGSize(width: notch.width, height: hasNotch ? notch.height : 0)
        switch scene {
        case .rest:
            // Inside the hardware notch: its bottom corners are rounder than this, its ears wider.
            return IslandFrame(size: n, radius: min(10, n.height / 2), ear: 0)
        case .peek:
            let h = max(n.height, 14) + 6
            return IslandFrame(size: CGSize(width: n.width + 14, height: h), radius: min(14, h / 2), ear: 6)
        case .flare:
            let h = max(n.height, 14) + 10
            return IslandFrame(size: CGSize(width: n.width + 26, height: h), radius: min(17, h / 2), ear: 8)
        case .compact:
            let h = hasNotch ? n.height : 30
            return IslandFrame(size: CGSize(width: n.width + IslandMetrics.wing * 2, height: h), radius: h / 2, ear: 9)
        case .expanded:
            let h = n.height + content
            return IslandFrame(size: CGSize(width: width, height: h),
                               radius: min(IslandMetrics.radius, h / 2), ear: IslandMetrics.ear)
        }
    }
}

// MARK: - Island view

struct IslandView: View {
    @ObservedObject var state: IslandState
    @ObservedObject var activity: Activity
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }
    /// Measured content heights (below the notch), by `Activity.serial`.
    @State private var heights: [Int: CGFloat] = [:]
    @State private var compact = false
    /// What the island shows. It follows `Activity`; a new presentation (a transfer's result, a notice) swaps in
    /// inside an animation, so the old content dissolves out while the new one blurs in.
    @State private var shown: IslandSnapshot?

    private var snap: IslandSnapshot { shown ?? IslandSnapshot(activity) }

    private var showing: Bool { state.expanded && snap.phase != .idle }

    private var scene: IslandScene {
        if showing { return compact || state.demoCompact && canCompact ? .compact : .expanded }
        switch state.light {
        case .off: return .rest
        case .accepted: return .flare
        case .keying, .failed: return .peek
        }
    }

    /// Long transfers tuck into the notch's sides; picking and reading keep their instructions visible.
    private var canCompact: Bool {
        let s = snap
        return s.phase == .active && s.kind == .transfer && s.progress != nil && s.bytesTotal > 0
    }

    private var top: CGFloat { state.hasNotch ? state.notch.height : 0 }

    /// Hugs short content (see `IslandFit`); never narrower than the notch plus its shoulders.
    private var width: CGFloat {
        let full = IslandMetrics.width(notch: state.notch)
        guard let fit = IslandFit.width(for: snap) else { return full }
        return min(IslandMetrics.maxWidth(notch: state.notch), max(IslandMetrics.minWidth(notch: state.notch), fit.rounded(.up)))
    }

    /// How the shape moves to its next size: out with the island spring, a springy swell when the key passes,
    /// back into the notch without a bounce.
    private var morph: Animation {
        if reduceMotion { return TD.Motion.reduced }
        if scene == .flare { return IslandMetrics.pop }
        return showing || state.light != .off ? TD.Motion.island : IslandMetrics.tuck
    }

    /// Identifies one piece of content: a presentation, tucked or not.
    private var contentKey: String { "\(snap.serial)-\(scene == .compact)" }

    var body: some View {
        let f = IslandFrame.make(scene, notch: state.notch, hasNotch: state.hasNotch,
                                 content: heights[snap.serial] ?? estimate, width: width)
        ZStack(alignment: .top) {
            if state.standIn && state.hasNotch {
                // demo only: the menu bar around the notch (the island covers it as it grows)
                StandInMenuBar(notch: state.notch)
            }
            if state.light != .off && !showing {
                // the light at the notch (an open island has taken it in)
                IslandBloom(light: state.light, size: f.size)
                    .transition(.opacity.animation(.easeOut(duration: 0.35)))
            }
            island(f)
            if state.standIn && state.hasNotch && !DSSnapshot.notchProof {
                // demo only: the hardware the island grows out of (it covers whatever is drawn there; the notch
                // proof leaves it out, to show what's really drawn under it)
                StandInNotch(size: state.notch)
            }
            IslandRim(light: state.light, frame: f, demoSweep: state.demoSweep)
                // the light runs around the notch, never under it (the hardware would hide it)
                .mask { NotchCutout(notch: state.hasNotch ? state.notch : .zero).fill(style: FillStyle(eoFill: true)) }
        }
        .frame(width: HUD.canvas.width, height: HUD.canvas.height, alignment: .top)
        .animation(IslandMetrics.paced(morph), value: f)
        .onChange(of: f.size, initial: true) { _, s in state.size = s }
        .environment(\.colorScheme, .dark)
        .onChange(of: IslandSnapshot(activity), initial: true) { _, now in follow(now) }
        .task(id: "\(snap.serial)-\(state.hover)") { await tuckIfLong() }
        .onChange(of: snap.phase) { _, p in if p != .active, compact { withTDAnimation(TD.Motion.island) { compact = false } } }
    }

    /// Progress within one presentation applies as it comes; a new presentation swaps in animated (the content's
    /// transitions carry their own curves; this transaction only has to be an animated one).
    private func follow(_ now: IslandSnapshot) {
        guard let shown, shown.serial != now.serial else {
            shown = now
            return
        }
        withAnimation(IslandMetrics.paced(TD.Motion.resolved(TD.Motion.island, reduceMotion: reduceMotion))) { self.shown = now }
    }

    private func island(_ f: IslandFrame) -> some View {
        let shape = IslandShape(radius: f.radius, ear: f.ear)
        let lifted = scene == .expanded || scene == .compact
        return ZStack(alignment: .top) {
            Color.black
            if showing {
                content
                    .id(contentKey)
                    .transition(reduceMotion ? .opacity.animation(TD.Motion.reduced) : .islandContent)
                    // a ZStack draws a view on its way out beneath its siblings: without this, the outgoing content
                    // would vanish behind the black at once instead of dissolving
                    .zIndex(1)
            }
        }
        .frame(width: f.size.width + f.ear * 2, height: f.size.height)
        .clipShape(shape)
        .background {
            // the lift off the desktop (not at rest, where it would show around the hardware)
            shape.fill(Color.black)
                .shadow(color: .black.opacity(lifted ? 0.42 : 0), radius: lifted ? 22 : 0, y: lifted ? 10 : 0)
        }
        .contentShape(shape)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("TapDrop")
    }

    @ViewBuilder private var content: some View {
        let snap = self.snap
        if scene == .compact {
            IslandCompact(activity: snap, notch: state.notch, wing: IslandMetrics.wing)
        } else {
            let width = self.width
            // measured per presentation (content on its way out keeps reporting under its own serial)
            let serial = snap.serial
            VStack(spacing: 0) {
                Color.clear.frame(height: top)
                IslandContent(activity: snap)
                    .padding(.horizontal, IslandMetrics.padding)
                    .padding(.top, state.hasNotch ? 8 : IslandMetrics.padding)
                    .padding(.bottom, IslandMetrics.padding)
                    .frame(width: width)
                    .fixedSize(horizontal: false, vertical: true)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                        if heights[serial] != h { heights[serial] = h }
                        if heights.count > 8 { heights = heights.filter { $0.key >= serial - 2 } }
                    }
            }
            .frame(width: width, alignment: .top)
            .overlay(alignment: .topTrailing) {
                if state.hover && snap.phase != .active && state.hasNotch {
                    IslandCloseButton { Activity.shared.dismiss() }
                        .frame(width: (width - state.notch.width) / 2, height: top)
                        .transition(.opacity)
                }
            }
        }
    }

    /// Until the content has been measured once: a good guess, so the first spring heads the right way.
    private var estimate: CGFloat {
        switch snap.kind {
        case .otp: 92
        default: 70
        }
    }

    /// After 4 s a running transfer tucks into the notch's sides (unless the pointer is on it).
    private func tuckIfLong() async {
        if state.hover {
            if compact { withTDAnimation(TD.Motion.island) { compact = false } }
            return
        }
        guard canCompact else { return }
        let wait = max(0.8, 4 - Date().timeIntervalSince(snap.startedAt))
        try? await Task.sleep(for: .seconds(wait))
        guard !Task.isCancelled, canCompact, !state.hover else { return }
        withTDAnimation(TD.Motion.island) { compact = true }
    }
}

extension AnyTransition {
    /// Island content: blurs and scales in from the notch as the shape grows; blurs out quickly as it changes.
    static var islandContent: AnyTransition {
        .asymmetric(
            insertion: .modifier(active: IslandBlurModifier(blur: 10, scale: 0.86, opacity: 0),
                                 identity: IslandBlurModifier(blur: 0, scale: 1, opacity: 1))
                .animation(IslandMetrics.paced(.spring(response: 0.4, dampingFraction: 0.86).delay(0.03))),
            removal: .modifier(active: IslandBlurModifier(blur: 8, scale: 0.96, opacity: 0),
                               identity: IslandBlurModifier(blur: 0, scale: 1, opacity: 1))
                .animation(IslandMetrics.paced(.easeOut(duration: 0.16))))
    }
}

private struct IslandBlurModifier: ViewModifier {
    let blur: CGFloat
    let scale: CGFloat
    let opacity: Double

    func body(content: Content) -> some View {
        content
            .blur(radius: blur)
            .scaleEffect(scale, anchor: .top)
            .opacity(opacity)
    }
}

/// The × in the island's right shoulder, beside the notch (shown on hover).
private struct IslandCloseButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("Dismiss", systemImage: "xmark")
        }
        .buttonStyle(IslandIconButtonStyle(size: 22, filled: false))
        .help("Dismiss")
    }
}

// MARK: - Shape

/// The notch's outline at any size: square top flush with the screen edge, concave ears that melt into the
/// bezel (as `NotchShape`), and continuous bottom corners (Apple's squircle curve, so the large island
/// corners read as smoothly as the hardware's). Radius and ears animate, so it morphs between sizes.
struct IslandShape: Shape {
    var radius: CGFloat
    var ear: CGFloat
    /// Leaves out the top edge (for drawing light along the rim).
    var open = false

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(radius, ear) }
        set {
            radius = newValue.first
            ear = newValue.second
        }
    }

    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        let e = max(0, min(ear, h / 2, w / 4))
        let bodyW = w - 2 * e
        // Apple's continuous corner reaches 1.528 r along each edge.
        let k: CGFloat = 1.52866483
        let r = max(0, min(radius, bodyW / 2 / k, (h - e) / k))
        let x0 = rect.minX, y0 = rect.minY
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x0 + x, y: y0 + y) }
        // bottom-left corner, in corner-local units: (dx from the left side, dy up from the bottom)
        func bl(_ dx: CGFloat, _ dy: CGFloat) -> CGPoint { pt(e + dx * r, h - dy * r) }
        func br(_ dx: CGFloat, _ dy: CGFloat) -> CGPoint { pt(w - e - dx * r, h - dy * r) }

        var p = Path()
        p.move(to: pt(0, 0))
        // left ear (concave) into the left side
        p.addQuadCurve(to: pt(e, e), control: pt(e, 0))
        p.addLine(to: bl(0, k))
        p.addCurve(to: bl(0.07491176, 0.63149399), control1: bl(0, 1.08849299), control2: bl(0, 0.86840701))
        p.addCurve(to: bl(0.63149399, 0.07491100), control1: bl(0.13491176, 0.39745705), control2: bl(0.39745705, 0.13491100))
        p.addLine(to: bl(0.66993427, 0.06549600))
        p.addCurve(to: bl(k, 0), control1: bl(0.86840689, 0), control2: bl(1.08849323, 0))
        p.addLine(to: br(k, 0))
        p.addCurve(to: br(0.66993427, 0.06549600), control1: br(1.08849323, 0), control2: br(0.86840689, 0))
        p.addLine(to: br(0.63149399, 0.07491100))
        p.addCurve(to: br(0.07491176, 0.63149399), control1: br(0.39745705, 0.13491100), control2: br(0.13491176, 0.39745705))
        p.addCurve(to: br(0, k), control1: br(0, 0.86840701), control2: br(0, 1.08849299))
        p.addLine(to: pt(w - e, e))
        // right ear
        p.addQuadCurve(to: pt(w, 0), control: pt(w - e, 0))
        if !open { p.closeSubpath() }
        return p
    }
}

/// Everything but the hardware notch (top centre of the rect), for masking with an even-odd fill.
struct NotchCutout: Shape {
    var notch: CGSize

    func path(in rect: CGRect) -> Path {
        var p = Path(rect)
        if notch.width > 0, notch.height > 0 {
            p.addRect(CGRect(x: rect.midX - notch.width / 2, y: rect.minY, width: notch.width, height: notch.height))
        }
        return p
    }
}

// MARK: - The light key

/// Light along the island's edge. Keying: from where the phone touches (the middle of the notch) light runs
/// out along the rim to both ears, again and again, while the notch looks for the phone's light. Accepted: the
/// whole rim flares and fades. Failed: it dims amber and goes out. Reduce Motion: a steady glow, no running.
struct IslandRim: View {
    var light: IslandLight
    var frame: IslandFrame
    /// Demo/QA: a frozen point in the sweep (0…1).
    var demoSweep: Double?
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        let rim = IslandShape(radius: frame.radius, ear: frame.ear, open: true)
        ZStack {
            switch light {
            case .off:
                EmptyView()
            case .keying:
                if reduceMotion {
                    glow(rim, color: TD.violet, intensity: 0.7)
                } else if let demoSweep {
                    Self.sweep(rim, x: demoSweep)
                } else {
                    Sweep(rim: rim)
                }
            case .accepted:
                glow(rim, color: TD.violet.mix(with: .white, by: 0.3), intensity: 1)
                    .transition(.asymmetric(insertion: .opacity.animation(.easeOut(duration: 0.12)),
                                            removal: .opacity.animation(.easeOut(duration: 0.8))))
            case .failed:
                glow(rim, color: TD.warning, intensity: 0.75)
                    .transition(.asymmetric(insertion: .opacity.animation(.easeOut(duration: 0.15)),
                                            removal: .opacity.animation(.easeOut(duration: 0.6))))
            }
        }
        .frame(width: frame.size.width + frame.ear * 2, height: frame.size.height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private static let period = 0.95

    /// The running sweep. Each key starts from the touch point, however brief it is.
    private struct Sweep: View {
        let rim: IslandShape
        @State private var start = Date()

        var body: some View {
            TimelineView(.animation(minimumInterval: 1 / 60)) { ctx in
                IslandRim.sweep(rim, x: (ctx.date.timeIntervalSince(start) / IslandRim.period).truncatingRemainder(dividingBy: 1))
            }
        }
    }

    private func glow(_ rim: IslandShape, color: Color, intensity: Double) -> some View {
        ZStack {
            rim.stroke(color.opacity(0.6 * intensity), lineWidth: 7).blur(radius: 7)
            rim.stroke(color.opacity(0.95 * intensity), lineWidth: 1.6).blur(radius: 0.5)
            rim.stroke(Color.white.opacity(0.6 * intensity), lineWidth: 0.7)
        }
    }

    /// `x` (0…1) through one pass. Two comets leave the middle of the rim, where the top of the phone's screen
    /// touches, and run out to the ears: quick at first, easing as they go, fading into the bezel at the end. A dim
    /// trail stays lit behind them, so the whole edge reads as light the notch is taking in.
    fileprivate static func sweep(_ rim: IslandShape, x: Double) -> some View {
        let travel = min(1, x / 0.74)
        let p = 1 - pow(1 - travel, 1.9)
        // fade in over the first moments; fade out as the comets turn the corners and climb into the ears,
        // so they melt into the bezel instead of parking there (then a beat of dark before the next pass)
        let a = smoothstep(0, 0.07, x) * (1 - smoothstep(0.66, 0.9, p))
        let half = 0.5 * p
        // the tail stretches while the comets are fast and never reaches back past the middle
        let tail = min(half, 0.05 + 0.1 * (1 - travel))
        let core = TD.violet.mix(with: TD.blue, by: 0.3)
        return ZStack {
            rim.stroke(TD.violet.opacity(0.14), lineWidth: 1)
            // what the comets have lit
            rim.trim(from: 0.5 - half, to: 0.5 + half)
                .stroke(TD.violet.opacity(0.5 * a), style: StrokeStyle(lineWidth: 6, lineCap: .round))
                .blur(radius: 6)
            rim.trim(from: 0.5 - half, to: 0.5 + half)
                .stroke(core.opacity(0.35 * a), style: StrokeStyle(lineWidth: 1.2, lineCap: .round))
            comet(rim, head: 0.5 - half, toward: 1, tail: tail, color: core, alpha: a)
            comet(rim, head: 0.5 + half, toward: -1, tail: tail, color: core, alpha: a)
        }
    }

    /// A comet along the rim: a white-hot head at `head`, its tail stretching `tail` (of the rim's length) in the
    /// direction `toward` (+1 = along the path, −1 = back), dimming to nothing.
    private static func comet(_ rim: IslandShape, head: Double, toward dir: Double, tail: Double, color: Color,
                              alpha a: Double) -> some View {
        let steps = 6
        func span(_ from: Double, _ to: Double) -> (Double, Double) {
            let lo = min(from, to), hi = max(from, to)
            return (max(0, lo), min(1, hi))
        }
        let whole = span(head, head + dir * tail)
        let tip = span(head, head + dir * min(tail, 0.012))
        return ZStack {
            // the glow around the whole comet
            rim.trim(from: whole.0, to: whole.1)
                .stroke(color.opacity(0.7 * a), style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .blur(radius: 4)
            // the tail, brightest at the head
            ForEach(0..<steps, id: \.self) { i in
                let s = span(head + dir * tail * Double(i) / Double(steps), head + dir * tail * Double(i + 1) / Double(steps))
                rim.trim(from: s.0, to: s.1)
                    .stroke(color.mix(with: .white, by: 0.55 * (1 - Double(i) / Double(steps)))
                        .opacity(a * pow(1 - Double(i) / Double(steps), 1.5)),
                            style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
            }
            // the head
            rim.trim(from: tip.0, to: tip.1)
                .stroke(Color.white.opacity(0.85 * a), style: StrokeStyle(lineWidth: 6, lineCap: .round))
                .blur(radius: 3)
            rim.trim(from: tip.0, to: tip.1)
                .stroke(Color.white.opacity(a), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
        }
    }

    private static func smoothstep(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
        let t = max(0, min(1, (x - e0) / (e1 - e0)))
        return t * t * (3 - 2 * t)
    }
}

/// The light the notch sees: a soft bloom spilling from under the notch while the phone lights it.
struct IslandBloom: View {
    var light: IslandLight
    var size: CGSize

    var body: some View {
        let tint: Color = light == .failed ? TD.warning : TD.violet
        Ellipse()
            .fill(RadialGradient(colors: [Color.white.opacity(light == .accepted ? 0.9 : 0.5), tint.opacity(0.55), tint.opacity(0)],
                                 center: .center, startRadius: 0, endRadius: size.width * 0.42))
            .frame(width: size.width * 1.05, height: max(46, size.height * 1.2))
            .blur(radius: 14)
            .opacity(light == .accepted ? 1 : 0.7)
            .offset(y: size.height * 0.45)
            .animation(.easeOut(duration: light == .accepted ? 0.15 : 0.4), value: light)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}
