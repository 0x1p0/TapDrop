import AppKit
import Charts
import SwiftUI

// Settings → Tap to Send: how to tap, whether it's ready, a live "Test your tap" chart of the light at the
// notch, and the recent taps with the numbers behind them.

struct TapPane: View {
    let visible: Bool
    /// The window can be seen; the live test pauses while it can't.
    var onScreen = true
    @ObservedObject private var store = Store.shared
    @ObservedObject private var status = TapStatus.shared
    @ObservedObject private var test = TapTestModel.shared

    private var readiness: TapReadiness { TapReadiness.current(store) }
    /// The test runs the sensor only when tapping is on (a cover during the test may start a real round), and only
    /// while someone can see it.
    private var canTest: Bool { onScreen && readiness != .unavailable && readiness != .off }

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xxl) {
            // The master switch sits in the header, as on the system's own panes; the live test comes right
            // after the gesture, so it's on screen when the pane opens.
            SettingsPaneHeader(.tap) {
                HStack(spacing: TD.Space.m) {
                    Group {
                        if readiness == .ready {
                            // a verdict, so it stays still (the chart below is what's live)
                            FactChip(text: "Ready", symbol: "checkmark", tint: TD.success)
                        } else if let p = pill {
                            StatusPill(p.0, p.1)
                        }
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
                    if readiness != .unavailable {
                        Toggle("Tap to Send", isOn: $store.settings.notchTap)
                            .toggleStyle(.switch)
                            .labelsHidden()
                            .tint(TD.violet)
                            .help(store.settings.notchTap ? "Turn off Tap to Send" : "Turn on Tap to Send")
                    }
                }
                .tdAnimation(TD.Motion.standard, value: pill?.1)
            }
            .tdReveal(0, visible)

            TapHero(readiness: readiness)
                .tdReveal(1, visible)

            if readiness != .unavailable {
                TestCard(model: test, readiness: readiness, phone: phoneName, phase: status.phase, dark: roomState == .dark)
                    .tdReveal(2, visible)
            }

            ReadinessGroup(readiness: readiness, phone: phoneName, room: roomState)
                .tdReveal(3, visible)

            if readiness != .unavailable {
                RecentTaps()
                    .tdReveal(4, visible)
            }
        }
        .onAppear { if canTest { test.start() } }
        .onDisappear { test.stop() }
        .onChange(of: canTest) { _, on in on ? test.start() : test.stop() }
    }

    private var phoneName: String {
        let live = store.devices.first { store.liveDevices.contains($0.id) }
        let recent = store.devices.max { ($0.lastSeen ?? .distantPast) < ($1.lastSeen ?? .distantPast) }
        return (live ?? recent)?.name ?? "your phone"
    }

    /// How ready tapping is (the switch next to it says on or off).
    private var pill: (TDStatus, String)? {
        switch readiness {
        case .ready: (.live, "Ready")
        case .waiting: (.searching, "Waiting for \(phoneName)")
        case .noPhone: (.offline, "No phone yet")
        case .off: nil
        case .unavailable: (.offline, "Not available")
        }
    }

    enum RoomState { case lit, dark, unknown }

    private var roomState: RoomState {
        if status.roomLux != nil { return status.dark ? .dark : .lit }
        if let d = test.roomDark, test.running { return d ? .dark : .lit }
        return .unknown
    }
}

// MARK: - Hero

