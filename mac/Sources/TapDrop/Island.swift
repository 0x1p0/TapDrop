import AppKit
import QuickLookThumbnailing
import SwiftUI
import UniformTypeIdentifiers

// A Dynamic-Island style panel that grows out of the MacBook notch (or the top
// edge of the screen on Macs without one) to show transfers.

@MainActor
final class IslandState: ObservableObject {
    @Published var expanded = false
    @Published var notch = CGSize(width: 190, height: 32)
    @Published var hasNotch = true
}

@MainActor
final class HUD {
    static let shared = HUD()
    static let canvas = CGSize(width: 600, height: 260)

    let state = IslandState()
    private var panel: NSPanel?
    private var generation = 0

    func show() {
        guard !TestMode.headless else { return }
        let panel = ensurePanel()
        generation += 1
        if !panel.isVisible { place(panel) }
        panel.ignoresMouseEvents = false
        panel.orderFrontRegardless()
        guard !state.expanded else { return }
        DispatchQueue.main.async {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.72)) { self.state.expanded = true }
        }
    }

    func hide(completion: @escaping () -> Void) {
        let gen = generation
        withAnimation(.spring(response: 0.42, dampingFraction: 0.88)) { state.expanded = false }
        panel?.ignoresMouseEvents = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) {
            guard gen == self.generation else { return }  // re-opened meanwhile
            self.panel?.orderOut(nil)
            completion()
        }
    }

    private func ensurePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(
            contentRect: NSRect(origin: .zero, size: Self.canvas),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        p.isMovable = false
        p.hidesOnDeactivate = false
        p.isReleasedWhenClosed = false
        let host = NSHostingView(rootView: IslandView(state: state, activity: Activity.shared))
        host.frame = NSRect(origin: .zero, size: Self.canvas)
        p.contentView = host
        panel = p
        return p
    }

    private func place(_ p: NSPanel) {
        guard let screen = NSScreen.screens.first else { return }
        let f = screen.frame
        if screen.safeAreaInsets.top > 0,
           let l = screen.auxiliaryTopLeftArea, let r = screen.auxiliaryTopRightArea {
            state.notch = CGSize(width: f.width - l.width - r.width, height: screen.safeAreaInsets.top)
            state.hasNotch = true
        } else {
            state.notch = CGSize(width: 180, height: 0)
            state.hasNotch = false
        }
        p.setFrame(
            NSRect(x: f.midX - Self.canvas.width / 2, y: f.maxY - Self.canvas.height,
                   width: Self.canvas.width, height: Self.canvas.height),
            display: false)
    }
}

// MARK: - Island view

struct IslandView: View {
    @ObservedObject var state: IslandState
    @ObservedObject var activity: Activity

    private var contentSize: CGSize { CGSize(width: 420, height: 84) }

    var body: some View {
        let top = state.hasNotch ? state.notch.height : 10
        let ear: CGFloat = state.expanded ? 14 : 6
        let width = state.expanded ? contentSize.width : state.notch.width
        let height = state.expanded ? top + contentSize.height : state.notch.height

        ZStack(alignment: .top) {
            IslandShape(radius: state.expanded ? 32 : min(10, height / 2), ear: ear)
                .fill(Color.black)
                .frame(width: width + ear * 2, height: height)
                .shadow(color: .black.opacity(state.expanded ? 0.5 : 0), radius: 24, y: 12)
                .opacity(state.hasNotch || state.expanded ? 1 : 0)

            if state.expanded && activity.phase != .idle {
                IslandContent(activity: activity)
                    .frame(width: contentSize.width - 44, height: contentSize.height - 8)
                    .padding(.top, top)
                    .transition(
                        .asymmetric(
                            insertion: .opacity.combined(with: .scale(scale: 0.5, anchor: .top))
                                .animation(.spring(response: 0.5, dampingFraction: 0.78).delay(0.06)),
                            removal: .opacity.combined(with: .scale(scale: 0.6, anchor: .top))
                                .animation(.easeIn(duration: 0.16))))
            }
        }
        .frame(width: HUD.canvas.width, height: HUD.canvas.height, alignment: .top)
        .animation(.spring(response: 0.45, dampingFraction: 0.78), value: contentSize)
        .onHover { activity.hovering = $0 }
    }
}

