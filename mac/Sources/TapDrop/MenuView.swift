import AppKit
import SwiftUI

// The menu bar panel: what TapDrop is doing, how to tap, what just arrived.

// MARK: - State

/// Where the link to the phone stands, as the panel tells it.
enum PanelLink: Equatable {
    /// No phone paired yet.
    case setup
    /// At least one phone holds a live link.
    case live
    /// Paired phone seen in the last few minutes; its screen is probably just off.
    case waiting
    /// Paired phone not around.
    case away
    /// TapDrop's listener is (re)starting.
    case starting
    /// This Mac has no network, so no phone can reach it.
    case macOffline
}

struct PanelView: View {
    @ObservedObject var state: PanelState
    @ObservedObject var store = Store.shared
    @ObservedObject var activity = Activity.shared
    let close: () -> Void

    private var livePhones: [PairedDevice] { store.devices.filter { store.liveDevices.contains($0.id) } }
    private var phone: PairedDevice? { livePhones.first ?? store.devices.max { ($0.lastSeen ?? .distantPast) < ($1.lastSeen ?? .distantPast) } }
    private var phoneName: String { phone?.name ?? "your phone" }

    private var link: PanelLink {
        if store.devices.isEmpty { return .setup }
        if let forced = state.demoLink { return forced }
        if !state.macOnline { return .macOffline }
        if !livePhones.isEmpty { return .live }
        if store.status != "Ready" { return .starting }
        if let seen = store.devices.compactMap(\.lastSeen).max(), Date().timeIntervalSince(seen) < 180 { return .waiting }
        return .away
    }

    private var showsTransfer: Bool {
        switch activity.phase {
        case .idle: false
        case .active: true
        case .failed: true
        case .done: activity.kind == .transfer
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PanelHeader(link: link, phones: store.devices, live: livePhones, status: store.status, close: close)
                .padding(.horizontal, TD.Space.l)
                .padding(.top, TD.Space.m + 2)
                .padding(.bottom, TD.Space.m)
                .tdReveal(0, state.visible)

            if link == .setup {
                SetupHero(close: close)
                    .padding(.horizontal, TD.Space.l)
                    .tdReveal(1, state.visible)
            } else {
                LinkHero(link: link, phoneName: phoneName, status: store.status, close: close)
                    .padding(.horizontal, TD.Space.l)
                    .tdReveal(1, state.visible)

                if showsTransfer {
                    TransferRow(activity: activity)
                        .padding(.horizontal, TD.Space.l)
                        .padding(.top, TD.Space.m)
                        .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                                removal: .opacity.combined(with: .scale(scale: 0.97, anchor: .top))))
                }

                SendActions(close: close)
                    .padding(.horizontal, TD.Space.l)
                    .padding(.top, TD.Space.m)
                    .tdReveal(2, state.visible)

                if !store.outbox.isEmpty {
                    QueueShelf(store: store, phoneName: phoneName, live: link == .live)
                        .padding(.horizontal, TD.Space.l)
                        .padding(.top, TD.Space.m)
                        .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity),
                                                removal: .opacity.combined(with: .scale(scale: 0.97, anchor: .top))))
                }
            }

            if link != .setup || !store.recent.isEmpty {
                RecentSection(store: store, close: close, hoverFirst: state.demoHoverFirst)
                    .padding(.top, TD.Space.xl)
                    .tdReveal(3, state.visible)
            }

            PanelUpdateRow(close: close)
                .padding(.horizontal, TD.Space.l)
                .padding(.top, TD.Space.m)
                .tdReveal(4, state.visible)
        }
        .padding(.bottom, TD.Space.m)
        .overlay {
            if state.dropTargeted {
                DropTarget(phoneName: link == .setup ? nil : phoneName)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
        }
        .dropDestination(for: URL.self) { urls, _ in
            // a recent item dragged out of this panel isn't something to send
            let files = urls.filter(\.isFileURL)
            guard !files.isEmpty, !store.devices.isEmpty, !FileDragSource.shared.dragging else { return false }
            ServiceProvider.queue(files)
            TD.haptic()
            return true
        } isTargeted: { t in
            withTDAnimation(TD.Motion.standard) { state.dropTargeted = t && !FileDragSource.shared.dragging }
        }
        .tdAnimation(TD.Motion.standard, value: store.outbox.map(\.id))
        .tdAnimation(TD.Motion.standard, value: store.recent.map(\.id))
        .tdAnimation(TD.Motion.standard, value: store.settings.showRecent)
        .tdAnimation(TD.Motion.standard, value: link)
        .tdAnimation(TD.Motion.standard, value: showsTransfer)
    }
}

