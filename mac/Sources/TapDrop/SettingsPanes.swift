import AppKit
import IOKit
import ServiceManagement
import SwiftUI

// Settings panes: General, Compatibility, About. (Tap to Send and Phones have their own files.)

// MARK: - General

struct GeneralPane: View {
    let visible: Bool
    @ObservedObject private var store = Store.shared
    @State private var login = GeneralPane.readLogin()
    @State private var loginError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xxl) {
            SettingsPaneHeader(.general)
                .tdReveal(0, visible)

            SettingsGroup("Received files") {
                SettingsRow(folderExists ? "folder.fill" : "exclamationmark.triangle.fill",
                            tint: folderExists ? TDTint.blue : TDTint.orange, title: "Save to", subtitle: folderSubtitle) {
                    FolderMenu(folder: store.saveFolder, missing: !folderExists) { url in
                        store.settings.saveFolder = url.path
                    }
                }
                SettingsRow("link", title: "Open links automatically",
                            subtitle: "A page sent from your phone opens in your browser.") {
                    Toggle("Open links automatically", isOn: $store.settings.openLinks).tdSwitch()
                }
            }
            .tdReveal(1, visible)

            SettingsGroup("Sounds & startup", footer: loginFooter) {
                SettingsRow("speaker.wave.2.fill", tint: TDTint.pink, title: "Play sounds",
                            subtitle: "A soft chime when something arrives.") {
                    HStack(spacing: TD.Space.s) {
                        Button {
                            NSSound(named: "Glass")?.play()
                        } label: {
                            Label("Play the arrival sound", systemImage: "play.fill")
                        }
                        .buttonStyle(.tdIcon(size: 24, quiet: true))
                        .help("Play the arrival sound")
                        Toggle("Play sounds", isOn: $store.settings.playSound).tdSwitch()
                    }
                }
                SettingsRow("power", tint: TDTint.gray, title: "Open at login", subtitle: loginSubtitle) {
                    HStack(spacing: TD.Space.s) {
                        if login == .needsApproval {
                            Button("Allow…") { SMAppService.openSystemSettingsLoginItems() }
                                .buttonStyle(.td(.secondary, size: .small))
                        }
                        Toggle("Open at login", isOn: loginBinding).tdSwitch()
                    }
                }
            }
            .tdReveal(2, visible)

            SettingsGroup("Clipboard") {
                SettingsRow("lock.fill", tint: TDTint.orange, title: "One-time codes stay private",
                            subtitle: "Codes from your phone are hidden from clipboard managers and cleared after 2 minutes, unless you copy something else first.")
            }
            .tdReveal(3, visible)

            SettingsGroup("Send from this Mac") {
                SettingsRow("menubar.arrow.up.rectangle", tint: TDTint.aurora, title: "Drop files on the menu bar icon",
                            subtitle: "They wait there until you touch the top of your phone's screen to the notch, or open TapDrop on it.")
                SettingsRow("cursorarrow.click.2", tint: TDTint.slate, title: "Right-click in Finder",
                            subtitle: "Quick Actions → Send to Phone with TapDrop")
                SettingsRow("keyboard", tint: TDTint.gray, title: "In the TapDrop menu",
                            subtitle: "Send files or what you copied.") {
                    HStack(spacing: TD.Space.xs) {
                        KeyHint("⌘O")
                        KeyHint("⌘V")
                    }
                }
            }
            .tdReveal(4, visible)
        }
        .onAppear { login = Self.readLogin() }
    }

    private static func readLogin() -> LoginItem {
        SettingsDemo.generalProblems ? .needsApproval : LoginItem.read()
    }

    private var folderExists: Bool {
        if SettingsDemo.generalProblems { return false }
        var dir: ObjCBool = false
        return FileManager.default.fileExists(atPath: store.saveFolder.path, isDirectory: &dir) && dir.boolValue
    }

    private var folderSubtitle: String {
        let path = Self.shortPath(store.saveFolder)
        return folderExists ? path : "\(path) is missing. Choose another folder."
    }

    /// "~/Downloads"; long paths keep their start and their last two folders.
    static func shortPath(_ url: URL) -> String {
        let full = (url.path as NSString).abbreviatingWithTildeInPath
        guard full.count > 44 else { return full }
        let parts = full.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count > 4 else { return full }
        let head = parts[0].isEmpty ? "/\(parts[1])" : parts[0]
        return "\(head)/…/\(parts.suffix(2).joined(separator: "/"))"
    }

    private var loginSubtitle: String {
        switch login {
        case .on: "So your phone can always reach this Mac."
        case .off: "So your phone can always reach this Mac."
        case .needsApproval: "Waiting for your OK in System Settings → General → Login Items."
        }
    }

    private var loginFooter: String? { loginError }

    private var loginBinding: Binding<Bool> {
        Binding(
            get: { login != .off },
            set: { on in
                loginError = LoginItem.set(on)
                withTDAnimation { login = LoginItem.read() }
            })
    }
}

