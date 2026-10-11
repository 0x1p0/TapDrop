import AppKit
import IOKit
import ServiceManagement
import SwiftUI

// MARK: - Window

enum SettingsPane: String, CaseIterable, Identifiable {
    case general, tap, phones, compatibility, about
    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: "General"
        case .tap: "Tap to Send"
        case .phones: "Phones"
        case .compatibility: "Compatibility"
        case .about: "About"
        }
    }

    var symbol: String {
        switch self {
        case .general: "gearshape.fill"
        case .tap: "hand.tap.fill"
        case .phones: "iphone.gen3"
        case .compatibility: "checkmark.seal.fill"
        case .about: "info.circle.fill"
        }
    }

    var tint: [Color] {
        switch self {
        case .general: [Color(white: 0.62), Color(white: 0.42)]
        case .tap: [Brand.a, Brand.b]
        case .phones: [Color(red: 0.3, green: 0.85, blue: 0.5), Color(red: 0.12, green: 0.64, blue: 0.39)]
        case .compatibility: [Color(red: 1, green: 0.76, blue: 0.29), Color(red: 1, green: 0.54, blue: 0.24)]
        case .about: [Color(red: 0.45, green: 0.55, blue: 0.75), Color(red: 0.3, green: 0.36, blue: 0.55)]
        }
    }
}

@MainActor
final class SettingsModel: ObservableObject {
    @Published var pane: SettingsPane = .general
}

@MainActor
final class SettingsWindow: NSObject, NSWindowDelegate {
    static let shared = SettingsWindow()
    static let size = NSSize(width: 780, height: 560)
    private var window: NSWindow?
    private var keyMonitor: Any?
    let model = SettingsModel()

    func show(_ pane: SettingsPane? = nil) {
        if let pane { model.pane = pane }
        if window == nil {
            let w = GlassWindow.make(size: Self.size, root: SettingsView(model: model))
            w.title = "TapDrop Settings"
            w.delegate = self
            w.center()
            window = w
            keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] e in
                guard let self, e.window === self.window else { return e }
                // No main menu in a menu bar app, so ⌘W / Esc are handled here.
                if e.keyCode == 53 || (e.modifierFlags.contains(.command) && e.charactersIgnoringModifiers == "w") {
                    self.window?.close()
                    return nil
                }
                return e
            }
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        NotchSensor.shared.setTesting(false)
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        keyMonitor = nil
        window = nil
    }
}

// MARK: - Layout

struct SettingsView: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        HStack(spacing: 0) {
            SettingsSidebar(model: model)
                .frame(width: 214)
            Rectangle().fill(.white.opacity(0.07)).frame(width: 1)
            ScrollView {
                Group {
                    switch model.pane {
                    case .general: GeneralPane()
                    case .tap: TapPane()
                    case .phones: PhonesPane()
                    case .compatibility: CompatibilityPane()
                    case .about: AboutPane()
                    }
                }
                .padding(.horizontal, 30)
                .padding(.top, 34)
                .padding(.bottom, 30)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .id(model.pane)
            .transition(.opacity)
        }
        .frame(width: SettingsWindow.size.width, height: SettingsWindow.size.height)
        .ignoresSafeArea()
        .background(alignment: .topTrailing) {
            Ellipse().fill(Brand.gradient).frame(width: 360, height: 220).blur(radius: 90).opacity(0.18).offset(x: 80, y: -60)
                .allowsHitTesting(false)
        }
        .environment(\.colorScheme, .dark)
        .animation(.easeOut(duration: 0.18), value: model.pane)
    }
}

