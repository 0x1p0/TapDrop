import AppKit
import SwiftUI

// Illustrations and motion pieces: AuroraBackdrop, ProgressRing, CheckBurst, TransferGlyph (DeviceHero is in
// DSDeviceHero.swift).
// All of them honour Reduce Motion (springs become fades, loops stop).

// MARK: - AuroraBackdrop

/// A slow, low-contrast mesh gradient in the brand colors, for hero areas only.
/// It drifts for `drift` seconds after appearing and then rests (nothing loops forever).
///
///     AuroraBackdrop()                         // inside a card: .background { AuroraBackdrop() }
///     AuroraBackdrop(intensity: 0.6, drift: nil)
struct AuroraBackdrop: View {
    /// 0…1: how much brand color is mixed into the neutral background.
    var intensity: Double = 1
    /// Seconds of motion after appearing; nil keeps drifting while visible (use sparingly).
    var drift: TimeInterval? = 12

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date()
    @State private var resting = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion || resting)) { ctx in
            let t = reduceMotion ? 0 : ctx.date.timeIntervalSince(start)
            MeshGradient(width: 3, height: 3, points: points(t), colors: colors, smoothsColors: true)
        }
        .onAppear {
            start = Date()
            resting = false
        }
        .task {
            guard let drift else { return }
            try? await Task.sleep(for: .seconds(drift))
            resting = true
        }
        .accessibilityHidden(true)
    }

    private func points(_ t: Double) -> [SIMD2<Float>] {
        func w(_ speed: Double, _ phase: Double, _ amp: Double) -> Float { Float(sin(t * speed + phase) * amp) }
        return [
            [0, 0], [0.5 + w(0.31, 0, 0.12), 0], [1, 0],
            [0, 0.5 + w(0.27, 1, 0.14)], [0.5 + w(0.23, 2, 0.16), 0.5 + w(0.29, 3, 0.14)], [1, 0.5 + w(0.25, 4, 0.14)],
            [0, 1], [0.5 + w(0.21, 5, 0.12), 1], [1, 1],
        ]
    }

    private var colors: [Color] {
        // Lumen: no hues. The light spills from the top centre, like the notch's glow: cool white on night, white on
        // bone; the rest is the background.
        let dark = scheme == .dark
        let base: SIMD3<Double> = dark ? [0.031, 0.031, 0.039] : [0.937, 0.929, 0.906]
        let k = max(0, min(1, intensity))
        func mix(_ c: SIMD3<Double>, _ a: Double) -> Color {
            let m = base + (c - base) * (a * k)
            return Color(red: m.x, green: m.y, blue: m.z)
        }
        let light: SIMD3<Double> = [0.839, 0.871, 1.0]
        let white: SIMD3<Double> = [1, 1, 1]
        let s = dark ? 1.0 : 0.85
        return [
            mix(light, 0.07 * s), mix(white, 0.20 * s), mix(light, 0.07 * s),
            mix(base, 0), mix(light, 0.06 * s), mix(base, 0),
            mix(base, 0), mix(base, 0), mix(base, 0),
        ]
    }
}

// MARK: - ProgressRing

/// Transfer progress: an arc of the accent on a quiet track. `progress == nil` spins (indeterminate).
/// Put a symbol or a percentage inside with `.overlay`.
///
///     ProgressRing(progress: 0.42).frame(width: 36, height: 36)
struct ProgressRing: View {
    var progress: Double?
    var lineWidth: CGFloat = 3.5
    var track: Color = TD.fillStrong

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var spin = false

    var body: some View {
        let p = progress.map { max(0.02, min(1, $0)) }
        ZStack {
            Circle().stroke(track, lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: p ?? 0.28)
                .stroke(
                    AngularGradient(colors: [TD.blue, TD.violet], center: .center,
                                    startAngle: .degrees(0), endAngle: .degrees(360 * max(p ?? 0.28, 0.12))),
                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .rotationEffect(.degrees(progress == nil && spin ? 360 : 0))
                .animation(.easeOut(duration: 0.3), value: p)
                .animation(progress == nil && !reduceMotion ? .linear(duration: 1).repeatForever(autoreverses: false) : .default,
                           value: spin)
        }
        .padding(lineWidth / 2)
        .onAppear { spin = true }
        .accessibilityElement()
        .accessibilityLabel("Progress")
        .accessibilityValue(progress.map { "\(Int(($0 * 100).rounded())) percent" } ?? "In progress")
    }
}

// MARK: - CheckBurst

/// Success: a ring fills into a disc, a check draws itself, and a short glow bursts out (once, on appear).
///
///     CheckBurst().frame(width: 40, height: 40)
///     CheckBurst(tint: TD.violet, haptic: true)
struct CheckBurst: View {
    var tint: Color = TD.success
    /// The check's colour: the accent's ink, so it reads on bone (dark) and on ink (light).
    var mark: Color = TD.onAccent
    /// Plays a haptic tick when the check lands.
    var haptic = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var filled = false
    @State private var drawn = false
    @State private var burst = false

    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            ZStack {
                Circle().fill(tint.opacity(0.55))
                    .blur(radius: s * 0.22)
                    .scaleEffect(burst ? 1.55 : 0.7)
                    .opacity(burst ? 0 : (filled ? 0.9 : 0))
                Circle().stroke(tint.opacity(0.7), lineWidth: max(1, s * 0.04))
                    .scaleEffect(burst ? 1.75 : 1)
                    .opacity(burst ? 0 : (filled ? 0.8 : 0))
                Circle()
                    .stroke(tint, lineWidth: max(1.5, s * 0.08))
                    .padding(s * 0.04)
                    .opacity(filled ? 0 : 1)
                Circle()
                    .fill(LinearGradient(colors: [tint.mix(with: .white, by: 0.12), tint], startPoint: .top, endPoint: .bottom))
                    .scaleEffect(filled ? 1 : 0.4)
                    .opacity(filled ? 1 : 0)
                TDCheckShape()
                    .trim(from: 0, to: drawn ? 1 : 0)
                    .stroke(mark, style: StrokeStyle(lineWidth: max(1.6, s * 0.1), lineCap: .round, lineJoin: .round))
                    .padding(s * 0.25)
            }
            .frame(width: s, height: s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .onAppear(perform: play)
        .accessibilityElement()
        .accessibilityLabel("Done")
    }