struct IslandShape: Shape {
    var radius: CGFloat
    var ear: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(radius, ear) }
        set { radius = newValue.first; ear = newValue.second }
    }

    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        let e = min(ear, h / 2)
        let r = max(0, min(radius, (w - 2 * e) / 2, h - e))
        var p = Path()
        p.move(to: .zero)
        p.addLine(to: CGPoint(x: w, y: 0))
        // concave "ears" melt the island into the bezel like the real notch
        p.addQuadCurve(to: CGPoint(x: w - e, y: e), control: CGPoint(x: w - e, y: 0))
        p.addLine(to: CGPoint(x: w - e, y: h - r))
        p.addCurve(to: CGPoint(x: w - e - r, y: h),
                   control1: CGPoint(x: w - e, y: h - r * 0.45), control2: CGPoint(x: w - e - r * 0.45, y: h))
        p.addLine(to: CGPoint(x: e + r, y: h))
        p.addCurve(to: CGPoint(x: e, y: h - r),
                   control1: CGPoint(x: e + r * 0.45, y: h), control2: CGPoint(x: e, y: h - r * 0.45))
        p.addLine(to: CGPoint(x: e, y: e))
        p.addQuadCurve(to: .zero, control: CGPoint(x: e, y: 0))
        p.closeSubpath()
        return p
    }
}

struct IslandContent: View {
    @ObservedObject var activity: Activity

    private var subtitle: String {
        if activity.phase == .active, activity.bytesTotal > 1 {
            let bytes = "\(activity.bytesDone.byteString) of \(activity.bytesTotal.byteString)"
            return activity.detail.isEmpty ? bytes : "\(activity.detail) · \(bytes)"
        }
        return activity.detail
    }

    var body: some View {
        if activity.kind == .otp && activity.phase == .done {
            CodeContent(activity: activity)
        } else {
            transferBody
        }
    }

    private var transferBody: some View {
        HStack(spacing: 14) {
            IslandGlyph(activity: activity)
                .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 3) {
                Text(activity.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(subtitle)
                    .font(.system(size: 12, weight: .medium))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 8)
            trailing
        }
        .frame(maxHeight: .infinity)
        .animation(.spring(response: 0.42, dampingFraction: 0.8), value: activity.phase)
    }

    @ViewBuilder private var trailing: some View {
        if activity.phase == .active, let p = activity.progress {
            Text("\(Int(p * 100))%")
                .font(.system(size: 20, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
                .transition(.opacity)
        } else if activity.phase == .done, !activity.files.isEmpty {
            HStack(spacing: 6) {
                if activity.files.count == 1 {
                    IslandButton(title: "Open", icon: nil, prominent: true) {
                        NSWorkspace.shared.open(activity.files[0])
                        activity.dismiss()
                    }
                }
                IslandButton(title: activity.files.count == 1 ? nil : "Show", icon: "folder") {
                    NSWorkspace.shared.activateFileViewerSelecting(activity.files)
                    activity.dismiss()
                }
                .help("Show in Finder")
            }
            .transition(.scale(scale: 0.8).combined(with: .opacity))
        } else if activity.phase == .done, let link = activity.link {
            IslandButton(title: "Open", icon: nil, prominent: true) {
                NSWorkspace.shared.open(link)
                activity.dismiss()
            }
            .transition(.scale(scale: 0.8).combined(with: .opacity))
        }
    }
}

struct IslandButton: View {
    let title: String?
    let icon: String?
    var prominent = false
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let icon { Image(systemName: icon) }
                if let title { Text(title) }
            }
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(prominent ? .black : .white)
            .padding(.horizontal, title == nil ? 0 : 13)
            .frame(minWidth: 30, minHeight: 30)
            .background(
                Capsule().fill(prominent ? Color.white.opacity(hover ? 1 : 0.92) : Color.white.opacity(hover ? 0.24 : 0.13)))
            .scaleEffect(hover ? 1.05 : 1)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) { hover = h } }
    }
}

// MARK: - glyphs

struct IslandGlyph: View {
    @ObservedObject var activity: Activity

    var body: some View {
        ZStack {
            switch activity.phase {
            case .active where activity.kind == .pick:
                ChoosingGlyph()
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            case .active:
                ActiveRing(progress: activity.progress, symbol: activity.symbol)
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
            case .done:
                doneGlyph.transition(.scale(scale: 0.5).combined(with: .opacity))
            case .failed:
                FailedGlyph().transition(.scale(scale: 0.5).combined(with: .opacity))
            case .idle:
                Color.clear
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.68), value: activity.phase)
    }

    @ViewBuilder private var doneGlyph: some View {
        switch activity.kind {
        case .queued:
            TapHintGlyph()
        case .paired:
            AnimatedCheck(tint: [Color(red: 0.35, green: 0.55, blue: 1), Color(red: 0.55, green: 0.35, blue: 1)])
        default:
            if !activity.files.isEmpty {
                ThumbStack(files: activity.files)
            } else {
                AnimatedCheck()
            }
        }
    }
}