private struct SettingsSidebar: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                AppGlyph(size: 30)
                VStack(alignment: .leading, spacing: 0) {
                    Text("TapDrop").font(.system(size: 14, weight: .semibold)).foregroundStyle(.white)
                    Text("Settings").font(.system(size: 11)).foregroundStyle(.white.opacity(0.5))
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 46)
            .padding(.bottom, 18)

            ForEach(SettingsPane.allCases) { p in
                SidebarItem(pane: p, selected: model.pane == p) { model.pane = p }
            }
            Spacer()
            QuitItem()
                .padding(.bottom, 14)
        }
        .padding(.horizontal, 10)
        .background(Color.black.opacity(0.12))
    }
}

private struct QuitItem: View {
    @State private var hover = false

    var body: some View {
        Button {
            NSApp.terminate(nil)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "power")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.white.opacity(hover ? 0.9 : 0.55))
                    .frame(width: 24, height: 24)
                Text("Quit TapDrop")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(.white.opacity(hover ? 0.9 : 0.55))
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(.white.opacity(hover ? 0.06 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help("Quit TapDrop (⌘Q)")
    }
}

private struct SidebarItem: View {
    let pane: SettingsPane
    let selected: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: pane.symbol)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 24, height: 24)
                    .background(RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(LinearGradient(colors: pane.tint, startPoint: .top, endPoint: .bottom)))
                Text(pane.title)
                    .font(.system(size: 13, weight: selected ? .semibold : .regular))
                    .foregroundStyle(.white.opacity(selected ? 1 : 0.8))
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(.white.opacity(selected ? 0.12 : (hover ? 0.05 : 0))))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

// MARK: - Building blocks

private struct PaneHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 24, weight: .bold, design: .rounded)).foregroundStyle(.white)
            Text(subtitle).font(.system(size: 13)).foregroundStyle(.white.opacity(0.55))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 20)
    }
}

/// A titled group of rows on a soft glass card.
private struct Card<Content: View>: View {
    var title: String?
    var footer: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            if let title {
                Text(title.uppercased())
                    .font(.system(size: 10.5, weight: .semibold))
                    .tracking(0.6)
                    .foregroundStyle(.white.opacity(0.45))
                    .padding(.leading, 4)
            }
            VStack(spacing: 0) { content }
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(.white.opacity(0.055)))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.08), lineWidth: 1))
            if let footer {
                Text(footer)
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.45))
                    .padding(.horizontal, 4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.bottom, 20)
    }
}

private struct Row<Trailing: View>: View {
    var symbol: String?
    var tint: [Color] = [Brand.a, Brand.b]
    let title: String
    var subtitle: String?
    var divider = true
    var oneLine = false
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                if let symbol {
                    Image(systemName: symbol)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 26, height: 26)
                        .background(RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .fill(LinearGradient(colors: tint, startPoint: .top, endPoint: .bottom)))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(.white)
                    if let subtitle {
                        if oneLine {
                            Text(subtitle).font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.5))
                                .lineLimit(1).truncationMode(.middle)
                        } else {
                            Text(subtitle).font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.5))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                Spacer(minLength: 12)
                trailing
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            if divider {
                Rectangle().fill(.white.opacity(0.06)).frame(height: 1).padding(.leading, symbol == nil ? 14 : 52)
            }
        }
    }
}

private struct Check: View {
    let ok: Bool?   // nil = neutral / informational

    var body: some View {
        Image(systemName: ok == true ? "checkmark.circle.fill" : (ok == false ? "xmark.circle.fill" : "minus.circle.fill"))
            .font(.system(size: 16))
            .foregroundStyle(ok == true ? Color.green : (ok == false ? Color.red.opacity(0.85) : Color.white.opacity(0.35)))
    }
}

private struct SwitchToggle: View {
    @Binding var on: Bool

    var body: some View {
        Toggle("", isOn: $on).toggleStyle(.switch).labelsHidden().tint(Brand.b).controlSize(.small)
    }
}

private struct SmallButton: View {
    let title: String
    var prominent = false
    let action: () -> Void