    private func play() {
        if reduceMotion {
            withAnimation(TD.Motion.reduced) {
                filled = true
                drawn = true
            }
            return
        }
        withAnimation(.spring(response: 0.34, dampingFraction: 0.62)) { filled = true }
        withAnimation(.easeOut(duration: 0.3).delay(0.16)) { drawn = true }
        withAnimation(.easeOut(duration: 0.7).delay(0.2)) { burst = true }
        if haptic {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { TD.haptic() }
        }
    }
}

/// A check mark path in a unit square.
struct TDCheckShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX + r.width * 0.12, y: r.minY + r.height * 0.54))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.40, y: r.minY + r.height * 0.80))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.90, y: r.minY + r.height * 0.22))
        return p
    }
}

// MARK: - TransferGlyph

/// The leading visual of a transfer: progress ring around a symbol → check burst → or a failure mark.
/// Shared by the menu bar panel and the notch island.
///
///     TransferGlyph(phase: .progress(0.4), symbol: "photo.fill").frame(width: 40, height: 40)
struct TransferGlyph: View {
    enum Phase: Equatable {
        case progress(Double?)
        case success
        case failure
    }

    var phase: Phase
    var symbol = "arrow.down"

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shake = 0.0

    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            ZStack {
                switch phase {
                case .progress(let p):
                    ProgressRing(progress: p, lineWidth: max(2.5, s * 0.085))
                        .overlay {
                            Image(systemName: symbol)
                                .font(.system(size: s * 0.36, weight: .semibold))
                                .foregroundStyle(TD.text)
                                .contentTransition(.symbolEffect(.replace))
                                .symbolEffect(.pulse, options: .repeating, isActive: p == nil && !reduceMotion)
                        }
                        .transition(.scale(scale: 0.7).combined(with: .opacity))
                case .success:
                    CheckBurst()
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                case .failure:
                    ZStack {
                        Circle().fill(LinearGradient(colors: [TD.danger.mix(with: .white, by: 0.1), TD.danger],
                                                     startPoint: .top, endPoint: .bottom))
                        Image(systemName: "xmark")
                            .font(.system(size: s * 0.36, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .modifier(TDShake(amount: shake))
                    .onAppear { if !reduceMotion { withAnimation(.linear(duration: 0.45)) { shake = 1 } } }
                    .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
            .frame(width: s, height: s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .tdAnimation(TD.Motion.emphasized, value: phase)
    }
}

/// A horizontal "no" shake (decaying).
struct TDShake: GeometryEffect {
    var amount: Double
    var animatableData: Double {
        get { amount }
        set { amount = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 6 * sin(amount * .pi * 6) * (1 - amount), y: 0))
    }
}

/// The notch outline: square top (it hangs from an edge), rounded bottom corners, concave ears.
struct NotchShape: Shape {
    var ear: CGFloat
    var radius: CGFloat

    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        let e = min(ear, h / 2)
        let r = max(0, min(radius, (w - 2 * e) / 2, h - e))
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + w, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.minX + w - e, y: rect.minY + e), control: CGPoint(x: rect.minX + w - e, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + w - e, y: rect.minY + h - r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + w - e - r, y: rect.minY + h), control: CGPoint(x: rect.minX + w - e, y: rect.minY + h))
        p.addLine(to: CGPoint(x: rect.minX + e + r, y: rect.minY + h))
        p.addQuadCurve(to: CGPoint(x: rect.minX + e, y: rect.minY + h - r), control: CGPoint(x: rect.minX + e, y: rect.minY + h))
        p.addLine(to: CGPoint(x: rect.minX + e, y: rect.minY + e))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.minY), control: CGPoint(x: rect.minX + e, y: rect.minY))
        p.closeSubpath()
        return p
    }
}
