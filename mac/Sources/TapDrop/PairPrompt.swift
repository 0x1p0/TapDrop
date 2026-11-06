import AppKit
import SwiftUI

/// Bluetooth-style numeric comparison: shows the 6-digit code and asks the user to approve.
@MainActor
enum PairPrompt {
    private static var open: [PairWindowController] = []

    static func askDemo() async -> Bool {
        await withCheckedContinuation { cont in
            present(device: "Android phone", code: "482915") { ok in cont.resume(returning: ok) }
        }
    }

    static func ask(device: String, code: String, conn: Conn) async -> Bool {
        await withCheckedContinuation { cont in
            let c = present(device: device, code: code) { ok in cont.resume(returning: ok) }
            // Phone hung up while we were asking → withdraw the prompt.
            Task.detached {
                _ = try? await conn.read(1)
                await MainActor.run { c.withdraw(.cancelled) }
            }
            Task {
                try? await Task.sleep(for: .seconds(120))
                c.withdraw(.expired)
            }
        }
    }

    @discardableResult
    private static func present(device: String, code: String, result: @escaping (Bool) -> Void) -> PairWindowController {
        let c = PairWindowController(device: device, code: code, result: result)
        open.append(c)
        c.onClose = { open.removeAll { $0 === c } }
        c.show()
        return c
    }

    /// Demo/QA states: approved, withdrawn, expired, long (a long phone name).
    static func demo(_ mode: String) {
        let long = mode == "long"
        let c = present(device: long ? "Galaxy S24 Ultra (Work phone)" : "Android phone", code: "482915") { _ in }
        switch mode {
        case "approved": DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { c.approveForDemo() }
        case "withdrawn": DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { c.withdraw(.cancelled, hold: true) }
        case "expired": DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { c.withdraw(.expired, hold: true) }
        default: break
        }
    }
}

@MainActor
final class PairPromptModel: ObservableObject {
    enum Stage: Equatable {
        case asking
        case paired
        /// The phone hung up, or nobody answered in time. Nothing was paired.
        case withdrawn(Withdrawal)
    }

    enum Withdrawal: Equatable { case cancelled, expired }

    let device: String
    let code: String
    @Published var stage: Stage = .asking
    /// Drives the entrance and exit.
    @Published var visible = false
    /// Return pairs only once the prompt has been on screen for a moment, so a Return meant for whatever the
    /// user was typing in can't approve a phone they haven't looked at. (Clicking Pair works right away.)
    @Published var armed = false

    init(device: String, code: String) {
        self.device = device
        self.code = code
    }
}

