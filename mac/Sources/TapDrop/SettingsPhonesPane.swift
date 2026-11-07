import AppKit
import SwiftUI

// Settings → Phones: paired phones as cards (status, last seen, tap readiness), forgetting with a
// confirmation, setting up a new one, and what's waiting to be sent.

struct PhonesPane: View {
    let visible: Bool
    @ObservedObject private var store = Store.shared
    @StateObject private var caps = PhoneCaps()
    @State private var forgetting: PairedDevice?
    @State private var renaming: PairedDevice?

    /// Connected first, then most recently seen.
    private var phones: [PairedDevice] {
        store.devices.sorted { a, b in
            let la = store.liveDevices.contains(a.id), lb = store.liveDevices.contains(b.id)
            if la != lb { return la }
            return (a.lastSeen ?? a.pairedAt) > (b.lastSeen ?? b.pairedAt)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xxl) {
            SettingsPaneHeader(.phones) {
                if !store.devices.isEmpty {
                    Button {
                        SetupWindow.shared.show()
                    } label: {
                        Label("Set Up a New Phone", systemImage: "plus")
                    }
                    .buttonStyle(.td(.secondary, size: .small))
                }
            }
            .tdReveal(0, visible)

            if store.devices.isEmpty {
                EmptyPhones()
                    .tdReveal(1, visible)
            } else {
                VStack(spacing: TD.Space.m) {
                    ForEach(phones) { d in
                        PhoneCard(device: d, live: store.liveDevices.contains(d.id), needsUpdate: caps.outdated.contains(d.name),
                                  rename: { renaming = d }) {
                            forgetting = d
                        }
                        .transition(.asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.98)),
                                                removal: .opacity.combined(with: .scale(scale: 0.96))))
                    }
                }
                .tdReveal(1, visible)

                if !store.outbox.isEmpty {
                    OutboxGroup()
                        .tdReveal(2, visible)
                        .transition(.opacity)
                }

                Text("Pairing happens once. After that, your phone finds this Mac by itself when both are on the same Wi-Fi.")
                    .tdFont(.caption)
                    .foregroundStyle(TD.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, TD.Space.xs)
                    .tdReveal(3, visible)
            }
        }
        .tdAnimation(TD.Motion.standard, value: store.devices.map(\.id))
        .tdAnimation(TD.Motion.standard, value: store.outbox.isEmpty)
        .renameAlert("Rename \(renaming?.name ?? "this phone")",
                     message: "This is the phone's own name: it changes on the phone too.",
                     isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } }),
                     current: renaming?.name ?? "",
                     save: { name in if let d = renaming { withTDAnimation { store.renamePhone(d.id, name) } } })
        .confirmationDialog(
            "Forget \(forgetting?.name ?? "this phone")?",
            isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }),
            titleVisibility: .visible,
            presenting: forgetting
        ) { d in
            Button("Forget \(d.name)", role: .destructive) {
                withTDAnimation { store.removeDevice(d.id) }
                TD.haptic()
                forgetting = nil
            }
            Button("Cancel", role: .cancel) { forgetting = nil }
        } message: { d in
            Text("It's removed from \(d.name) too, and won't be able to send to this Mac until you pair it again.")
        }
        .onAppear {
            caps.start()
            if SettingsDemo.forgetFirst {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { forgetting = phones.first }
            }
        }
        .onDisappear { caps.stop() }
    }
}

/// Which live phones still run a TapDrop that predates the light key (they can't tap until updated).
@MainActor
final class PhoneCaps: ObservableObject {
    @Published private(set) var outdated: Set<String> = []
    private var timer: Timer?

    func start() {
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func refresh() {
        if !SettingsDemo.outdatedPhones.isEmpty {
            outdated = SettingsDemo.outdatedPhones
            return
        }
        // `caps` arrives right after a session opens; a phone that's been live for a refresh without it is older.
        let old = Set(WatchHub.shared.phones().filter { $0.key < LightKey.version }.map(\.name))
        let confirmed = old.intersection(pending)
        pending = old
        if outdated != confirmed { outdated = confirmed }
    }

    private var pending: Set<String> = []
}

// MARK: - Card

private struct PhoneCard: View {
    let device: PairedDevice
    let live: Bool
    let needsUpdate: Bool
    let rename: () -> Void
    let forget: () -> Void
    @State private var hover = false

    private var recent: Bool {
        if let s = device.lastSeen { return Date().timeIntervalSince(s) < 180 }
        return false
    }

    private var status: (TDStatus, String) {
        live ? (.live, "Connected") : (.offline, "Not connected")
    }

