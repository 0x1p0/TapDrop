import AppKit
import SwiftUI

enum Brand {
    static let a = Color(red: 0.25, green: 0.62, blue: 1.0)
    static let b = Color(red: 0.52, green: 0.38, blue: 1.0)
    static let c = Color(red: 0.95, green: 0.42, blue: 0.85)
    static var gradient: LinearGradient {
        LinearGradient(colors: [a, b], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

// MARK: - Panel content

struct PanelView: View {
    @ObservedObject var state: PanelState
    @ObservedObject var store = Store.shared
    let close: () -> Void

    private var targeted: Bool { state.dropTargeted }

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .reveal(0, state.visible)

            if store.devices.isEmpty {
                EmptyHero(close: close)
                    .padding(.vertical, 22)
                    .reveal(1, state.visible)
            } else {
                hero
                    .reveal(1, state.visible)
                actions
                    .padding(.horizontal, 16)
                    .reveal(2, state.visible)
                if !store.devices.isEmpty {
                    NotchHint(live: !store.liveDevices.isEmpty, notch: store.settings.notchTap && NotchSensor.shared.available) {
                        close()
                        SettingsWindow.shared.show(.tap)
                    }
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                        .reveal(2, state.visible)
                }
                if !store.outbox.isEmpty {
                    QueueShelf(store: store)
                        .padding(.horizontal, 12)
                        .padding(.top, 14)
                        .transition(.asymmetric(
                            insertion: .move(edge: .top).combined(with: .opacity),
                            removal: .opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                }
            }

            if !store.recent.isEmpty {
                RecentShelf(store: store, close: close)
                    .padding(.top, 18)
                    .reveal(3, state.visible)
            }
            Spacer().frame(height: 18)
        }
        .background(alignment: .top) { if state.visible { AmbientGlow(targeted: targeted) } }
        .overlay {
            if targeted {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .strokeBorder(Brand.gradient, lineWidth: 2)
                    .transition(.opacity)
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            let files = urls.filter(\.isFileURL)
            guard !files.isEmpty, !store.devices.isEmpty else { return false }
            ServiceProvider.queue(files)
            return true
        } isTargeted: { t in
            withAnimation(.spring(response: 0.35, dampingFraction: 0.7)) { state.dropTargeted = t }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.82), value: store.outbox.map(\.id))
        .animation(.spring(response: 0.45, dampingFraction: 0.82), value: store.recent.map(\.id))
        .animation(.spring(response: 0.5, dampingFraction: 0.8), value: store.devices.map(\.id))
    }

    private var header: some View {
        HStack(spacing: 9) {
            AppGlyph(size: 22)
            Text("TapDrop")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white.opacity(0.92))
            if store.status != "Ready" {
                Text(store.status)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(.orange.opacity(0.15)))
                    .transition(.scale.combined(with: .opacity))
            }
            Spacer()
            Button {
                close()
                SettingsWindow.shared.show()
            } label: {
                CircleIconLabel(systemName: "gearshape.fill")
            }
            .buttonStyle(.plain)
            .help("Settings (⌘,)")
        }
    }

    private var hero: some View {
        ZStack {
            if state.visible {
                RadarRings(targeted: targeted)
                    .frame(width: 340, height: 220)
            }
            HStack(spacing: 26) {
                ForEach(store.devices) { d in
                    DeviceHero(device: d, badge: store.outbox.count, targeted: targeted, compact: store.devices.count > 1,
                               live: store.liveDevices.contains(d.id))
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
        }
        .frame(height: 214)
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                PanelActions.chooseFiles(close: close)
            } label: {
                Label("Send Files", systemImage: "plus")
            }
            .buttonStyle(GlassButtonStyle(prominent: true))
            .help("Choose files to send (⌘O)")

            Button {
                PanelActions.queueClipboard()
            } label: {
                Label("Send Clipboard", systemImage: "doc.on.clipboard")
            }
            .buttonStyle(GlassButtonStyle())
            .help("Send what you copied (⌘V)")
        }
    }
}

// MARK: - Hero pieces

struct AppGlyph: View {
    var size: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.3, style: .continuous).fill(Brand.gradient)
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: size * 0.48, weight: .bold))
                .foregroundStyle(.white)
        }
        .frame(width: size, height: size)
        .shadow(color: Brand.b.opacity(0.5), radius: 4, y: 1)
    }
}