@MainActor
final class PairWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var result: ((Bool) -> Void)?
    private let model: PairPromptModel
    private var closing = false
    var onClose: (() -> Void)?

    init(device: String, code: String, result: @escaping (Bool) -> Void) {
        model = PairPromptModel(device: device, code: code)
        self.result = result
    }

    func show() {
        let root = PairRoot(model: model,
                            onPair: { [weak self] in self?.approve() },
                            onDecline: { [weak self] in self?.decline() },
                            onClose: { [weak self] in self?.close() })
        let w = PairWindow(contentRect: NSRect(origin: .zero, size: PairRoot.windowSize),
                           styleMask: [.borderless], backing: .buffered, defer: false)
        w.isOpaque = false
        w.backgroundColor = .clear
        w.hasShadow = false  // the glass carries its own
        w.level = .floating
        w.isReleasedWhenClosed = false
        w.isMovableByWindowBackground = true
        w.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        w.delegate = self
        w.title = "Pair with \(model.device)"
        let host = NSHostingView(rootView: root)
        host.frame = NSRect(origin: .zero, size: PairRoot.windowSize)
        w.contentView = host
        place(w)
        window = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
        DispatchQueue.main.async {
            withTDAnimation(TD.Motion.emphasized) { self.model.visible = true }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { self.model.armed = true }
    }

    /// A little above the middle of the screen the pointer is on (where the user is looking).
    private func place(_ w: NSWindow) {
        let mouse = NSEvent.mouseLocation
        guard let screen = NSScreen.screens.first(where: { $0.frame.contains(mouse) }) ?? NSScreen.main else {
            w.center()
            return
        }
        let vf = screen.visibleFrame
        let s = PairRoot.windowSize
        w.setFrameOrigin(NSPoint(x: (vf.midX - s.width / 2).rounded(), y: (vf.midY - s.height / 2 + vf.height * 0.08).rounded()))
    }

    private func approve() {
        guard let r = result, model.stage == .asking else { return }
        result = nil
        r(true)
        TD.haptic()
        withTDAnimation(TD.Motion.emphasized) { model.stage = .paired }
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.5) { [weak self] in self?.close() }
    }

    /// Demo only: the approved state without a phone waiting for the answer.
    func approveForDemo() {
        result = nil
        withTDAnimation(TD.Motion.emphasized) { model.stage = .paired }
    }

    private func decline() {
        finish(false)
        close()
    }

    /// The phone gave up, or the request timed out: say so briefly, then go.
    func withdraw(_ why: PairPromptModel.Withdrawal, hold: Bool = false) {
        guard model.stage == .asking else { return }
        finish(false)
        withTDAnimation(TD.Motion.standard) { model.stage = .withdrawn(why) }
        if !hold { DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { [weak self] in self?.close() } }
    }

    func finish(_ ok: Bool) {
        guard let r = result else { return }
        result = nil
        r(ok)
    }

    /// Fades out, then closes.
    private func close() {
        guard !closing, let w = window else { return }
        closing = true
        withTDAnimation(.easeIn(duration: 0.16)) { model.visible = false }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) { w.close() }
    }

    func windowWillClose(_ notification: Notification) {
        finish(false)
        onClose?()
    }
}

/// Borderless, but takes the keyboard (Return pairs, Esc declines).
private final class PairWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

// MARK: - View

struct PairRoot: View {
    static let cardWidth: CGFloat = 384
    /// The card plus room for its shadow.
    static let windowSize = NSSize(width: cardWidth + 80, height: 580)

    @ObservedObject var model: PairPromptModel
    let onPair: () -> Void
    let onDecline: () -> Void
    let onClose: () -> Void
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        PairView(model: model, onPair: onPair, onDecline: onDecline, onClose: onClose)
            .frame(width: Self.cardWidth)
            .contentShape(RoundedRectangle(cornerRadius: TD.Radius.panel, style: .continuous))
            .gesture(WindowDragGesture())  // borderless: drag it anywhere by its body
            .tdGlass(.panel, in: RoundedRectangle(cornerRadius: TD.Radius.panel, style: .continuous))
            .scaleEffect(model.visible || reduceMotion ? 1 : 0.94)
            .opacity(model.visible ? 1 : 0)
            .frame(width: Self.windowSize.width, height: Self.windowSize.height)
    }
}

struct PairView: View {
    @ObservedObject var model: PairPromptModel
    let onPair: () -> Void
    let onDecline: () -> Void
    let onClose: () -> Void

    private var asking: Bool { model.stage == .asking }

    var body: some View {
        VStack(spacing: 0) {
            PairHero(model: model)
            VStack(spacing: TD.Space.m) {
                HStack(spacing: 7) {
                    if model.stage == .paired {
                        CheckBurst(tint: TD.violet)
                            .frame(width: 18, height: 18)
                            .transition(.scale(scale: 0.4).combined(with: .opacity))
                    }
                    Text(prompt)
                        .tdFont(.body)
                        .foregroundStyle(TD.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.interpolate)
                }
                PairCode(code: model.code, stage: model.stage)
            }
            .padding(.top, TD.Space.xl)
            .padding(.horizontal, TD.Space.s)
            buttons
                .padding(.top, TD.Space.xl + 2)
        }
        .padding(TD.Space.l)
        .tdAnimation(TD.Motion.emphasized, value: model.stage)
    }

