import AppKit
import Combine
import SwiftUI

// The Setup window's flow: Install (QR code / link) → Pair → Tap to Send → done.
// It moves on by itself when the Mac sees each step happen (the page opened and the app downloaded, a new
// phone paired, the phone's tap link up, a tap that counted); Continue always works too.

@MainActor
final class SetupModel: ObservableObject {
    enum Step: Int, CaseIterable, Comparable {
        case install, pair, tap, done

        static func < (a: Step, b: Step) -> Bool { a.rawValue < b.rawValue }

        var title: String {
            switch self {
            case .install: "Install"
            case .pair: "Pair"
            case .tap: "Tap to Send"
            case .done: "Done"
            }
        }
    }

    @Published private(set) var step: Step = .install
    /// The phone paired while this window was open.
    @Published private(set) var phone: PairedDevice?
    /// A tap counted while on the last step.
    @Published private(set) var tapped = false
    /// Which way the last step change went (for the slide).
    @Published private(set) var forward = true

    private var known: Set<String> = []
    private var bag = Set<AnyCancellable>()
    private var pending: DispatchWorkItem?
    /// Demo: no automatic moves.
    var frozen = false

    var phoneName: String { phone?.name ?? "your phone" }
    var phoneLive: Bool {
        guard let phone else { return false }
        return Store.shared.liveDevices.contains(phone.id)
    }

    func begin() {
        bag.removeAll()
        pending?.cancel()
        known = Set(Store.shared.devices.map(\.id))
        step = .install
        phone = nil
        tapped = false
        forward = true

        Store.shared.$devices
            .receive(on: RunLoop.main)
            .sink { [weak self] devices in self?.devicesChanged(devices) }
            .store(in: &bag)
        TapStatus.shared.$last
            .compactMap { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self] r in self?.tapFinished(r) }
            .store(in: &bag)
        // The whole app reached the phone: installing is up to the phone now, and pairing comes next.
        InstallStatus.shared.$download
            .map { $0?.done == true }
            .removeDuplicates()
            .filter { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self, self.step == .install else { return }
                self.advance(to: .pair, after: 3.2)
            }
            .store(in: &bag)
    }

    func end() {
        bag.removeAll()
        pending?.cancel()
    }

    func go(_ s: Step) {
        pending?.cancel()
        guard s != step else { return }
        forward = s > step
        withTDAnimation(TD.Motion.standard) { step = s }
    }

    func next() {
        go(Step(rawValue: min(step.rawValue + 1, Step.done.rawValue)) ?? .done)
    }

    func back() {
        go(Step(rawValue: max(step.rawValue - 1, 0)) ?? .install)
    }

    /// Moves on after a beat, so the person sees the check land first.
    private func advance(to s: Step, after delay: Double) {
        guard !frozen, s > step else { return }
        pending?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.go(s) }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func devicesChanged(_ devices: [PairedDevice]) {
        guard !frozen, let new = devices.last(where: { !known.contains($0.id) }) else { return }
        known.insert(new.id)
        withTDAnimation { phone = new }
        TD.haptic()
        advance(to: .tap, after: 1.4)
    }

    /// A tap that counted proves every step at once, so it finishes the flow from wherever it is (it can land
    /// before the window has moved on from pairing). Only the phone paired here counts, or, on the last step,
    /// any phone (someone re-checking a phone that was already paired).
    private func tapFinished(_ r: TapStatus.TapResult) {
        guard !frozen, r.accepted, step != .done, Date().timeIntervalSince(r.time) < 5 else { return }
        if let phone {
            guard phone.name == r.phone else { return }
        } else {
            guard step == .tap else { return }
        }
        withTDAnimation { tapped = true }
        advance(to: .done, after: 1.6)
    }

    // MARK: demo

    func demo(step s: Step, phone p: PairedDevice?, tapped t: Bool) {
        frozen = true
        step = s
        phone = p
        tapped = t
    }
}

// MARK: - Root

