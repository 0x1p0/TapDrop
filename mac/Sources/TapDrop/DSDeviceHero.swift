import SwiftUI

// MARK: - DeviceHero

/// The top of the Mac's display, with its notch, and the phone. Live, it teaches the gesture as you'd see it from
/// your seat: the phone's back (camera bar and all) rises to the notch, the top of its screen presses against it,
/// the screen's light spills out around the phone onto the display, and the notch answers with a little island.
/// The other states show the phone's screen, away from the notch.
///
///     DeviceHero(link: .live).frame(width: 112)       // height follows the container
///
/// Draw it against the top edge of a clipped container: the lid sits just below that edge and runs out of the
/// bottom. The drawing scales with its width (design canvas 120 pt wide; it needs about 100 pt of height to read).
struct DeviceHero: View {
    enum Link: Hashable {
        /// Connected and ready: the phone's back at the notch, its light spilling out. The approach plays on appear
        /// and on hover.
        case live
        /// Connected, but tapping isn't available (no light sensor, or Tap to Send is off): screen on, at rest.
        case connected
        /// Expected soon (starting, waking): dim screen, a little below the notch.
        case searching
        /// Paired but away: screen off.
        case offline
        /// This Mac can't be reached: screen off, a warning on the display.
        case error
        /// No phone yet: a QR code on its screen.
        case setup
    }

    var link: Link
    /// Plays the approach when the hero appears (live only). Hovering replays it.
    var animated = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var scheme
    @State private var plays = 0