    private var prompt: String {
        switch model.stage {
        case .asking: "Check that \(model.device) shows the same code."
        case .paired: "\(model.device) can send to this Mac now."
        case .withdrawn: "Nothing was paired."
        }
    }

    @ViewBuilder private var buttons: some View {
        switch model.stage {
        case .asking:
            HStack(spacing: TD.Space.s) {
                Button(action: onDecline) {
                    HStack(spacing: 7) {
                        Text("Decline")
                        PairKeyCap("esc", onAccent: false)
                    }
                }
                .buttonStyle(.td(.secondary, size: .large, fullWidth: true))
                .keyboardShortcut(.cancelAction)
                Button(action: onPair) {
                    HStack(spacing: 7) {
                        Text("Pair")
                        PairKeyCap("⏎", onAccent: true)
                    }
                }
                .buttonStyle(.td(.primary, size: .large, fullWidth: true))
                .keyboardShortcut(model.armed ? .defaultAction : nil)
            }
            .transition(.opacity)
        case .paired, .withdrawn:
            Button(model.stage == .paired ? "Done" : "Close", action: onClose)
                .buttonStyle(.td(model.stage == .paired ? .primary : .secondary, size: .large, fullWidth: true))
                .keyboardShortcut(.defaultAction)
                .background {
                    // Esc closes it too
                    Button("Close", action: onClose)
                        .keyboardShortcut(.cancelAction)
                        .opacity(0)
                        .accessibilityHidden(true)
                }
                .transition(.opacity)
        }
    }
}

/// The phone asking, drawn like the panel's hero: the notch hangs from the card's top edge, the phone below it.
/// When pairing completes, the phone rises to touch the notch and it lights (the gesture it can use now).
private struct PairHero: View {
    @ObservedObject var model: PairPromptModel

    private var link: DeviceHero.Link {
        switch model.stage {
        case .asking: .connected
        case .paired: .live
        case .withdrawn: .offline
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xs + 1) {
            Text(title)
                .tdFont(.title)
                .foregroundStyle(TD.text)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.interpolate)
            Text(subtitle)
                .tdFont(.body)
                .foregroundStyle(TD.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.interpolate)
            if model.stage == .asking {
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 9.5, weight: .semibold))
                    Text("End-to-end encrypted, on your Wi-Fi")
                }
                .tdFont(.caption)
                .foregroundStyle(TD.textTertiary)
                .padding(.top, TD.Space.s)
                .transition(.opacity)
            }
        }
        .padding(.leading, TD.Space.l)
        .padding(.trailing, 120)
        .padding(.vertical, TD.Space.l + 2)
        .frame(maxWidth: .infinity, minHeight: 136, alignment: .topLeading)
        .overlay(alignment: .topTrailing) {
            DeviceHero(link: link)
                .id(link)
                .frame(width: 104)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.trailing, TD.Space.s)
                .transition(.opacity)
        }
        .background { AuroraBackdrop(intensity: model.stage == .withdrawn(.cancelled) || model.stage == .withdrawn(.expired) ? 0.35 : 0.9) }
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        switch model.stage {
        case .asking: "Pair with \(model.device)?"
        case .paired: "Paired with \(model.device)"
        case .withdrawn(.cancelled): "\(model.device) stopped pairing"
        case .withdrawn(.expired): "The request expired"
        }
    }

    private var subtitle: String {
        switch model.stage {
        case .asking: PhoneFamily.describe(model.device)
        case .paired: "Touch the top of its screen to the notch to send."
        case .withdrawn(.cancelled): "Start again from the phone when you’re ready."
        case .withdrawn(.expired): "Start pairing again from \(model.device)."
        }
    }
}