struct SetupView: View {
    @ObservedObject var model: SetupModel
    @ObservedObject private var store = Store.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 0) {
            SetupStepper(step: model.step)
                .frame(height: SettingsChrome.toolbarHeight)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 90)

            ZStack {
                content
                    .id(model.step)
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .offset(x: reduceMotion ? 0 : (model.forward ? 24 : -24))),
                        removal: .opacity.combined(with: .offset(x: reduceMotion ? 0 : (model.forward ? -24 : 24)))))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            BottomBar(model: model)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            ZStack {
                SettingsChrome.Backdrop()
                if model.step == .done {
                    // the celebration's light, behind the whole window
                    AuroraBackdrop(intensity: 0.75)
                        .mask(RadialGradient(colors: [.black, .black.opacity(0.6), .clear], center: UnitPoint(x: 0.5, y: 0.42),
                                             startRadius: 30, endRadius: 420))
                        .transition(.opacity)
                }
            }
            .tdAnimation(TD.Motion.standard, value: model.step == .done)
        }
        .ignoresSafeArea()
        .opacity(appeared ? 1 : 0)
        .onAppear { withTDAnimation(TD.Motion.standard) { appeared = true } }
    }

    @ViewBuilder private var content: some View {
        switch model.step {
        case .install: InstallStep()
        case .pair: PairStep(model: model)
        case .tap: TapStep(model: model)
        case .done: DoneStep(model: model)
        }
    }
}

// MARK: - Stepper

/// Install — Pair — Tap to Send, with the progress drawn between them.
private struct SetupStepper: View {
    let step: SetupModel.Step
    private let steps: [SetupModel.Step] = [.install, .pair, .tap]

    var body: some View {
        HStack(spacing: TD.Space.s) {
            ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                if i > 0 {
                    Connector(filled: step >= s)
                }
                StepDot(index: i + 1, state: state(of: s), title: s.title)
            }
        }
        .tdAnimation(TD.Motion.standard, value: step)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(step == .done ? "All steps done" : "Step \(step.rawValue + 1) of 3, \(step.title)")
    }

    private func state(of s: SetupModel.Step) -> StepDot.State {
        if step > s { return .done }
        if step == s { return .current }
        return .upcoming
    }

    private struct Connector: View {
        let filled: Bool

        var body: some View {
            ZStack(alignment: .leading) {
                Capsule().fill(TD.fillStrong)
                Capsule().fill(TD.aurora)
                    .scaleEffect(x: filled ? 1 : 0.001, anchor: .leading)
            }
            .frame(width: 44, height: 2)
        }
    }
}

private struct StepDot: View {
    enum State { case done, current, upcoming }
    let index: Int
    let state: State
    let title: String

    var body: some View {
        HStack(spacing: TD.Space.s - 1) {
            ZStack {
                switch state {
                case .done:
                    Circle().fill(TD.aurora)
                    Image(systemName: "checkmark")
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundStyle(.white)
                        .transition(.scale.combined(with: .opacity))
                case .current:
                    Circle().fill(TD.violet.opacity(0.16))
                    Circle().strokeBorder(TD.violet, lineWidth: 1.5)
                    Text("\(index)")
                        .tdFont(.number(11, .bold))
                        .foregroundStyle(TD.violet)
                case .upcoming:
                    Circle().strokeBorder(TD.hairline, lineWidth: 1.5)
                    Text("\(index)")
                        .tdFont(.number(11, .semibold))
                        .foregroundStyle(TD.textTertiary)
                }
            }
            .frame(width: 22, height: 22)
            Text(title)
                .tdFont(state == .current ? .bodySemibold : .bodyMedium)
                .foregroundStyle(state == .upcoming ? TD.textTertiary : TD.text)
                .fixedSize()
        }
    }
}

// MARK: - Layout of a step

/// The square stage each step's picture sits on (the QR card, the phone, the notch).
enum SetupStage {
    static let side: CGFloat = 300
    static let radius: CGFloat = 28
}