private struct TapHero: View {
    let readiness: TapReadiness

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: TD.Space.s) {
                    Text(title)
                        .tdFont(.title)
                        .foregroundStyle(TD.text)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                    Text(secondLine)
                        .tdFont(.body)
                        .foregroundStyle(TD.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
                if readiness == .noPhone {
                    Button {
                        SetupWindow.shared.show()
                    } label: {
                        Label("Set Up a Phone", systemImage: "qrcode")
                    }
                    .buttonStyle(.td(.primary, size: .small))
                    .padding(.top, TD.Space.m + 2)
                }
                Spacer(minLength: TD.Space.l)
                if readiness != .unavailable {
                    HStack(alignment: .firstTextBaseline, spacing: TD.Space.s) {
                        Image(systemName: "checkmark.shield.fill")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(TD.success)
                        Text(TapCopy.safety)
                            .tdFont(.caption)
                            .foregroundStyle(TD.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.leading, TD.Space.xl + 2)
            .padding(.vertical, TD.Space.xl)
            .frame(maxWidth: .infinity, alignment: .leading)

            DeviceHero(link: heroLink)
                .frame(width: 200)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.horizontal, TD.Space.m)
                .accessibilityHidden(true)
        }
        .frame(minHeight: 188)
        .background { AuroraBackdrop(intensity: readiness == .ready ? 1 : 0.5) }
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .tdAnimation(TD.Motion.standard, value: readiness)
        .accessibilityElement(children: .contain)
    }

    private var title: String {
        readiness == .unavailable ? "Tap to Send isn't available on this Mac." : TapCopy.gesture
    }

    private var secondLine: String {
        switch readiness {
        case .unavailable: "It needs the light sensor next to a MacBook's camera. You can still share from your phone, and send from the TapDrop menu."
        case .noPhone: "Set up your phone first. It takes about a minute."
        default: TapCopy.hold
        }
    }

    private var heroLink: DeviceHero.Link {
        switch readiness {
        case .ready: .live
        case .waiting: .searching
        case .noPhone: .setup
        case .off: .connected
        case .unavailable: .offline
        }
    }
}

// MARK: - Readiness

private struct ReadinessGroup: View {
    let readiness: TapReadiness
    let phone: String
    let room: TapPane.RoomState
    @ObservedObject private var store = Store.shared
    @ObservedObject private var status = TapStatus.shared

    var body: some View {
        SettingsGroup("Status", footer: footer) {
            SettingsRow("sun.max.fill", tint: TDTint.orange, title: "Light sensor",
                        subtitle: readiness == .unavailable ? "This Mac doesn't have one TapDrop can use. You can still share from your phone."
                                                            : "In the notch, next to the camera. It sees your phone's screen light up.") {
                CheckBadge(state: readiness == .unavailable ? .missing : .ok)
            }
            SettingsRow("smartphone", tint: TDTint.green, title: "Phone", subtitle: phoneLine) {
                switch readiness {
                case .noPhone:
                    Button("Set Up…") { SetupWindow.shared.show() }
                        .buttonStyle(.td(.secondary, size: .small))
                case .ready:
                    CheckBadge(state: .ok)
                default:
                    CheckBadge(state: store.liveDevices.isEmpty ? .warning : .ok)
                }
            }
            ForEach(status.videos.keys.sorted(), id: \.self) { name in
                if let v = status.videos[name], v.total > 0 {
                    SettingsRow("film.stack.fill", tint: TDTint.pink, title: status.videos.count > 1 ? "Videos on \(name)" : "Videos",
                                subtitle: v.ready
                                    ? "All \(v.total) are ready: a tap sends the original video, even mid-clip."
                                    : "Getting \(name)'s videos ready to send by tap: \(v.done) of \(v.total). It keeps going while the phone is unlocked.") {
                        if v.ready {
                            CheckBadge(state: .ok)
                        } else {
                            ProgressView(value: Double(v.done), total: Double(max(v.total, 1)))
                                .progressViewStyle(.circular)
                                .controlSize(.small)
                                .accessibilityLabel("\(v.done) of \(v.total) videos ready")
                        }
                    }
                }
            }
            if readiness != .unavailable {
                SettingsRow(room == .dark ? "moon.stars.fill" : "lightbulb.fill", tint: TDTint.slate, title: "Room light",
                            subtitle: roomLine) {
                    if room != .unknown {
                        Text(room == .dark ? "Dark" : "Lit")
                            .tdFont(.caption)
                            .foregroundStyle(TD.textSecondary)
                            .padding(.horizontal, 8)
                            .frame(height: 22)
                            .background(Capsule().fill(TD.fill))
                    }
                }
            }
        }
    }

    private var footer: String? {
        switch readiness {
        case .off: "Tap to Send is off, so touching your phone to the notch does nothing."
        case .unavailable: nil
        default: "Tap to Send works with every phone paired with this Mac."
        }
    }

    private var phoneLine: String {
        let live = store.devices.filter { store.liveDevices.contains($0.id) }
        if let p = live.first { return "\(p.name) is connected and ready." }
        if store.devices.isEmpty { return "No phone yet." }
        return "\(phone) connects while its screen is on and Tap to Send is on in TapDrop."
    }

