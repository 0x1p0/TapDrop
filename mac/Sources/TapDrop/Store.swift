import AppKit
import Foundation
import SystemConfiguration

struct PairedDevice: Codable, Identifiable, Equatable {
    var id: String
    var name: String
    var key: Data
    var pairedAt: Date
    var lastSeen: Date?
}

/// A phone this Mac forgot, kept (with its key) only to tell it so: the next time it connects it gets an authenticated
/// "forgotten", and removes this Mac too. Nothing else is ever served to it.
struct ForgottenDevice: Codable, Equatable {
    var id: String
    var key: Data
    var at: Date
}

struct OutboxItem: Codable, Identifiable, Equatable {
    var id = UUID()
    var path: String?
    var text: String?
    var addedAt = Date()

    var displayName: String {
        if let path { return (path as NSString).lastPathComponent }
        return text ?? ""
    }
}

struct AppSettings: Codable, Equatable {
    var saveFolder: String?
    var openLinks = true
    var playSound = true
    var didFirstLaunch = false
    var notchTap = true
    /// Look for a new TapDrop once a day (Settings → About).
    var checkForUpdates = true
    /// "system" (follow the Mac), "light" or "dark".
    var appearance = "system"

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        saveFolder = try c.decodeIfPresent(String.self, forKey: .saveFolder)
        openLinks = try c.decodeIfPresent(Bool.self, forKey: .openLinks) ?? true
        playSound = try c.decodeIfPresent(Bool.self, forKey: .playSound) ?? true
        didFirstLaunch = try c.decodeIfPresent(Bool.self, forKey: .didFirstLaunch) ?? false
        notchTap = try c.decodeIfPresent(Bool.self, forKey: .notchTap) ?? true
        checkForUpdates = try c.decodeIfPresent(Bool.self, forKey: .checkForUpdates) ?? true
        appearance = try c.decodeIfPresent(String.self, forKey: .appearance) ?? "system"
    }
}

struct RecentEvent: Identifiable {
    let id = UUID()
    let date = Date()
    let incoming: Bool
    let peer: String
    let summary: String
    let files: [URL]
    var text: String? = nil
}