/// Open at login, through SMAppService.
enum LoginItem: Equatable {
    case on, off, needsApproval

    static func read() -> LoginItem {
        switch SMAppService.mainApp.status {
        case .enabled: .on
        case .requiresApproval: .needsApproval
        default: .off
        }
    }

    /// Returns a message when it didn't work.
    static func set(_ on: Bool) -> String? {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            return nil
        } catch {
            return on ? "Couldn't add TapDrop to your login items. Move TapDrop to Applications and try again."
                      : "Couldn't remove TapDrop from your login items. You can remove it in System Settings → General → Login Items."
        }
    }
}

/// The save folder as a pop-up button: Downloads, Desktop, Documents, Other…, Show in Finder.
private struct FolderMenu: View {
    let folder: URL
    let missing: Bool
    let choose: (URL) -> Void

    private static let places: [(String, FileManager.SearchPathDirectory)] = [
        ("Downloads", .downloadsDirectory), ("Desktop", .desktopDirectory), ("Documents", .documentDirectory),
    ]

    var body: some View {
        Button(action: popUp) {
            HStack(spacing: 6) {
                Image(nsImage: Self.icon(missing ? nil : folder, size: 16))
                    .frame(width: 16, height: 16)
                Text(missing ? "Choose…" : folder.lastPathComponent)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: 140)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 8.5, weight: .bold))
                    .foregroundStyle(TD.textSecondary)
            }
        }
        .buttonStyle(.td(.secondary, size: .small))
        .fixedSize()
        .accessibilityLabel("Save to \(folder.lastPathComponent)")
        .accessibilityHint("Shows places to save to")
        .help((folder.path as NSString).abbreviatingWithTildeInPath)
    }

    private static func icon(_ url: URL?, size: CGFloat) -> NSImage {
        let img = (NSWorkspace.shared.icon(forFile: url?.path ?? "/System/Library/CoreServices/Finder.app").copy() as? NSImage) ?? NSImage()
        img.size = NSSize(width: size, height: size)
        return img
    }

    /// A native menu under the pointer.
    private func popUp() {
        let menu = NSMenu()
        for (name, dir) in Self.places {
            guard let url = FileManager.default.urls(for: dir, in: .userDomainMask).first else { continue }
            let item = ActionMenuItem(title: name) { choose(url) }
            item.image = Self.icon(url, size: 16)
            item.state = url.standardizedFileURL == folder.standardizedFileURL ? .on : .off
            menu.addItem(item)
        }
        if !Self.places.contains(where: { FileManager.default.urls(for: $0.1, in: .userDomainMask).first?.standardizedFileURL == folder.standardizedFileURL }),
           !missing {
            let item = ActionMenuItem(title: folder.lastPathComponent) {}
            item.image = Self.icon(folder, size: 16)
            item.state = .on
            menu.insertItem(item, at: 0)
            menu.insertItem(.separator(), at: 1)
        }
        menu.addItem(.separator())
        menu.addItem(ActionMenuItem(title: "Other…") { pickFolder() })
        menu.addItem(.separator())
        let show = ActionMenuItem(title: "Show in Finder") { NSWorkspace.shared.open(folder) }
        show.isEnabled = !missing
        menu.addItem(show)
        menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
    }

    private func pickFolder() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.message = "Choose where files from your phone are saved"
        panel.directoryURL = folder
        if panel.runModal() == .OK, let url = panel.url { choose(url) }
    }
}

// MARK: - Compatibility

enum Compatibility {
    struct Group: Hashable {
        let name: String
        let detail: String
        var symbol = "smartphone"
        var supported = true
    }