/// A step: the visual on the left, the words on the right.
private struct StepLayout<Visual: View, Words: View>: View {
    @ViewBuilder var visual: Visual
    @ViewBuilder var words: Words

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.xxxl + 8) {
            visual
                .frame(width: SetupStage.side)
            VStack(alignment: .leading, spacing: 0) { words }
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 48)
        .padding(.bottom, TD.Space.s)
    }
}

/// Eyebrow, title and body of a step.
private struct StepWords: View {
    let eyebrow: String
    let title: String
    var text: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(eyebrow.uppercased())
                .tdFont(.micro)
                .tracking(0.8)
                .foregroundStyle(TD.violet)
                .padding(.bottom, TD.Space.s)
            Text(title)
                .tdFont(.display)
                .foregroundStyle(TD.text)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let text {
                Text(text)
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, TD.Space.s + 2)
            }
        }
    }
}

/// Numbered instructions. `em` marks the words the person will see on their phone or this Mac.
private struct StepList: View {
    let items: [Text]

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s + 2) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                HStack(alignment: .firstTextBaseline, spacing: TD.Space.s + 2) {
                    Text("\(i + 1)")
                        .tdFont(.number(11, .bold))
                        .foregroundStyle(TD.violet)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(TD.violet.opacity(0.14)))
                        .accessibilityHidden(true)
                    item
                        .tdFont(.body)
                        .foregroundStyle(TD.textSecondary)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    /// Emphasis inside an item: the label of a button or a name, as it appears on screen.
    static func em(_ s: String) -> Text {
        Text(s).fontWeight(.semibold).foregroundStyle(TD.text)
    }
}

/// A short requirement with a symbol.
private struct Need: View {
    let symbol: String
    let text: String

    var body: some View {
        HStack(spacing: TD.Space.s + 2) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(TD.violet)
                .frame(width: 26, height: 26)
                .background(Circle().fill(TD.violet.opacity(0.12)))
            Text(text)
                .tdFont(.bodyMedium)
                .foregroundStyle(TD.text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// What the Mac sees happening right now.
private struct LiveStatus: View {
    enum Kind: Equatable { case waiting, done, warning, error }
    let kind: Kind
    let text: String

    var body: some View {
        HStack(spacing: TD.Space.s + 1) {
            icon.frame(width: 16, height: 16)
            Text(text)
                .tdFont(.bodyMedium)
                .foregroundStyle(kind == .waiting ? TD.textSecondary : TD.text)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.interpolate)
        }
        .padding(.leading, TD.Space.s + 2)
        .padding(.trailing, TD.Space.m + 2)
        .padding(.vertical, TD.Space.s)
        .frame(minHeight: 34)
        .background(Capsule().fill(fill))
        .overlay(Capsule().strokeBorder(stroke, lineWidth: 1))
        .tdAnimation(TD.Motion.standard, value: kind)
        .tdAnimation(TD.Motion.standard, value: text)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

    @ViewBuilder private var icon: some View {
        switch kind {
        case .waiting:
            ProgressRing(progress: nil, lineWidth: 2)
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 15, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, TD.success)
                .transition(.scale.combined(with: .opacity))
        case .warning:
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 15, weight: .semibold))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, TD.warning)
        case .error:
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(TD.danger)
        }
    }

    private var fill: Color {
        switch kind {
        case .done: TD.success.opacity(0.10)
        case .warning: TD.warning.opacity(0.10)
        case .error: TD.danger.opacity(0.10)
        default: TD.fill
        }
    }

    private var stroke: Color {
        switch kind {
        case .done: TD.success.opacity(0.22)
        case .warning: TD.warning.opacity(0.24)
        case .error: TD.danger.opacity(0.24)
        default: TD.hairline
        }
    }
}

// MARK: - 1. Install

private struct InstallStep: View {
    @ObservedObject private var install = InstallStatus.shared

    private var url: String? { SetupDemo.noNetwork ? nil : InstallServer.shared.pageURL }