    var body: some View {
        Button(title, action: action)
            .buttonStyle(.plain)
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 11)
            .frame(height: 26)
            .background(Capsule().fill(prominent ? AnyShapeStyle(Brand.gradient) : AnyShapeStyle(Color.white.opacity(0.1))))
            .overlay(Capsule().strokeBorder(.white.opacity(prominent ? 0 : 0.1), lineWidth: 1))
    }
}

// MARK: - General

private struct GeneralPane: View {
    @ObservedObject var store = Store.shared
    @State private var loginEnabled = SMAppService.mainApp.status == .enabled

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PaneHeader(title: "General", subtitle: "Where things land and how TapDrop behaves.")

            Card(title: "Received files") {
                Row(symbol: "folder.fill", tint: [Color(red: 0.35, green: 0.65, blue: 1), Color(red: 0.2, green: 0.45, blue: 0.95)],
                    title: "Save to", subtitle: (store.saveFolder.path as NSString).abbreviatingWithTildeInPath, oneLine: true) {
                    HStack(spacing: 6) {
                        SmallButton(title: "Show") { NSWorkspace.shared.open(store.saveFolder) }
                        SmallButton(title: "Change…") { chooseFolder() }
                    }
                }
                Row(symbol: "link", tint: [Brand.a, Brand.b], title: "Open links automatically",
                    subtitle: "A page sent from your phone opens in your browser", divider: false) {
                    SwitchToggle(on: $store.settings.openLinks)
                }
            }

            Card(title: "Sounds & startup") {
                Row(symbol: "speaker.wave.2.fill", tint: [Color(red: 1, green: 0.45, blue: 0.55), Color(red: 0.9, green: 0.25, blue: 0.45)],
                    title: "Play sounds", subtitle: "A soft chime when something arrives") {
                    SwitchToggle(on: $store.settings.playSound)
                }
                Row(symbol: "power", tint: [Color(white: 0.6), Color(white: 0.4)], title: "Open at login",
                    subtitle: "So your phone can always reach this Mac", divider: false) {
                    SwitchToggle(on: Binding(
                        get: { loginEnabled },
                        set: { on in
                            if on { try? SMAppService.mainApp.register() } else { try? SMAppService.mainApp.unregister() }
                            loginEnabled = SMAppService.mainApp.status == .enabled
                        }))
                }
            }

            Card(title: "Clipboard", footer: "One-time codes are hidden from clipboard managers and cleared after 2 minutes, unless you copy something else first.") {
                Row(symbol: "lock.fill", tint: [Color(red: 1, green: 0.76, blue: 0.29), Color(red: 1, green: 0.54, blue: 0.24)],
                    title: "Codes stay private", subtitle: nil, divider: false) { EmptyView() }
            }
        }
    }

    private func chooseFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.directoryURL = store.saveFolder
        if panel.runModal() == .OK, let url = panel.url { store.settings.saveFolder = url.path }
    }
}

// MARK: - Tap to Send

private struct TapPane: View {
    @ObservedObject var store = Store.shared
    @ObservedObject var log = TapLog.shared
    private let sensor = NotchSensor.shared