    static let androidVersions = "Android 13 or newer"
    static let phones = [
        Group(name: "Google Pixel", detail: "Pixel 4 and newer, including Pixel Fold and Pixel Tablet"),
        Group(name: "Samsung Galaxy", detail: "S20 and newer, Note 20, Z Fold and Z Flip 3 and newer, most A-series from 2021 on"),
        Group(name: "OnePlus", detail: "OnePlus 8 and newer, and Nord models on Android 13 or newer"),
        Group(name: "Nothing", detail: "Phone (1), (2), (2a), (3) and CMF Phone"),
        Group(name: "Xiaomi, Redmi, POCO", detail: "Models on HyperOS or MIUI 14"),
        Group(name: "Motorola, Sony, Oppo, Realme, vivo", detail: "Models updated to Android 13 or newer"),
    ]
    static let notSupported = "Not supported: Pixel 3 and older, any phone on Android 12 or older, Huawei phones on HarmonyOS, and Amazon Fire tablets."
    static let macs = [
        Group(name: "MacBook Air and MacBook Pro with Apple silicon",
              detail: "Everything, including Tap to Send at the notch (or the camera, on models without a notch).",
              symbol: "laptopcomputer"),
        Group(name: "iMac, Mac mini, Mac Studio, Mac Pro",
              detail: "Share from your phone, send from the menu bar, codes and clipboard. Tap to Send needs a MacBook.",
              symbol: "desktopcomputer"),
        Group(name: "Intel Macs", detail: "Not supported yet.", symbol: "nosign", supported: false),
    ]
    static let phoneNeeds = [
        Group(name: "The same Wi-Fi", detail: "Your phone and this Mac on one network. Guest or hotel Wi-Fi that keeps devices apart won't work.",
              symbol: "wifi"),
        Group(name: "Accessibility, for Tap to Send", detail: "Turn TapDrop on in Accessibility. If Android won't let you, open App info → ⋮ → Allow restricted settings first.",
              symbol: "accessibility"),
        Group(name: "Running in the background", detail: "Samsung: Battery → Unrestricted. Xiaomi: Autostart on, no restrictions. OnePlus, Oppo, Realme: allow background activity. vivo: allow background power use.",
              symbol: "battery.100percent.bolt"),
        Group(name: "Photos and videos", detail: "So the photo or video you're looking at arrives as the original.",
              symbol: "photo.on.rectangle"),
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
        return sysctl("hw.model") ?? "This Mac"
    }

    /// "Apple M4".
    static var chip: String? { sysctl("machdep.cpu.brand_string") }

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

    private static func sysctl(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buf, &size, nil, 0) == 0 else { return nil }
        let s = String(cString: buf)
        return s.isEmpty ? nil : s
    }
}

struct CompatibilityPane: View {
    let visible: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xxl) {
            SettingsPaneHeader(.compatibility)
                .tdReveal(0, visible)

            ThisMacCard()
                .tdReveal(1, visible)

            SettingsGroup("Android phones · \(Compatibility.androidVersions), any brand",
                          footer: "\(Compatibility.notSupported) \(Compatibility.tested)") {
                ForEach(Compatibility.phones, id: \.self) { g in
                    SettingsRow(title: g.name, subtitle: g.detail)
                }
            }
            .tdReveal(2, visible)

            SettingsGroup("Macs", footer: "macOS 15 Sequoia or newer.") {
                ForEach(Compatibility.macs, id: \.self) { g in
                    SettingsRow(g.symbol, tint: g.supported ? TDTint.aurora : TDTint.gray, title: g.name, subtitle: g.detail)
                }
            }
            .tdReveal(3, visible)

            SettingsGroup("What your phone needs") {
                ForEach(Compatibility.phoneNeeds, id: \.self) { g in
                    SettingsRow(g.symbol, tint: TDTint.blue, title: g.name, subtitle: g.detail)
                }
            }
            .tdReveal(4, visible)
        }
    }
}

/// This Mac, and whether each part TapDrop relies on is there.
private struct ThisMacCard: View {
    private let sensor = NotchSensor.shared.available && !SettingsDemo.noSensor

    private var ready: Bool { ThisMac.macOSSupported && ThisMac.appleSilicon }