    var body: some View {
        StepLayout {
            QRCard(url: url, server: install.server)
        } words: {
            StepWords(eyebrow: "Step 1 of 3", title: "Install TapDrop on your phone",
                      text: "Scan the code with your phone's camera. It opens a page on this Mac where you can download TapDrop for Android.")
            VStack(alignment: .leading, spacing: TD.Space.s + 2) {
                Need(symbol: "smartphone", text: "Android 13 or newer, any brand")
                Need(symbol: "wifi", text: "On the same Wi-Fi as this Mac")
            }
            .padding(.top, TD.Space.xl)
            status
                .padding(.top, TD.Space.xl + 4)
        }
    }

    @ViewBuilder private var status: some View {
        if url == nil {
            VStack(alignment: .leading, spacing: TD.Space.m) {
                LiveStatus(kind: .error, text: "This Mac isn't on Wi-Fi")
                Button("Wi-Fi Settings…") {
                    if let u = URL(string: "x-apple.systempreferences:com.apple.wifi-settings-extension") { NSWorkspace.shared.open(u) }
                }
                .buttonStyle(.td(.secondary, size: .small))
            }
        } else if case .failed(let why) = install.server {
            // the headline in the capsule, the reason next to the way out
            VStack(alignment: .leading, spacing: TD.Space.m) {
                LiveStatus(kind: .error, text: "The download page couldn't start")
                HStack(spacing: TD.Space.m) {
                    Button("Try Again") { InstallServer.shared.start() }
                        .buttonStyle(.td(.secondary, size: .small))
                    Text("\(why).")
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        } else if let d = install.download {
            if d.failed {
                LiveStatus(kind: .warning, text: "Download stopped. Try again on your phone.")
            } else if d.done {
                LiveStatus(kind: .done, text: "Downloaded. Open TapDrop.apk on your phone.")
            } else {
                DownloadStatus(download: d)
            }
        } else if InstallServer.shared.apkURL == nil {
            LiveStatus(kind: .warning, text: "This copy doesn't include the Android app.")
        } else if install.visits > 0 {
            LiveStatus(kind: .done, text: "Opened on your phone. Tap Download there.")
        } else {
            LiveStatus(kind: .waiting, text: "Waiting for your phone to scan the code…")
        }
    }
}

/// The app on its way to the phone: how much has arrived, on a bar that fills.
private struct DownloadStatus: View {
    let download: InstallStatus.Download

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s + 1) {
            HStack(alignment: .firstTextBaseline, spacing: TD.Space.m) {
                Text("Downloading TapDrop…")
                    .tdFont(.bodyMedium)
                    .foregroundStyle(TD.text)
                Spacer(minLength: 0)
                Text("\(Self.size(download.sent)) of \(Self.size(download.total))")
                    .tdFont(.number(11.5, .medium))
                    .foregroundStyle(TD.textSecondary)
                    .contentTransition(.numericText(value: Double(download.sent)))
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(TD.fillStrong)
                    Capsule()
                        .fill(TD.aurora)
                        .frame(width: max(6, g.size.width * download.fraction))
                        .shadow(color: TD.violet.opacity(0.45), radius: 4)
                }
            }
            .frame(height: 5)
        }
        .padding(.horizontal, TD.Space.l - 2)
        .padding(.top, TD.Space.m - 1)
        .padding(.bottom, TD.Space.m + 1)
        .frame(maxWidth: 340)
        .background(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).fill(TD.fill))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .tdAnimation(.easeOut(duration: 0.25), value: download.sent)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Downloading TapDrop")
        .accessibilityValue("\(Int((download.fraction * 100).rounded())) percent")
        .accessibilityAddTraits(.updatesFrequently)
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

/// The big QR code on a white card, glowing on the aurora; the link under it.
private struct QRCard: View {
    let url: String?
    let server: InstallStatus.Server
    @State private var copied = false

    /// The page can be reached: show the code.
    private var reachable: Bool {
        guard url != nil else { return false }
        if case .failed = server { return false }
        return true
    }