    private var livePhones: [PairedDevice] { store.devices.filter { store.liveDevices.contains($0.id) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PaneHeader(title: "Tap to Send",
                       subtitle: "Tap your phone on your Mac and whatever is on its screen comes here: the page, the photo, the file, or a screenshot.")

            Card(title: "Ways to tap") {
                Row(symbol: "dot.radiowaves.left.and.right", title: "Tap your phone on its spot",
                    subtitle: "Works in any light. Your Mac has magnets inside (speakers, trackpad motor, MagSafe); the phone feels the strongest one. Set it up on the phone: TapDrop → Settings → Tap spot.",
                    divider: true) {
                    Check(ok: nil)
                }
                Row(symbol: "hand.raised.fill", tint: [Color(red: 1, green: 0.76, blue: 0.29), Color(red: 1, green: 0.54, blue: 0.24)],
                    title: "Hold phone over the notch",
                    subtitle: sensor.available ? "In a lit room: the light sensor next to the camera sees the phone arrive" : "This Mac has no light sensor TapDrop can use",
                    divider: false) {
                    SwitchToggle(on: $store.settings.notchTap).disabled(!sensor.available)
                }
            }

            Card(title: "Status") {
                Row(symbol: "sun.max.fill", tint: [Color(red: 1, green: 0.76, blue: 0.29), Color(red: 1, green: 0.54, blue: 0.24)],
                    title: "Light sensor in the notch",
                    subtitle: sensor.available ? "Found" : "Not found — use the phone's Share button or an NFC sticker instead") {
                    Check(ok: sensor.available)
                }
                Row(symbol: "iphone.gen3.radiowaves.left.and.right", tint: [Color(red: 0.3, green: 0.85, blue: 0.5), Color(red: 0.12, green: 0.64, blue: 0.39)],
                    title: "Phone link",
                    subtitle: livePhones.isEmpty
                        ? (store.devices.isEmpty ? "No phone paired yet" : "Not connected — it connects while the phone's screen is on and Tap to send is on in the TapDrop app")
                        : "\(livePhones.map(\.name).joined(separator: ", ")) ready for taps",
                    divider: false) {
                    Check(ok: livePhones.isEmpty ? (store.devices.isEmpty ? false : nil) : true)
                }
            }

            if sensor.available {
                Card(title: "Try it: notch", footer: "In a lit room, hold the back of your phone over the notch: the light drops and it sends. A phone lying still on the desk ignores it, so a hand over the notch won't send anything.") {
                    LightMeter(lastCover: log.lastCover)
                }
            }

            Card(title: "Recent taps") {
                if log.events.isEmpty {
                    Row(title: "No taps yet", subtitle: "Every tap is listed here with what your phone did", divider: false) { EmptyView() }
                } else {
                    ForEach(Array(log.events.prefix(8).enumerated()), id: \.element.id) { i, e in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Circle()
                                .fill(e.mood == .good ? Color.green : (e.mood == .bad ? Color.orange : Color.white.opacity(0.35)))
                                .frame(width: 7, height: 7)
                            Text(e.date.formatted(date: .omitted, time: .standard))
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(.white.opacity(0.4))
                            Text(e.text).font(.system(size: 12)).foregroundStyle(.white.opacity(0.85))
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        if i < min(7, log.events.count - 1) {
                            Rectangle().fill(.white.opacity(0.06)).frame(height: 1).padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }
}

/// Live light level from the notch sensor, with a flash when a tap is recognized.
/// Live view of the light at the notch, with a flash when a tap is recognized.
private struct LightMeter: View {
    let lastCover: Date?
    @State private var lux: Double = 0
    @State private var normal: Double = 1
    @State private var flash = false

    private var dark: Bool { normal < NotchSensor.minLux }
    private var fill: Double { min(1, lux / max(normal, 0.1) / 1.2) }

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle().fill(flash ? AnyShapeStyle(Color.green) : AnyShapeStyle(Color.white.opacity(0.08)))
                Image(systemName: flash ? "checkmark" : (dark ? "moon.fill" : "sun.max.fill"))
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                    .contentTransition(.symbolEffect(.replace))
            }
            .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 7) {
                Text(flash ? "Tap recognized" : (dark ? "Too dark for the notch — tap your phone on its spot instead" : "Hold your phone over the notch"))
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(.white.opacity(0.08))
                        Capsule()
                            .fill(LinearGradient(colors: [Brand.a, Brand.b], startPoint: .leading, endPoint: .trailing))
                            .frame(width: max(6, geo.size.width * fill))
                        Rectangle().fill(.white.opacity(0.55)).frame(width: 1.5, height: 12)
                            .offset(x: geo.size.width * (0.45 / 1.2))
                    }
                }
                .frame(height: 8)
                Text(String(format: "Light %.0f lux · normal %.0f", lux, normal))
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
        .padding(14)
        .animation(.easeOut(duration: 0.12), value: fill)
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: flash)
        .onAppear {
            NotchSensor.shared.onReading = { l, n in
                lux = l
                normal = max(n, 0.1)
            }
            NotchSensor.shared.setTesting(true)
        }
        .onDisappear {
            NotchSensor.shared.setTesting(false)
            NotchSensor.shared.onReading = nil
        }
        .onChange(of: lastCover) { _, d in
            guard d != nil else { return }
            flash = true
            Task {
                try? await Task.sleep(for: .seconds(1.6))
                flash = false
            }
        }
    }
}