    private var roomLine: String {
        switch room {
        case .lit, .dark: "Taps work the same in any light: only your phone's screen counts."
        case .unknown: "Checked while your phone is connected."
        }
    }
}

// MARK: - Test your tap

private struct TestCard: View {
    @ObservedObject var model: TapTestModel
    let readiness: TapReadiness
    let phone: String
    let phase: TapStatus.Phase
    let dark: Bool
    @ObservedObject private var store = Store.shared

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s) {
            SectionHeader("Test your tap") {
                readout
            }
            .padding(.horizontal, TD.Space.xs)

            GlassCard(.surface, padding: 0, radius: TD.Radius.row + 2) {
                VStack(alignment: .leading, spacing: 0) {
                    if chartless {
                        // nothing to plot: the reason and the way out, on their own
                        plot
                            .frame(height: 212)
                            .frame(maxWidth: .infinity)
                    } else {
                        plot
                            .frame(height: 168)
                            .padding(.horizontal, TD.Space.m)
                            .padding(.top, TD.Space.m)
                        Legend()
                            .padding(.horizontal, TD.Space.l)
                            .padding(.top, TD.Space.s)
                            .padding(.bottom, TD.Space.m)
                        TDDivider()
                        Guidance(words: words)
                            .padding(.horizontal, TD.Space.l)
                            .padding(.vertical, TD.Space.m + 2)
                        CalibrationStrip()
                    }
                }
            }
            .tdAnimation(TD.Motion.standard, value: chartless)
        }
    }

    /// Tapping is off, or the sensor went quiet: no chart, legend or running commentary.
    private var chartless: Bool { readiness == .off || readiness == .unavailable || model.stalled }

    @ViewBuilder private var readout: some View {
        if model.stalled {
            StatusPill(.error, "No readings")
        } else if model.running && model.hasReadings {
            HStack(spacing: TD.Space.s) {
                LuxReadout(trace: model.trace)
                if let p = phasePill {
                    StatusPill(p.0, p.1)
                        .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
            }
            .tdAnimation(TD.Motion.standard, value: phasePill?.1)
        } else if model.running {
            StatusPill(.searching, "Starting")
        }
    }

    @ViewBuilder private var plot: some View {
        if readiness == .unavailable {
            EmptyState("sun.max.trianglebadge.exclamationmark", title: "No light sensor",
                       message: "This Mac doesn't have a light sensor TapDrop can use.", compact: true)
                .frame(maxWidth: 380)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if readiness == .off {
            VStack(spacing: TD.Space.m) {
                EmptyState("hand.tap", title: "Tap to Send is off",
                           message: "Nothing is sent when your phone touches the notch. Turn it on to try a tap.", compact: true)
                Button("Turn On") { withTDAnimation { store.settings.notchTap = true } }
                    .buttonStyle(.td(.primary, size: .small))
            }
            .frame(maxWidth: 380)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if model.stalled {
            VStack(spacing: TD.Space.m) {
                EmptyState("exclamationmark.triangle", title: "The light sensor went quiet",
                           message: "No reading for a few seconds. If the lid is closed, open it: the sensor sits next to the camera.",
                           compact: true)
                Button("Try Again") { model.restart() }
                    .buttonStyle(.td(.secondary, size: .small))
            }
            .frame(maxWidth: 380)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if !model.hasReadings {
            LoadingPlot()
        } else {
            LightChart(model: model, trace: model.trace)
        }
    }

    /// What's happening during a tap (the light key being judged, then its verdict).
    private var phasePill: (TDStatus, String)? {
        switch phase {
        case .keying: (.searching, "Looking for its light")
        case .accepted: (.live, "That counted")
        case .failed: (.error, "Didn't count")
        case .idle: nil
        }
    }

    // MARK: words

    private var words: Guidance.Words {
        switch readiness {
        case .unavailable:
            return .init(symbol: "iphone.and.arrow.forward", tint: TDTint.gray, title: "Share from your phone instead",
                         detail: "Use the share sheet on your phone, or send files from the TapDrop menu.")
        case .off:
            return .init(symbol: "hand.tap.fill", tint: TDTint.gray, title: "Tap to Send is off",
                         detail: "Nothing is sent when your phone touches the notch.")
        default:
            break
        }
        if case .keying = phase {
            return .init(symbol: "light.max", tint: TDTint.aurora, title: "Your phone is lighting up", detail: TapCopy.hold)
        }
        if let m = model.moment {
            switch m {
            case .sent(let p, _):
                return .init(symbol: "checkmark", tint: TDTint.green, title: "That counted",
                             detail: "\(p) is sending what's on its screen.")
            case .result(let r):
                if r.accepted {
                    return .init(symbol: "checkmark", tint: TDTint.green, title: "That counted",
                                 detail: "The notch saw \(TapWords.light(r.src)). \(r.phone) is sending.")
                }
                if TapCopy.quiet(r.why ?? "") {
                    // a calibration sample: measured, not failed
                    return .init(symbol: "sun.max.fill", tint: TDTint.aurora, title: "Measured your screen",
                                 detail: "That was a calibration tap, so nothing was sent.")
                }
                // the reasons every surface words the same way, then the test's own fuller words
                let w = TapCopy.hint(r.why ?? "").map { (title: $0.title, detail: $0.detail + ".") }
                    ?? TapWords.why(r.why ?? "", phone: r.phone)
                return .init(symbol: r.src == nil ? "hand.raised.fill" : "xmark", tint: r.src == nil ? TDTint.gray : TDTint.orange,
                             title: w.title, detail: w.detail)
            case .trigger(let k, let phones, _):
                if !phones {
                    return .init(symbol: "hand.raised.fill", tint: TDTint.gray,
                                 title: k == .cover ? "Covered, but nothing was sent" : "Light at the notch, but nothing was sent",
                                 detail: "No phone is connected. A hand or a lamp can never send.")
                }
                return .init(symbol: "dot.radiowaves.left.and.right", tint: TDTint.aurora, title: "Asking \(phone) to light up",
                             detail: "The notch is watching for its light.")
            }
        }
        if readiness == .noPhone {
            return .init(symbol: "qrcode", tint: TDTint.aurora, title: "Set up your phone to try a tap",
                         detail: "Meanwhile, cover the notch with your hand: the line drops, and nothing is sent.")
        }
        if readiness == .waiting {
            return .init(symbol: "smartphone", tint: TDTint.orange, title: "Wake \(phone) to try a tap",
                         detail: "It connects while its screen is on. Meanwhile, cover the notch: nothing is sent.")
        }
        return .init(symbol: "hand.point.up.left.fill", tint: TDTint.aurora,
                     title: "Touch the top of your phone's screen to the notch",
                     detail: "Hold it there until your phone buzzes. The line jumps when the notch sees your screen light up.")
    }
}

/// The current level, steadied a little so it doesn't flicker, with a still dot that says it's live.
private struct LuxReadout: View {
    @ObservedObject var trace: TapTrace

    var body: some View {
        let lux = trace.latest?.lux ?? 0
        let v = lux < 10 ? (lux * 10).rounded() / 10 : lux.rounded()
        HStack(spacing: 6) {
            Circle()
                .fill(TD.success)
                .frame(width: 6, height: 6)
                .shadow(color: TD.success.opacity(0.6), radius: 2.5)
            Text(lux < 10 ? String(format: "%.1f lux", v) : "\(Int(v)) lux")
                .tdFont(.number(12, .semibold))
                .foregroundStyle(TD.textSecondary)
                .contentTransition(.numericText(value: v))
                .animation(.easeOut(duration: 0.2), value: v)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Light at the notch, live: \(Int(v)) lux")
    }
}

/// The chart while the first readings arrive.
private struct LoadingPlot: View {
    var body: some View {
        VStack(spacing: TD.Space.s) {
            ProgressRing(progress: nil, lineWidth: 2.5)
                .frame(width: 22, height: 22)
            Text("Reading the light sensor…")
                .tdFont(.callout)
                .foregroundStyle(TD.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            // a placeholder trace, parted in the middle for the words
            WaveShape()
                .stroke(TD.fillStrong, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .tdShimmer()
                .padding(.vertical, 40)
                .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.24),
                                             .init(color: .clear, location: 0.36), .init(color: .clear, location: 0.64),
                                             .init(color: .black, location: 0.76), .init(color: .black, location: 1)],
                                     startPoint: .leading, endPoint: .trailing))
        }
    }

    private struct WaveShape: Shape {
        func path(in r: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: r.minX, y: r.midY))
            for i in 1...48 {
                let x = r.minX + r.width * CGFloat(i) / 48
                p.addLine(to: CGPoint(x: x, y: r.midY + sin(CGFloat(i) * 0.5) * r.height * 0.12))
            }
            return p
        }
    }
}

