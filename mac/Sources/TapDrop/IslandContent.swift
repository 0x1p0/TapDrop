import AppKit
import SwiftUI

// What the notch island shows, state by state. One anatomy throughout (as the panel's rows and Android's pill):
// a leading visual (ring, thumbnail, tile or chip), a title and a subtitle, and a trailing number or actions.
// The island is hardware black in both appearances, so everything here is drawn for a dark surface.

/// What one presentation of the island shows: a copy of `Activity`'s fields. Content views take this value
/// rather than observing `Activity`, so when the island moves on (a transfer becomes its result) the content on
/// its way out keeps showing what it showed, and the two cross-dissolve instead of the old one popping to the new.
struct IslandSnapshot: Equatable {
    var serial: Int
    var phase: Activity.Phase
    var kind: Activity.Kind
    var incoming: Bool
    var peer: String
    var title: String
    var detail: String
    var progress: Double?
    var bytesDone: Int64
    var bytesTotal: Int64
    var files: [URL]
    var link: URL?
    var symbol: String
    var noticeStyle: Activity.NoticeStyle
    var codeSource: String
    var arrived: [URL]
    var outgoing: [OutboxItem]
    var startedAt: Date

    @MainActor init(_ a: Activity) {
        serial = a.serial
        phase = a.phase
        kind = a.kind
        incoming = a.incoming
        peer = a.peer
        title = a.title
        detail = a.detail
        progress = a.progress
        bytesDone = a.bytesDone
        bytesTotal = a.bytesTotal
        files = a.files
        link = a.link
        symbol = a.symbol
        noticeStyle = a.noticeStyle
        codeSource = a.codeSource
        arrived = a.arrived
        outgoing = a.outgoing
        startedAt = a.startedAt
    }
}

/// The expanded island's content for the current activity.
struct IslandContent: View {
    let activity: IslandSnapshot

    var body: some View {
        switch activity.phase {
        case .active:
            IslandTransfer(activity: activity)
        case .done:
            switch activity.kind {
            case .otp: IslandCode(activity: activity)
            case .transfer, .pick: IslandResult(activity: activity)
            case .paired, .queued, .clipboard: IslandNotice(activity: activity)
            }
        case .failed:
            IslandFailure(activity: activity)
        case .idle:
            EmptyView()
        }
    }
}

// MARK: - Width

/// How wide the expanded island is. Like the Dynamic Island it hugs short notices, results and failures;
/// transfers and codes keep one steady width (their text changes while they're up, and mustn't make it jitter).
@MainActor
enum IslandFit {
    /// The body width for this activity, or nil for the standard width.
    static func width(for a: IslandSnapshot) -> CGFloat? {
        switch a.phase {
        case .active, .idle:
            return nil
        case .failed:
            return fit(title: a.title, subtitle: a.detail, trailing: 0)
        case .done:
            switch a.kind {
            case .otp: return nil
            case .transfer, .pick: return fit(title: a.title, subtitle: IslandResult.subtitle(a), trailing: IslandResult.trailingWidth(a))
            case .paired, .queued, .clipboard: return fit(title: a.title, subtitle: a.detail, trailing: 0)
            }
        }
    }

    private static func fit(title: String, subtitle: String, trailing: CGFloat) -> CGFloat {
        let t = measure(IslandText.typeset(title), size: 15, weight: .semibold, tracking: -0.15)
        let s = measure(IslandText.typeset(subtitle), size: 12, weight: .regular, tracking: 0)
        let text = max(t, s)
        return IslandMetrics.padding * 2 + IslandMetrics.glyph + 14 + text + (trailing > 0 ? 14 + trailing : 0) + 6
    }

    private static func measure(_ s: String, size: CGFloat, weight: NSFont.Weight, tracking: CGFloat) -> CGFloat {
        guard !s.isEmpty else { return 0 }
        let attr = NSAttributedString(string: s, attributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .kern: tracking])
        return ceil(attr.size().width)
    }
}

// MARK: - Row