/// Two soft brand-colored glows behind the panel. Plain radial gradients, drawn once:
/// a drifting, re-blurred version cost ~10% CPU for as long as the panel was open.
private struct AmbientGlow: View {
    let targeted: Bool

    var body: some View {
        ZStack {
            Ellipse()
                .fill(RadialGradient(colors: [Brand.a, Brand.a.opacity(0.45), .clear], center: .center, startRadius: 0, endRadius: 150))
                .frame(width: 330, height: 250)
                .offset(x: -50, y: 70)
            Ellipse()
                .fill(RadialGradient(colors: [Brand.b, Brand.b.opacity(0.45), .clear], center: .center, startRadius: 0, endRadius: 140))
                .frame(width: 310, height: 240)
                .offset(x: 60, y: 90)
        }
        .opacity(targeted ? 0.75 : 0.38)
        .animation(.easeInOut(duration: 0.3), value: targeted)
        .allowsHitTesting(false)
    }
}

/// Concentric guide circles plus expanding pulses, AirDrop style. The pulses play for a few seconds
/// after the panel opens (and while files are dragged over it), then the rings rest.
private struct RadarRings: View {
    let targeted: Bool
    @State private var opened = Date()
    @State private var resting = false
    private static let liveFor: TimeInterval = 5.2
    private static let fade: TimeInterval = 0.8

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: resting && !targeted)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            let since = ctx.date.timeIntervalSince(opened)
            let pulses = targeted ? 1 : max(0, min(1, (Self.liveFor + Self.fade - since) / Self.fade))
            Canvas { gc, size in
                let c = CGPoint(x: size.width / 2, y: size.height / 2 - 18)
                for r in [62.0, 92.0, 124.0] {
                    let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
                    gc.stroke(Path(ellipseIn: rect), with: .color(.white.opacity(0.05)), lineWidth: 1)
                }
                guard pulses > 0 else { return }
                let period = targeted ? 1.1 : 2.6
                for i in 0..<3 {
                    let p = (t / period + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                    let r = 44 + p * 110
                    let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
                    let alpha = (1 - p) * (targeted ? 0.55 : 0.28) * pulses
                    gc.stroke(Path(ellipseIn: rect),
                              with: .linearGradient(
                                Gradient(colors: [Brand.a.opacity(alpha), Brand.b.opacity(alpha)]),
                                startPoint: CGPoint(x: rect.minX, y: rect.minY),
                                endPoint: CGPoint(x: rect.maxX, y: rect.maxY)),
                              lineWidth: targeted ? 2 : 1.5)
                }
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            opened = Date()
            resting = false
        }
        .task {
            try? await Task.sleep(for: .seconds(Self.liveFor + Self.fade + 0.1))
            resting = true
        }
    }
}

private struct DeviceHero: View {
    let device: PairedDevice
    let badge: Int
    let targeted: Bool
    let compact: Bool
    let live: Bool
    @State private var hover = false

    private var online: Bool { live || (device.lastSeen.map { Date().timeIntervalSince($0) < 120 } ?? false) }
    private var size: CGFloat { compact ? 64 : 78 }

    var body: some View {
        VStack(spacing: 12) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    Circle().fill(Brand.gradient)
                    Circle().fill(LinearGradient(colors: [.white.opacity(0.38), .clear], startPoint: .top, endPoint: .center))
                    Image(systemName: targeted ? "arrow.down" : "smartphone")
                        .font(.system(size: size * 0.38, weight: .semibold))
                        .foregroundStyle(.white)
                        .contentTransition(.symbolEffect(.replace.downUp))
                }
                .frame(width: size, height: size)
                .overlay(Circle().strokeBorder(.white.opacity(0.28), lineWidth: 1))
                .overlay(alignment: .bottomTrailing) {
                    if online {
                        Circle().fill(Color.green)
                            .frame(width: 14, height: 14)
                            .overlay(Circle().strokeBorder(Color.black.opacity(0.6), lineWidth: 2.5))
                            .offset(x: -2, y: -2)
                    }
                }
                .shadow(color: Brand.b.opacity(targeted ? 0.85 : 0.5), radius: targeted ? 30 : 16)
                .scaleEffect(targeted ? 1.14 : (hover ? 1.04 : 1))