    var body: some View {
        VStack(spacing: TD.Space.l) {
            ZStack {
                if reachable {
                    RoundedRectangle(cornerRadius: 34, style: .continuous)
                        .fill(TD.auroraHero)
                        .blur(radius: 42)
                        .opacity(0.62)
                        .padding(6)
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(.white)
                        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
                } else {
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(TD.fill)
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .strokeBorder(TD.hairline, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5]))
                }
                code
                    .padding(22)
            }
            .frame(width: 264, height: 264)
            .tdAnimation(TD.Motion.standard, value: reachable)

            if let url, reachable {
                HStack(spacing: TD.Space.xs) {
                    Text(url.replacingOccurrences(of: "http://", with: "").trimmingCharacters(in: CharacterSet(charactersIn: "/")))
                        .tdFont(.number(12, .medium))
                        .foregroundStyle(TD.textSecondary)
                        .textSelection(.enabled)
                    Button {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(url, forType: .string)
                        withTDAnimation { copied = true }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { withTDAnimation { copied = false } }
                    } label: {
                        Label(copied ? "Copied" : "Copy link", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .contentTransition(.symbolEffect(.replace))
                    }
                    .buttonStyle(.tdIcon(size: 24, quiet: true))
                    .help("Copy the link")
                }
                .accessibilityElement(children: .contain)
            } else {
                Text(" ").tdFont(.number(12, .medium))
            }
        }
    }

    @ViewBuilder private var code: some View {
        if let url, reachable {
            if server != .ready && !SetupDemo.frozen {
                Shimmer(radius: 10)
            } else {
                QRCodeView(text: url)
                    .accessibilityLabel("QR code for \(url)")
            }
        } else if url == nil {
            EmptyState("wifi.slash", title: "No Wi-Fi", message: "The code appears when this Mac is on Wi-Fi.", compact: true)
        } else {
            EmptyState("exclamationmark.triangle", title: "No download page",
                       message: "The code appears once the page is up again.", compact: true)
        }
    }
}

/// A QR code drawn with soft modules and round eyes, the app glyph in the middle.
struct QRCodeView: View {
    let text: String

    var body: some View {
        if let m = QR.matrix(for: text) {
            let n = m.count
            GeometryReader { g in
                let side = min(g.size.width, g.size.height)
                let cell = side / CGFloat(n)
                let hole = n <= 41 ? Self.hole(n) : 0
                ZStack {
                    Canvas { ctx, _ in
                        Self.draw(m, cell: cell, hole: hole, in: ctx)
                    }
                    if hole > 0 {
                        AppGlyph(size: cell * CGFloat(hole - 1))
                            .shadow(color: .clear, radius: 0)
                    }
                }
                .frame(width: side, height: side)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .aspectRatio(1, contentMode: .fit)
        }
    }

    /// Modules left clear for the glyph (odd, about a fifth of the side).
    static func hole(_ n: Int) -> Int {
        let k = Int((Double(n) * 0.22).rounded())
        return k % 2 == 0 ? k + 1 : k
    }

    static func draw(_ m: [[Bool]], cell: CGFloat, hole: Int, in ctx: GraphicsContext) {
        let n = m.count
        let eyes = [(0, 0), (n - 7, 0), (0, n - 7)]
        func inEye(_ x: Int, _ y: Int) -> Bool { eyes.contains { x >= $0.0 && x < $0.0 + 7 && y >= $0.1 && y < $0.1 + 7 } }
        let lo = (n - hole) / 2, hi = lo + hole
        func inHole(_ x: Int, _ y: Int) -> Bool { hole > 0 && x >= lo && x < hi && y >= lo && y < hi }

        var dots = Path()
        for y in 0..<n {
            for x in 0..<n where m[y][x] && !inEye(x, y) && !inHole(x, y) {
                let r = CGRect(x: CGFloat(x) * cell, y: CGFloat(y) * cell, width: cell, height: cell).insetBy(dx: cell * 0.07, dy: cell * 0.07)
                dots.addRoundedRect(in: r, cornerSize: CGSize(width: r.width * 0.34, height: r.width * 0.34), style: .continuous)
            }
        }
        let size = CGFloat(n) * cell
        let ink = GraphicsContext.Shading.linearGradient(
            Gradient(colors: [Color(tdHex: 0x152052), Color(tdHex: 0x2C1466)]),
            startPoint: .zero, endPoint: CGPoint(x: size, y: size))
        ctx.fill(dots, with: ink)
        for (ex, ey) in eyes {
            let outer = CGRect(x: CGFloat(ex) * cell, y: CGFloat(ey) * cell, width: cell * 7, height: cell * 7)
            var ring = Path(roundedRect: outer, cornerRadius: cell * 2.3, style: .continuous)
            ring.addPath(Path(roundedRect: outer.insetBy(dx: cell, dy: cell), cornerRadius: cell * 1.5, style: .continuous))
            ctx.fill(ring, with: ink, style: FillStyle(eoFill: true))
            ctx.fill(Path(roundedRect: outer.insetBy(dx: cell * 2, dy: cell * 2), cornerRadius: cell * 1.0, style: .continuous),
                     with: .linearGradient(Gradient(colors: [Color(tdHex: 0x2F6BE8), Color(tdHex: 0x5B34D6)]),
                                           startPoint: outer.origin, endPoint: CGPoint(x: outer.maxX, y: outer.maxY)))
        }
    }
}

// MARK: - 2. Pair

private struct PairStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        StepLayout {
            PairArt(paired: model.phone != nil)
        } words: {
            StepWords(eyebrow: "Step 2 of 3", title: "Pair with this Mac")
            StepList(items: [
                Text("Open TapDrop on your phone and tap \(StepList.em("Pair with your Mac"))."),
                Text("Choose \(StepList.em(Store.computerName))."),
                Text("Check that the codes match, then click \(StepList.em("Pair")) here."),
            ])
            .padding(.top, TD.Space.l)
            Group {
                if let p = model.phone {
                    LiveStatus(kind: .done, text: "Paired with \(p.name)")
                } else {
                    LiveStatus(kind: .waiting, text: "Waiting for your phone…")
                }
            }
            .padding(.top, TD.Space.xl + 4)
        }
    }
}