// MARK: - Header

private struct PanelHeader: View {
    let link: PanelLink
    let phones: [PairedDevice]
    let live: [PairedDevice]
    let status: String
    let close: () -> Void

    var body: some View {
        HStack(spacing: TD.Space.s) {
            AppGlyph(size: 22)
            Wordmark(size: 16)
            Spacer(minLength: TD.Space.s)
            if let pill {
                StatusPill(pill.0, pill.1)
                    .help(pill.2)
                    .transition(.scale(scale: 0.85).combined(with: .opacity))
            }
            Button {
                close()
                SettingsWindow.shared.show()
            } label: {
                Label("Settings", systemImage: "gearshape")
            }
            .buttonStyle(.tdIcon(size: 26, quiet: true))
            .help("Settings  ⌘,")
        }
        .frame(height: 26)
    }

    /// (status, label, tooltip)
    private var pill: (TDStatus, String, String)? {
        let name = (live.first ?? phones.first)?.name ?? ""
        let more = max(0, (live.isEmpty ? phones.count : live.count) - 1)
        let label = more > 0 ? "\(name) +\(more)" : name
        switch link {
        case .setup: return nil
        case .live: return (.live, label, "Connected and ready for a tap")
        case .waiting: return (.searching, label, "Waiting for the phone to connect")
        case .starting:
            return status.hasPrefix("Restart") ? (.searching, "Reconnecting", "TapDrop is getting back on your network")
                                               : (.searching, "Starting", "TapDrop is getting ready")
        case .away: return (.offline, label, "Not connected")
        case .macOffline: return (.error, "Offline", "This Mac isn't on a network")
        }
    }
}

// MARK: - Heroes

/// Paired: what's going on with the phone and how to tap.
private struct LinkHero: View {
    let link: PanelLink
    let phoneName: String
    let status: String
    let close: () -> Void
    @ObservedObject private var store = Store.shared

    private var tapReady: Bool { NotchSensor.shared.available && store.settings.notchTap }

    var body: some View {
        VStack(spacing: TD.Space.xs + 1) {
            NotchlineGlow(glow: link == .live ? 1 : (link == .waiting || link == .starting ? 0.55 : 0.2))
                .frame(width: 150, height: 30)
                .padding(.top, 2)
            title
                .multilineTextAlignment(.center)
                .foregroundStyle(TD.text)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.interpolate)
                .padding(.top, 4)
            Text(message)
                .tdFont(.body)
                .foregroundStyle(TD.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(1)
                .frame(maxWidth: 270)
            if let note {
                Text(note)
                    .tdFont(.caption)
                    .foregroundStyle(TD.textTertiary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 2)
            }
            if link == .live && NotchSensor.shared.available && !store.settings.notchTap {
                Button("Turn On") { store.settings.notchTap = true }
                    .buttonStyle(.td(.primary, size: .small))
                    .padding(.top, TD.Space.s)
            }
            if link == .macOffline {
                Button("Wi-Fi Settings…") {
                    close()
                    if let url = URL(string: "x-apple.systempreferences:com.apple.wifi-settings-extension") {
                        NSWorkspace.shared.open(url)
                    }
                }
                .buttonStyle(.td(.secondary, size: .small))
                .padding(.top, TD.Space.s)
            }
        }
        .padding(.horizontal, TD.Space.l)
        .padding(.top, TD.Space.m)
        .padding(.bottom, TD.Space.l)
        .frame(maxWidth: .infinity)
        .background {
            // The light spilling from the notch line.
            RadialGradient(colors: [TD.light.opacity(scheme == .dark ? (link == .live ? 0.16 : 0.07) : 0), .clear],
                           center: .top, startRadius: 0, endRadius: 190)
        }
        .background(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).fill(TD.fill))
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .help(link == .live && tapReady ? TapCopy.safety : "")
        .accessibilityElement(children: .combine)
    }

    @Environment(\.colorScheme) private var scheme

    /// The hero line in the serif, its last word in italic ("Tap to *send.*").
    private var title: Text {
        let words = titleText.split(separator: " ")
        guard words.count > 1 else { return Text(titleText).font(.serif(30)) }
        return Text(words.dropLast().joined(separator: " ") + " ").font(.serif(30))
            + Text(String(words.last!)).font(.serif(30, italic: true))
    }

    private var heroLink: DeviceHero.Link {
        switch link {
        case .live: tapReady ? .live : .connected
        case .waiting, .starting: .searching
        case .away: .offline
        case .macOffline: .error
        case .setup: .setup
        }
    }

    private var titleText: String {
        switch link {
        case .live: tapReady ? "Tap to send." : (NotchSensor.shared.available ? "Tap to send is off." : "\(phoneName) is connected.")
        case .waiting: "Waiting for \(phoneName)."
        case .away: "\(phoneName) isn't connected."
        case .starting: status.hasPrefix("Restart") ? "Getting back online." : "Getting ready."
        case .macOffline: "This Mac is offline."
        case .setup: ""
        }
    }

    private var message: String {
        switch link {
        case .live:
            if tapReady { return TapCopy.gesture }
            if NotchSensor.shared.available { return "Turn it on to send what's on your phone with a touch." }
            return "Share from your phone, or drop files here to send them."
        case .waiting: return "Wake your phone. It connects while its screen is on."
        case .away: return "Open TapDrop on your phone. Both need to be on the same Wi-Fi."
        case .starting:
            return status.hasPrefix("Restart") ? "TapDrop is reconnecting to your network. Your phone will find it again in a moment."
                                               : "TapDrop is starting up."
        case .macOffline: return "Connect to Wi-Fi to reach \(phoneName)."
        case .setup: return ""
        }
    }

    private var note: String? {
        link == .live && tapReady ? TapCopy.hold : nil
    }
}