/// The light at the notch over the last few seconds, with the moments that matter.
private struct LightChart: View {
    @ObservedObject var model: TapTestModel
    @ObservedObject var trace: TapTrace
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || model.demoFeed || !model.running)) { ctx in
            chart(end: end(ctx.date))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Light at the notch")
        .accessibilityValue(axValue)
    }

    /// The right edge trails the clock a little, so new readings slide in rather than pop.
    private func end(_ now: Date) -> Date {
        guard let last = trace.latest?.t else { return now }
        if reduceMotion || model.demoFeed || !model.running { return last }
        return min(now, last.addingTimeInterval(0.25)).addingTimeInterval(-0.12)
    }

    private var axValue: String {
        var parts: [String] = []
        if let l = trace.latest { parts.append("\(Int(l.lux.rounded())) lux now") }
        if let r = trace.room { parts.append("room \(Int(r.rounded())) lux") }
        if let m = trace.marks.last { parts.append(Self.label(m.kind)) }
        return parts.joined(separator: ", ")
    }

    static func label(_ k: TapTestModel.MarkKind) -> String {
        switch k {
        case .cover: "Covered"
        case .touch: "Phone at the notch"
        case .keyOK: "Light seen"
        case .keyFail: "Not seen"
        case .sent: "Sent"
        }
    }

    /// Steps of the light axis, so it doesn't twitch with every reading.
    private var top: Double {
        let peak = max(trace.samples.map(\.lux).max() ?? 0, trace.need?.lux ?? 0, trace.marks.compactMap(\.lux).max() ?? 0)
        let want = max(peak * 1.3, (trace.room ?? 0) * 2.2, 30)
        return [30, 60, 120, 200, 300, 500, 750, 1000, 1500, 2000, 3000, 5000, 8000, 12000, 20000, 40000]
            .first { $0 >= want } ?? want
    }

    private func chart(end: Date) -> some View {
        let start = end.addingTimeInterval(-TapTestModel.span)
        let top = top
        return Chart {
            // vectorized plots: one pass over the readings per frame instead of a mark per reading
            AreaPlot(trace.samples, x: .value("Time", \.t), y: .value("Light", \.lux))
                .interpolationMethod(.monotone)
                .foregroundStyle(LinearGradient(colors: [TD.violet.opacity(0.32), TD.blue.opacity(0.02)],
                                                startPoint: .top, endPoint: .bottom))
            LinePlot(trace.samples, x: .value("Time", \.t), y: .value("Light", \.lux))
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                .foregroundStyle(LinearGradient(colors: [TD.blue, TD.violet], startPoint: .leading, endPoint: .trailing))
            if let room = trace.room {
                RuleMark(y: .value("Room", room))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 4]))
                    .foregroundStyle(TD.textTertiary.opacity(0.7))
                    .annotation(position: .top, alignment: .leading, spacing: 3) {
                        AxisTag(text: "Room", tint: TD.textTertiary)
                    }
            }
            if let n = trace.need {
                RuleMark(xStart: .value("From", n.at.addingTimeInterval(-0.9)), xEnd: .value("To", n.at.addingTimeInterval(1.3)),
                         y: .value("Needed", n.lux))
                    .lineStyle(StrokeStyle(lineWidth: 1.4, dash: [4, 3]))
                    .foregroundStyle(TD.success.opacity(0.9))
            }
            ForEach(trace.marks) { m in
                if m.kind == .cover || m.kind == .touch || m.kind == .sent {
                    RuleMark(x: .value("When", m.t))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 3]))
                        .foregroundStyle(TD.textTertiary.opacity(0.45))
                }
                PointMark(x: .value("When", m.t), y: .value("Light", y(m, top: top)))
                    .symbolSize(0)
                    .annotation(position: .overlay) {
                        MarkBadge(kind: m.kind)
                    }
            }
        }
        .chartXScale(domain: start...end)
        .chartYScale(domain: 0...top, range: .plotDimension(startPadding: 14, endPadding: 16), type: .squareRoot)
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
        .chartPlotStyle { plot in
            plot.clipShape(Rectangle().inset(by: -1))
        }
        .tdAnimation(TD.Motion.standard, value: top)
    }

    /// Covers sit in the dip, verdicts at their peak, sends along the top.
    private func y(_ m: TapTestModel.Mark, top: Double) -> Double {
        switch m.kind {
        // (the axis is a square root: these keep a sent badge clear of the verdict just under it)
        case .sent: return top * 0.97
        case .cover, .touch: return max(0, m.lux ?? trace.lux(near: m.t) ?? 0)
        case .keyOK, .keyFail: return min(top * 0.62, max(m.lux ?? trace.lux(near: m.t) ?? 0, (trace.room ?? 0) * 1.25))
        }
    }
}