                if badge > 0 {
                    Text("\(badge)")
                        .font(.system(size: 12, weight: .bold, design: .rounded))
                        .foregroundStyle(Brand.b)
                        .contentTransition(.numericText(value: Double(badge)))
                        .padding(.horizontal, 7)
                        .frame(minWidth: 23, minHeight: 23)
                        .background(Capsule().fill(.white))
                        .shadow(color: .black.opacity(0.3), radius: 4, y: 2)
                        .offset(x: 6, y: -4)
                        .transition(.scale(scale: 0.3).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.6), value: targeted)
            .animation(.spring(response: 0.4, dampingFraction: 0.6), value: badge)

            VStack(spacing: 3) {
                Text(targeted ? "Drop to send" : device.name)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                    .contentTransition(.interpolate)
                TimelineView(.periodic(from: .now, by: 30)) { _ in
                    HStack(spacing: 5) {
                        Text(statusText)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(.white.opacity(0.5))
                    }
                }
            }
            .animation(.easeOut(duration: 0.2), value: targeted)
        }
        .onHover { h in withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { hover = h } }
        .help(device.name)
    }

    private var statusText: String {
        if targeted { return "to \(device.name)" }
        if live { return "Ready to tap" }
        if online { return "Nearby" }
        guard let s = device.lastSeen else { return "Paired" }
        return "Seen \(ShortTime.string(s))"
    }
}

private struct EmptyHero: View {
    let close: () -> Void
    @State private var float = false

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Circle()
                    .strokeBorder(.white.opacity(0.18), style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                    .frame(width: 84, height: 84)
                Image(systemName: "smartphone")
                    .font(.system(size: 30, weight: .light))
                    .foregroundStyle(Brand.gradient)
                    .offset(y: float ? -4 : 3)
                    .animation(.easeInOut(duration: 1.8).repeatForever(autoreverses: true), value: float)
            }
            VStack(spacing: 5) {
                Text("Connect your phone")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
                Text("Install TapDrop on your Android phone\nand pair in about 30 seconds.")
                    .font(.system(size: 12))
                    .foregroundStyle(.white.opacity(0.55))
                    .multilineTextAlignment(.center)
            }
            Button {
                close()
                SetupWindow.shared.show()
            } label: {
                Label("Get Started", systemImage: "qrcode")
            }
            .buttonStyle(GlassButtonStyle(prominent: true))
            .frame(width: 170)
            .padding(.top, 4)
        }
        .onAppear { float = true }
    }
}

/// "Hold your phone over the notch" — the NFC-less tap.
private struct NotchHint: View {
    let live: Bool
    let notch: Bool
    let open: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: open) { content }
            .buttonStyle(.plain)
            .onHover { hover = $0 }
            .help("Tap to Send settings and recent taps")
    }

    private var content: some View {
        HStack(spacing: 10) {
            ZStack {
                // a phone settling onto the Mac
                RoundedRectangle(cornerRadius: 3, style: .continuous)
                    .strokeBorder(.white.opacity(0.5), lineWidth: 1.5)
                    .frame(width: 24, height: 15)
                    .offset(y: 5)
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Brand.gradient)
                    .phaseAnimator([false, true]) { v, down in
                        v.offset(y: down ? 2 : -6)
                    } animation: { _ in .easeInOut(duration: 0.9) }
            }
            .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 1) {
                Text(!live ? "Wake your phone to tap" : "Ready — tap your phone on its spot")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.85))
                Text(!live ? "Tap to send connects while its screen is on" : (notch ? "Its magnet spot on this Mac, or over the notch in a lit room" : "The magnet spot you found on this Mac, in any light"))
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.5))
            }
            Spacer()
            if live {
                PulseDot(color: .green, size: 7)
            } else {
                Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold)).foregroundStyle(.white.opacity(0.3))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white.opacity(hover ? 0.09 : 0.06)))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.07), lineWidth: 1))
        .animation(.easeInOut(duration: 0.25), value: live)
    }
}