/// No phone yet: the one thing to do.
private struct SetupHero: View {
    let close: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            DeviceHero(link: .setup)
                .frame(width: 150, height: 112)
                .clipped()
                .mask(LinearGradient(stops: [.init(color: .black, location: 0.74), .init(color: .clear, location: 1)],
                                     startPoint: .top, endPoint: .bottom))
            VStack(spacing: TD.Space.xs + 1) {
                Text("Set up your phone")
                    .tdFont(.title2)
                    .foregroundStyle(TD.text)
                Text("Send photos, links and files between your Android phone and this Mac. It takes about a minute.")
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, TD.Space.s)
            }
            .padding(.top, TD.Space.l)
            Button {
                close()
                SetupWindow.shared.show()
            } label: {
                Label("Set Up a Phone", systemImage: "qrcode")
            }
            .buttonStyle(.td(.primary, size: .regular))
            .padding(.top, TD.Space.l)
            .padding(.bottom, TD.Space.xl)
        }
        .frame(maxWidth: .infinity)
        .background { AuroraBackdrop(intensity: 0.9) }
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
    }
}

// MARK: - Send actions

private struct SendActions: View {
    let close: () -> Void

    var body: some View {
        TDGlassGroup(spacing: TD.Space.s) {
            HStack(spacing: TD.Space.s) {
                Button {
                    PanelActions.chooseFiles(close: close)
                } label: {
                    Label("Send Files", systemImage: "plus")
                }
                .buttonStyle(.td(.primary, fullWidth: true))
                .help("Choose files to send to your phone  ⌘O")

                Button {
                    PanelActions.queueClipboard()
                } label: {
                    Label("Send Clipboard", systemImage: "doc.on.clipboard")
                }
                .buttonStyle(.td(.secondary, fullWidth: true))
                .help("Send what you copied  ⌘V")
            }
        }
    }
}

// MARK: - Transfer in progress

private struct TransferRow: View {
    @ObservedObject var activity: Activity

    private var phase: TransferGlyph.Phase {
        switch activity.phase {
        case .active, .idle: .progress(activity.progress)
        case .done: .success
        case .failed: .failure
        }
    }

    /// "12.4 MB of 48 MB" while bytes are moving.
    private var bytes: String? {
        guard activity.phase == .active, activity.bytesTotal > 1 else { return nil }
        return "\(activity.bytesDone.byteString) of \(activity.bytesTotal.byteString)"
    }