private struct AxisTag: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.system(size: 9.5, weight: .semibold))
            .tracking(0.2)
            .foregroundStyle(tint)
    }
}

/// A moment on the chart.
private struct MarkBadge: View {
    let kind: TapTestModel.MarkKind

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 9, weight: .heavy))
            .foregroundStyle(filled ? (kind == .keyFail ? .white : TD.onAccent) : TD.textSecondary)
            .frame(width: 20, height: 20)
            .background {
                if filled {
                    Circle().fill(LinearGradient(colors: tint, startPoint: .top, endPoint: .bottom))
                } else {
                    Circle().fill(TD.surfaceRaised)
                }
            }
            .overlay(Circle().strokeBorder(TD.hairline, lineWidth: 1))
            .shadow(color: .black.opacity(filled ? 0.25 : 0.12), radius: 4, y: 1)
            .help(LightChart.label(kind))
    }

    private var filled: Bool { kind != .cover && kind != .touch }

    private var symbol: String {
        switch kind {
        case .cover: "hand.raised.fill"
        case .touch: "dot.radiowaves.left.and.right"
        case .keyOK: "checkmark"
        case .keyFail: "xmark"
        case .sent: "arrow.up"
        }
    }

    private var tint: [Color] {
        switch kind {
        case .cover, .touch: TDTint.gray
        case .keyOK, .sent: [TD.accent, TD.accent]
        case .keyFail: TDTint.orange
        }
    }
}