// MARK: - Queue shelf (waiting for the phone)

private struct QueueShelf: View {
    @ObservedObject var store: Store

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Waiting for your phone")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.75))
                Spacer()
                Button("Clear") { store.clearOutbox() }
                    .buttonStyle(.plain)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.45))
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(store.outbox) { item in
                        QueueTile(item: item) { store.dequeue([item.id]) }
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                }
                .padding(.vertical, 4)
                .padding(.horizontal, 2)
            }
            .mask(
                HStack(spacing: 0) {
                    Color.black
                    LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: 18)
                })
            HStack(spacing: 7) {
                TapHintMini()
                Text("Tap your phone on the Mac, or open TapDrop on it")
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.5))
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(.white.opacity(0.06)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.white.opacity(0.07), lineWidth: 1))
    }
}

private struct QueueTile: View {
    let item: OutboxItem
    let remove: () -> Void
    @State private var hover = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let path = item.path {
                    FileThumb(url: URL(fileURLWithPath: path), size: 50, radius: 12)
                } else {
                    TextTile(text: item.text ?? "", size: 50)
                }
            }
            .scaleEffect(hover ? 1.05 : 1)
            if hover {
                Button(action: remove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .heavy))
                        .foregroundStyle(.black)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(.white))
                        .shadow(radius: 2)
                }
                .buttonStyle(.plain)
                .offset(x: 5, y: -5)
                .transition(.scale.combined(with: .opacity))
            }
        }
        .onHover { h in withAnimation(.spring(response: 0.25, dampingFraction: 0.7)) { hover = h } }
        .help(item.displayName)
    }
}

// MARK: - Recent shelf

private struct RecentShelf: View {
    @ObservedObject var store: Store
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Recent")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.75))
                Spacer()
                Button {
                    close()
                    NSWorkspace.shared.open(store.saveFolder)
                } label: {
                    HStack(spacing: 3) {
                        Text(store.saveFolder.lastPathComponent)
                        Image(systemName: "arrow.up.right")
                    }
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.45))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 18)

            HStack(alignment: .top, spacing: 0) {
                ForEach(Array(store.recent.prefix(4).enumerated()), id: \.element.id) { i, e in
                    if i > 0 { Spacer(minLength: 0) }
                    RecentCard(event: e)
                        .transition(.scale(scale: 0.5, anchor: .leading).combined(with: .opacity))
                }
                if store.recent.count < 4 { Spacer(minLength: 0) }
            }
            .padding(.horizontal, 18)
        }
    }
}

private struct RecentCard: View {
    let event: RecentEvent
    @State private var hover = false

    var body: some View {
        Button(action: open) {
            VStack(spacing: 6) {
                ZStack(alignment: .bottomLeading) {
                    Group {
                        if let f = event.files.first {
                            FileThumb(url: f, size: 62, radius: 14)
                        } else {
                            TextTile(text: event.text ?? event.summary, size: 62)
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        if event.files.count > 1 {
                            Text("\(event.files.count)")
                                .font(.system(size: 10, weight: .bold, design: .rounded))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 5)
                                .frame(minWidth: 18, minHeight: 18)
                                .background(Capsule().fill(.black.opacity(0.65)))
                                .offset(x: -4, y: 4)
                        }
                    }
                    Image(systemName: event.incoming ? "arrow.down" : "arrow.up")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(.white)
                        .frame(width: 19, height: 19)
                        .background(Circle().fill(event.incoming ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(Color.green)))
                        .overlay(Circle().strokeBorder(.black.opacity(0.5), lineWidth: 2))
                        .offset(x: -5, y: 5)
                }
                .shadow(color: .black.opacity(hover ? 0.4 : 0), radius: 8, y: 4)
                .scaleEffect(hover ? 1.06 : 1)

                Text(event.summary)
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(hover ? 0.85 : 0.55))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(width: 66)
            }
        }
        .buttonStyle(.plain)
        .onHover { h in withAnimation(.spring(response: 0.28, dampingFraction: 0.7)) { hover = h } }
        .help("\(event.incoming ? "From" : "To") \(event.peer) · \(ShortTime.string(event.date))")
    }

    private func open() {
        if !event.files.isEmpty {
            NSWorkspace.shared.activateFileViewerSelecting(event.files)
        } else if let t = event.text {
            if let u = URL(string: t), u.scheme?.hasPrefix("http") == true {
                NSWorkspace.shared.open(u)
            } else {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(t, forType: .string)
            }
        }
    }
}