    var body: some View {
        GlassCard(.surface, padding: TD.Space.m, radius: TD.Radius.card) {
            HStack(alignment: .top, spacing: TD.Space.l) {
                PhoneArt(link: live ? .live : (recent ? .searching : .offline))  // a phone seen lately rests nearer
                    .frame(width: 76, height: 92)

                VStack(alignment: .leading, spacing: TD.Space.xs + 1) {
                    HStack(alignment: .center, spacing: TD.Space.s) {
                        Text(device.name)
                            .tdFont(.headline)
                            .foregroundStyle(TD.text)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Spacer(minLength: TD.Space.s)
                        StatusPill(status.0, status.1)
                    }
                    Text(line)
                        .tdFont(.callout)
                        .foregroundStyle(TD.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: TD.Space.s - 2) {
                        if needsUpdate {
                            FactChip(text: "Update TapDrop to tap", symbol: "exclamationmark.triangle.fill", tint: TD.warning)
                        } else if live && NotchSensor.shared.available && Store.shared.settings.notchTap {
                            FactChip(text: "Ready for taps", symbol: "hand.tap.fill", tint: TD.violet)
                        }
                        FactChip(text: "Paired \(device.pairedAt.formatted(.dateTime.month(.abbreviated).day().year()))",
                                 symbol: "link")
                        Spacer(minLength: TD.Space.s)
                        Button("Rename…", action: rename)
                            .buttonStyle(.tdQuiet)
                            .help("Give \(device.name) a new name, here and on the phone")
                        Button("Forget…", action: forget)
                            .buttonStyle(.tdQuiet)
                            .help("Unpair \(device.name) from this Mac")
                    }
                    .padding(.top, TD.Space.xs + 2)
                }
                .padding(.top, 2)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(device.name), \(status.1)")
    }

    private var line: String {
        if live && needsUpdate { return "Connected, but its TapDrop is out of date. Update it to send with a touch; sharing from it still works." }
        if live { return "Ready to send and receive. Touch the top of its screen to the notch to send what's on it." }
        if let s = device.lastSeen {
            let when = ShortTime.string(s)
            if recent { return "Seen \(when). It connects while its screen is on." }
            return "Last seen \(when). Open TapDrop on it to connect."
        }
        return "Not seen since pairing. Open TapDrop on it to connect."
    }
}

/// The phone at the notch, on a small aurora tile (the same drawing as the menu bar panel).
private struct PhoneArt: View {
    let link: DeviceHero.Link

    var body: some View {
        DeviceHero(link: link, animated: false)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(AuroraBackdrop(intensity: link == .live ? 1 : 0.4, drift: 0))
            .clipShape(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
            .accessibilityHidden(true)
    }
}

// MARK: - Empty

private struct EmptyPhones: View {
    var body: some View {
        VStack(spacing: 0) {
            // tall enough that the code on the phone's screen sits above the fade
            DeviceHero(link: .setup)
                .frame(width: 168, height: 150)
                .clipped()
                .mask(LinearGradient(stops: [.init(color: .black, location: 0.8), .init(color: .clear, location: 1)],
                                     startPoint: .top, endPoint: .bottom))
            VStack(spacing: TD.Space.xs + 2) {
                Text("No phones yet")
                    .tdFont(.title2)
                    .foregroundStyle(TD.text)
                Text("Set up your Android phone to send photos, links and files with a touch. It takes about a minute.")
                    .tdFont(.body)
                    .foregroundStyle(TD.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: 360)
            }
            .padding(.top, TD.Space.l)
            Button {
                SetupWindow.shared.show()
            } label: {
                Label("Set Up a Phone", systemImage: "qrcode")
            }
            .buttonStyle(.td(.primary))
            .padding(.top, TD.Space.l + 2)
            .padding(.bottom, TD.Space.xxl)
        }
        .frame(maxWidth: .infinity)
        .background { AuroraBackdrop(intensity: 0.8) }
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
    }
}

// MARK: - Outbox

private struct OutboxGroup: View {
    @ObservedObject private var store = Store.shared

    var body: some View {
        SettingsGroup("Waiting to send") {
            SettingsRow("tray.full.fill", tint: TDTint.aurora,
                        title: store.outbox.count == 1 ? "1 item" : "\(store.outbox.count) items",
                        subtitle: "Touch the top of your phone's screen to the notch, or open TapDrop on it, to get \(store.outbox.count == 1 ? "it" : "them").") {
                Button("Clear") { withTDAnimation { store.clearOutbox() } }
                    .buttonStyle(.td(.secondary, size: .small))
            }
        }
    }
}