struct ActiveRing: View {
    let progress: Double?
    let symbol: String
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.12), lineWidth: 4)
            Circle()
                .trim(from: 0, to: progress.map { max(0.02, $0) } ?? 0.28)
                .stroke(
                    AngularGradient(colors: [.cyan, Color(red: 0.3, green: 0.5, blue: 1), .cyan], center: .center),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.25), value: progress)
                .rotationEffect(.degrees(progress == nil && spin ? 360 : 0))
                .animation(progress == nil ? .linear(duration: 1).repeatForever(autoreverses: false) : .default, value: spin)
            Image(systemName: symbol)
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(.white)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.pulse, options: .repeating)
        }
        .padding(2)
        .background {
            Ripples(color: .cyan).frame(width: 86, height: 86).allowsHitTesting(false)
        }
        .onAppear { spin = true }
    }
}

/// The phone is showing its picker: a phone with a gently pulsing selection.
struct ChoosingGlyph: View {
    var body: some View {
        ZStack {
            Ripples(color: Brand.b, count: 2, period: 1.6).frame(width: 84, height: 84)
            Circle().fill(Color.white.opacity(0.08))
            Image(systemName: "iphone.gen3")
                .font(.system(size: 26, weight: .regular))
                .foregroundStyle(.white.opacity(0.9))
            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .strokeBorder(Brand.gradient, lineWidth: 2)
                .frame(width: 12, height: 9)
                .phaseAnimator([false, true]) { v, on in
                    v.scaleEffect(on ? 1.15 : 0.85).opacity(on ? 1 : 0.5)
                } animation: { _ in .easeInOut(duration: 0.7) }
        }
    }
}

/// AirDrop-style radar rings.
struct Ripples: View {
    var color: Color
    var count = 3
    var period = 1.8

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            Canvas { gc, size in
                for i in 0..<count {
                    let phase = (t / period + Double(i) / Double(count)).truncatingRemainder(dividingBy: 1)
                    let r = size.width / 2 * (0.55 + phase * 0.45)
                    let rect = CGRect(x: size.width / 2 - r, y: size.height / 2 - r, width: r * 2, height: r * 2)
                    gc.stroke(Path(ellipseIn: rect), with: .color(color.opacity((1 - phase) * 0.45)), lineWidth: 1.5)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct CheckShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX + r.width * 0.18, y: r.minY + r.height * 0.53))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.42, y: r.minY + r.height * 0.75))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.84, y: r.minY + r.height * 0.28))
        return p
    }
}

struct AnimatedCheck: View {
    var tint: [Color] = [Color(red: 0.2, green: 0.85, blue: 0.45), Color(red: 0.1, green: 0.7, blue: 0.4)]
    var lineWidth: CGFloat = 4.5
    @State private var pop = false
    @State private var draw = false

    var body: some View {
        ZStack {
            Circle()
                .fill(LinearGradient(colors: tint, startPoint: .topLeading, endPoint: .bottomTrailing))
                .shadow(color: tint[0].opacity(0.6), radius: pop ? 12 : 0)
                .scaleEffect(pop ? 1 : 0.3)
            GeometryReader { g in
                CheckShape()
                    .trim(from: 0, to: draw ? 1 : 0)
                    .stroke(.white, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round))
                    .padding(g.size.width * 0.22)
            }
        }
        .onAppear {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.55)) { pop = true }
            withAnimation(.easeOut(duration: 0.32).delay(0.14)) { draw = true }
        }
    }
}

struct FailedGlyph: View {
    @State private var shake = 0.0

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [.red, Color(red: 0.8, green: 0.1, blue: 0.2)],
                                         startPoint: .top, endPoint: .bottom))
            Image(systemName: "xmark").font(.system(size: 22, weight: .heavy)).foregroundStyle(.white)
        }
        .modifier(Shake(amount: shake))
        .onAppear { withAnimation(.linear(duration: 0.45)) { shake = 1 } }
    }
}

struct Shake: GeometryEffect {
    var amount: Double
    var animatableData: Double { get { amount } set { amount = newValue } }

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 7 * sin(amount * .pi * 6) * (1 - amount), y: 0))
    }
}