/// The phone, showing TapDrop's "Pair with your Mac" list with this Mac in it.
private struct PairArt: View {
    let paired: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The phone rises from the bottom edge of a lit stage, like the Tap to Send step's.
    var body: some View {
        ZStack(alignment: .top) {
            AuroraBackdrop(intensity: paired ? 1 : 0.7)
            // the screen's glow
            Ellipse()
                .fill(TD.violet.opacity(paired ? 0.5 : 0.36))
                .frame(width: 220, height: 200)
                .blur(radius: 44)
                .offset(y: 64)
            phone
                .offset(y: 38)
        }
        .frame(width: SetupStage.side, height: SetupStage.side)
        .clipShape(RoundedRectangle(cornerRadius: SetupStage.radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: SetupStage.radius, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .tdAnimation(TD.Motion.standard, value: paired)
        .accessibilityElement()
        .accessibilityLabel(paired ? "Your phone, paired with \(Store.computerName)"
                                   : "On your phone, choose \(Store.computerName)")
    }

    private var phone: some View {
        let body = RoundedRectangle(cornerRadius: 36, style: .continuous)
        let screen = RoundedRectangle(cornerRadius: 30, style: .continuous)
        return ZStack(alignment: .top) {
            body.fill(Color(tdHex: 0x0C0C10))
            body.strokeBorder(LinearGradient(colors: [.white.opacity(0.42), .white.opacity(0.1), .white.opacity(0.05)],
                                             startPoint: .top, endPoint: .bottom), lineWidth: 1.2)
            screen
                .fill(LinearGradient(colors: [Color(tdHex: 0x1F1A3D), Color(tdHex: 0x0E0E16)], startPoint: .top, endPoint: .bottom))
                .overlay(alignment: .top) {
                    Ellipse().fill(TD.violet.opacity(0.35)).frame(width: 240, height: 130).blur(radius: 40).offset(y: -56)
                }
                .clipShape(screen)
                .padding(6)
            Circle().fill(.black).frame(width: 9, height: 9).offset(y: 16)
            VStack(alignment: .leading, spacing: 0) {
                Text("Pair with your Mac")
                    .font(.system(size: 15, weight: .semibold))
                    .tracking(-0.2)
                    .foregroundStyle(.white)
                Text("Macs on this Wi-Fi")
                    .font(.system(size: 10, weight: .medium))
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.top, 3)
                VStack(spacing: 6) {
                    macRow(Store.computerName, highlighted: true)
                    macRow("Studio iMac", highlighted: false).opacity(0.5)
                }
                .padding(.top, 14)
                HStack(spacing: 6) {
                    ProgressRing(progress: nil, lineWidth: 1.4, track: .white.opacity(0.12))
                        .frame(width: 10, height: 10)
                    Text("Looking for more Macs…")
                        .font(.system(size: 9, weight: .medium))
                        .foregroundStyle(.white.opacity(0.45))
                }
                .padding(.top, 12)
                .padding(.leading, 4)
                Spacer()
            }
            .padding(.horizontal, 15)
            .padding(.top, 44)
        }
        .frame(width: 214, height: 330)
    }

    private func macRow(_ name: String, highlighted: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "laptopcomputer")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(highlighted ? AnyShapeStyle(TD.aurora) : AnyShapeStyle(Color.white.opacity(0.12))))
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Text(highlighted && paired ? "Paired" : "TapDrop")
                    .font(.system(size: 8.5, weight: .medium))
                    .foregroundStyle(highlighted && paired ? Color(tdHex: 0x5BE39A) : .white.opacity(0.5))
            }
            Spacer(minLength: 0)
            if highlighted && paired {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(tdHex: 0x2ED17A))
            } else {
                Image(systemName: "chevron.right")
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 38)
        .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(.white.opacity(highlighted ? 0.10 : 0.05)))
        .overlay {
            if highlighted {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .strokeBorder(TD.violet.opacity(0.9), lineWidth: 1.2)
                if !paired && !reduceMotion {
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .strokeBorder(TD.violet, lineWidth: 1.2)
                        .phaseAnimator([false, true]) { v, on in
                            v.scaleEffect(on ? 1.12 : 1).opacity(on ? 0 : 0.8)
                        } animation: { on in on ? .easeOut(duration: 1.4) : .linear(duration: 0.01).delay(0.5) }
                }
            }
        }
    }
}