/// Six digits in cells, grouped 3 + 3, dropping in one by one. Light up in the brand gradient once paired.
private struct PairCode: View {
    let code: String
    let stage: PairPromptModel.Stage
    @State private var shown = false
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        let chars = Array(code)
        HStack(spacing: 7) {
            ForEach(Array(chars.enumerated()), id: \.offset) { i, ch in
                cell(ch, index: i)
                    .padding(.leading, i == chars.count / 2 && chars.count >= 6 ? 12 : 0)
            }
        }
        .opacity(isWithdrawn ? 0.45 : 1)
        .onAppear {
            if reduceMotion { shown = true } else { withAnimation { shown = true } }
        }
        .accessibilityElement()
        .accessibilityLabel("Code")
        .accessibilityValue(code.map(String.init).joined(separator: " "))
    }

    private var isWithdrawn: Bool {
        if case .withdrawn = stage { return true }
        return false
    }

    private func cell(_ ch: Character, index i: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous)
        let paired = stage == .paired
        let light = scheme == .light
        return Text(String(ch))
            .tdFont(.number(30, .semibold))
            .foregroundStyle(TD.text)
            .frame(width: 44, height: 56)
            // solid enough to read as keys on any wallpaper the glass lets through
            .background(shape.fill(TD.surface.opacity(light ? 0.88 : 0.62)))
            .overlay(shape.strokeBorder(paired ? AnyShapeStyle(TD.aurora) : AnyShapeStyle(TD.hairline), lineWidth: paired ? 1.5 : 1))
            .shadow(color: .black.opacity(light && !paired ? 0.06 : 0), radius: 3, y: 1)
            .shadow(color: TD.violet.opacity(paired ? 0.35 : 0), radius: 8)
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 10)
            .scaleEffect(shown || reduceMotion ? 1 : 0.86)
            .animation(reduceMotion ? TD.Motion.reduced
                       : .spring(response: 0.46, dampingFraction: 0.7).delay(0.12 + Double(i) * 0.05), value: shown)
            .animation(TD.Motion.resolved(TD.Motion.emphasized, reduceMotion: reduceMotion).delay(paired ? Double(i) * 0.04 : 0),
                       value: paired)
    }
}

/// A shortcut drawn as a key cap inside a button ("esc", "⏎"); on the accent button it takes the accent's ink.
private struct PairKeyCap: View {
    let keys: String
    let onAccent: Bool

    init(_ keys: String, onAccent: Bool) {
        self.keys = keys
        self.onAccent = onAccent
    }

    var body: some View {
        Text(keys)
            .font(.system(size: 10.5, weight: .semibold, design: .rounded))
            .foregroundStyle(onAccent ? AnyShapeStyle(TD.onAccent.opacity(0.75)) : AnyShapeStyle(TD.textSecondary))
            .padding(.horizontal, 5)
            .frame(minWidth: 18, minHeight: 17)
            .background(RoundedRectangle(cornerRadius: 5, style: .continuous).fill(onAccent ? TD.onAccent.opacity(0.10) : TD.fillStrong))
            .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous)
                .strokeBorder(onAccent ? TD.onAccent.opacity(0.22) : TD.hairline, lineWidth: 0.75))
            .accessibilityHidden(true)
    }
}

/// "Google Pixel · Android phone" from a phone's name, when the name says which family it is.
enum PhoneFamily {
    private static let families: [(String, String)] = [
        ("pixel", "Google Pixel"), ("galaxy", "Samsung Galaxy"), ("sm-", "Samsung Galaxy"), ("samsung", "Samsung"),
        ("oneplus", "OnePlus"), ("nothing", "Nothing"), ("redmi", "Xiaomi Redmi"), ("poco", "Xiaomi POCO"),
        ("xiaomi", "Xiaomi"), ("moto", "Motorola"), ("xperia", "Sony Xperia"), ("oppo", "OPPO"), ("vivo", "vivo"),
        ("iqoo", "iQOO"), ("realme", "realme"), ("honor", "HONOR"), ("huawei", "HUAWEI"), ("nokia", "Nokia"),
        ("fairphone", "Fairphone"), ("zenfone", "ASUS Zenfone"), ("rog phone", "ASUS ROG Phone"),
    ]

    static func describe(_ name: String) -> String {
        let n = name.lowercased()
        guard let family = families.first(where: { n.contains($0.0) })?.1 else { return "Android phone" }
        return "\(family) · Android"
    }
}
