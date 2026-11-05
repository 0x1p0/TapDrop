import AppKit
import SwiftUI

// What the updater (Updater.swift) looks like: the Updates group in Settings → About, the What's new renderer, and
// the quiet row at the foot of the menu bar panel.

// MARK: - Settings → About

/// The Updates group: where this copy stands (or the new release, What's new and Update Now), and the daily check.
struct UpdatesGroup: View {
    @ObservedObject private var updater = Updater.shared
    @ObservedObject private var store = Store.shared

    var body: some View {
        SettingsGroup("Updates") {
            if let release = updater.available, !updater.skipped, !updater.deferred || updater.install.busy {
                ReleaseCard(release: release)
            } else {
                UpdateStatusRow()
            }
            SettingsRow("clock.arrow.circlepath", tint: TDTint.slate, title: "Check for updates automatically",
                        subtitle: "Once a day. TapDrop only asks GitHub for its newest version and sends nothing about you.") {
                Toggle("Check for updates automatically", isOn: $store.settings.checkForUpdates).tdSwitch()
            }
        }
        .tdAnimation(TD.Motion.standard, value: updater.available?.version)
        .tdAnimation(TD.Motion.standard, value: updater.deferred)
        .tdAnimation(TD.Motion.standard, value: updater.skipped)
    }
}

/// One line on where this copy stands: up to date, checking, couldn't check, skipped, postponed.
private struct UpdateStatusRow: View {
    @ObservedObject private var updater = Updater.shared

    private enum Look {
        case checking, failed(String), installFailed(UpdateProblem), skipped(String), later(String), upToDate, never
    }

    private var look: Look {
        if updater.checking { return .checking }
        if let e = updater.checkError { return .failed(e) }
        if case .failed(let p) = updater.install { return .installFailed(p) }
        if let a = updater.available {
            return updater.skipped ? .skipped(a.version) : .later(a.version)
        }
        return updater.record.lastCheck == nil ? .never : .upToDate
    }

    var body: some View {
        HStack(spacing: TD.Space.m) {
            icon
                .frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .tdFont(.bodyMedium)
                    .foregroundStyle(TD.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.interpolate)
                Text(subtitle)
                    .tdFont(.callout)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: TD.Space.m)
            trailing
        }
        .padding(.horizontal, TD.Space.m)
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .containerValue(\.tdRowInset, TD.Space.m + 26 + TD.Space.m)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private var icon: some View {
        switch look {
        case .checking:
            ProgressView().controlSize(.small).accessibilityLabel("Checking")
        case .failed, .installFailed:
            IconChip("exclamationmark.triangle.fill", tint: TDTint.orange, size: 26)
        case .skipped:
            IconChip("forward.end.fill", tint: TDTint.gray, size: 26)
        case .later:
            IconChip("arrow.down.circle.fill", tint: TDTint.aurora, size: 26)
        case .upToDate:
            IconChip("checkmark.seal.fill", tint: TDTint.green, size: 26)
        case .never:
            IconChip("arrow.triangle.2.circlepath", tint: TDTint.gray, size: 26)
        }
    }

    private var title: String {
        switch look {
        case .checking: "Checking…"
        case .failed: "Couldn't check for updates"
        case .installFailed: "Couldn't update"
        case .skipped(let v): "You skipped TapDrop \(v)"
        case .later(let v): "TapDrop \(v) is available"
        case .upToDate: "You're up to date"
        case .never: "Not checked yet"
        }
    }

    private var subtitle: String {
        switch look {
        case .checking: return "Looking for a newer version on GitHub."
        case .failed(let message): return message
        case .installFailed(let p): return p.message
        case .skipped: return "Check for Updates shows it again."
        case .later: return "You have \(updater.currentText)."
        case .upToDate:
            let checked = updater.record.lastCheck.map { " · Checked \(Self.when($0))" } ?? ""
            return "TapDrop \(updater.currentText) is the newest version\(checked)."
        case .never:
            return Store.shared.settings.checkForUpdates ? "TapDrop looks for a newer version once a day." : "Automatic checks are off."
        }
    }