private struct Legend: View {
    var body: some View {
        // one row when it fits; in a narrow window the two lines move to a row of their own
        ViewThatFits(in: .horizontal) {
            HStack(spacing: TD.Space.l) {
                marks
                Spacer(minLength: TD.Space.m)
                lines
            }
            VStack(alignment: .leading, spacing: TD.Space.s) {
                HStack(spacing: TD.Space.l) { marks }
                HStack(spacing: TD.Space.l) { lines }
            }
        }
        .tdFont(.caption)
        .foregroundStyle(TD.textSecondary)
        .accessibilityHidden(true)
    }

    @ViewBuilder private var marks: some View {
        item(.cover)
        item(.keyOK)
        item(.keyFail)
        item(.sent)
    }

    @ViewBuilder private var lines: some View {
        line("Room", TD.textTertiary)
        line("Needed", TD.success)
    }

    private func line(_ title: String, _ tint: Color) -> some View {
        HStack(spacing: 5) {
            Path { p in
                p.move(to: CGPoint(x: 0, y: 1))
                p.addLine(to: CGPoint(x: 14, y: 1))
            }
            .stroke(tint, style: StrokeStyle(lineWidth: 1.4, dash: [3, 2.5]))
            .frame(width: 14, height: 2)
            Text(title)
        }
        .fixedSize()
    }

    private func item(_ k: TapTestModel.MarkKind) -> some View {
        HStack(spacing: 5) {
            MarkBadge(kind: k).scaleEffect(0.8).frame(width: 16, height: 16)
            Text(LightChart.label(k))
        }
        .fixedSize()
    }
}

/// What's happening, in plain words.
private struct Guidance: View {
    struct Words: Equatable {
        let symbol: String
        let tint: [Color]
        let title: String
        let detail: String
    }

    let words: Words