// MARK: - Phones

private struct PhonesPane: View {
    @ObservedObject var store = Store.shared
    @State private var confirm: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PaneHeader(title: "Phones", subtitle: "Phones paired with this Mac. Pairing happens once; after that they find each other on your Wi-Fi.")

            Card(title: "Paired") {
                if store.devices.isEmpty {
                    Row(symbol: "iphone.gen3", tint: [Color(white: 0.6), Color(white: 0.4)], title: "No phones yet",
                        subtitle: "Set up a phone to start sharing", divider: false) { EmptyView() }
                }
                ForEach(Array(store.devices.enumerated()), id: \.element.id) { i, d in
                    Row(symbol: "iphone.gen3", tint: [Color(red: 0.3, green: 0.85, blue: 0.5), Color(red: 0.12, green: 0.64, blue: 0.39)],
                        title: d.name, subtitle: status(d), divider: i < store.devices.count - 1) {
                        SmallButton(title: confirm == d.id ? "Confirm unpair" : "Unpair") {
                            if confirm == d.id {
                                store.removeDevice(d.id)
                                confirm = nil
                            } else {
                                confirm = d.id
                            }
                        }
                    }
                }
            }

            HStack {
                SmallButton(title: "Set Up a New Phone…", prominent: true) { SetupWindow.shared.show() }
                Spacer()
            }
            .padding(.bottom, 24)

            if !store.outbox.isEmpty {
                Card(title: "Waiting for your phone") {
                    Row(symbol: "tray.full.fill", tint: [Brand.a, Brand.b],
                        title: "\(store.outbox.count) item\(store.outbox.count == 1 ? "" : "s") queued",
                        subtitle: "Tap your phone on the Mac, or open TapDrop on it, to receive", divider: false) {
                        SmallButton(title: "Clear") { store.clearOutbox() }
                    }
                }
            }
        }
    }

    private func status(_ d: PairedDevice) -> String {
        let paired = "Paired \(d.pairedAt.formatted(date: .abbreviated, time: .omitted))"
        if store.liveDevices.contains(d.id) { return "Connected · ready for taps · \(paired)" }
        if let s = d.lastSeen, Date().timeIntervalSince(s) < 120 { return "Nearby · \(paired)" }
        if let s = d.lastSeen { return "Last seen \(ShortTime.string(s)) · \(paired)" }
        return paired
    }
}

// MARK: - Compatibility

enum Compatibility {
    struct Group: Hashable { let name: String; let models: String }