    var body: some View {
        GeometryReader { g in
            let u = g.size.width / 120
            let tall = g.size.height / max(u, 0.01)
            Group {
                if link == .live && animated && !reduceMotion {
                    Color.clear
                        .phaseAnimator(Pose.allCases, trigger: plays) { _, pose in
                            scene(u, tall: tall, lift: pose.lift, glow: pose.glow, island: pose.island)
                        } animation: { pose in pose.animation }
                } else {
                    scene(u, tall: tall, lift: link == .live ? 1 : 0, glow: link == .live ? Pose.rest.glow : 0,
                          island: link == .live ? 1 : 0)
                }
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .top)
            // the lid runs out of the frame: let it melt into the card rather than end on a cut
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.56),
                                         .init(color: .black.opacity(0), location: 1)],
                                 startPoint: .top, endPoint: .bottom))
        }
        .frame(minHeight: 60)
        .onAppear {
            guard animated, link == .live, !reduceMotion else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { plays += 1 }
        }
        .onHover { h in
            if h, animated, link == .live, !reduceMotion { plays += 1 }
        }
        .accessibilityElement()
        .accessibilityLabel(accessibilityText)
    }

    private var accessibilityText: String {
        switch link {
        case .live: TapCopy.drawing
        case .connected: "A connected phone"
        case .searching, .offline: "A phone away from the notch"
        case .error: "This Mac is offline"
        case .setup: "A phone to set up"
        }
    }

    /// The approach: resting at the notch, lit, the island showing → the phone drops away and its light goes →
    /// it rises and touches, and its light floods the display → the notch answers (back to rest).
    private enum Pose: CaseIterable {
        case rest, away, touch

        var lift: CGFloat { self == .away ? 0 : 1 }
        var glow: Double {
            switch self {
            case .rest: 0.82
            case .away: 0
            case .touch: 1
            }
        }
        var island: CGFloat { self == .rest ? 1 : 0 }

        var animation: Animation {
            switch self {
            case .away: .easeInOut(duration: 0.6)
            case .touch: .spring(response: 0.55, dampingFraction: 0.82)
            case .rest: .spring(response: 0.6, dampingFraction: 0.7).delay(0.15)
            }
        }
    }

    // MARK: geometry (design units; the canvas is 120 wide)

    private static let lidTop: CGFloat = 7
    private static let lidInset: CGFloat = 3
    private static let lidRadius: CGFloat = 10
    /// Display edge, in from the lid's edge (the black glass border shows between).
    private static let bezel: CGFloat = 3.4
    private static var displayTop: CGFloat { lidTop + bezel }
    private static let notchSize = CGSize(width: 26, height: 7)
    private static var notchBottom: CGFloat { displayTop + notchSize.height }
    private static let phoneSize = CGSize(width: 40, height: 86)

    private func scene(_ u: CGFloat, tall: CGFloat, lift: CGFloat, glow: Double, island: CGFloat) -> some View {
        let restTop: CGFloat = switch link {
        case .live: Self.notchBottom + 34
        case .searching: 36
        case .setup, .connected: 31
        case .offline, .error: 42
        }
        let touchTop = Self.notchBottom - 0.5
        let top = link == .live ? restTop + (touchTop - restTop) * lift : restTop
        let display = RoundedRectangle(cornerRadius: (Self.lidRadius - Self.bezel + 1) * u, style: .continuous)
        return ZStack(alignment: .top) {
            lid(u, tall: tall)
            ZStack(alignment: .top) {
                wallpaper(u, tall: tall)
                if link == .live {
                    // the phone's screen light, washing over the display around it: brightest where the top of the
                    // screen meets the notch, spreading out to both sides
                    Ellipse()
                        .fill(RadialGradient(stops: [.init(color: .white, location: 0),
                                                     .init(color: Color(tdHex: 0xF1ECFF).opacity(0.8), location: 0.24),
                                                     .init(color: TD.violet.opacity(0.42), location: 0.55),
                                                     .init(color: TD.violet.opacity(0), location: 1)],
                                             center: .center, startRadius: 0, endRadius: 60 * u))
                        .scaleEffect(x: 1, y: 0.6)
                        .frame(width: 120 * u, height: 120 * u)
                        .offset(y: (Self.notchSize.height + 9 - 60) * u)
                        .opacity(glow)
                }
                if link == .error {
                    Image(systemName: "wifi.slash")
                        .font(.system(size: 11 * u, weight: .bold))
                        .foregroundStyle(TD.danger)
                        .offset(x: -34 * u, y: 16 * u)
                }
                // glass sheen
                LinearGradient(stops: [.init(color: .white.opacity(0.07), location: 0), .init(color: .clear, location: 0.4)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            }
            .frame(width: (120 - 2 * (Self.lidInset + Self.bezel)) * u, height: max(0, tall - Self.displayTop + 12) * u)
            .clipShape(display)
            .offset(y: Self.displayTop * u)

            if link == .live {
                // light escaping around the phone's edges, where its screen faces the display
                ZStack {
                    RoundedRectangle(cornerRadius: 8.5 * u, style: .continuous)
                        .stroke(Color(tdHex: 0xF4F0FF), lineWidth: 5 * u)
                        .blur(radius: 5 * u)
                    RoundedRectangle(cornerRadius: 8.5 * u, style: .continuous)
                        .stroke(Color.white, lineWidth: 1.4 * u)
                        .blur(radius: 0.9 * u)
                }
                .frame(width: Self.phoneSize.width * u, height: Self.phoneSize.height * u)
                .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black.opacity(0.55), location: 0.5),
                                             .init(color: .black.opacity(0.15), location: 1)],
                                     startPoint: .top, endPoint: .bottom).padding(-8 * u))
                .offset(y: top * u)
                .opacity(glow)
                phoneBack(u)
                    .offset(y: top * u)
            } else {
                phoneFront(u)
                    .offset(y: top * u)
            }
            notch(u, lit: link == .live ? glow : 0, island: island)
                .offset(y: Self.displayTop * u)
        }
        .frame(width: 120 * u, alignment: .top)
    }

    // MARK: the Mac

    private var dark: Bool { scheme == .dark }

    /// The top of the MacBook's lid: aluminium, a black glass border, its rounded top corners in frame; the rest
    /// runs out of the bottom.
    private func lid(_ u: CGFloat, tall: CGFloat) -> some View {
        let shape = RoundedRectangle(cornerRadius: Self.lidRadius * u, style: .continuous)
        let glass = RoundedRectangle(cornerRadius: (Self.lidRadius - 1.2) * u, style: .continuous)
        return ZStack {
            shape.fill(LinearGradient(colors: dark ? [Color(tdHex: 0x3A3C46), Color(tdHex: 0x23242B)]
                                                   : [Color(tdHex: 0xF1F2F5), Color(tdHex: 0xCDCFD7)],
                                      startPoint: .top, endPoint: .bottom))
            shape.strokeBorder(dark ? Color.white.opacity(0.16) : Color.white, lineWidth: 0.7 * u)
            glass.fill(Color(tdHex: 0x050507))
                .padding(1.2 * u)
                .padding(.bottom, -2 * u)
        }
        .frame(width: (120 - 2 * Self.lidInset) * u, height: max(0, tall - Self.lidTop + 20) * u)
        .shadow(color: .black.opacity(dark ? 0.35 : 0.14), radius: 6 * u, y: 2 * u)
        .offset(y: Self.lidTop * u)
    }

    /// A night wallpaper in the brand colours (the display is lit, whatever the room).
    private func wallpaper(_ u: CGFloat, tall: CGFloat) -> some View {
        let dim = link == .error || link == .offline ? 0.55 : 1.0
        return ZStack {
            LinearGradient(colors: dark ? [Color(tdHex: 0x0C0C18), Color(tdHex: 0x1B1440)]
                                        : [Color(tdHex: 0x141430), Color(tdHex: 0x2A1D5E)],
                           startPoint: .top, endPoint: .bottom)
            RadialGradient(colors: [TD.blue.opacity(0.5 * dim), .clear], center: UnitPoint(x: 0.1, y: 0.95),
                           startRadius: 0, endRadius: 80 * u)
            RadialGradient(colors: [TD.violet.opacity(0.5 * dim), .clear], center: UnitPoint(x: 0.75, y: 0.9),
                           startRadius: 0, endRadius: 84 * u)
            RadialGradient(colors: [TD.orchid.opacity(0.24 * dim), .clear], center: UnitPoint(x: 1.0, y: 0.45),
                           startRadius: 0, endRadius: 50 * u)
        }
    }

    /// The notch, black like the hardware. When the light counts it answers: a violet glow, and it grows into a
    /// little island with a check.
    private func notch(_ u: CGFloat, lit: Double, island: CGFloat) -> some View {
        let w = Self.notchSize.width * (1 + 0.72 * island)
        let h = Self.notchSize.height * (1 + 0.24 * island)
        let shape = NotchShape(ear: 1.8 * u, radius: (2.6 + 1.2 * island) * u)
        return ZStack(alignment: .top) {
            shape
                .stroke(TD.violet.opacity(0.95 * lit), lineWidth: 1.6 * u)
                .blur(radius: 2.2 * u)
            shape.fill(Color.black)
            Circle()
                .fill(Color(tdHex: 0x1C2238))
                .overlay(Circle().fill(TD.blue.opacity(0.5 * lit)))
                .frame(width: 2.2 * u, height: 2.2 * u)
                .offset(y: 2.4 * u)
            // what the island says: done, and a line of text
            HStack(spacing: 1.6 * u) {
                Image(systemName: "checkmark")
                    .font(.system(size: 2.4 * u, weight: .black))
                    .foregroundStyle(.white)
                    .frame(width: 4 * u, height: 4 * u)
                    .background(Circle().fill(TD.success))
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 0.8 * u) {
                    Capsule().fill(.white.opacity(0.9)).frame(width: 9 * u, height: 1.1 * u)
                    Capsule().fill(.white.opacity(0.45)).frame(width: 6 * u, height: 1 * u)
                }
            }
            .padding(.horizontal, 3.4 * u)
            .frame(width: w * u, height: h * u)
            .offset(y: 0.6 * u)
            .opacity(Double(max(0, island - 0.45) / 0.55))
        }
        .frame(width: w * u, height: h * u)
    }

    // MARK: the phone

    /// The phone from behind (its screen faces the Mac): a glass back with a soft sheen and a Pixel-style camera bar.
    private func phoneBack(_ u: CGFloat) -> some View {
        let w = Self.phoneSize.width, h = Self.phoneSize.height
        let body = RoundedRectangle(cornerRadius: 8.5 * u, style: .continuous)
        let back = dark ? Color(tdHex: 0x2A2B33) : Color(tdHex: 0xE8E6E1)
        let barTop = w * 0.15, barH = w * 0.19
        let lens = barH * 0.25
        return ZStack(alignment: .top) {
            body.fill(LinearGradient(stops: [.init(color: back.mix(with: .white, by: 0.08), location: 0),
                                             .init(color: back, location: 0.35),
                                             .init(color: back.mix(with: .black, by: 0.14), location: 1)],
                                     startPoint: .top, endPoint: .bottom))
            // glass, not a slab: a soft vertical sheen
            body.fill(LinearGradient(stops: [.init(color: .white.opacity(dark ? 0.1 : 0.22), location: 0),
                                             .init(color: .white.opacity(0.03), location: 0.2),
                                             .init(color: .clear, location: 0.55),
                                             .init(color: .black.opacity(0.14), location: 1)],
                                     startPoint: .leading, endPoint: .trailing))
            body.strokeBorder(LinearGradient(colors: [.white.opacity(dark ? 0.3 : 0.9), .white.opacity(dark ? 0.08 : 0.35)],
                                             startPoint: .top, endPoint: .bottom),
                              lineWidth: 0.8 * u)
            // the camera bar, across the back near the top
            ZStack {
                Rectangle()
                    .fill(LinearGradient(colors: dark ? [Color(tdHex: 0x5A5C67), Color(tdHex: 0x34363E)]
                                                      : [Color(tdHex: 0xD3D4D9), Color(tdHex: 0xB8B9C0)],
                                         startPoint: .top, endPoint: .bottom))
                    .overlay(alignment: .top) { Rectangle().fill(.white.opacity(dark ? 0.28 : 0.6)).frame(height: 0.5 * u) }
                ForEach([0.24, 0.42], id: \.self) { fx in
                    Circle()
                        .fill(RadialGradient(colors: [Color(tdHex: 0x2E3A58), Color(tdHex: 0x07080B)], center: .init(x: 0.4, y: 0.4),
                                             startRadius: 0, endRadius: lens * u))
                        .overlay(Circle().fill(.white.opacity(0.45)).frame(width: lens * 0.42 * u, height: lens * 0.42 * u)
                            .offset(x: -lens * 0.3 * u, y: -lens * 0.3 * u))
                        .background(Circle().fill(Color(tdHex: 0x050608)).padding(-lens * 0.28 * u))
                        .frame(width: lens * 2 * u, height: lens * 2 * u)
                        .position(x: w * fx * u, y: barH / 2 * u)
                }
                Circle()
                    .fill(Color(tdHex: 0xFFF4D6).opacity(0.8))
                    .frame(width: lens * 0.7 * u, height: lens * 0.7 * u)
                    .position(x: w * 0.6 * u, y: barH / 2 * u)
            }
            .frame(width: (w - 0.8) * u, height: barH * u)
            .offset(y: barTop * u)
        }
        .frame(width: w * u, height: h * u)
        .compositingGroup()
        .shadow(color: .black.opacity(0.35), radius: 2.5 * u, y: 1.5 * u)
    }

    /// The phone from the front: dark body, metal rim, punch-hole camera, side buttons, and its screen.
    private func phoneFront(_ u: CGFloat) -> some View {
        let w = Self.phoneSize.width, h = Self.phoneSize.height
        let body = RoundedRectangle(cornerRadius: 8.5 * u, style: .continuous)
        let screen = RoundedRectangle(cornerRadius: 6.6 * u, style: .continuous)
        let on = link == .connected || link == .setup
        return ZStack(alignment: .top) {
            if link == .connected {
                body.fill(TD.violet.opacity(0.35)).blur(radius: 10 * u).padding(-3 * u).offset(y: -2 * u)
            }
            // side buttons
            VStack(spacing: 2 * u) {
                Capsule().frame(width: 1.4 * u, height: 9 * u)
                Capsule().frame(width: 1.4 * u, height: 14 * u)
            }
            .foregroundStyle(Color(tdHex: 0x2A2A33))
            .frame(maxWidth: .infinity, alignment: .trailing)
            .offset(x: 0.9 * u, y: 17 * u)
            body.fill(Color(tdHex: 0x0C0C10))
            body.strokeBorder(LinearGradient(colors: [.white.opacity(0.42), .white.opacity(0.1), .white.opacity(0.06)],
                                             startPoint: .top, endPoint: .bottom),
                              lineWidth: 0.8 * u)
            screen
                .fill(screenFill)
                .padding(2.1 * u)
            if on {
                screen
                    .fill(LinearGradient(colors: [.white.opacity(0.45), .white.opacity(0)], startPoint: .top,
                                         endPoint: UnitPoint(x: 0.5, y: 0.2)))
                    .padding(2.1 * u)
            }
            Circle().fill(Color.black).frame(width: 3.4 * u, height: 3.4 * u).offset(y: 4.6 * u)
            screenContent(u)
                .frame(width: w * u, height: h * u)
        }
        .frame(width: w * u, height: h * u)
    }

    private var screenFill: AnyShapeStyle {
        switch link {
        case .live, .connected:
            AnyShapeStyle(LinearGradient(colors: [Color(tdHex: 0x8CC6FF), TD.blue, TD.violet, Color(tdHex: 0x5B34D6)],
                                         startPoint: .top, endPoint: .bottom))
        case .setup:
            AnyShapeStyle(LinearGradient(colors: [TD.blue, TD.violet], startPoint: .top, endPoint: .bottom))
        case .searching:
            AnyShapeStyle(LinearGradient(colors: [Color(tdHex: 0x2C2B45), Color(tdHex: 0x1A1A26)], startPoint: .top, endPoint: .bottom))
        case .offline, .error:
            AnyShapeStyle(LinearGradient(colors: [Color(tdHex: 0x22222B), Color(tdHex: 0x15151B)], startPoint: .top, endPoint: .bottom))
        }
    }

    @ViewBuilder private func screenContent(_ u: CGFloat) -> some View {
        switch link {
        case .setup:
            Image(systemName: "qrcode")
                .font(.system(size: 16 * u, weight: .medium))
                .foregroundStyle(.white)
                .padding(4.5 * u)
                .background(RoundedRectangle(cornerRadius: 4.5 * u, style: .continuous).fill(.white.opacity(0.18)))
                .offset(y: 11 * u)
        case .searching:
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 11 * u, weight: .semibold))
                .foregroundStyle(.white.opacity(0.45))
                .offset(y: 28 * u)
        case .offline:
            Image(systemName: "moon.fill")
                .font(.system(size: 9 * u, weight: .semibold))
                .foregroundStyle(.white.opacity(0.22))
                .offset(y: 29 * u)
        case .live, .connected, .error:
            EmptyView()
        }
    }
}
