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

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        saveFolder = try c.decodeIfPresent(String.self, forKey: .saveFolder)
        openLinks = try c.decodeIfPresent(Bool.self, forKey: .openLinks) ?? true
        playSound = try c.decodeIfPresent(Bool.self, forKey: .playSound) ?? true
        didFirstLaunch = try c.decodeIfPresent(Bool.self, forKey: .didFirstLaunch) ?? false
        notchTap = try c.decodeIfPresent(Bool.self, forKey: .notchTap) ?? true
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
    @Published var settings = AppSettings() {
        didSet {
            guard settings != oldValue else { return }
            save()
            if settings.notchTap != oldValue.notchTap { WatchHub.shared.settingsChanged() }
        }
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
    }

    nonisolated static var dir: URL {
        if let custom = ProcessInfo.processInfo.environment["TAPDROP_HOME"] {
            return URL(fileURLWithPath: custom, isDirectory: true)
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
        } else {
            macId = TDCrypto.random(8).map { String(format: "%02x", $0) }.joined()
            save()
        }
    }

    func save() {
        let p = Persisted(macId: macId, devices: devices, outbox: outbox, settings: settings)
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
        devices.append(PairedDevice(id: id, name: name, key: key, pairedAt: Date(), lastSeen: Date()))
        save()
    }

    func removeDevice(_ id: String) {
        devices.removeAll { $0.id == id }
        save()
    }

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