    static let androidVersions = "Android 13, 14, 15, 16 and newer"
    static let pixels = [
        "Pixel 10 · 10 Pro · 10 Pro XL · 10 Pro Fold",
        "Pixel 9 · 9 Pro · 9 Pro XL · 9 Pro Fold · 9a",
        "Pixel 8 · 8 Pro · 8a",
        "Pixel 7 · 7 Pro · 7a · Fold · Tablet",
        "Pixel 6 · 6 Pro · 6a",
        "Pixel 5 · 5a",
        "Pixel 4 · 4 XL · 4a · 4a (5G)",
    ]
    static let brands = [
        Group(name: "Samsung Galaxy", models: "S20 and newer, Note 20, Z Fold / Flip 3 and newer, most A-series from 2021 on"),
        Group(name: "OnePlus", models: "OnePlus 8 and newer, Nord models on Android 13+"),
        Group(name: "Nothing", models: "Phone (1), (2), (2a), (3) and CMF Phone"),
        Group(name: "Xiaomi · Redmi · POCO", models: "Models on HyperOS or MIUI 14"),
        Group(name: "Motorola", models: "Edge and moto g models updated to Android 13+"),
        Group(name: "Sony Xperia", models: "Xperia 1, 5 and 10 IV and newer"),
        Group(name: "Oppo · Realme · Vivo · iQOO", models: "Models updated to Android 13+"),
    ]
    static let notSupported = "Pixel 3 and older (they stopped at Android 12), any phone on Android 12 or older, Huawei phones on HarmonyOS, Amazon Fire tablets"
    static let macsWithTap = [
        Group(name: "Every MacBook with Apple silicon", models: "Tap the phone on its magnet spot (found once, on the phone), in any light"),
        Group(name: "Notched MacBook Air / Pro", models: "Also: hold the phone over the notch in a lit room (Air M2 and newer, 14\"/16\" Pro)"),
    ]
    static let macsOther = [
        Group(name: "iMac, Mac mini, Mac Studio, Mac Pro", models: "Tap a magnet spot if the phone finds one (speakers, MagSafe-style stands); otherwise share from the phone, codes, clipboard, NFC sticker"),
        Group(name: "Intel Macs", models: "Not yet: needs a universal build"),
    ]
    static let phoneNeeds = [
        Group(name: "Accessibility", models: "Tap to send needs TapDrop turned on in Accessibility. Phones installed from outside the Play Store first need App info → ⋮ → Allow restricted settings."),
        Group(name: "Background", models: "Samsung: Battery → Unrestricted. Xiaomi: Autostart on + No restrictions. OnePlus / Oppo / Realme: Allow background activity. Vivo: allow background power use."),
        Group(name: "Photos & videos access", models: "So the photo or video you're viewing is sent as the original."),
    ]
    static let tested = "Works with Android 13 or newer from any brand. Tap to send needs a Mac with a notch."
}

/// Facts about this Mac for the compatibility check.
enum ThisMac {
    static var modelName: String {
        let entry = IORegistryEntryFromPath(kIOMainPortDefault, "IODeviceTree:/product")
        defer { IOObjectRelease(entry) }
        if entry != 0,
           let data = IORegistryEntryCreateCFProperty(entry, "product-name" as CFString, kCFAllocatorDefault, 0)?
               .takeRetainedValue() as? Data {
            let name = String(decoding: data.prefix { $0 != 0 }, as: UTF8.self)
            if !name.isEmpty { return name }
        }
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        var buf = [CChar](repeating: 0, count: max(size, 1))
        sysctlbyname("hw.model", &buf, &size, nil, 0)
        return String(cString: buf)
    }

    static var macOS: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "macOS \(v.majorVersion).\(v.minorVersion)" + (v.patchVersion > 0 ? ".\(v.patchVersion)" : "")
    }

    static var macOSSupported: Bool { ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 15 }

    static var appleSilicon: Bool {
        #if arch(arm64)
        return true
        #else
        return false
        #endif
    }

    static var hasNotch: Bool { NSScreen.screens.contains { $0.safeAreaInsets.top > 0 } }
}