@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published private(set) var devices: [PairedDevice] = []
    @Published private(set) var outbox: [OutboxItem] = []
    private(set) var forgotten: [ForgottenDevice] = []
    @Published var settings = AppSettings() {
        didSet {
            guard settings != oldValue else { return }
            save()
            if settings.notchTap != oldValue.notchTap { WatchHub.shared.settingsChanged() }
            if settings.checkForUpdates != oldValue.checkForUpdates { Updater.shared.settingsChanged() }
            if settings.appearance != oldValue.appearance { Store.applyAppearance(settings.appearance) }
        }
    }
    /// What the update check remembers: the last check, what it found, a skipped version (Updater keeps it).
    var updates = UpdateRecord() {
        didSet { if updates != oldValue { save() } }
    }
    @Published var recent: [RecentEvent] = []
    @Published var status = "Starting…"
    /// Phones currently holding a live connection (screen on, TapDrop link active).
    @Published var liveDevices: Set<String> = []
    @Published var port: UInt16 = 0

    let macId: String

    private struct Persisted: Codable {
        var macId: String
        var devices: [PairedDevice]
        var outbox: [OutboxItem]
        var settings: AppSettings
        var updates: UpdateRecord?
        var forgotten: [ForgottenDevice]?
    }

    nonisolated static var dir: URL {
        let env = ProcessInfo.processInfo.environment
        if let custom = env["TAPDROP_HOME"] {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        // Demo and screenshot runs stage fake phones, recents and queues: they never touch the real paired phones.
        if env["TAPDROP_DEMO"] != nil {
            return FileManager.default.temporaryDirectory.appendingPathComponent("TapDrop-demo", isDirectory: true)
        }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("TapDrop", isDirectory: true)
    }

    private static var stateURL: URL { dir.appendingPathComponent("state.json") }

    nonisolated static var computerName: String {
        #if DEBUG
        // Test and screenshot runs can name the Mac (TAPDROP_TEST_NAME), so captures never show a real computer's name.
        if let n = ProcessInfo.processInfo.environment["TAPDROP_TEST_NAME"], !n.isEmpty { return n }
        #endif
        return (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? Host.current().localizedName ?? "Mac"
    }

    private init() {
        try? FileManager.default.createDirectory(at: Self.dir, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: Self.stateURL),
           let p = try? JSONDecoder().decode(Persisted.self, from: data) {
            macId = p.macId
            devices = p.devices
            outbox = p.outbox.filter { item in
                item.path.map { FileManager.default.fileExists(atPath: $0) } ?? true
            }
            // The backing store, so `didSet` doesn't run while loading: with Tap to Send off it would reach
            // `Store.shared` from inside its own initializer and crash at launch.
            _settings = Published(initialValue: p.settings)
            updates = p.updates ?? UpdateRecord()
            forgotten = (p.forgotten ?? []).filter { Date().timeIntervalSince($0.at) < Self.tellForgottenFor }
        } else {
            macId = TDCrypto.random(8).map { String(format: "%02x", $0) }.joined()
            save()
        }
    }

    /// TapDrop's own light or dark, or nil to follow the Mac (Settings → General → Appearance). Demo runs pick theirs
    /// with TAPDROP_APPEARANCE. The notch island stays dark either way (it sits on black hardware).
    static func applyAppearance(_ value: String) {
        guard ProcessInfo.processInfo.environment["TAPDROP_APPEARANCE"] == nil else { return }
        NSApp.appearance = switch value {
        case "light": NSAppearance(named: .aqua)
        case "dark": NSAppearance(named: .darkAqua)
        default: nil
        }
    }

    func save() {
        let p = Persisted(macId: macId, devices: devices, outbox: outbox, settings: settings, updates: updates,
                          forgotten: forgotten)
        guard let data = try? JSONEncoder().encode(p) else { return }
        try? data.write(to: Self.stateURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Self.stateURL.path)
    }

    var saveFolder: URL {
        if let f = ProcessInfo.processInfo.environment["TAPDROP_SAVE_DIR"] { return URL(fileURLWithPath: f, isDirectory: true) }
        if let f = settings.saveFolder { return URL(fileURLWithPath: f, isDirectory: true) }
        return FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    }

    // MARK: devices

    func device(_ id: String) -> PairedDevice? { devices.first { $0.id == id } }

    func addDevice(id: String, name: String, key: Data) {
        devices.removeAll { $0.id == id }
        forgotten.removeAll { $0.id == id }
        devices.append(PairedDevice(id: id, name: name, key: key, pairedAt: Date(), lastSeen: Date()))
        save()
    }

    /// Forgets a phone, and tells it: at once over its live link if it has one, or the next time it connects. The
    /// phone then removes this Mac too. [tellPhone] false is for a phone that asked to be forgotten (it already knows).
    func removeDevice(_ id: String, tellPhone: Bool = true) {
        if tellPhone, let d = device(id) {
            forgotten.removeAll { $0.id == id }
            forgotten.append(ForgottenDevice(id: id, key: d.key, at: Date()))
        }
        devices.removeAll { $0.id == id }
        save()
        WatchHub.shared.forgot(deviceId: id, tell: tellPhone)
    }

    /// A phone forgotten here not long ago (its key, to tell it so).
    func forgottenDevice(_ id: String) -> ForgottenDevice? {
        forgotten.first { $0.id == id && Date().timeIntervalSince($0.at) < Self.tellForgottenFor }
    }

    /// How long a forgotten phone is remembered, to tell it it was forgotten.
    static let tellForgottenFor: TimeInterval = 120 * 24 * 3600

    func touch(_ id: String) {
        guard let i = devices.firstIndex(where: { $0.id == id }) else { return }
        let old = devices[i].lastSeen
        devices[i].lastSeen = Date()
        if old == nil || Date().timeIntervalSince(old!) > 60 { save() }
    }

    // MARK: outbox (things waiting for the phone to collect)

    func enqueue(urls: [URL]) {
        let existing = Set(outbox.compactMap(\.path))
        for u in urls where !existing.contains(u.path) {
            outbox.append(OutboxItem(path: u.path))
        }
        save()
        WatchHub.shared.pushInbox(pending: outbox.count)
    }

    func enqueue(text: String) {
        outbox.append(OutboxItem(text: text))
        save()
        WatchHub.shared.pushInbox(pending: outbox.count)
    }

    func dequeue(_ ids: Set<UUID>) {
        outbox.removeAll { ids.contains($0.id) }
        save()
    }

    func clearOutbox() {
        outbox.removeAll()
        save()
    }

    func addRecent(_ e: RecentEvent) {
        recent.insert(e, at: 0)
        if recent.count > 8 { recent.removeLast(recent.count - 8) }
    }
}

enum TestMode {
    /// Integration tests run the app headless-ish: no clipboard writes, no opening links.
    static let on = ProcessInfo.processInfo.environment["TAPDROP_TEST"] == "1"
    /// Test runs without a demo screen: no menu bar icon, no island, no light sensor.
    static let headless = on && ProcessInfo.processInfo.environment["TAPDROP_DEMO"] == nil
}

enum Net {
    /// Best-guess LAN IPv4 address (Wi-Fi / Ethernet).
    static func lanIPv4() -> String? {
        // Demo runs can show a stand-in address (TAPDROP_DEMO_IP), so captures never carry the real network's.
        let env = ProcessInfo.processInfo.environment
        if env["TAPDROP_DEMO"] != nil, let ip = env["TAPDROP_DEMO_IP"] { return ip }
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var candidates: [(String, String)] = []
        for p in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = p.pointee
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            let ip = String(cString: host)
            let name = String(cString: ifa.ifa_name)
            if ip.hasPrefix("169.254.") { continue }
            candidates.append((name, ip))
        }
        return (candidates.first { $0.0 == "en0" } ?? candidates.first { $0.0.hasPrefix("en") } ?? candidates.first)?.1
    }
}