struct TextTile: View {
    let text: String
    var size: CGFloat

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var link: URL? {
        guard let u = URL(string: trimmed), u.scheme?.hasPrefix("http") == true, u.host != nil else { return nil }
        return u
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: size * 0.23, style: .continuous)
                .fill(LinearGradient(colors: [.white.opacity(0.16), .white.opacity(0.06)], startPoint: .top, endPoint: .bottom))
            VStack(alignment: .leading, spacing: size * 0.06) {
                Image(systemName: link != nil ? "link" : "text.alignleft")
                    .font(.system(size: size * 0.2, weight: .bold))
                    .foregroundStyle(Brand.gradient)
                Text(link?.host?.replacingOccurrences(of: "www.", with: "") ?? String(trimmed.prefix(60)))
                    .font(.system(size: max(8, size * 0.15), weight: .medium))
                    .foregroundStyle(.white.opacity(0.8))
                    .lineLimit(link != nil ? 2 : 3)
                    .multilineTextAlignment(.leading)
            }
            .padding(size * 0.13)
        }
        .frame(width: size, height: size)
        .overlay(RoundedRectangle(cornerRadius: size * 0.23, style: .continuous).strokeBorder(.white.opacity(0.12), lineWidth: 1))
    }
}

// MARK: - Actions & helpers

@MainActor
enum PanelActions {
    static func chooseFiles(close: @escaping () -> Void) {
        close()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            NSApp.activate(ignoringOtherApps: true)
            let panel = NSOpenPanel()
            panel.allowsMultipleSelection = true
            panel.canChooseDirectories = true
            panel.prompt = "Send to Phone"
            panel.message = "Choose files or folders to send to your phone"
            if panel.runModal() == .OK, !panel.urls.isEmpty { ServiceProvider.queue(panel.urls) }
        }
    }

    static func queueClipboard() {
        let store = Store.shared
        switch ClipboardSnapshot.read() {
        case .files(let urls):
            ServiceProvider.queue(urls)
        case .image(let png):
            let url = OutgoingFiles.tempDir().appendingPathComponent("Mac Clipboard.png")
            if (try? png.write(to: url)) != nil { ServiceProvider.queue([url]) }
        case .text(let s):
            store.enqueue(text: s)
            Activity.shared.notice(.queued, title: "Ready to send text", detail: "Tap your phone on the Mac to receive",
                                   sound: false, hideAfter: 3.5)
        case .empty:
            NSSound.beep()
        }
    }
}

enum ShortTime {
    static func string(_ d: Date) -> String {
        let s = Date().timeIntervalSince(d)
        if s < 60 { return "just now" }
        if s < 3600 { return "\(Int(s / 60))m ago" }
        if s < 86400 { return "\(Int(s / 3600))h ago" }
        if s < 172_800 { return "yesterday" }
        return d.formatted(.dateTime.month(.abbreviated).day())
    }
}

struct PulseDot: View {
    var color: Color
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .background(
                Circle().fill(color.opacity(0.5))
                    .phaseAnimator([false, true]) { v, on in
                        v.scaleEffect(on ? 2.4 : 1).opacity(on ? 0 : 0.7)
                    } animation: { on in on ? .easeOut(duration: 1.4) : .linear(duration: 0.01) })
    }
}

struct TapHintMini: View {
    var body: some View {
        Image(systemName: "wave.3.right")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(Brand.gradient)
            .symbolEffect(.variableColor.iterative, options: .repeating)
    }
}

extension View {
    /// Staggered entrance each time the panel opens.
    func reveal(_ index: Int, _ on: Bool) -> some View {
        self
            .opacity(on ? 1 : 0)
            .offset(y: on ? 0 : 10)
            .animation(.spring(response: 0.5, dampingFraction: 0.8).delay(on ? 0.05 + Double(index) * 0.045 : 0), value: on)
    }
}