    var body: some View {
        HStack(spacing: TD.Space.m) {
            TransferGlyph(phase: phase, symbol: activity.symbol)
                .frame(width: 34, height: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(activity.title.isEmpty ? "Receiving…" : activity.title)
                    .tdFont(.bodyMedium)
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
                .tdFont(.caption)
                .foregroundStyle(TD.textSecondary)
            }
            Spacer(minLength: TD.Space.s)
            trailing
        }
        .padding(.horizontal, TD.Space.m)
        .padding(.vertical, TD.Space.s + 2)
        .background(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).fill(TD.fill))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .tdAnimation(TD.Motion.standard, value: activity.phase)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var trailing: some View {
        switch activity.phase {
        case .active:
            if let p = activity.progress {
                Text("\(Int((p * 100).rounded()))%")
                    .tdFont(.number(15))
                    .foregroundStyle(TD.text)
                    .contentTransition(.numericText(value: p))
                    .animation(.easeOut(duration: 0.2), value: Int(p * 100))
            }
        case .done:
            if !activity.files.isEmpty {
                Button {
                    NSWorkspace.shared.activateFileViewerSelecting(activity.files)
                } label: { Label("Show in Finder", systemImage: "folder") }
                    .buttonStyle(.tdIcon(size: 28))
                    .help("Show in Finder")
            } else if let link = activity.link {
                Button {
                    NSWorkspace.shared.open(link)
                } label: { Label("Open", systemImage: "arrow.up.right") }
                    .buttonStyle(.tdIcon(size: 28))
                    .help("Open")
            }
        case .failed:
            Button {
                activity.dismiss()
            } label: { Label("Dismiss", systemImage: "xmark") }
                .buttonStyle(.tdIcon(size: 26, quiet: true))
                .help("Dismiss")
        case .idle:
            EmptyView()
        }
    }
}

// MARK: - Queue (waiting for the phone)

private struct QueueShelf: View {
    @ObservedObject var store: Store
    let phoneName: String
    let live: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s + 2) {
            HStack(spacing: TD.Space.s) {
                Text("Waiting for \(phoneName)")
                    .tdFont(.bodyMedium)
                    .foregroundStyle(TD.text)
                Text("\(store.outbox.count)")
                    .tdFont(.number(11, .bold))
                    .foregroundStyle(TD.textSecondary)
                    .contentTransition(.numericText(value: Double(store.outbox.count)))
                    .padding(.horizontal, 6)
                    .frame(minWidth: 20, minHeight: 18)
                    .background(Capsule().fill(TD.fillStrong))
                Spacer()
                Button("Clear") { store.clearOutbox() }
                    .buttonStyle(.tdQuiet)
                    .help("Remove everything waiting to be sent")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: TD.Space.s) {
                    ForEach(store.outbox) { item in
                        QueueTile(item: item) { store.dequeue([item.id]) }
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 2)
            }
            .scrollClipDisabled()
            .mask(HStack(spacing: 0) {
                Color.black
                LinearGradient(colors: [.black, .clear], startPoint: .leading, endPoint: .trailing).frame(width: 20)
            })
            Label {
                Text(live ? "Touch the top of your phone's screen to the notch to get them, or open TapDrop on it."
                          : "They'll go to \(phoneName) when you open TapDrop on it.")
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "smartphone")
                    .foregroundStyle(TD.aurora)
            }
            .tdFont(.caption)
            .foregroundStyle(TD.textSecondary)
        }
        .padding(TD.Space.m)
        .background(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).fill(TD.fill))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
    }
}

private struct QueueTile: View {
    let item: OutboxItem
    let remove: () -> Void
    @State private var hover = false

    var body: some View {
        Group {
            if let path = item.path {
                Thumbnail(url: URL(fileURLWithPath: path), size: 46, radius: 10)
            } else {
                ContentTile(kind: .text(item.text ?? ""), size: 46)
            }
        }
        .overlay(alignment: .topTrailing) {
            if hover {
                Button(action: remove) {
                    Image(systemName: "xmark")
                        .font(.system(size: 8, weight: .heavy))
                        .foregroundStyle(TD.text)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(TD.surfaceRaised))
                        .overlay(Circle().strokeBorder(TD.hairline, lineWidth: 1))
                        .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
                }
                .buttonStyle(.plain)
                .offset(x: 6, y: -6)
                .transition(.scale(scale: 0.5).combined(with: .opacity))
                .accessibilityLabel("Remove \(item.displayName)")
            }
        }
        .scaleEffect(hover ? 1.04 : 1)
        .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
        .help(item.displayName)
    }
}