// MARK: - 3. Tap to Send

private struct TapStep: View {
    @ObservedObject var model: SetupModel
    @ObservedObject private var store = Store.shared

    private var sensor: Bool { NotchSensor.shared.available && !SettingsDemo.noSensor }

    var body: some View {
        StepLayout {
            ZStack {
                AuroraBackdrop(intensity: model.phoneLive || model.tapped ? 1 : 0.55)
                DeviceHero(link: heroLink)
                    .frame(width: 236)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .frame(width: SetupStage.side, height: SetupStage.side)
            .clipShape(RoundedRectangle(cornerRadius: SetupStage.radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: SetupStage.radius, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        } words: {
            if sensor {
                StepWords(eyebrow: "Step 3 of 3", title: "Turn on Tap to Send")
                StepList(items: [
                    Text("On your phone, tap \(StepList.em("Turn on tap to send"))."),
                    Text("Turn TapDrop on in Accessibility. If Android says it's restricted, TapDrop shows you how to allow it."),
                    Text("Touch the top of your phone's screen to the notch, and hold it there until your phone buzzes."),
                ])
                .padding(.top, TD.Space.l)
                status
                    .padding(.top, TD.Space.xl + 4)
            } else {
                StepWords(eyebrow: "Step 3 of 3", title: "Share from your phone",
                          text: "This Mac has no light sensor TapDrop can use, so Tap to Send isn't available here. Share photos, links and files from your phone's share sheet, or send them from the TapDrop menu on this Mac.")
            }
        }
    }

    /// The gesture itself, as you'll do it (the phone's back at the notch, its screen's light spilling out); without a
    /// light sensor, just the phone.
    private var heroLink: DeviceHero.Link {
        sensor ? .live : .connected
    }

    @ViewBuilder private var status: some View {
        if model.tapped {
            LiveStatus(kind: .done, text: "That counted. \(model.phoneName) sent what was on its screen.")
        } else if model.phoneLive {
            LiveStatus(kind: .done, text: "\(model.phoneName) is ready. Try a tap!")
        } else if model.phone == nil, let live = store.devices.first(where: { store.liveDevices.contains($0.id) }) {
            LiveStatus(kind: .done, text: "\(live.name) is ready. Try a tap!")
        } else {
            LiveStatus(kind: .waiting, text: "Waiting for \(model.phoneName) to turn on Tap to Send…")
        }
    }
}

// MARK: - Done

private struct DoneStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle().fill(TD.success.opacity(0.18)).frame(width: 150, height: 150).blur(radius: 40)
                CheckBurst(tint: TD.success, haptic: true)
                    .frame(width: 80, height: 80)
            }
            .frame(height: 108)
            Text("You're all set")
                .tdFont(.display)
                .foregroundStyle(TD.text)
                .padding(.top, TD.Space.m + 2)
                .accessibilityAddTraits(.isHeader)
            Text(NotchSensor.shared.available && !SettingsDemo.noSensor
                 ? "Open anything on \(model.phoneName), then touch the top of its screen to the notch. It lands right here."
                 : "Share from \(model.phoneName) and it lands right here.")
                .tdFont(.body)
                .foregroundStyle(TD.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 400)
                .padding(.top, TD.Space.s)
            HStack(spacing: TD.Space.s) {
                if let p = model.phone {
                    FactChip(text: p.name, symbol: "smartphone")
                }
                FactChip(text: "Paired", symbol: "link", tint: TD.success)
                if NotchSensor.shared.available && !SettingsDemo.noSensor {
                    FactChip(text: model.tapped ? "First tap done" : "Tap to Send", symbol: "hand.tap.fill", tint: TD.violet)
                }
            }
            .padding(.top, TD.Space.xl)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, TD.Space.l)
    }
}