    @ViewBuilder private var trailing: some View {
        switch look {
        case .later:
            Button("What's New") { updater.showAgain() }
                .buttonStyle(.td(.secondary, size: .small))
        case .installFailed:
            Button("Download from GitHub") { updater.openReleasePage() }
                .buttonStyle(.td(.secondary, size: .small))
        case .failed:
            Button("Try Again") { updater.check(manual: true) }
                .buttonStyle(.td(.secondary, size: .small))
        default:
            Button("Check for Updates…") { updater.check(manual: true) }
                .buttonStyle(.td(.secondary, size: .small))
                .disabled(updater.checking)
        }
    }

    /// "today at 9:41", "yesterday at 9:41", "Nov 3 at 9:41".
    static func when(_ d: Date) -> String {
        let time = d.formatted(date: .omitted, time: .shortened)
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "today at \(time)" }
        if cal.isDateInYesterday(d) { return "yesterday at \(time)" }
        return "\(d.formatted(.dateTime.month(.abbreviated).day())) at \(time)"
    }
}

/// A newer release: its version, What's new, and Update Now / Later / Skip This Version (or how the update is going).
private struct ReleaseCard: View {
    let release: ReleaseInfo
    @ObservedObject private var updater = Updater.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: TD.Space.m) {
                AppGlyph(size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text("TapDrop \(release.version) is available")
                        .tdFont(.headline)
                        .foregroundStyle(TD.text)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("You have \(updater.currentText).")
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                }
                Spacer(minLength: 0)
            }
            .padding(TD.Space.l)
            .background(alignment: .topTrailing) {
                AuroraBackdrop(intensity: 0.35, drift: 0)
                    .mask(LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom))
                    .allowsHitTesting(false)
            }
            .accessibilityElement(children: .combine)

            if !release.notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                TDDivider()
                VStack(alignment: .leading, spacing: TD.Space.s) {
                    Text("What's new")
                        .tdFont(.bodySemibold)
                        .foregroundStyle(TD.text)
                        .accessibilityAddTraits(.isHeader)
                    WhatsNew(markdown: release.notes, more: release.page)
                }
                .padding(.horizontal, TD.Space.l)
                .padding(.vertical, TD.Space.m + 2)
            }

            TDDivider()
            footer
                .padding(.horizontal, TD.Space.l)
                .padding(.vertical, TD.Space.m)
                .frame(minHeight: 56)
                .tdAnimation(TD.Motion.standard, value: updater.install)
        }
    }

    @ViewBuilder private var footer: some View {
        switch updater.install {
        case .none:
            HStack(spacing: TD.Space.s) {
                Button("Update Now") { updater.installNow() }
                    .buttonStyle(.td(.primary))
                    .keyboardShortcut(.defaultAction)
                Button("Later") { updater.later() }
                    .buttonStyle(.td(.secondary))
                Spacer(minLength: TD.Space.s)
                Button("Skip This Version") { updater.skip() }
                    .buttonStyle(.tdQuiet)
                    .help("Don't mention TapDrop \(release.version) again")
            }
        case .downloading(let done, let total):
            DownloadProgress(done: done, total: total) { updater.cancelInstall() }
        case .verifying:
            busy("Checking the download…")
        case .restarting(let waiting):
            busy(waiting ? "TapDrop restarts when the transfer finishes…" : "Restarting TapDrop…")
        case .failed(let problem):
            VStack(alignment: .leading, spacing: TD.Space.m) {
                HStack(alignment: .firstTextBaseline, spacing: TD.Space.s) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(TD.warning)
                        .accessibilityHidden(true)
                    Text(problem.message)
                        .tdFont(.body)
                        .foregroundStyle(TD.text)
                        .fixedSize(horizontal: false, vertical: true)
                }
                HStack(spacing: TD.Space.s) {
                    if problem.retry {
                        Button("Try Again") { updater.installNow() }
                            .buttonStyle(.td(.primary))
                    }
                    Button("Download from GitHub") { updater.openReleasePage() }
                        .buttonStyle(.td(problem.retry ? .secondary : .primary))
                    Spacer(minLength: TD.Space.s)
                    Button("Later") { updater.later() }
                        .buttonStyle(.tdQuiet)
                }
            }
        }
    }

    private func busy(_ text: String) -> some View {
        HStack(spacing: TD.Space.s + 2) {
            ProgressView().controlSize(.small)
            Text(text)
                .tdFont(.bodyMedium)
                .foregroundStyle(TD.text)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}

/// "Downloading… 42%" over a bar that fills, with Cancel.
private struct DownloadProgress: View {
    let done: Int64
    let total: Int64
    let cancel: () -> Void

    private var fraction: Double { total > 0 ? min(1, Double(done) / Double(total)) : 0 }
    private var percent: Int { Int((fraction * 100).rounded(.down)) }

    var body: some View {
        HStack(spacing: TD.Space.l) {
            VStack(alignment: .leading, spacing: TD.Space.s) {
                HStack(alignment: .firstTextBaseline, spacing: TD.Space.m) {
                    Text("Downloading… \(percent)%")
                        .tdFont(.bodyMedium)
                        .foregroundStyle(TD.text)
                        .monospacedDigit()
                        .contentTransition(.numericText(value: Double(percent)))
                    Spacer(minLength: 0)
                    if total > 0 {
                        Text("\(done.byteString) of \(total.byteString)")
                            .tdFont(.number(11.5, .medium))
                            .foregroundStyle(TD.textSecondary)
                            .contentTransition(.numericText(value: Double(done)))
                    }
                }
                GeometryReader { g in
                    ZStack(alignment: .leading) {
                        Capsule().fill(TD.fillStrong)
                        Capsule()
                            .fill(TD.aurora)
                            .frame(width: max(6, g.size.width * fraction))
                            .shadow(color: TD.violet.opacity(0.45), radius: 4)
                    }
                }
                .frame(height: 5)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Downloading the update")
            .accessibilityValue("\(percent) percent")
            .accessibilityAddTraits(.updatesFrequently)
            Button("Cancel", action: cancel)
                .buttonStyle(.td(.secondary, size: .small))
        }
        .tdAnimation(.easeOut(duration: 0.25), value: done)
    }
}

// MARK: - What's new

/// Release notes in the small Markdown releases use: headings, "- " bullets, **bold**, *italic*, `code` and links.
/// Anything else shows as plain text; only web links are clickable, and they open in the browser.
struct WhatsNew: View {
    let markdown: String
    /// The release page, for "More on GitHub" when the notes are cut short.
    var more: URL?
    var limit = 40

    enum Block: Equatable {
        case heading(String)
        case bullet(String, level: Int)
        case numbered(String, String)
        case paragraph(String)
        case code(String)
    }

    var body: some View {
        let all = Self.blocks(markdown)
        let shown = Array(all.prefix(limit))
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(shown.enumerated()), id: \.offset) { i, block in
                view(block)
                    .padding(.top, i > 0 && Self.isHeading(block) ? TD.Space.s : 0)
            }
            if all.count > shown.count, let more {
                Link("More on GitHub", destination: more)
                    .tdFont(.callout)
                    .padding(.top, 2)
            }
        }
        .tint(TD.violet)
        .environment(\.openURL, OpenURLAction { url in
            guard let s = url.scheme?.lowercased(), s == "https" || s == "http" else { return .discarded }
            NSWorkspace.shared.open(url)
            return .handled
        })
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func view(_ block: Block) -> some View {
        switch block {
        case .heading(let t):
            Text(Self.inline(t))
                .tdFont(.bodySemibold)
                .foregroundStyle(TD.text)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
        case .bullet(let t, let level):
            HStack(alignment: .firstTextBaseline, spacing: 7) {
                Text(level == 0 ? "•" : "◦")
                    .tdFont(.body)
                    .foregroundStyle(TD.violet)
                    .accessibilityHidden(true)
                Text(Self.inline(t))
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.leading, CGFloat(min(level, 3)) * 14)
        case .numbered(let n, let t):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(n)
                    .tdFont(.number(12, .medium))
                    .foregroundStyle(TD.textTertiary)
                Text(Self.inline(t))
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        case .paragraph(let t):
            Text(Self.inline(t))
                .tdFont(.body)
                .foregroundStyle(TD.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        case .code(let t):
            Text(t)
                .font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(TD.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private static func isHeading(_ b: Block) -> Bool {
        if case .heading = b { return true }
        return false
    }

    /// Splits the notes into blocks. A first heading that only says "What's new" is dropped (the card says it).
    static func blocks(_ markdown: String) -> [Block] {
        var out: [Block] = []
        var paragraph: [String] = []
        var fenced = false
        func flush() {
            if !paragraph.isEmpty { out.append(.paragraph(paragraph.joined(separator: " "))) }
            paragraph = []
        }
        let lines = markdown.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
        for raw in lines {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("```") || line.hasPrefix("~~~") {
                flush()
                fenced.toggle()
                continue
            }
            if fenced {
                out.append(.code(raw))
                continue
            }
            if line.isEmpty || line.hasPrefix("<!--") || ["---", "***", "___"].contains(line) {
                flush()
                continue
            }
            if line.hasPrefix("#") {
                let text = line.drop { $0 == "#" }
                if text.first == " " || text.isEmpty {
                    flush()
                    let t = text.trimmingCharacters(in: .whitespaces)
                    if !t.isEmpty { out.append(.heading(t)) }
                    continue
                }
            }
            if let marker = line.first, "-*+".contains(marker), line.dropFirst().first == " " {
                flush()
                let indent = raw.prefix { $0 == " " || $0 == "\t" }.reduce(0) { $0 + ($1 == "\t" ? 4 : 1) }
                out.append(.bullet(String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces), level: indent / 2))
                continue
            }
            if let dot = line.firstIndex(where: { $0 == "." || $0 == ")" }), dot > line.startIndex,
               line[..<dot].count <= 3, line[..<dot].allSatisfy(\.isNumber), line[line.index(after: dot)...].first == " " {
                flush()
                out.append(.numbered(String(line[...dot]), String(line[line.index(after: dot)...]).trimmingCharacters(in: .whitespaces)))
                continue
            }
            paragraph.append(line)
        }
        flush()
        if case .heading(let h) = out.first {
            let plain = h.lowercased().replacingOccurrences(of: "\u{2019}", with: "'").trimmingCharacters(in: .punctuationCharacters)
            if ["what's new", "whats new", "what's changed", "changes"].contains(plain) { out.removeFirst() }
        }
        return out
    }

    /// Inline Markdown (bold, italic, code, links) as styled text. Links that aren't web links become plain text.
    static func inline(_ s: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(
            allowsExtendedAttributes: false, interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible)
        var a = (try? AttributedString(markdown: s, options: options)) ?? AttributedString(s)
        for run in a.runs {
            if let url = run.link, !["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                a[run.range].link = nil
            }
            if run.inlinePresentationIntent?.contains(.code) == true {
                a[run.range].swiftUI.font = .system(size: 12, weight: .regular, design: .monospaced)
                a[run.range].swiftUI.foregroundColor = TD.text
            }
        }
        return a
    }
}

// MARK: - The menu bar panel

/// "Update available" at the foot of the panel: one quiet row that opens Settings → About.
struct PanelUpdateRow: View {
    let close: () -> Void
    @ObservedObject private var updater = Updater.shared
    @State private var hover = false

    var body: some View {
        if let release = updater.available, updater.nudging || updater.install.busy {
            Button {
                close()
                updater.showAgain()
                SettingsWindow.shared.show(.about)
            } label: {
                HStack(spacing: TD.Space.m) {
                    IconChip("arrow.down.circle.fill", tint: TDTint.aurora, size: 26, style: .tinted)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Update available")
                            .tdFont(.bodyMedium)
                            .foregroundStyle(TD.text)
                        Text(subtitle(release))
                            .tdFont(.caption)
                            .foregroundStyle(TD.textSecondary)
                            .monospacedDigit()
                            .lineLimit(1)
                    }
                    Spacer(minLength: TD.Space.s)
                    Image(systemName: "chevron.forward")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(TD.textTertiary)
                }
                .padding(.horizontal, TD.Space.m - 2)
                .padding(.vertical, TD.Space.s)
                .background(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).fill(hover ? TD.fillHover : TD.fill))
                .overlay(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
                .contentShape(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous))
            }
            .buttonStyle(.plain)
            .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
            .help("See what's new in Settings")
            .accessibilityLabel("Update available, TapDrop \(release.version)")
            .accessibilityHint("Opens Settings")
        }
    }

    private func subtitle(_ r: ReleaseInfo) -> String {
        switch updater.install {
        case .downloading(let done, let total) where total > 0:
            "Downloading… \(Int((Double(done) / Double(total) * 100).rounded(.down)))%"
        case .verifying: "Checking the download…"
        case .restarting: "Restarting TapDrop…"
        default: "TapDrop \(r.version) · See what's new"
        }
    }
}