// MARK: - Recent

private struct RecentSection: View {
    @ObservedObject var store: Store
    let close: () -> Void
    let hoverFirst: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.s) {
            SectionHeader(store.settings.showRecent ? "Recent" : "Received files") {
                // where things land, one click away
                let folder = Self.short(FileManager.default.displayName(atPath: store.saveFolder.path))
                Button {
                    close()
                    NSWorkspace.shared.open(store.saveFolder)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "folder")
                            .font(.system(size: 11, weight: .semibold))
                        Text(folder)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Image(systemName: "chevron.forward")
                            .font(.system(size: 8.5, weight: .bold))
                            .opacity(0.7)
                    }
                    .fixedSize()
                }
                .buttonStyle(.tdQuiet)
                .help("Open \(folder) in Finder")
                .accessibilityLabel("Open \(folder) in Finder")
            }
            .padding(.leading, TD.Space.l + 4)
            .padding(.trailing, TD.Space.m)
            .contextMenu {
                if store.settings.showRecent {
                    Button("Clear Recent") { withTDAnimation { store.clearRecent() } }
                        .disabled(store.recent.isEmpty)
                    Button("Hide Recent") { withTDAnimation { store.settings.showRecent = false } }
                } else {
                    Button("Show Recent") { withTDAnimation { store.settings.showRecent = true } }
                }
            }

            if !store.settings.showRecent {
                // Hidden (Settings → General → Recent): just where things land.
            } else if store.recent.isEmpty {
                EmptyState("tray", title: "Nothing yet",
                           message: "Photos, links and files you share show up here.", compact: true)
                    .padding(.vertical, TD.Space.l)
                    .padding(.horizontal, TD.Space.l)
                    .background(
                        RoundedRectangle(cornerRadius: TD.Radius.row + 2, style: .continuous)
                            .strokeBorder(TD.hairline, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
                    .padding(.horizontal, TD.Space.l)
                    .transition(.opacity)
            } else {
                VStack(spacing: 2) {
                    ForEach(Array(store.recent.prefix(4).enumerated()), id: \.element.id) { i, e in
                        RecentRow(event: e, forceHover: hoverFirst && i == 0)
                            .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                    }
                }
                .padding(.horizontal, TD.Space.s)
            }
        }
    }
}

extension RecentSection {
    /// A folder name short enough for the header, cut in the middle ("Screenshots…2026").
    static func short(_ name: String, max: Int = 22) -> String {
        guard name.count > max else { return name }
        let half = (max - 1) / 2
        return String(name.prefix(half)) + "…" + String(name.suffix(max - 1 - half))
    }
}

/// What a recent event is, for its thumbnail, title and actions.
private enum RecentKind {
    case files([URL])
    case link(URL)
    case text(String)
    case code(String)
    case other

    init(_ e: RecentEvent) {
        if !e.files.isEmpty {
            self = .files(e.files)
        } else if let t = e.text?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty {
            if !t.contains(where: \.isWhitespace), let u = URL(string: t), let s = u.scheme?.lowercased(),
               s == "http" || s == "https", u.host != nil {
                self = .link(u)
            } else {
                self = .text(t)
            }
        } else if e.summary.hasPrefix("Code") {
            self = .code(e.summary.replacingOccurrences(of: "Code · ", with: ""))
        } else {
            self = .other
        }
    }
}

private struct RecentRow: View {
    let event: RecentEvent
    var forceHover = false
    @State private var hover = false
    @State private var copied = false

    private var kind: RecentKind { RecentKind(event) }
    private var lit: Bool { hover || forceHover }