// MARK: - Bottom bar

private struct BottomBar: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        HStack(spacing: TD.Space.s) {
            if model.step == .done {
                Button {
                    SetupWindow.shared.close()
                    SettingsWindow.shared.show(.tap)
                } label: {
                    Text("Tap to Send Settings")
                }
                .buttonStyle(.tdQuiet)
            } else {
                Button {
                    SettingsWindow.shared.show(.compatibility)
                } label: {
                    HStack(spacing: 3) {
                        Text("Supported phones")
                        Image(systemName: "arrow.up.right").font(.system(size: 9, weight: .bold))
                    }
                }
                .buttonStyle(.tdQuiet)
            }
            Spacer()
            TDGlassGroup(spacing: TD.Space.s) {
                HStack(spacing: TD.Space.s) {
                    if model.step != .install && model.step != .done {
                        Button("Back") { model.back() }
                            .buttonStyle(.tdSecondary)
                    }
                    Button(primaryTitle) {
                        if model.step == .done { SetupWindow.shared.close() } else { model.next() }
                    }
                    // Skipping a step isn't the main thing to do there.
                    .buttonStyle(skipping ? .tdSecondary : .tdPrimary)
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(.horizontal, TD.Space.xl)
        .frame(height: 64)
        .overlay(alignment: .top) { TDDivider() }
    }

    private var skipping: Bool { model.step == .pair && model.phone == nil }

    private var primaryTitle: String {
        switch model.step {
        case .install: "Continue"
        case .pair: model.phone == nil ? "Skip" : "Continue"
        case .tap: "Finish"
        case .done: "Done"
        }
    }
}

/// Demo / QA switches for the Setup window (set by DemoWindows.swift only).
@MainActor
enum SetupDemo {
    /// Pretend this Mac has no network address.
    static var noNetwork = false
    /// Show the QR code even if the page server isn't up (snapshots).
    static var frozen = false
}