    var body: some View {
        GlassCard(.surface, padding: 0, radius: TD.Radius.card) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: TD.Space.l) {
                    Image(systemName: ThisMac.hasNotch ? "laptopcomputer" : "desktopcomputer")
                        .font(.system(size: 30, weight: .regular))
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(TD.aurora)
                        .frame(width: 64, height: 64)
                        .background(Circle().fill(TD.violet.opacity(0.12)))
                        .overlay(Circle().strokeBorder(TD.violet.opacity(0.18), lineWidth: 1))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("This Mac")
                            .tdFont(.caption)
                            .foregroundStyle(TD.textTertiary)
                        Text(ThisMac.modelName)
                            .tdFont(.title2)
                            .foregroundStyle(TD.text)
                            .fixedSize(horizontal: false, vertical: true)
                        Text([ThisMac.chip, ThisMac.macOS].compactMap { $0 }.joined(separator: " · "))
                            .tdFont(.callout)
                            .foregroundStyle(TD.textSecondary)
                    }
                    Spacer(minLength: TD.Space.m)
                    verdict
                        .fixedSize()
                }
                .padding(TD.Space.l)
                .background(alignment: .topTrailing) {
                    AuroraBackdrop(intensity: 0.35, drift: 0)
                        .mask(LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom))
                }

                TDDivider()

                Grid(alignment: .leading, horizontalSpacing: TD.Space.l, verticalSpacing: TD.Space.m) {
                    GridRow {
                        fact(.init(ok: ThisMac.macOSSupported), "macOS 15 or newer", ThisMac.macOS)
                        fact(.init(ok: ThisMac.appleSilicon), "Apple silicon", ThisMac.appleSilicon ? (ThisMac.chip ?? "M-series chip") : "Intel isn't supported yet")
                    }
                    GridRow {
                        fact(ThisMac.hasNotch ? .ok : .neutral, "Notch", ThisMac.hasNotch ? "Notices grow out of it" : "Notices drop from the top")
                        fact(sensor ? .ok : .missing, "Light sensor",
                             sensor ? (ThisMac.hasNotch ? "At the notch, for Tap to Send" : "By the camera, for Tap to Send") : "Tap to Send isn't available")
                    }
                }
                .padding(TD.Space.l)
            }
        }
    }

    /// A verdict, not a live link, so it stays still.
    @ViewBuilder private var verdict: some View {
        if !ready {
            FactChip(text: "Not supported", symbol: "xmark", tint: TD.danger)
        } else if sensor {
            FactChip(text: "Ready", symbol: "checkmark", tint: TD.success)
        } else {
            FactChip(text: "No Tap to Send", symbol: "minus")
        }
    }

    private func fact(_ state: CheckBadge.State, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: TD.Space.s + 2) {
            CheckBadge(state: state)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).tdFont(.bodyMedium).foregroundStyle(TD.text)
                Text(detail).tdFont(.callout).foregroundStyle(TD.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

private extension CheckBadge.State {
    init(ok: Bool) { self = ok ? .ok : .missing }
}

// MARK: - About

struct AboutPane: View {
    let visible: Bool
    @ObservedObject private var store = Store.shared

    private var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        switch (v, b) {
        case let (v?, b?) where b != v: return "Version \(v) (\(b))"
        case let (v?, _): return "Version \(v)"
        default: return "Development build"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xxl) {
            hero
                .tdReveal(0, visible)

            SettingsGroup("Privacy") {
                SettingsRow("lock.fill", tint: TDTint.green, title: "End-to-end encrypted",
                            subtitle: "Every transfer is sealed with a key only this Mac and your phone share.")
                SettingsRow("wifi", tint: TDTint.blue, title: "Stays on your Wi-Fi",
                            subtitle: "Nothing goes through a server or the internet.")
                SettingsRow("checkmark.shield.fill", tint: TDTint.aurora, title: "Only your phone's screen can send",
                            subtitle: TapCopy.safety)
                SettingsRow("eye.slash.fill", tint: TDTint.gray, title: "Looks only when you tap",
                            subtitle: "Your phone reads its screen at the moment you tap, never in the background.")
            }
            .tdReveal(1, visible)

            SettingsGroup {
                SettingsRow("qrcode", tint: TDTint.aurora, title: "Set Up a Phone",
                            subtitle: "Install TapDrop on an Android phone and pair it.", action: { SetupWindow.shared.show() })
                SettingsRow("list.bullet.rectangle", tint: TDTint.slate, title: "Tap Log",
                            subtitle: "Every tap, with the light levels behind it.", action: { TapLogFile.open() })
            }
            .tdReveal(2, visible)
        }
    }

    private var hero: some View {
        VStack(spacing: TD.Space.m) {
            AppGlyph(size: 84, glow: true)
                .padding(.bottom, TD.Space.xs)
            VStack(spacing: 4) {
                Text("TapDrop")
                    .tdFont(.display)
                    .foregroundStyle(TD.text)
                Text(version)
                    .tdFont(.number(12, .medium))
                    .foregroundStyle(TD.textSecondary)
                    .textSelection(.enabled)
            }
            Text("AirDrop-style sharing between your Android phone and your Mac.")
                .tdFont(.body)
                .foregroundStyle(TD.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, TD.Space.xxxl)
        .padding(.horizontal, TD.Space.xxl)
        .frame(maxWidth: .infinity)
        .background { AuroraBackdrop(intensity: 0.9) }
        .clipShape(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: TD.Radius.card, style: .continuous).strokeBorder(TD.hairline, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// An NSMenuItem that runs a closure.
final class ActionMenuItem: NSMenuItem {
    private let handler: () -> Void

    init(title: String, handler: @escaping () -> Void) {
        self.handler = handler
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }

    required init(coder: NSCoder) { fatalError("not used") }

    @objc private func fire() { handler() }
}

/// The rolling tap log file (TapLog writes it).
enum TapLogFile {
    static var url: URL { Store.dir.appendingPathComponent("taps.log") }

    static func open() {
        if FileManager.default.fileExists(atPath: url.path) {
            NSWorkspace.shared.open(url)
        } else {
            NSWorkspace.shared.activateFileViewerSelecting([Store.dir])
        }
    }
}