    var body: some View {
        HStack(alignment: .center, spacing: TD.Space.m) {
            IconChip(words.symbol, tint: words.tint, size: 30)
                .contentTransition(.symbolEffect(.replace))
            VStack(alignment: .leading, spacing: 2) {
                Text(words.title)
                    .tdFont(.bodySemibold)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                Text(words.detail)
                    .tdFont(.callout)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .id(words.title)
            .transition(.opacity)
            Spacer(minLength: 0)
        }
        .tdAnimation(TD.Motion.standard, value: words)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

// MARK: - Recent taps

private struct RecentTaps: View {
    @ObservedObject private var log = TapLog.shared
    @State private var open: Set<UUID> = []

    var body: some View {
        let records = TapRecords.build(log.events)
        VStack(alignment: .leading, spacing: TD.Space.s) {
            SectionHeader("Recent taps") {
                Button {
                    TapLogFile.open()
                } label: {
                    HStack(spacing: 3) {
                        Text("Tap Log")
                        Image(systemName: "arrow.up.right").font(.system(size: 9, weight: .bold))
                    }
                }
                .buttonStyle(.tdQuiet)
                .help("Open the full tap log, with the light levels behind every tap")
            }
            .padding(.leading, TD.Space.xs)

            GlassCard(.surface, padding: 0, radius: TD.Radius.row + 2) {
                if records.isEmpty {
                    EmptyState("hand.tap", title: "No taps yet",
                               message: "Each tap shows up here with what happened, and the numbers behind it.", compact: true)
                        .padding(.vertical, TD.Space.xl)
                        .padding(.horizontal, TD.Space.l)
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(records.enumerated()), id: \.element.id) { i, r in
                            if i > 0 { TDDivider(inset: TD.Space.m + 26 + TD.Space.m) }
                            TapRecordRow(record: r, expanded: open.contains(r.id)) {
                                withTDAnimation {
                                    if open.contains(r.id) { open.remove(r.id) } else { open.insert(r.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        .tdAnimation(TD.Motion.standard, value: records.map(\.id))
        .onAppear {
            if SettingsDemo.expandFirstTap, let first = records.first { open.insert(first.id) }
        }
    }
}

private struct TapRecordRow: View {
    let record: TapRecord
    let expanded: Bool
    let toggle: () -> Void
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: TD.Space.m) {
                IconChip(symbol, tint: tint, size: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.title)
                        .tdFont(.bodyMedium)
                        .foregroundStyle(TD.text)
                        .fixedSize(horizontal: false, vertical: true)
                    if let d = record.detail {
                        Text(d)
                            .tdFont(.callout)
                            .foregroundStyle(TD.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: TD.Space.m)
                Text(record.date.formatted(date: .omitted, time: .shortened))
                    .tdFont(.number(11, .medium))
                    .foregroundStyle(TD.textTertiary)
                    .help(record.date.formatted(date: .abbreviated, time: .standard))
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(TD.textTertiary)
                    .rotationEffect(.degrees(expanded ? 90 : 0))
            }
            .padding(.horizontal, TD.Space.m)
            .padding(.vertical, 10)
            .frame(minHeight: 48)
            .background(hover ? TD.fill : .clear)
            .contentShape(Rectangle())
            .onTapGesture(perform: toggle)
            .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(expanded ? "Hides the details" : "Shows the details")

            if expanded {
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(Array(record.lines.enumerated()), id: \.offset) { _, line in
                        HStack(alignment: .firstTextBaseline, spacing: TD.Space.s) {
                            Text(line.0.formatted(.dateTime.hour().minute().second()))
                                .foregroundStyle(TD.textTertiary)
                            Text(line.1)
                                .foregroundStyle(TD.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .textSelection(.enabled)
                        }
                    }
                }
                .font(.system(size: 11, design: .monospaced))
                .padding(.leading, TD.Space.m + 26 + TD.Space.m)
                .padding(.trailing, TD.Space.l)
                .padding(.bottom, TD.Space.m)
                .transition(.opacity.combined(with: .move(edge: .top)))
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Details")
            }
        }
        .clipped()
    }

    private var symbol: String {
        switch record.outcome {
        case .sent: "checkmark"
        case .failed: "xmark"
        case .ignored: "hand.raised.fill"
        case .nothing: "hand.raised.slash.fill"
        case .other: "info"
        }
    }

    private var tint: [Color] {
        switch record.outcome {
        case .sent: TDTint.green
        case .failed: TDTint.orange
        case .ignored, .nothing, .other: TDTint.gray
        }
    }
}
