import AppKit
import SwiftUI

/// Bluetooth-style numeric comparison: shows the 6-digit code and asks the user to approve.
@MainActor
enum PairPrompt {
    private static var open: [PairWindowController] = []

    static func askDemo() async -> Bool {
        await withCheckedContinuation { cont in
            let c = PairWindowController(device: "Android phone", code: "482915") { ok in cont.resume(returning: ok) }
            open.append(c)
            c.onClose = { open.removeAll { $0 === c } }
            c.show()
        }
    }

    static func ask(device: String, code: String, conn: Conn) async -> Bool {
        await withCheckedContinuation { cont in
            let c = PairWindowController(device: device, code: code) { ok in cont.resume(returning: ok) }
            open.append(c)
            c.onClose = { open.removeAll { $0 === c } }
            c.show()
            // Phone hung up while we were asking → withdraw the prompt.
            Task.detached {
                _ = try? await conn.read(1)
                await MainActor.run { c.finish(false) }
            }
            Task {
                try? await Task.sleep(for: .seconds(120))
                c.finish(false)
            }
        }
    }
}

@MainActor
final class PairPromptModel: ObservableObject {
    @Published var approved = false
}

@MainActor
final class PairWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var result: ((Bool) -> Void)?
    private let model = PairPromptModel()
    private let device: String
    private let code: String
    var onClose: (() -> Void)?

    init(device: String, code: String, result: @escaping (Bool) -> Void) {
        self.device = device
        self.code = code
        self.result = result
    }

    func show() {
        let w = GlassWindow.make(size: NSSize(width: 400, height: 392), root: PairView(
            device: device, code: code, model: model,
            onPair: { [weak self] in self?.approve() },
            onCancel: { [weak self] in self?.finish(false) }))
        w.level = .floating
        w.delegate = self
        w.center()
        window = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    private func approve() {
        guard result != nil else { return }
        model.approved = true
        let r = result
        result = nil
        r?(true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) { self.window?.close() }
    }

    func finish(_ ok: Bool) {
        guard let r = result else { return }
        result = nil
        r(ok)
        window?.close()
    }

    func windowWillClose(_ notification: Notification) {
        finish(false)
        onClose?()
    }
}

struct PairView: View {
    let device: String
    let code: String
    @ObservedObject var model: PairPromptModel
    let onPair: () -> Void
    let onCancel: () -> Void

    @State private var shown = false

    var body: some View {
        VStack(spacing: 18) {
            ZStack {
                if model.approved {
                    AnimatedCheck(tint: [Brand.a, Brand.b], lineWidth: 6)
                        .frame(width: 84, height: 84)
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                } else {
                    LinkAnimation()
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                }
            }
            .frame(height: 88)
            .padding(.top, 30)

            VStack(spacing: 6) {
                Text(model.approved ? "Paired" : "Pair with “\(device)”?")
                    .font(.system(size: 21, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .contentTransition(.interpolate)
                Text(model.approved
                     ? "You can now share from your phone to this Mac."
                     : "Make sure your phone shows the same code.")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.55))
                    .multilineTextAlignment(.center)
                    .contentTransition(.interpolate)
            }

            HStack(spacing: 8) {
                ForEach(Array(code.enumerated()), id: \.offset) { i, ch in
                    Text(String(ch))
                        .font(.system(size: 30, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .frame(width: 42, height: 54)
                        .foregroundStyle(.white)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(LinearGradient(colors: [.white.opacity(0.12), .white.opacity(0.05)], startPoint: .top, endPoint: .bottom)))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(model.approved ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(Color.white.opacity(0.12)), lineWidth: model.approved ? 2 : 1))
                        .padding(.leading, i == 3 ? 10 : 0)
                        .opacity(shown ? 1 : 0)
                        .offset(y: shown ? 0 : 14)
                        .scaleEffect(shown ? 1 : 0.7)
                        .animation(.spring(response: 0.45, dampingFraction: 0.62).delay(0.15 + Double(i) * 0.06), value: shown)
                }
            }

            Spacer(minLength: 8)

            HStack(spacing: 10) {
                Button("Don’t Pair", action: onCancel)
                    .buttonStyle(GlassButtonStyle())
                    .keyboardShortcut(.cancelAction)
                Button("Pair", action: onPair)
                    .buttonStyle(GlassButtonStyle(prominent: true))
                    .keyboardShortcut(.defaultAction)
            }
            .disabled(model.approved)
            .opacity(model.approved ? 0 : 1)
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
        }
        .frame(width: 400, height: 392)
        .background(alignment: .top) {
            Ellipse().fill(Brand.gradient).frame(width: 300, height: 180).blur(radius: 70).opacity(0.45).offset(y: -20)
        }
        .environment(\.colorScheme, .dark)
        .animation(.spring(response: 0.5, dampingFraction: 0.75), value: model.approved)
        .onAppear { shown = true }
    }
}

/// Phone and laptop with packets flowing between them.
struct LinkAnimation: View {
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "smartphone")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(Brand.gradient)
            TimelineView(.animation(minimumInterval: 1 / 30)) { ctx in
                let t = ctx.date.timeIntervalSinceReferenceDate
                HStack(spacing: 7) {
                    ForEach(0..<5) { i in
                        let phase = (t * 1.6 - Double(i) * 0.18).truncatingRemainder(dividingBy: 1.6)
                        let lit = max(0, 1 - abs(phase - 0.5) * 2.2)
                        Circle()
                            .fill(Brand.gradient)
                            .frame(width: 7, height: 7)
                            .scaleEffect(0.6 + lit * 0.6)
                            .opacity(0.25 + lit * 0.75)
                    }
                }
            }
            Image(systemName: "laptopcomputer")
                .font(.system(size: 46, weight: .light))
                .foregroundStyle(Brand.gradient)
        }
    }
}


/// Dark glass window with a hidden titlebar (traffic lights stay for closing).
@MainActor
enum GlassWindow {
    static func make<V: View>(size: NSSize, root: V) -> NSWindow {
        let w = NSWindow(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.titled, .closable, .fullSizeContentView],
            backing: .buffered, defer: false)
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.isMovableByWindowBackground = true
        w.isReleasedWhenClosed = false
        w.appearance = NSAppearance(named: .darkAqua)
        w.isOpaque = false
        w.backgroundColor = .clear
        let fx = NSVisualEffectView(frame: NSRect(origin: .zero, size: size))
        fx.material = .hudWindow
        fx.blendingMode = .behindWindow
        fx.state = .active
        // A dark wash over the blur keeps text readable on light wallpapers.
        let tint = NSView(frame: fx.bounds)
        tint.wantsLayer = true
        tint.layer?.backgroundColor = NSColor.black.withAlphaComponent(0.34).cgColor
        tint.autoresizingMask = [.width, .height]
        fx.addSubview(tint)
        let host = NSHostingView(rootView: root)
        host.frame = fx.bounds
        host.autoresizingMask = [.width, .height]
        fx.addSubview(host)
        w.contentView = fx
        w.center()
        return w
    }
}