    var body: some View {
        HStack(spacing: TD.Space.m) {
            leading
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .tdFont(.bodyMedium)
                    .foregroundStyle(TD.text)
                    .lineLimit(1)
                    .truncationMode(.middle)
                TimelineView(.periodic(from: .now, by: 30)) { ctx in
                    HStack(spacing: 4) {
                        Image(systemName: event.incoming ? "arrow.down" : "arrow.up")
                            .font(.system(size: 8.5, weight: .bold))
                            .foregroundStyle(event.incoming ? AnyShapeStyle(TD.aurora) : AnyShapeStyle(TD.success))
                        Text("\(event.incoming ? "From" : "To") \(event.peer) · \(ShortTime.string(event.date, now: ctx.date))")
                            .lineLimit(1)
                    }
                    .tdFont(.caption)
                    .foregroundStyle(TD.textSecondary)
                }
            }
            Spacer(minLength: TD.Space.xs)
            if lit {
                actions
                    .transition(.opacity.combined(with: .offset(x: 6)))
            }
        }
        .padding(.horizontal, TD.Space.s)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous).fill(lit ? TD.fill : .clear))
        .contentShape(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous))
        .onHover { h in withTDAnimation(TD.Motion.hover) { hover = h } }
        .onTapGesture(perform: primary)
        .modifier(DragOut(kind: kind))
        .contextMenu { menu }
        .help(help)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Copy") { copy() }
    }

    // MARK: pieces

    @ViewBuilder private var leading: some View {
        switch kind {
        case .files(let urls):
            FileStack(urls: urls, size: 38)
        case .link:
            ContentTile(kind: .link, size: 38)
        case .text(let t):
            ContentTile(kind: .text(t), size: 38)
        case .code:
            ContentTile(kind: .code, size: 38)
        case .other:
            ContentTile(kind: .text(event.summary), size: 38)
        }
    }

    private var title: String {
        switch kind {
        case .files(let urls) where urls.count > 1:
            let photos = urls.allSatisfy { ["jpg", "jpeg", "heic", "png", "gif", "webp"].contains($0.pathExtension.lowercased()) }
            return "\(urls.count) \(photos ? "photos" : "files")"
        case .files(let urls):
            return urls[0].lastPathComponent
        case .link(let u):
            let host = (u.host ?? "").replacingOccurrences(of: "www.", with: "")
            let path = u.path.count > 1 ? u.path : ""
            return host + path
        case .text(let t):
            return t.replacingOccurrences(of: "\n", with: " ")
        case .code(let source):
            return "Code from \(source)"
        case .other:
            return event.summary
        }
    }

    private var help: String {
        switch kind {
        case .files(let urls): urls.count == 1 ? "Click to open · drag to move it anywhere" : "Click to show in Finder · drag to move them anywhere"
        case .link: "Click to open"
        case .text: "Click to copy"
        default: ""
        }
    }

    @ViewBuilder private var actions: some View {
        HStack(spacing: 2) {
            switch kind {
            case .files(let urls):
                if urls.count == 1 {
                    action("Open", "arrow.up.forward.app") { NSWorkspace.shared.open(urls[0]) }
                }
                action("Show in Finder", "folder") { NSWorkspace.shared.activateFileViewerSelecting(urls) }
                copyButton
            case .link(let u):
                action("Open", "safari") { NSWorkspace.shared.open(u) }
                copyButton
            case .text:
                copyButton
            default:
                EmptyView()
            }
        }
    }

    private func action(_ title: String, _ symbol: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) { Label(title, systemImage: symbol) }
            .buttonStyle(.tdIcon(size: 26, quiet: true))
            .help(title)
    }

    private var copyButton: some View {
        Button(action: copy) {
            Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(.tdIcon(size: 26, quiet: true))
        .help(copied ? "Copied" : "Copy")
    }

    @ViewBuilder private var menu: some View {
        switch kind {
        case .files(let urls):
            if urls.count == 1 { Button("Open") { NSWorkspace.shared.open(urls[0]) } }
            Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting(urls) }
            Button("Copy") { copy() }
        case .link(let u):
            Button("Open") { NSWorkspace.shared.open(u) }
            Button("Copy Link") { copy() }
        case .text:
            Button("Copy") { copy() }
        default:
            EmptyView()
        }
        Divider()
        Button("Remove from Recent") {
            withTDAnimation { Store.shared.recent.removeAll { $0.id == event.id } }
        }
    }

    // MARK: actions

    private func primary() {
        switch kind {
        case .files(let urls):
            if urls.count == 1 { NSWorkspace.shared.open(urls[0]) } else { NSWorkspace.shared.activateFileViewerSelecting(urls) }
        case .link(let u):
            NSWorkspace.shared.open(u)
        case .text:
            copy()
        default:
            break
        }
    }

    private func copy() {
        let pb = NSPasteboard.general
        switch kind {
        case .files(let urls):
            pb.clearContents()
            pb.writeObjects(urls.map { $0 as NSURL })
        case .link(let u):
            pb.clearContents()
            pb.setString(u.absoluteString, forType: .string)
        case .text(let t):
            pb.clearContents()
            pb.setString(t, forType: .string)
        default:
            return
        }
        withTDAnimation(TD.Motion.hover) { copied = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { withTDAnimation(TD.Motion.hover) { copied = false } }
    }
}