/// Leading visual · title over subtitle · trailing.
struct IslandRow<Leading: View, Trailing: View>: View {
    var title: String
    var subtitle: String
    var subtitleLines = 1
    /// Sentences (notices, failures) may wrap once rather than lose their end, say a long phone name.
    var titleLines = 1
    /// Middle truncation suits file names; tail suits sentences.
    var titleTruncation: Text.TruncationMode = .middle
    @ViewBuilder var leading: Leading
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            leading
                .frame(width: IslandMetrics.glyph, height: IslandMetrics.glyph)
            VStack(alignment: .leading, spacing: 3) {
                IslandText(text: title)
                    .tdFont(.headline)
                    .foregroundStyle(TD.text)
                    .lineLimit(titleLines)
                    .truncationMode(titleTruncation)
                    .fixedSize(horizontal: false, vertical: titleLines > 1)
                if !subtitle.isEmpty {
                    Text(IslandText.typeset(subtitle))
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                        .lineLimit(subtitleLines)
                        .truncationMode(subtitleLines == 1 ? .middle : .tail)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .accessibilityElement(children: .combine)
    }

}

/// Text that blurs from one string to the next (a title that changes while the island is open).
struct IslandText: View {
    var text: String
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        ZStack(alignment: .leading) {
            Text(IslandText.typeset(text))
                .id(text)
                .transition(reduceMotion ? .opacity : .modifier(active: BlurFade(on: false), identity: BlurFade(on: true)))
        }
        .animation(TD.Motion.resolved(.easeOut(duration: 0.22), reduceMotion: reduceMotion), value: text)
    }

    private struct BlurFade: ViewModifier {
        var on: Bool
        func body(content: Content) -> some View {
            content.blur(radius: on ? 0 : 4).opacity(on ? 1 : 0)
        }
    }

    /// Display copy with typographic apostrophes ("It’s dark", "Android phone’s screen"); file names are left alone.
    static func typeset(_ s: String) -> String {
        guard s.contains("'"), !s.contains("/"), s.range(of: #"\.[A-Za-z0-9]{2,5}$"#, options: .regularExpression) == nil
        else { return s }
        return s.replacingOccurrences(of: "'", with: "\u{2019}")
    }
}

// MARK: - Transfer in progress

struct IslandTransfer: View {
    let activity: IslandSnapshot

    private var title: String {
        if !activity.title.isEmpty { return activity.title }
        return activity.incoming ? "Receiving…" : "Sending…"
    }

    /// "From Android phone · 12.4 MB of 48 MB"
    private var bytes: String? {
        guard activity.bytesTotal > 1 else { return nil }
        return "\(activity.bytesDone.byteString) of \(activity.bytesTotal.byteString)"
    }

    /// The newest file that has landed (incoming), or the first file going out.
    private var thumbnail: URL? {
        if activity.incoming { return activity.arrived.last }
        return activity.outgoing.lazy.compactMap { $0.path.map { URL(fileURLWithPath: $0) } }.first
    }

    /// Spins until bytes flow ("Connecting…" has no size yet).
    private var ringProgress: Double? { activity.bytesTotal > 0 ? activity.progress : nil }