/// "Tap your phone on the Mac" hint: a phone bumping a laptop.
struct TapHintGlyph: View {
    var body: some View {
        ZStack {
            Circle().fill(Color.white.opacity(0.08))
            Image(systemName: "laptopcomputer")
                .font(.system(size: 22, weight: .regular))
                .foregroundStyle(.white.opacity(0.85))
                .offset(x: -6, y: 4)
            Image(systemName: "smartphone")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.cyan)
                .phaseAnimator([false, true]) { v, near in
                    v.offset(x: near ? 9 : 18, y: near ? -3 : -10).rotationEffect(.degrees(near ? -8 : -20))
                } animation: { near in near ? .spring(response: 0.35, dampingFraction: 0.5) : .easeInOut(duration: 0.7) }
        }
    }
}

/// Thumbnails of received files, fanned out like a little photo stack, with a check badge.
struct ThumbStack: View {
    let files: [URL]
    @State private var fanned = false

    var body: some View {
        let shown = Array(files.prefix(3))
        ZStack(alignment: .bottomTrailing) {
            ZStack {
                ForEach(Array(shown.enumerated()), id: \.offset) { i, url in
                    let k = Double(i) - Double(shown.count - 1) / 2
                    FileThumb(url: url)
                        .rotationEffect(.degrees(fanned ? k * 9 : 0))
                        .offset(x: fanned ? k * 5 : 0)
                        .zIndex(Double(i))
                }
            }
            AnimatedCheck(lineWidth: 2.6)
                .frame(width: 22, height: 22)
                .overlay(Circle().stroke(Color.black, lineWidth: 2))
                .offset(x: 6, y: 6)
        }
        .onAppear { withAnimation(.spring(response: 0.5, dampingFraction: 0.6).delay(0.1)) { fanned = true } }
    }
}

struct FileThumb: View {
    let url: URL
    var size: CGFloat = 50
    var radius: CGFloat = 11
    @State private var image: NSImage?

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else {
                Image(nsImage: NSWorkspace.shared.icon(forFile: url.path)).resizable().aspectRatio(contentMode: .fit)
                    .padding(4)
            }
        }
        .frame(width: size, height: size)
        .overlay {
            if UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) == true {
                Image(systemName: "play.fill")
                    .font(.system(size: size * 0.22, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: size * 0.42, height: size * 0.42)
                    .background(Circle().fill(.black.opacity(0.45)))
            }
        }
        .background(Color.white.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Color.white.opacity(0.16), lineWidth: 1))
        .task(id: url) {
            let req = QLThumbnailGenerator.Request(
                fileAt: url, size: CGSize(width: size * 2, height: size * 2), scale: 2, representationTypes: .thumbnail)
            if let rep = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: req) {
                withAnimation(.easeOut(duration: 0.25)) { image = rep.nsImage }
            }
        }
    }
}


// MARK: - one-time code

/// A code from the phone: key glyph, big digits dropping in one by one, source, copy button.
struct CodeContent: View {
    @ObservedObject var activity: Activity
    @State private var shown = false
    @State private var copied = false

    private var code: String { activity.title }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(LinearGradient(colors: [Color(red: 1, green: 0.76, blue: 0.29), Color(red: 1, green: 0.54, blue: 0.24)],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                Circle().fill(LinearGradient(colors: [.white.opacity(0.35), .clear], startPoint: .top, endPoint: .center))
                Image(systemName: "key.fill")
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(.white)
                    .rotationEffect(.degrees(shown ? -45 : 0))
                    .symbolEffect(.bounce, value: shown)
            }
            .frame(width: 50, height: 50)
            .shadow(color: .orange.opacity(0.5), radius: shown ? 12 : 0)
            .scaleEffect(shown ? 1 : 0.4)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 3) {
                    ForEach(Array(code.enumerated()), id: \.offset) { i, ch in
                        Text(String(ch))
                            .font(.system(size: 27, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(.white)
                            .padding(.leading, code.count == 6 && i == 3 ? 7 : 0)
                            .opacity(shown ? 1 : 0)
                            .offset(y: shown ? 0 : -14)
                            .blur(radius: shown ? 0 : 4)
                            .animation(.spring(response: 0.42, dampingFraction: 0.62).delay(0.08 + Double(i) * 0.045), value: shown)
                    }
                }
                Text(activity.detail)
                    .font(.system(size: 11.5, weight: .medium))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            IslandButton(title: copied ? "Copied" : "Copy", icon: copied ? "checkmark" : "doc.on.doc", prominent: true) {
                SecureClipboard.set(code)
                withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) { copied = true }
            }
            .contentTransition(.symbolEffect(.replace))
        }
        .frame(maxHeight: .infinity)
        .onAppear {
            withAnimation(.spring(response: 0.45, dampingFraction: 0.55)) { shown = true }
        }
    }
}