private struct CompatibilityPane: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PaneHeader(title: "Compatibility", subtitle: "Which phones and Macs TapDrop works with, and what each needs.")

            Card(title: "This Mac") {
                Row(symbol: "laptopcomputer", tint: [Brand.a, Brand.b], title: ThisMac.modelName, subtitle: ThisMac.macOS) {
                    Check(ok: ThisMac.macOSSupported && ThisMac.appleSilicon)
                }
                Row(title: "macOS 15 or newer", subtitle: ThisMac.macOS) { Check(ok: ThisMac.macOSSupported) }
                Row(title: "Apple silicon", subtitle: ThisMac.appleSilicon ? "M-series chip" : "Intel — needs a universal build") { Check(ok: ThisMac.appleSilicon) }
                Row(title: "Notch", subtitle: ThisMac.hasNotch ? "The island appears around it" : "No notch — notices drop from the top of the screen") { Check(ok: ThisMac.hasNotch ? true : nil) }
                Row(title: "Light sensor in the notch", subtitle: NotchSensor.shared.available ? "Found — hold the phone over it in a lit room" : "Not available", divider: false) {
                    Check(ok: NotchSensor.shared.available ? true : nil)
                }
            }

            Card(title: "Android phones · \(Compatibility.androidVersions)") {
                ListRow(title: "Google Pixel", body: Compatibility.pixels.joined(separator: "\n"))
                ForEach(Compatibility.brands, id: \.self) { g in ListRow(title: g.name, body: g.models) }
                ListRow(title: "Not supported", body: Compatibility.notSupported, muted: true, divider: false)
            }

            Card(title: "Macs · macOS 15 Sequoia or newer, Apple silicon") {
                ForEach(Compatibility.macsWithTap, id: \.self) { g in ListRow(title: g.name, body: g.models) }
                ForEach(Array(Compatibility.macsOther.enumerated()), id: \.element) { i, g in
                    ListRow(title: g.name, body: g.models, muted: g.name.hasPrefix("Intel"), divider: i < Compatibility.macsOther.count - 1)
                }
            }

            Card(title: "What the phone needs", footer: Compatibility.tested) {
                ListRow(title: "Same Wi-Fi", body: "Phone and Mac on the same network. Guest or hotel Wi-Fi that isolates devices won't work.")
                ForEach(Array(Compatibility.phoneNeeds.enumerated()), id: \.element) { i, g in
                    ListRow(title: g.name, body: g.models, divider: i < Compatibility.phoneNeeds.count - 1)
                }
            }
        }
    }
}

private struct ListRow: View {
    let title: String
    let body_: String
    var muted = false
    var divider = true

    init(title: String, body: String, muted: Bool = false, divider: Bool = true) {
        self.title = title
        self.body_ = body
        self.muted = muted
        self.divider = divider
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(.white.opacity(muted ? 0.55 : 0.95))
                Text(body_).font(.system(size: 11.5)).foregroundStyle(.white.opacity(0.5))
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            if divider { Rectangle().fill(.white.opacity(0.06)).frame(height: 1).padding(.leading, 14) }
        }
    }
}

// MARK: - About

private struct AboutPane: View {
    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                AppGlyph(size: 64)
                VStack(alignment: .leading, spacing: 3) {
                    Text("TapDrop").font(.system(size: 26, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    Text("Version \(version)").font(.system(size: 12)).foregroundStyle(.white.opacity(0.5))
                    Text("AirDrop-style sharing between Android and your Mac")
                        .font(.system(size: 13)).foregroundStyle(.white.opacity(0.7))
                }
            }
            .padding(.bottom, 26)

            Card(title: "Privacy") {
                Row(symbol: "lock.fill", tint: [Color(red: 0.3, green: 0.85, blue: 0.5), Color(red: 0.12, green: 0.64, blue: 0.39)],
                    title: "End-to-end encrypted", subtitle: "Every transfer is sealed with a key only your paired phone and this Mac share.") { EmptyView() }
                Row(symbol: "wifi", tint: [Brand.a, Brand.b], title: "Stays on your Wi-Fi",
                    subtitle: "Nothing goes through a server or the internet.") { EmptyView() }
                Row(symbol: "eye.slash.fill", tint: [Color(white: 0.6), Color(white: 0.4)], title: "Looks only when you tap",
                    subtitle: "The phone reads its screen at the moment you tap, never in the background.", divider: false) { EmptyView() }
            }

            HStack(spacing: 8) {
                SmallButton(title: "Set Up a Phone…") { SetupWindow.shared.show() }
                Spacer()
            }
        }
    }
}