    var body: some View {
        HStack(spacing: 14) {
            IslandRing(progress: ringProgress, symbol: activity.symbol, thumbnail: thumbnail)
                .frame(width: IslandMetrics.glyph, height: IslandMetrics.glyph)
            VStack(alignment: .leading, spacing: 3) {
                IslandText(text: title)
                    .tdFont(.headline)
                    .foregroundStyle(TD.text)
                    .lineLimit(1)
                    .truncationMode(.middle)
                HStack(spacing: 0) {
                    if !activity.detail.isEmpty {
                        Text(activity.detail)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        if bytes != nil { Text(" · ") }
                    }
                    if let bytes {
                        Text(bytes)
                            .monospacedDigit()
                            .contentTransition(.numericText())
                            .fixedSize()
                    }
                }
                .tdFont(.callout)
                .foregroundStyle(TD.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let p = activity.progress, activity.bytesTotal > 0 {
                IslandPercent(progress: p, size: 19)
                    .transition(.opacity)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// "63%", rounded and steady (tabular digits that roll as they change).
struct IslandPercent: View {
    var progress: Double
    var size: CGFloat

    var body: some View {
        let pct = Int((progress * 100).rounded(.down))
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text("\(pct)")
                .tdFont(.number(size))
                .contentTransition(.numericText(value: Double(pct)))
            Text("%")
                .tdFont(.number(size * 0.6, .bold))
                .foregroundStyle(TD.textSecondary)
                .padding(.leading, -size * 0.02)
        }
        .foregroundStyle(TD.text)
        .animation(.easeOut(duration: 0.2), value: pct)
        .fixedSize()
        .accessibilityElement()
        .accessibilityLabel("\(pct) percent")
    }
}

/// Progress ring around what's moving: a thumbnail once there is one, else a symbol for the kind of thing.
/// The arc glows softly on black.
struct IslandRing: View {
    var progress: Double?
    var symbol: String
    var thumbnail: URL?
    var lineWidth: CGFloat = 3.5
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        GeometryReader { g in
            let s = min(g.size.width, g.size.height)
            let inner = s - lineWidth * 2 - max(4, s * 0.12)
            ZStack {
                // glow under the arc
                ProgressRing(progress: progress, lineWidth: lineWidth, track: .clear)
                    .blur(radius: max(2, s * 0.08))
                    .opacity(0.65)
                    .accessibilityHidden(true)
                ProgressRing(progress: progress, lineWidth: lineWidth, track: .white.opacity(0.13))
                if let thumbnail {
                    Thumbnail(url: thumbnail, size: inner, radius: inner / 2, badge: false)
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                        .id(thumbnail)
                } else {
                    Image(systemName: symbol)
                        .font(.system(size: s * 0.36, weight: .semibold))
                        .foregroundStyle(TD.text)
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.pulse, options: .repeating, isActive: progress == nil && !reduceMotion)
                        .transition(.opacity)
                }
            }
            .frame(width: s, height: s)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .tdAnimation(TD.Motion.emphasized, value: thumbnail)
    }
}

// MARK: - Compact (a long transfer, tucked into the notch's sides)

struct IslandCompact: View {
    let activity: IslandSnapshot
    let notch: CGSize
    let wing: CGFloat

    var body: some View {
        HStack(spacing: 0) {
            IslandRing(progress: activity.progress, symbol: activity.symbol, lineWidth: 2.6)
                .frame(width: 23, height: 23)
                .frame(width: wing)
            Color.clear.frame(width: notch.width)
            Group {
                if let p = activity.progress {
                    IslandPercent(progress: p, size: 14)
                } else {
                    Image(systemName: activity.incoming ? "arrow.down" : "arrow.up")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(TD.textSecondary)
                }
            }
            .frame(width: wing)
        }
        .frame(height: notch.height)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(activity.title)
    }
}

// MARK: - Results

struct IslandResult: View {
    let activity: IslandSnapshot
    @State private var copied = false

    enum What { case files([URL]), link(URL), text(String), sent([OutboxItem]), nothing }

    static func what(_ a: IslandSnapshot) -> What {
        if !a.files.isEmpty { return .files(a.files) }
        if let link = a.link { return .link(link) }
        if !a.incoming { return .sent(a.outgoing) }
        if a.title == "Nothing received" { return .nothing }
        return .text(a.detail)
    }

    private var what: What { Self.what(activity) }

    /// The subtitle: the caller's detail, a link as people read it ("apple.com/macbook-air"), or for things sent,
    /// what went ("Sunset.jpg and Lagoon.jpg").
    static func subtitle(_ a: IslandSnapshot) -> String {
        if case .link(let url) = what(a), a.detail == url.absoluteString { return readable(url) }
        guard a.detail.isEmpty, case .sent(let items) = what(a), !items.isEmpty else { return a.detail }
        let names = items.map(\.displayName)
        if names.count > 2 { return "\(names[0]), \(names[1]) and \(names.count - 2) more" }
        return ListFormatter.localizedString(byJoining: names)
    }

    /// "https://www.apple.com/macbook-air/" → "apple.com/macbook-air" (queries and fragments kept).
    static func readable(_ url: URL) -> String {
        guard var host = url.host(percentEncoded: false), !host.isEmpty else { return url.absoluteString }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        var rest = url.absoluteString
        if let r = rest.range(of: "://") { rest = String(rest[r.upperBound...]) }
        if let slash = rest.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            rest = String(rest[slash...])
        } else {
            rest = ""
        }
        if rest == "/" { rest = "" }
        if rest.hasSuffix("/"), !rest.contains("?"), !rest.contains("#") { rest.removeLast() }
        return host + (rest.removingPercentEncoding ?? rest)
    }

    /// Width of the trailing actions (for fitting the island to its content).
    static func trailingWidth(_ a: IslandSnapshot) -> CGFloat {
        switch what(a) {
        case .files: 60 + 6 + 28 + 6 + 28
        case .link: 60 + 6 + 28
        case .text: 86
        case .sent, .nothing: 0
        }
    }

    var body: some View {
        let isText: Bool = if case .text = what { true } else { false }
        IslandRow(title: activity.title, subtitle: Self.subtitle(activity), subtitleLines: isText ? 2 : 1,
                  titleTruncation: activity.files.count == 1 ? .middle : .tail) {
            leading
        } trailing: {
            actions
        }
    }

    @ViewBuilder private var leading: some View {
        switch what {
        case .files(let urls):
            IslandStack(urls: urls)
                .overlay(alignment: .bottomTrailing) { IslandCheckBadge().offset(x: 6, y: 6) }
                .tdFileDrag(urls)
                .onTapGesture(perform: open)
                .help(urls.count == 1 ? "Click to open · drag it anywhere" : "Drag them anywhere")
        case .link:
            ContentTile(kind: .link, size: IslandMetrics.glyph)
                .overlay(alignment: .bottomTrailing) { IslandCheckBadge().offset(x: 6, y: 6) }
        case .text:
            IslandTextTile()
                .overlay(alignment: .bottomTrailing) { IslandCheckBadge().offset(x: 6, y: 6) }
        case .sent(let items) where !items.isEmpty:
            IslandOutbox(items: items)
                .overlay(alignment: .bottomTrailing) { IslandCheckBadge().offset(x: 6, y: 6) }
        case .sent, .nothing:
            CheckBurst()
        }
    }

    @ViewBuilder private var actions: some View {
        switch what {
        case .files(let urls):
            HStack(spacing: 6) {
                Button("Open", action: open)
                    .buttonStyle(IslandButtonStyle(prominent: true))
                    .help(urls.count == 1 ? "Open" : "Open all")
                Button {
                    NSWorkspace.shared.activateFileViewerSelecting(urls)
                    Activity.shared.dismiss()
                } label: { Label("Show in Finder", systemImage: "folder") }
                    .buttonStyle(IslandIconButtonStyle())
                    .help("Show in Finder")
                copyButton
            }
            .transition(.opacity)
        case .link(let url):
            HStack(spacing: 6) {
                Button("Open") {
                    NSWorkspace.shared.open(url)
                    Activity.shared.dismiss()
                }
                .buttonStyle(IslandButtonStyle(prominent: true))
                copyButton
            }
        case .text:
            IslandCopiedPill(copied: true)
        case .sent, .nothing:
            EmptyView()
        }
    }

    private var copyButton: some View {
        Button(action: copy) {
            Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(IslandIconButtonStyle(tint: copied ? TD.success : nil))
        .help(copied ? "Copied" : "Copy")
    }

    private func open() {
        switch what {
        case .files(let urls): IslandActions.open(urls)
        case .link(let url): NSWorkspace.shared.open(url)
        default: return
        }
        Activity.shared.dismiss()
    }

    private func copy() {
        let pb = NSPasteboard.general
        switch what {
        case .files(let urls):
            pb.clearContents()
            pb.writeObjects(urls.map { $0 as NSURL })
        case .link(let url):
            pb.clearContents()
            pb.setString(url.absoluteString, forType: .string)
        default:
            return
        }
        TD.haptic()
        withTDAnimation(TD.Motion.hover) { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { withTDAnimation(TD.Motion.hover) { copied = false } }
    }
}

enum IslandActions {
    /// Opens files in their default app — all together when they share one (photos land in one Preview window).
    @MainActor static func open(_ urls: [URL]) {
        let ws = NSWorkspace.shared
        let apps = Set(urls.compactMap { ws.urlForApplication(toOpen: $0) })
        if urls.count > 1, apps.count == 1, let app = apps.first {
            ws.open(urls, withApplicationAt: app, configuration: NSWorkspace.OpenConfiguration())
        } else {
            urls.prefix(12).forEach { ws.open($0) }
        }
    }
}

/// Received files: one thumbnail, or up to three fanned out like a little stack of photos.
/// (The island's take on the panel's file stack: same fan, bigger, and it fans out as it lands.)
struct IslandStack: View {
    let urls: [URL]
    var size: CGFloat = IslandMetrics.glyph
    @State private var fanned = false
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    var body: some View {
        Group {
            if urls.count == 1 {
                Thumbnail(url: urls[0], size: size, radius: 12)
            } else {
                ZStack {
                    ForEach(Array(urls.prefix(3).enumerated().reversed()), id: \.offset) { i, url in
                        Thumbnail(url: url, size: size - 6, radius: 11, badge: false)
                            .rotationEffect(.degrees(fanned ? Double(i) * 8 : 0))
                            .offset(x: fanned ? CGFloat(i) * 3 : 0, y: fanned ? CGFloat(i) * -1.5 : 0)
                            .shadow(color: .black.opacity(i == 0 ? 0.5 : 0), radius: 3, y: 1)
                    }
                }
                .frame(width: size, height: size)
                .onAppear {
                    if reduceMotion { fanned = true } else { withAnimation(TD.Motion.emphasized.delay(0.12)) { fanned = true } }
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files")
    }
}

/// What's queued for the phone: thumbnails of the files, or the text.
struct IslandOutbox: View {
    let items: [OutboxItem]

    var body: some View {
        let files = items.compactMap { $0.path.map { URL(fileURLWithPath: $0) } }
        if !files.isEmpty {
            IslandStack(urls: files)
        } else if items.first?.text != nil {
            IslandTextTile()
        } else {
            IconChip("tray.and.arrow.up.fill", size: IslandMetrics.glyph)
        }
    }
}

/// Text, as a little page: an opening quote over a few lines. (The words themselves are in the subtitle; at this
/// size they'd only be specks, cut mid-word.)
struct IslandTextTile: View {
    var size: CGFloat = IslandMetrics.glyph

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: (size * 0.26).rounded(), style: .continuous)
        let line = size * 0.068
        VStack(alignment: .leading, spacing: size * 0.1) {
            Image(systemName: "quote.opening")
                .font(.system(size: size * 0.24, weight: .black))
                .foregroundStyle(TD.aurora)
            VStack(alignment: .leading, spacing: line * 1.25) {
                Capsule().frame(width: size * 0.6, height: line)
                Capsule().frame(width: size * 0.6, height: line)
                Capsule().frame(width: size * 0.36, height: line)
            }
            .foregroundStyle(TD.textTertiary.opacity(0.9))
        }
        .padding(.leading, size * 0.19)
        .padding(.top, size * 0.17)
        .frame(width: size, height: size, alignment: .topLeading)
        .background(shape.fill(LinearGradient(colors: [TD.fillHover, TD.fill], startPoint: .top, endPoint: .bottom)))
        .overlay(shape.strokeBorder(TD.hairline, lineWidth: 1))
        .accessibilityHidden(true)
    }
}

/// The small "done" check on a thumbnail's corner (a CheckBurst, ringed in the island's black).
struct IslandCheckBadge: View {
    var body: some View {
        CheckBurst()
            .frame(width: 19, height: 19)
            .background(Circle().fill(Color.black).padding(-2.5))
    }
}

/// "Copied" confirmation, or a Copy button once it has settled.
struct IslandCopiedPill: View {
    var copied: Bool

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: "checkmark")
                .font(.system(size: 10.5, weight: .bold))
            Text("Copied")
        }
        .font(.system(size: 12, weight: .semibold))
        .foregroundStyle(TD.success)
        .padding(.horizontal, 11)
        .frame(height: 28)
        .background(Capsule().fill(TD.success.opacity(0.16)))
        .accessibilityElement(children: .combine)
    }
}

// MARK: - One-time code

/// The code, big: rounded, tabular digits grouped in threes (or fours), dropping in one by one.
/// It's already on the clipboard; the pill confirms that, and copies it again on a click.
struct IslandCode: View {
    let activity: IslandSnapshot
    @State private var copied = true
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    private var reduceMotion: Bool { systemReduceMotion || DemoReduceMotion.on }

    private var code: String { activity.title }

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            IconChip("key.fill", tint: TDTint.orange, size: IslandMetrics.glyph)
            VStack(alignment: .leading, spacing: 0) {
                Text(activity.codeSource.isEmpty ? "One-time code" : activity.codeSource)
                    .tdFont(.caption)
                    .foregroundStyle(TD.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                digits
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            VStack(spacing: 6) {
                Button(action: copy) {
                    HStack(spacing: 5) {
                        Image(systemName: copied ? "checkmark" : "doc.on.doc")
                            .font(.system(size: 11, weight: .bold))
                            .contentTransition(.symbolEffect(.replace))
                        Text(copied ? "Copied" : "Copy")
                            .contentTransition(.interpolate)
                    }
                }
                .buttonStyle(IslandButtonStyle(prominent: false, tint: copied ? TD.success : nil))
                .help(copied ? "On your clipboard — ⌘V to paste" : "Copy the code")
                if copied {
                    Text("⌘V to paste")
                        .tdFont(.caption)
                        .foregroundStyle(TD.textTertiary)
                        .transition(.opacity)
                }
            }
            .fixedSize()
        }
        .onAppear {
            if reduceMotion { shown = true } else { withAnimation(.spring(response: 0.45, dampingFraction: 0.7)) { shown = true } }
        }
        .task {
            // The confirmation settles into a Copy button (the clipboard may have moved on since).
            try? await Task.sleep(for: .seconds(5))
            withTDAnimation(TD.Motion.standard) { copied = false }
        }
        .accessibilityElement(children: .contain)
    }

    private var digits: some View {
        let chars = Array(code)
        // 482 915 · 4829 1537; codes with letters or dashes stay as they are
        let grouped = chars.count >= 6 && chars.allSatisfy(\.isNumber)
        let group = chars.count % 4 == 0 && chars.count != 12 ? 4 : 3
        let size: CGFloat = chars.count <= 6 ? 44 : chars.count <= 8 ? 38 : 30
        return HStack(spacing: 0) {
            ForEach(Array(chars.enumerated()), id: \.offset) { i, ch in
                Text(String(ch))
                    .tdFont(.number(size, .semibold))
                    .foregroundStyle(TD.text)
                    .tracking(1.5)
                    .padding(.leading, grouped && i > 0 && i % group == 0 ? 11 : 0)
                    .opacity(shown ? 1 : 0)
                    .offset(y: shown || reduceMotion ? 0 : -10)
                    .blur(radius: shown || reduceMotion ? 0 : 5)
                    .animation(reduceMotion ? TD.Motion.reduced
                               : .spring(response: 0.42, dampingFraction: 0.66).delay(0.1 + Double(i) * 0.04), value: shown)
            }
        }
        // digits have no descenders: give back the empty space under the baseline, so the island's bottom margin
        // looks the same as everywhere else
        .padding(.bottom, -(size * 0.17).rounded())
        .accessibilityElement()
        .accessibilityLabel(code.map(String.init).joined(separator: " "))
    }

    private func copy() {
        SecureClipboard.set(code)
        TD.haptic()
        withTDAnimation(TD.Motion.hover) { copied = true }
    }
}

// MARK: - Notices

struct IslandNotice: View {
    let activity: IslandSnapshot

    var body: some View {
        IslandRow(title: activity.title, subtitle: activity.detail, subtitleLines: 2, titleLines: 2, titleTruncation: .tail) {
            glyph
        } trailing: {
            EmptyView()
        }
    }

    @ViewBuilder private var glyph: some View {
        let s = IslandMetrics.glyph
        switch activity.noticeStyle {
        case .ready: IslandOutbox(items: activity.outgoing)
        case .gesture: IslandGestureTile()
        case .locked: IconChip("lock.fill", tint: TDTint.orange, size: s)
        case .asleep: IconChip("moon.zzz.fill", tint: TDTint.slate, size: s)
        case .update: IconChip("arrow.down.circle.fill", tint: TDTint.blue, size: s)
        case .openApp: IconChip("square.on.square", tint: TDTint.aurora, size: s)
        case .paired: CheckBurst(tint: TD.violet)
        case .clipboard: IconChip("doc.on.clipboard.fill", tint: TDTint.aurora, size: s)
        case .text: IconChip("text.viewfinder", tint: TDTint.aurora, size: s)
        case .noText: IconChip("text.magnifyingglass", tint: TDTint.gray, size: s)
        case .info: IconChip("info", tint: TDTint.gray, size: s)
        }
    }
}

/// The gesture, small: the panel's hero, closer in (the notch, the phone's back pressed to it, its screen's light
/// spilling onto the display) in an island-sized tile. Replays on hover.
struct IslandGestureTile: View {
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        DeviceHero(link: .live)
            .frame(width: 96, height: 92)
            .offset(y: -6)
            .frame(width: IslandMetrics.glyph, height: IslandMetrics.glyph, alignment: .top)
            .clipShape(shape)
            .overlay(shape.strokeBorder(.white.opacity(0.12), lineWidth: 1))
            .accessibilityElement()
            .accessibilityLabel(TapCopy.drawing)
    }
}

// MARK: - Failure

struct IslandFailure: View {
    let activity: IslandSnapshot

    var body: some View {
        IslandRow(title: activity.title, subtitle: activity.detail, subtitleLines: 2, titleLines: 2, titleTruncation: .tail) {
            TransferGlyph(phase: .failure)
        } trailing: {
            EmptyView()
        }
    }
}

// MARK: - Buttons on the island

/// Capsule buttons for the island's black surface. Same metrics as the design system's small buttons
/// (26–28 pt, 12 pt semibold); drawn rather than glass, because the island is never the key window (glass
/// tints go gray there) and there's nothing behind solid black to refract.
struct IslandButtonStyle: ButtonStyle {
    var prominent = false
    /// A semantic tint for the label and fill (e.g. green "Copied").
    var tint: Color?

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, prominent: prominent, tint: tint)
    }