/// Drag a recent item out: files go as files, links and text as text.
private struct DragOut: ViewModifier {
    let kind: RecentKind

    func body(content: Content) -> some View {
        switch kind {
        case .files(let urls):
            content.tdFileDrag(urls)
        case .link(let u):
            content.onDrag { NSItemProvider(object: u as NSURL) }
        case .text(let t):
            content.onDrag { NSItemProvider(object: t as NSString) }
        default:
            content
        }
    }
}

/// One thumbnail, or a small fanned stack with a count for several files.
private struct FileStack: View {
    let urls: [URL]
    let size: CGFloat

    var body: some View {
        if urls.count == 1 {
            Thumbnail(url: urls[0], size: size, radius: 9)
        } else {
            ZStack {
                ForEach(Array(urls.prefix(3).enumerated().reversed()), id: \.offset) { i, url in
                    Thumbnail(url: url, size: size - 4, radius: 8, badge: false)
                        .rotationEffect(.degrees(Double(i) * 7))
                        .offset(x: CGFloat(i) * 2.5, y: CGFloat(i) * -1)
                        .shadow(color: .black.opacity(i == 0 ? 0.18 : 0), radius: 2, y: 1)
                }
            }
            .frame(width: size, height: size)
            .overlay(alignment: .bottomTrailing) {
                Text("\(urls.count)")
                    .tdFont(.number(9.5, .bold))
                    .foregroundStyle(TD.onAccent)
                    .padding(.horizontal, 4)
                    .frame(minWidth: 16, minHeight: 16)
                    .background(Capsule().fill(TD.violet))
                    .overlay(Capsule().strokeBorder(.white.opacity(0.25), lineWidth: 0.5))
                    .offset(x: 4, y: 3)
            }
        }
    }
}

// MARK: - Drop target

private struct DropTarget: View {
    let phoneName: String?
    @State private var dash: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TD.Radius.panel - 6, style: .continuous)
        let ok = phoneName != nil
        ZStack {
            shape.fill(.regularMaterial)
            shape.fill(TD.violet.opacity(0.10))
            RadialGradient(colors: [TD.violet.opacity(0.38), TD.blue.opacity(0.12), .clear],
                           center: .center, startRadius: 0, endRadius: 240)
                .clipShape(shape)
            shape.strokeBorder(TD.auroraHero, style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [9, 7], dashPhase: dash))
            VStack(spacing: TD.Space.l) {
                ZStack {
                    Circle().fill(TD.glow).frame(width: 120, height: 120).blur(radius: 28)
                    Circle()
                        .fill(ok ? AnyShapeStyle(TD.aurora) : AnyShapeStyle(TD.fillStrong))
                        .overlay(Circle().fill(LinearGradient(colors: [.white.opacity(0.3), .clear], startPoint: .top, endPoint: .center)))
                        .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 1))
                        .frame(width: 68, height: 68)
                        .shadow(color: TD.violet.opacity(0.5), radius: 14, y: 6)
                    Image(systemName: ok ? "arrow.down" : "iphone.slash")
                        .font(.system(size: 28, weight: .bold))
                        .foregroundStyle(ok ? TD.onAccent : TD.text)
                        .symbolEffect(.bounce.down, options: .repeat(3), isActive: ok && !reduceMotion)
                }
                VStack(spacing: 4) {
                    Text(phoneName.map { "Drop to send to \($0)" } ?? "Set up a phone first")
                        .tdFont(.headline)
                        .foregroundStyle(TD.text)
                    Text(ok ? "They'll be waiting for your phone." : "Then drop files here to send them.")
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                }
                .multilineTextAlignment(.center)
            }
            .padding(TD.Space.xxl)
        }
        .padding(6)
        .allowsHitTesting(false)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.linear(duration: 1.4).repeatForever(autoreverses: false)) { dash = -32 }
        }
    }
}

// MARK: - Actions

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
            ServiceProvider.announce("text")
        case .empty:
            NSSound.beep()
        }
    }
}