    private struct Styled: View {
        let configuration: Configuration
        let prominent: Bool
        let tint: Color?
        @State private var hover = false

        var body: some View {
            configuration.label
                .font(.system(size: 12, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(tint ?? .white)
                .padding(.horizontal, 13)
                .frame(height: 28)
                .background { fill }
                .contentShape(Capsule())
                .scaleEffect(configuration.isPressed ? 0.96 : 1)
                .animation(TD.Motion.hover, value: hover)
                .animation(TD.Motion.hover, value: configuration.isPressed)
                .animation(TD.Motion.standard, value: tint)
                .onHover { hover = $0 }
        }

        @ViewBuilder private var fill: some View {
            if prominent && tint == nil {
                Capsule()
                    .fill(LinearGradient(colors: [TD.violet.mix(with: .white, by: 0.06), TD.violetDeep],
                                         startPoint: .top, endPoint: .bottom))
                    .overlay(Capsule().fill(.white.opacity(configuration.isPressed ? 0.16 : (hover ? 0.1 : 0))))
                    .overlay(Capsule().strokeBorder(
                        LinearGradient(colors: [.white.opacity(0.35), .white.opacity(0.06)], startPoint: .top, endPoint: .bottom),
                        lineWidth: 0.75))
                    .shadow(color: TD.violet.opacity(hover ? 0.55 : 0.35), radius: hover ? 10 : 7, y: 2)
            } else {
                Capsule()
                    .fill((tint ?? .white).opacity(configuration.isPressed ? 0.26 : (hover ? 0.2 : 0.14)))
                    .overlay(Capsule().strokeBorder(.white.opacity(0.06), lineWidth: 0.5))
            }
        }
    }
}

/// Round icon buttons for the island (Show in Finder, Copy, dismiss).
struct IslandIconButtonStyle: ButtonStyle {
    var size: CGFloat = 28
    /// A quiet button has no fill until hovered.
    var filled = true
    var tint: Color?

    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, size: size, filled: filled, tint: tint)
    }

    private struct Styled: View {
        let configuration: Configuration
        let size: CGFloat
        let filled: Bool
        let tint: Color?
        @State private var hover = false

        var body: some View {
            configuration.label
                .labelStyle(.iconOnly)
                .font(.system(size: (size * 0.44).rounded(), weight: .semibold))
                .foregroundStyle(tint ?? (hover || filled ? Color.white : TD.textSecondary))
                .frame(width: size, height: size)
                .background(Circle().fill((tint ?? .white).opacity(fillOpacity)))
                .contentShape(Circle())
                .scaleEffect(configuration.isPressed ? 0.92 : 1)
                .animation(TD.Motion.hover, value: hover)
                .animation(TD.Motion.hover, value: configuration.isPressed)
                .onHover { hover = $0 }
        }

        private var fillOpacity: Double {
            if configuration.isPressed { return 0.26 }
            if hover { return 0.2 }
            return filled ? 0.14 : 0
        }
    }
}
