import AppKit
import CryptoKit
import Foundation
import Security

// Updates. Once a day TapDrop asks GitHub for its latest release. When there's a newer one it says so (Settings →
// About, a row in the menu bar panel, once in the notch) with What's new, and Update Now installs it: download the
// Mac zip, check its SHA-256 against the release's SHA256SUMS.txt, unzip it, check that its code signature satisfies
// this copy's own designated requirement, then a small helper swaps it in place once TapDrop has quit (in place, so
// macOS keeps TapDrop's permissions) and opens it again. A copy that can't do that (built on this Mac, signed
// differently, or somewhere it can't write) says why and offers the release page instead.
//
// The views are in UpdateViews.swift.

// MARK: - Versions

/// MAJOR.MINOR.PATCH, compared as numbers ("2.10.0" is newer than "2.9.3"). A leading "v" is allowed.
struct AppVersion: Comparable, CustomStringConvertible, Sendable {
    let major: Int
    let minor: Int
    let patch: Int

    static let zero = AppVersion(0, 0, 0)

    init(_ major: Int, _ minor: Int, _ patch: Int) {
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    init?(_ text: String) {
        var s = Substring(text.trimmingCharacters(in: .whitespaces))
        if s.first == "v" || s.first == "V" { s = s.dropFirst() }
        // "2.1.0-beta+5": prereleases never reach the check, but a suffix shouldn't break it
        let core = s.prefix { $0 != "-" && $0 != "+" }
        let parts = core.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...3).contains(parts.count) else { return nil }
        var n: [Int] = []
        for p in parts {
            guard !p.isEmpty, p.count <= 6, p.allSatisfy({ $0.isASCII && $0.isNumber }), let v = Int(p) else { return nil }
            n.append(v)
        }
        while n.count < 3 { n.append(0) }
        self.init(n[0], n[1], n[2])
    }

    static func < (a: AppVersion, b: AppVersion) -> Bool {
        (a.major, a.minor, a.patch) < (b.major, b.minor, b.patch)
    }

    var description: String { "\(major).\(minor).\(patch)" }

    /// This copy's version (CFBundleShortVersionString); nil for a bare development build.
    static let current: AppVersion? = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        .flatMap(AppVersion.init)
}

// MARK: - What a check remembers

/// A published release, as the check found it.
struct ReleaseInfo: Codable, Equatable, Sendable {
    /// "2.1.1"
    var version: String
    /// What's new, in the simple Markdown release notes use.
    var notes: String
    /// The release's page on GitHub.
    var page: URL
    /// TapDrop-<version>-mac.zip, and its size in bytes.
    var zipName: String
    var zip: URL?
    var zipSize: Int64
    /// SHA256SUMS.txt from the same release.
    var sums: URL?
}

/// The update check's memory, saved with the rest of TapDrop's state (see `Store.updates`).
struct UpdateRecord: Codable, Equatable {
    /// The last check that got an answer, for "Checked today at 9:41".
    var lastCheck: Date?
    /// The last check that ran at all: automatic checks wait a day after it, even when it failed.
    var lastAttempt: Date?
    /// The newest release the last check found (nil when none is published).
    var latest: ReleaseInfo?
    /// "Skip This Version".
    var skipped: String?
    /// The newest version the notch has mentioned (once per version).
    var announced: String?
    /// Set while TapDrop restarts into an update; the next launch checks it took.
    var installing: String?
}

/// Why an update can't go ahead, in one plain sentence.
struct UpdateProblem: Equatable, Sendable, Error {
    var message: String
    /// Worth trying again (the network, a download that broke). Otherwise only the release page is offered.
    var retry: Bool
}

// MARK: - The feed (GitHub's latest release)

enum UpdateFeed {
    static let latestURL = URL(string: "https://api.github.com/repos/0x1p0/TapDrop/releases/latest")!
    /// Where "Download from GitHub" goes when a release's own page isn't known.
    static let releasesPage = URL(string: "https://github.com/0x1p0/TapDrop/releases/latest")!

    /// Debug builds can read a stand-in feed (TAPDROP_UPDATE_URL), for testing updates end to end; its downloads may
    /// then be plain http (a local test server). Release builds only ever ask GitHub, over https.
    static let testFeed: URL? = {
        #if DEBUG
        return ProcessInfo.processInfo.environment["TAPDROP_UPDATE_URL"].flatMap(URL.init(string:))
        #else
        return nil
        #endif
    }()

    static var url: URL { testFeed ?? latestURL }

    /// "TapDrop/2.1.0 (macOS)": the app and its version, nothing about the person or the Mac.
    static let userAgent = "TapDrop/\(AppVersion.current?.description ?? "0.0.0") (macOS)"

    static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 15 * 60
        c.httpShouldSetCookies = false
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: c)
    }()

    static func request(_ url: URL, accept: String? = nil) -> URLRequest {
        var r = URLRequest(url: url)
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let accept { r.setValue(accept, forHTTPHeaderField: "Accept") }
        return r
    }

    /// The latest published release (GitHub never lists drafts or prereleases here), or nil when there's none yet.
    static func latest() async throws(UpdateProblem) -> ReleaseInfo? {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request(url, accept: "application/vnd.github+json"))
        } catch {
            throw UpdateProblem(message: "Couldn't reach GitHub. Check your internet connection and try again.", retry: true)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200: break
        case 404: return nil
        case 403, 429:
            throw UpdateProblem(message: "GitHub is limiting checks from your network right now. Try again in an hour.", retry: true)
        default:
            throw UpdateProblem(message: "GitHub didn't answer properly. Try again later.", retry: true)
        }
        guard let release = parse(data) else {
            throw UpdateProblem(message: "GitHub's answer didn't make sense. Try again later.", retry: true)
        }
        return release
    }

    private struct Wire: Decodable {
        struct Asset: Decodable {
            let name: String
            let browserDownloadUrl: String
            let size: Int64?
        }

        let tagName: String
        let htmlUrl: String?
        let body: String?
        let draft: Bool?
        let prerelease: Bool?
        let assets: [Asset]?
    }

    /// Reads GitHub's release JSON. Nil for anything that isn't a usable, published release.
    static func parse(_ data: Data) -> ReleaseInfo? {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let w = try? decoder.decode(Wire.self, from: data), w.draft != true, w.prerelease != true,
              let version = AppVersion(w.tagName)
        else { return nil }
        let v = version.description
        let assets = w.assets ?? []
        let zipName = "TapDrop-\(v)-mac.zip"
        let zip = assets.first { $0.name == zipName }
        let sums = assets.first { $0.name == "SHA256SUMS.txt" }
        return ReleaseInfo(version: v, notes: String((w.body ?? "").prefix(20_000)),
                           page: safe(w.htmlUrl) ?? releasesPage, zipName: zipName,
                           zip: safe(zip?.browserDownloadUrl), zipSize: zip?.size ?? 0, sums: safe(sums?.browserDownloadUrl))
    }

    /// Only https links (plain http too, from a debug build's test feed).
    private static func safe(_ s: String?) -> URL? {
        guard let s, let u = URL(string: s), let scheme = u.scheme?.lowercased(), u.host != nil else { return nil }
        if scheme == "https" || (scheme == "http" && testFeed != nil) { return u }
        return nil
    }
}

// MARK: - The updater

@MainActor
final class Updater: ObservableObject {
    static let shared = Updater()

    enum Install: Equatable {
        case none
        case downloading(done: Int64, total: Int64)
        /// Checking the checksum, unzipping, checking the signature.
        case verifying
        /// The helper is about to swap TapDrop and open it again. `waiting`: a transfer is running; it goes after it.
        case restarting(waiting: Bool)
        case failed(UpdateProblem)

        var busy: Bool {
            switch self {
            case .downloading, .verifying, .restarting: true
            case .none, .failed: false
            }
        }
    }

    @Published private(set) var record: UpdateRecord {
        didSet { if record != oldValue { Store.shared.updates = record } }
    }
    @Published private(set) var checking = false
    /// Why the last check someone asked for didn't work (automatic checks fail quietly and try again the next day).
    @Published private(set) var checkError: String?
    @Published private(set) var install: Install = .none
    /// "Later": the update keeps out of the way (no panel row, no About card) until the next check finds it again.
    @Published private(set) var deferred = false

    /// This copy's version (a bare development build has none and counts as older than any release).
    private(set) var current = AppVersion.current
    /// Demo/QA: a staged state; nothing goes to the network.
    private(set) var demoMode = false
    private var loop: Task<Void, Never>?
    private var installTask: Task<Void, Never>?
    private var announcing: Task<Void, Never>?

    private init() {
        record = Store.shared.updates
    }

    // MARK: what the views read

    /// A release newer than this copy, from the last check.
    var available: ReleaseInfo? {
        guard let r = record.latest, let v = AppVersion(r.version), v > (current ?? .zero) else { return nil }
        return r
    }

    var skipped: Bool { available.map { record.skipped == $0.version } ?? false }

    /// The panel and the notch mention the update.
    var nudging: Bool { available != nil && !skipped && !deferred }

    var currentText: String { current?.description ?? "a development build" }

    // MARK: checking

    /// Checks run on their own unless this is a demo or a test run (a test feed turns them back on).
    static var automatic: Bool {
        if UpdateFeed.testFeed != nil { return true }
        return !TestMode.on && ProcessInfo.processInfo.environment["TAPDROP_DEMO"] == nil
    }

    /// At launch: finishes an update that was restarting, then checks about 30 s in (if the last check was over
    /// 20 hours ago) and then once a day while TapDrop runs.
    func start() {
        finishPendingInstall()
        schedule()
        #if DEBUG
        startTestHook()
        #endif
    }

    /// The automatic checks changed (Settings).
    func settingsChanged() { schedule() }

    private func schedule() {
        loop?.cancel()
        loop = nil
        guard Self.automatic, !demoMode, Store.shared.settings.checkForUpdates else { return }
        var first: Double = 30
        #if DEBUG
        if let s = ProcessInfo.processInfo.environment["TAPDROP_UPDATE_DELAY"].flatMap(Double.init) { first = s }
        #endif
        loop = Task { [weak self] in
            try? await Task.sleep(for: .seconds(first))
            guard !Task.isCancelled else { return }
            self?.checkIfDue(after: 20 * 3600)
            while !Task.isCancelled {
                // hourly, against the clock, so a Mac that slept through the day still checks when it wakes
                try? await Task.sleep(for: .seconds(3600))
                guard !Task.isCancelled else { return }
                self?.checkIfDue(after: 24 * 3600)
            }
        }
    }

    private func checkIfDue(after interval: TimeInterval) {
        if Self.isDue(lastAttempt: record.lastAttempt, now: Date(), interval: interval) { check(manual: false) }
    }

    /// Never checked, checked at least `interval` ago, or the clock went back past the last check.
    static func isDue(lastAttempt: Date?, now: Date, interval: TimeInterval) -> Bool {
        guard let last = lastAttempt, last <= now else { return true }
        return now.timeIntervalSince(last) >= interval
    }

    /// `manual`: Check for Updates… (says what went wrong, and brings back a skipped or postponed version).
    func check(manual: Bool) {
        if manual {
            deferred = false
            checkError = nil
            if case .failed = install { install = .none }
        }
        guard !checking, !install.busy, !demoMode else { return }
        checking = true
        record.lastAttempt = Date()
        Task {
            let result: Result<ReleaseInfo?, UpdateProblem>
            do throws(UpdateProblem) { result = .success(try await UpdateFeed.latest()) } catch { result = .failure(error) }
            finishCheck(result, manual: manual)
        }
    }

    private func finishCheck(_ result: Result<ReleaseInfo?, UpdateProblem>, manual: Bool) {
        checking = false
        switch result {
        case .success(let latest):
            record.lastCheck = Date()
            record.latest = latest
            if let a = available {
                if manual && record.skipped == a.version { record.skipped = nil }
                // the next day's check brings a postponed update back
                if !manual { deferred = false }
                UpdateLog.write("\(manual ? "manual" : "automatic") check: \(a.version) is available (this is \(currentText))")
                if !manual { announce(a.version) }
            } else {
                UpdateLog.write("\(manual ? "manual" : "automatic") check: up to date (this is \(currentText), latest \(latest?.version ?? "none"))")
            }
        case .failure(let problem):
            if manual { checkError = problem.message }
            UpdateLog.write("\(manual ? "manual" : "automatic") check failed: \(problem.message)")
        }
        #if DEBUG
        testHookChecked()
        #endif
    }

    func later() {
        withTDAnimation { deferred = true }
        if case .failed = install { install = .none }
    }

    func showAgain() {
        withTDAnimation { deferred = false }
    }

    func skip() {
        guard let a = available else { return }
        record.skipped = a.version
        if case .failed = install { install = .none }
        UpdateLog.write("skipped \(a.version)")
    }

    func openReleasePage() {
        NSWorkspace.shared.open(available?.page ?? record.latest?.page ?? UpdateFeed.releasesPage)
    }

    // MARK: the notch, once per version

    /// Mentions a newly found version in the notch, once, when nothing else is going on there (never over a transfer,
    /// a code or a tap).
    private func announce(_ version: String) {
        guard record.announced != version, !TestMode.headless else { return }
        announcing?.cancel()
        announcing = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let a = self.available, a.version == version, self.nudging else { return }
                if Activity.shared.phase == .idle && TapStatus.shared.phase == .idle {
                    self.record.announced = version
                    UpdateLog.write("mentioned \(version) in the notch")
                    Activity.shared.notice(.queued, title: "TapDrop \(version) is available", detail: "See what's new in Settings",
                                           sound: false, hideAfter: 6, style: .appUpdate)
                    return
                }
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    // MARK: installing

    /// Update Now.
    func installNow() {
        guard let release = available, !install.busy else { return }
        deferred = false
        if demoMode { return }
        if let problem = Installer.cannotInstall() {
            UpdateLog.write("can't install here: \(problem.message)")
            install = .failed(problem)
            return
        }
        guard let zip = release.zip, let sums = release.sums else {
            install = .failed(UpdateProblem(message: "This release has no Mac download TapDrop can install by itself.", retry: false))
            return
        }
        install = .downloading(done: 0, total: release.zipSize)
        installTask = Task { await run(release, zip: zip, sums: sums) }
    }

    /// Stops a download (nothing is installed).
    func cancelInstall() {
        guard case .downloading = install else { return }
        installTask?.cancel()
    }

    private func run(_ release: ReleaseInfo, zip: URL, sums: URL) async {
        var work: URL?
        do {
            let dir = try Installer.workDir()
            work = dir
            UpdateLog.write("downloading \(release.zipName)")
            let file = dir.appendingPathComponent(release.zipName)
            let throttle = ProgressThrottle()
            try await Installer.download(zip, to: file, expected: release.zipSize) { done, total in
                throttle.tick {
                    if case .downloading = Updater.shared.install { Updater.shared.install = .downloading(done: done, total: total) }
                }
            }
            install = .verifying
            try await Installer.verifyChecksum(file, name: release.zipName, sums: sums)
            UpdateLog.write("checksum matches SHA256SUMS.txt")
            let app = try await Installer.unpack(file, in: dir)
            let requirement = try await Installer.check(app, newerThan: current)
            UpdateLog.write("\(app.lastPathComponent) is TapDrop \(release.version) and satisfies this copy's signing requirement")

            // Never cut a transfer (or a tap) short: restart after it.
            install = .restarting(waiting: Self.midTransfer)
            while Self.midTransfer {
                try await Task.sleep(for: .milliseconds(500))
            }
            install = .restarting(waiting: false)
            record.installing = release.version
            do {
                try Installer.launchHelper(app: app, work: dir, requirement: requirement)
            } catch {
                record.installing = nil
                throw error
            }
            UpdateLog.write("restarting into \(release.version)")
            try? await Task.sleep(for: .milliseconds(900))
            NSApp.terminate(nil)
        } catch let problem as UpdateProblem {
            UpdateLog.write("update stopped: \(problem.message)")
            install = .failed(problem)
            if let work { try? FileManager.default.removeItem(at: work) }
        } catch {
            // cancelled
            UpdateLog.write("download cancelled")
            install = .none
            if let work { try? FileManager.default.removeItem(at: work) }
        }
    }

    /// Something is arriving or going out, or a phone is at the notch.
    private static var midTransfer: Bool {
        if Activity.shared.phase == .active { return true }
        switch TapStatus.shared.phase {
        case .keying, .accepted: return true
        case .idle, .failed: return false
        }
    }

    /// After a restart into an update: did it take?
    private func finishPendingInstall() {
        guard let target = record.installing else { return }
        record.installing = nil
        if let t = AppVersion(target), let c = current, c >= t {
            UpdateLog.write("now running \(c) (updated)")
            deferred = false
        } else {
            UpdateLog.write("the update to \(target) didn't take; still \(currentText) (see the helper's lines above)")
            install = .failed(UpdateProblem(message: "The update didn't finish, so TapDrop is still on \(currentText).", retry: true))
        }
    }

    // MARK: demo (visual QA)

    /// Stages one state for screenshots (TAPDROP_DEMO=settings:about:<state>, panel:update). Never touches the network.
    func demo(_ state: String) {
        demoMode = true
        loop?.cancel()
        installTask?.cancel()
        let base = current ?? AppVersion(2, 1, 0)
        if current == nil { current = base }
        let next = AppVersion(base.major, base.minor, base.patch + 1).description
        let sample = ReleaseInfo(
            version: next, notes: Self.sampleNotes, page: UpdateFeed.releasesPage, zipName: "TapDrop-\(next)-mac.zip",
            zip: URL(string: "https://github.com/0x1p0/TapDrop/releases/download/v\(next)/TapDrop-\(next)-mac.zip"),
            zipSize: 31_400_000, sums: URL(string: "https://github.com/0x1p0/TapDrop/releases/download/v\(next)/SHA256SUMS.txt"))
        var r = UpdateRecord()
        r.lastCheck = Calendar.current.date(bySettingHour: 9, minute: 41, second: 0, of: Date())
        r.lastAttempt = r.lastCheck
        r.latest = sample
        checking = false
        checkError = nil
        deferred = false
        install = .none
        switch state {
        case "downloading": install = .downloading(done: 13_188_000, total: 31_400_000)
        case "verifying": install = .verifying
        case "restarting": install = .restarting(waiting: false)
        case "waiting": install = .restarting(waiting: true)
        case "failed": install = .failed(Installer.Failures.checksum)
        case "mismatch": install = .failed(Installer.Failures.signedDifferently)
        case "uptodate": r.latest = ReleaseInfo(version: base.description, notes: "", page: UpdateFeed.releasesPage, zipName: "", zipSize: 0)
        case "checking":
            r.latest = nil
            checking = true
        case "checkfailed":
            r.latest = nil
            checkError = "Couldn't reach GitHub. Check your internet connection and try again."
        case "never":
            r = UpdateRecord()
        case "skipped": r.skipped = next
        case "later": deferred = true
        default: break  // available
        }
        record = r
    }

    static let sampleNotes = """
        ### What's new
        - **Updates from TapDrop itself.** TapDrop checks once a day and can install a new version for you.
        - Files with long names keep their whole name on your phone.
        - The notch shows what's arriving a little sooner.

        ### Fixes
        - The menu bar icon no longer flickers when a transfer starts.
        - `Downloads` is remembered after you pick another folder and switch back.

        Everything else is on [GitHub](https://github.com/0x1p0/TapDrop/releases).
        """
}

// MARK: - Installing

/// The steps of Update Now that don't touch the UI.
enum Installer {
    static let bundleID = "app.tapdrop.mac"

    enum Failures {
        static let notAnApp = UpdateProblem(message: "This copy of TapDrop isn't running as an app, so it can't update itself.", retry: false)
        static let translocated = UpdateProblem(message: "Move TapDrop to your Applications folder and open it from there to update it.", retry: false)
        static let unsigned = UpdateProblem(message: "This copy of TapDrop isn't signed like the releases, so it can't update itself.", retry: false)
        static let signedDifferently = UpdateProblem(message: "This copy of TapDrop is signed differently from the release, so it can't update itself.", retry: false)
        static let download = UpdateProblem(message: "The download stopped. Check your internet connection and try again.", retry: true)
        static let noChecksum = UpdateProblem(message: "The release doesn't list a checksum for the Mac download, so nothing was installed.", retry: false)
        static let checksum = UpdateProblem(message: "The download didn't match its checksum, so nothing was installed.", retry: true)
        static let unzip = UpdateProblem(message: "The download couldn't be opened, so nothing was installed.", retry: true)
        static let notNewer = UpdateProblem(message: "The download wasn't a newer TapDrop, so nothing was installed.", retry: false)
        static let helper = UpdateProblem(message: "TapDrop couldn't start the installer, so nothing was installed.", retry: true)
        static let disk = UpdateProblem(message: "There isn't room to download the update.", retry: true)

        static func readOnly(_ folder: String) -> UpdateProblem {
            UpdateProblem(message: "TapDrop doesn't have permission to replace itself in \(folder).", retry: false)
        }
    }

    /// This copy as it runs: the bundle it was opened from, symlinks resolved.
    static var bundle: URL { Bundle.main.bundleURL.resolvingSymlinksInPath() }

    /// Why this copy can't replace itself, or nil when it can. Checked before anything is downloaded.
    static func cannotInstall() -> UpdateProblem? {
        let app = bundle
        guard app.pathExtension == "app", let exe = Bundle.main.executableURL?.resolvingSymlinksInPath(),
              exe.path.hasPrefix(app.path + "/")
        else { return Failures.notAnApp }
        // Opened straight from a download: macOS runs it from a read-only stand-in folder.
        if app.path.contains("/AppTranslocation/") { return Failures.translocated }
        guard let code = ownStaticCode(), (try? requirement(of: code)) != nil, !isAdHoc(code) else { return Failures.unsigned }
        let folder = FileManager.default.displayName(atPath: app.deletingLastPathComponent().path)
        // Writable on paper, and in fact (macOS can still say no to changing an app).
        let contents = app.appendingPathComponent("Contents", isDirectory: true)
        guard access(app.path, W_OK) == 0, access(contents.path, W_OK) == 0 else { return Failures.readOnly(folder) }
        let probe = contents.appendingPathComponent(".tapdrop-update-\(UUID().uuidString)")
        guard FileManager.default.createFile(atPath: probe.path, contents: Data()) else { return Failures.readOnly(folder) }
        try? FileManager.default.removeItem(at: probe)
        return nil
    }

    /// A fresh folder for one update (the download, the unzipped app, the helper).
    static func workDir() throws(UpdateProblem) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("TapDrop Update \(UUID().uuidString)", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            throw Failures.disk
        }
        return dir
    }

    // MARK: download

    static func download(_ url: URL, to file: URL, expected: Int64,
                         progress: @escaping @Sendable (Int64, Int64) -> Void) async throws {
        let d = FileDownload(destination: file, expected: expected, progress: progress)
        do {
            try await d.run(UpdateFeed.request(url))
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if Task.isCancelled { throw CancellationError() }
            if (error as? CocoaError)?.code == .fileWriteOutOfSpace { throw Failures.disk }
            throw Failures.download
        }
    }

    // MARK: checksum

    static func verifyChecksum(_ file: URL, name: String, sums: URL) async throws(UpdateProblem) {
        let text: String
        do {
            let (data, response) = try await UpdateFeed.session.data(for: UpdateFeed.request(sums))
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw Failures.download }
            text = String(decoding: data, as: UTF8.self)
        } catch {
            throw Failures.download
        }
        guard let want = checksum(for: name, in: text) else { throw Failures.noChecksum }
        let got = await Task.detached(priority: .userInitiated) { sha256(file) }.value
        guard let got else { throw Failures.unzip }
        guard got == want else {
            UpdateLog.write("checksum mismatch: SHA256SUMS.txt says \(want), the download is \(got)")
            throw Failures.checksum
        }
    }

    /// The hash SHA256SUMS.txt lists for `name` ("<64 hex>  <name>" lines, as `shasum -a 256` writes them).
    static func checksum(for name: String, in sums: String) -> String? {
        for line in sums.split(whereSeparator: \.isNewline) {
            let parts = line.split(maxSplits: 1, whereSeparator: { $0 == " " || $0 == "\t" })
            guard parts.count == 2 else { continue }
            let hash = parts[0].lowercased()
            var file = parts[1].trimmingCharacters(in: .whitespaces)
            if file.hasPrefix("*") { file.removeFirst() }  // shasum's binary-mode marker
            if file == name, hash.count == 64, hash.allSatisfy(\.isHexDigit) { return hash }
        }
        return nil
    }

    static func sha256(_ file: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: file) else { return nil }
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try? h.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    // MARK: unzip

    /// Unzips the release (ditto made it, with TapDrop.app at the top) and returns the app.
    static func unpack(_ zip: URL, in work: URL) async throws(UpdateProblem) -> URL {
        let out = work.appendingPathComponent("unzipped", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        } catch {
            throw Failures.disk
        }
        let status = await run("/usr/bin/ditto", ["-x", "-k", zip.path, out.path])
        guard status == 0 else { throw Failures.unzip }
        let app = out.appendingPathComponent("TapDrop.app", isDirectory: true)
        var dir: ObjCBool = false
        let link = (try? app.resourceValues(forKeys: [.isSymbolicLinkKey]))?.isSymbolicLink ?? false
        guard !link, FileManager.default.fileExists(atPath: app.path, isDirectory: &dir), dir.boolValue else { throw Failures.notNewer }
        return app
    }

    private static func run(_ tool: String, _ args: [String]) async -> Int32 {
        await withCheckedContinuation { c in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: tool)
            p.arguments = args
            p.standardInput = FileHandle.nullDevice
            p.standardOutput = FileHandle.nullDevice
            p.standardError = FileHandle.nullDevice
            p.terminationHandler = { c.resume(returning: $0.terminationStatus) }
            do { try p.run() } catch { c.resume(returning: -1) }
        }
    }

    // MARK: the new app

    /// The unzipped app must be TapDrop, newer than this copy, and signed so it satisfies this copy's designated
    /// requirement (the same signing identity as the release this copy came from). Returns that requirement as
    /// text, for the helper's own last check.
    static func check(_ app: URL, newerThan current: AppVersion?) async throws(UpdateProblem) -> String {
        let info = NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist"))
        guard info?["CFBundleIdentifier"] as? String == bundleID,
              let v = (info?["CFBundleShortVersionString"] as? String).flatMap(AppVersion.init), v > (current ?? .zero)
        else { throw Failures.notNewer }
        let result = await Task.detached(priority: .userInitiated) {
            Result<String, UpdateProblem> { () throws(UpdateProblem) in try satisfiesOwnRequirement(app) }
        }.value
        return try result.get()
    }

    private static func satisfiesOwnRequirement(_ app: URL) throws(UpdateProblem) -> String {
        guard let me = ownStaticCode() else { throw Failures.unsigned }
        let req = try requirement(of: me)
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code else { throw Failures.signedDifferently }
        let flags = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSStrictValidate)
        let status = SecStaticCodeCheckValidity(code, flags, req)
        guard status == errSecSuccess else {
            UpdateLog.write("the download's signature doesn't satisfy this copy's requirement (OSStatus \(status))")
            throw Failures.signedDifferently
        }
        var text: CFString?
        guard SecRequirementCopyString(req, [], &text) == errSecSuccess, let text else { throw Failures.unsigned }
        return text as String
    }

    private static func ownStaticCode() -> SecStaticCode? {
        var me: SecCode?
        guard SecCodeCopySelf([], &me) == errSecSuccess, let me else { return nil }
        var code: SecStaticCode?
        guard SecCodeCopyStaticCode(me, [], &code) == errSecSuccess else { return nil }
        return code
    }

    private static func requirement(of code: SecStaticCode) throws(UpdateProblem) -> SecRequirement {
        var req: SecRequirement?
        guard SecCodeCopyDesignatedRequirement(code, [], &req) == errSecSuccess, let req else { throw Failures.unsigned }
        return req
    }

    /// Signed ad hoc (a copy built on this Mac): its requirement names only its own exact build.
    private static func isAdHoc(_ code: SecStaticCode) -> Bool {
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let dict = info as? [String: Any], let flags = (dict[kSecCodeInfoFlags as String] as? NSNumber)?.uint32Value
        else { return true }
        return flags & SecCodeSignatureFlags.adhoc.rawValue != 0
    }

    // MARK: the swap

    /// Waits for TapDrop to quit, checks the new app once more, replaces this copy in place (rsync, so macOS keeps
    /// treating it as the same app and keeps its permissions), checks the result, clears quarantine and opens it.
    /// If anything goes wrong the previous copy is put back and opened.
    private static let helper = #"""
        #!/bin/sh
        # TapDrop's update helper: swaps TapDrop in place once it has quit, then opens it again.
        pid="$1"; new="$2"; app="$3"; work="$4"; req="$5"; shift 5
        say() { echo "$(date '+%Y-%m-%d %H:%M:%S') helper: $*"; }
        say "waiting for TapDrop ($pid) to quit"
        n=0
        while kill -0 "$pid" 2>/dev/null; do
          n=$((n + 1))
          if [ "$n" -gt 600 ]; then say "TapDrop didn't quit; nothing changed"; exit 1; fi
          sleep 0.1
        done
        if ! /usr/bin/codesign --verify --strict --all-architectures -R "=$req" "$new"; then
          say "the new copy failed its signature check; nothing changed"
        elif ! /usr/bin/ditto "$app" "$work/previous.app"; then
          say "couldn't keep a copy of the current version; nothing changed"
        # --checksum: a file can change and keep its size and time (a version number, a re-signed binary)
        elif /usr/bin/rsync -a --delete --checksum "$new/" "$app/" \
          && /usr/bin/codesign --verify --strict --all-architectures -R "=$req" "$app"; then
          say "replaced $app with $(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$app/Contents/Info.plist")"
        else
          say "replacing failed; putting the previous version back"
          /usr/bin/rsync -a --delete --checksum "$work/previous.app/" "$app/"
        fi
        /usr/bin/xattr -dr com.apple.quarantine "$app" 2>/dev/null
        /System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$app" 2>/dev/null
        say "opening $app"
        /usr/bin/open -n "$@" "$app"
        rm -rf "$work"
        """#

    /// Starts the helper in its own session, so it outlives TapDrop. Its lines go to update.log.
    @MainActor static func launchHelper(app new: URL, work: URL, requirement: String) throws(UpdateProblem) {
        let script = work.appendingPathComponent("update.sh")
        do {
            try helper.write(to: script, atomically: true, encoding: .utf8)
        } catch {
            throw Failures.helper
        }
        var args = ["/bin/sh", script.path, String(getpid()), new.path, bundle.path, work.path, requirement]
        // Settings a test run was started with (its own home, port, feed) go to the copy it restarts into.
        for (k, v) in ProcessInfo.processInfo.environment.sorted(by: { $0.key < $1.key }) where k.hasPrefix("TAPDROP_") {
            args += ["--env", "\(k)=\(v)"]
        }
        guard spawnDetached(args, log: UpdateLog.url.path) else { throw Failures.helper }
    }

    private static func spawnDetached(_ argv: [String], log: String) -> Bool {
        var attr: posix_spawnattr_t?
        posix_spawnattr_init(&attr)
        defer { posix_spawnattr_destroy(&attr) }
        // Its own session (so it isn't taken down with TapDrop), and none of TapDrop's open files or sockets.
        posix_spawnattr_setflags(&attr, Int16(POSIX_SPAWN_SETSID | POSIX_SPAWN_CLOEXEC_DEFAULT))
        var actions: posix_spawn_file_actions_t?
        posix_spawn_file_actions_init(&actions)
        defer { posix_spawn_file_actions_destroy(&actions) }
        posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0)
        posix_spawn_file_actions_addopen(&actions, 1, log, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        posix_spawn_file_actions_adddup2(&actions, 1, 2)
        let env = ProcessInfo.processInfo.environment.map { "\($0.key)=\($0.value)" }
        let cArgs = argv.map { strdup($0) } + [nil]
        let cEnv = env.map { strdup($0) } + [nil]
        defer {
            cArgs.forEach { free($0) }
            cEnv.forEach { free($0) }
        }
        var pid: pid_t = 0
        let rc = posix_spawn(&pid, argv[0], &actions, &attr, cArgs, cEnv)
        if rc != 0 { UpdateLog.write("couldn't start the helper (errno \(rc))") }
        return rc == 0
    }
}

/// One file download with progress (URLSession's async download reports none).
private final class FileDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let destination: URL
    private let expected: Int64
    private let progress: @Sendable (Int64, Int64) -> Void
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var task: URLSessionDownloadTask?
    private var cancelled = false
    private var failure: Error?

    init(destination: URL, expected: Int64, progress: @escaping @Sendable (Int64, Int64) -> Void) {
        self.destination = destination
        self.expected = expected
        self.progress = progress
    }

    func run(_ request: URLRequest) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                let session = URLSession(configuration: UpdateFeed.session.configuration, delegate: self, delegateQueue: nil)
                let t = session.downloadTask(with: request)
                let go = lock.withLock { () -> Bool in
                    guard !cancelled else { return false }
                    continuation = c
                    task = t
                    return true
                }
                if go {
                    t.resume()
                } else {
                    session.invalidateAndCancel()
                    c.resume(throwing: CancellationError())
                }
            }
        } onCancel: {
            let t = lock.withLock { () -> URLSessionDownloadTask? in
                cancelled = true
                return task
            }
            t?.cancel()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        progress(totalBytesWritten, totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : expected)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            guard (downloadTask.response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
        } catch {
            lock.withLock { failure = error }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        session.finishTasksAndInvalidate()
        let (c, failed, wasCancelled) = lock.withLock { () -> (CheckedContinuation<Void, Error>?, Error?, Bool) in
            defer { continuation = nil }
            return (continuation, failure, cancelled)
        }
        if wasCancelled {
            c?.resume(throwing: CancellationError())
        } else if let e = error ?? failed {
            c?.resume(throwing: e)
        } else {
            c?.resume()
        }
    }
}

// MARK: - Log

/// update.log in TapDrop's folder: what each check and update did, the helper's lines included.
enum UpdateLog {
    static var url: URL { Store.dir.appendingPathComponent("update.log") }
    private static let queue = DispatchQueue(label: "app.tapdrop.update-log")

    static func write(_ line: String) {
        NSLog("TapDrop[update]: %@", line)
        let pid = getpid()
        queue.async {
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd HH:mm:ss"
            let entry = Data("\(f.string(from: Date())) [\(pid)] \(line)\n".utf8)
            let path = url.path
            // Keep it small: start over past 256 KB.
            if let size = (try? FileManager.default.attributesOfItem(atPath: path))?[.size] as? Int, size > 256_000 {
                try? FileManager.default.removeItem(atPath: path)
            }
            if let h = FileHandle(forWritingAtPath: path) {
                defer { try? h.close() }
                _ = try? h.seekToEnd()
                try? h.write(contentsOf: entry)
            } else {
                FileManager.default.createFile(atPath: path, contents: entry)
            }
        }
    }
}

// MARK: - Test hook (debug builds)

#if DEBUG
extension Updater {
    /// TAPDROP_TEST_UPDATE, with a test feed (TAPDROP_UPDATE_URL), drives the real flow without clicks:
    ///   install  after each automatic check, show Settings → About; if there's an update, press Update Now
    ///   cancel   the same, then Cancel a quarter of the way into the download
    ///   manual   a manual check right away (as Check for Updates… does), with About open
    /// TAPDROP_SNAP=<dir> photographs About at each step (update_e2e_<step>_<version>), without quitting.
    private static var testMode: String? { ProcessInfo.processInfo.environment["TAPDROP_TEST_UPDATE"] }

    fileprivate func startTestHook() {
        guard Self.testMode == "manual" else { return }
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            SettingsWindow.shared.show(.about)
            check(manual: true)
        }
    }

    fileprivate func testHookChecked() {
        guard let mode = Self.testMode, !demoMode else { return }
        Task {
            SettingsWindow.shared.show(.about)
            try? await Task.sleep(for: .seconds(2.5))
            let step = available != nil ? "available" : (checkError != nil ? "checkfailed" : "uptodate")
            await testSnap(step)
            guard mode == "install" || mode == "cancel", available != nil else { return }
            installNow()
            // a shot of the download a quarter of the way in, then the outcome, if it isn't a restart
            var shotDownload = false
            while install.busy {
                if !shotDownload, case .downloading(let done, let total) = install, total > 0, Double(done) / Double(total) > 0.25 {
                    shotDownload = true
                    await testSnap("downloading")
                    if mode == "cancel" {
                        UpdateLog.write("test: pressing Cancel")
                        cancelInstall()
                    }
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
            if install == .none { UpdateLog.write("test: Update Now ended with nothing to show (cancelled)") }
            if case .failed(let p) = install {
                UpdateLog.write("test: Update Now ended with: \(p.message)")
                try? await Task.sleep(for: .seconds(1.2))
                await testSnap("failed")
            }
        }
    }

    private func testSnap(_ step: String) async {
        guard let dir = ProcessInfo.processInfo.environment["TAPDROP_SNAP"] else { return }
        let dark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        await DSSnapshot.capture(dir: dir, name: "update_e2e_\(step)_\(currentText)", dark: dark)
    }
}
#endif

// MARK: - Self-test (debug builds)

#if DEBUG
/// TAPDROP_DEMO=updatecheck: the updater's logic without the network, then quit (exit 1 on a failure).
@MainActor
enum UpdateSelfTest {
    static func run() {
        var failures: [String] = []
        func expect(_ ok: Bool, _ what: String) {
            print("updatecheck: \(ok ? "ok  " : "FAIL") \(what)")
            if !ok { failures.append(what) }
        }

        // versions
        let v = { (s: String) in AppVersion(s) }
        expect(v("v2.1.0")?.description == "2.1.0", "v2.1.0 reads as 2.1.0")
        expect(v("2.1")?.description == "2.1.0", "2.1 reads as 2.1.0")
        expect(v("2.1.0-beta.1")?.description == "2.1.0", "a suffix is ignored")
        expect(v("2.10.0")! > v("2.9.3")!, "2.10.0 is newer than 2.9.3 (numbers, not text)")
        expect(v("2.1.1")! > v("v2.1.0")!, "2.1.1 is newer than v2.1.0")
        expect(v("10.0.0")! > v("9.99.99")!, "10.0.0 is newer than 9.99.99")
        expect(!(v("2.1.0")! > v("2.1.0")!), "the same version isn't newer")
        expect([v(""), v("x.y.z"), v("1.2.3.4"), v("1..2"), v("2.-1.0"), v(" ")].allSatisfy { $0 == nil }, "nonsense doesn't parse")

        // SHA256SUMS.txt
        let a = String(repeating: "a", count: 64), b = String(repeating: "B", count: 64)
        let sums = "\(a)  TapDrop-2.1.1-android.apk\n\(b) *TapDrop-2.1.1-mac.zip\r\nnot a line\n\(a.prefix(63))  short.zip\n"
        expect(Installer.checksum(for: "TapDrop-2.1.1-mac.zip", in: sums) == b.lowercased(), "finds the zip's hash (binary marker, CRLF, upper case)")
        expect(Installer.checksum(for: "TapDrop-2.1.1-android.apk", in: sums) == a, "finds the apk's hash")
        expect(Installer.checksum(for: "short.zip", in: sums) == nil, "a short hash doesn't count")
        expect(Installer.checksum(for: "TapDrop-2.1.2-mac.zip", in: sums) == nil, "a missing file has no hash")

        // the feed
        func release(tag: String, draft: Bool = false, pre: Bool = false, zipURL: String? = nil) -> Data {
            let zip = zipURL ?? "https://github.com/0x1p0/TapDrop/releases/download/\(tag)/TapDrop-2.1.1-mac.zip"
            let json: [String: Any] = [
                "tag_name": tag, "name": "TapDrop 2.1.1", "html_url": "https://github.com/0x1p0/TapDrop/releases/tag/\(tag)",
                "body": "### What's new\n- One", "draft": draft, "prerelease": pre,
                "assets": [
                    ["name": "TapDrop-2.1.1-mac.zip", "browser_download_url": zip, "size": 31_400_000],
                    ["name": "TapDrop-2.1.1-android.apk", "browser_download_url": "https://github.com/x/y.apk", "size": 9_000_000],
                    ["name": "SHA256SUMS.txt", "browser_download_url": "https://github.com/0x1p0/TapDrop/releases/download/\(tag)/SHA256SUMS.txt", "size": 200],
                ],
            ]
            return (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
        }
        let r = UpdateFeed.parse(release(tag: "v2.1.1"))
        expect(r?.version == "2.1.1" && r?.zipName == "TapDrop-2.1.1-mac.zip" && r?.zipSize == 31_400_000, "reads the release and its mac zip")
        expect(r?.sums?.lastPathComponent == "SHA256SUMS.txt" && r?.page.absoluteString.hasSuffix("/tag/v2.1.1") == true, "reads the checksums and the page")
        expect(UpdateFeed.parse(release(tag: "v2.1.1", draft: true)) == nil, "a draft is ignored")
        expect(UpdateFeed.parse(release(tag: "v2.1.1", pre: true)) == nil, "a prerelease is ignored")
        expect(UpdateFeed.parse(release(tag: "nightly")) == nil, "a tag that isn't a version is ignored")
        expect(UpdateFeed.parse(Data("{}".utf8)) == nil && UpdateFeed.parse(Data("[".utf8)) == nil, "junk is ignored")
        if UpdateFeed.testFeed == nil {
            expect(UpdateFeed.parse(release(tag: "v2.1.1", zipURL: "http://example.com/TapDrop-2.1.1-mac.zip"))?.zip == nil,
                   "a plain-http download is refused")
        }

        // when to check
        let now = Date()
        expect(Updater.isDue(lastAttempt: nil, now: now, interval: 20 * 3600), "never checked: due")
        expect(!Updater.isDue(lastAttempt: now.addingTimeInterval(-19 * 3600), now: now, interval: 20 * 3600), "19 h ago: not due at launch")
        expect(Updater.isDue(lastAttempt: now.addingTimeInterval(-21 * 3600), now: now, interval: 20 * 3600), "21 h ago: due at launch")
        expect(!Updater.isDue(lastAttempt: now.addingTimeInterval(-23 * 3600), now: now, interval: 24 * 3600), "23 h ago: not due while running")
        expect(Updater.isDue(lastAttempt: now.addingTimeInterval(3600), now: now, interval: 24 * 3600), "a check in the future (clock went back): due")

        // What's new
        let notes = """
            ## What’s New\r
            - **Bold** start
              - nested `code`
            * star bullet
            1. first
            Some text
            on two lines.

            ```
            raw <b>code</b>
            ```
            ---
            ### Fixes
            [ok](https://github.com) [bad](javascript:alert(1)) [file](file:///etc/passwd)
            """
        let blocks = WhatsNew.blocks(notes)
        let expected: [WhatsNew.Block] = [
            .bullet("**Bold** start", level: 0), .bullet("nested `code`", level: 1), .bullet("star bullet", level: 0),
            .numbered("1.", "first"), .paragraph("Some text on two lines."), .code("raw <b>code</b>"), .heading("Fixes"),
            .paragraph("[ok](https://github.com) [bad](javascript:alert(1)) [file](file:///etc/passwd)"),
        ]
        expect(blocks == expected, "splits notes into blocks (drops the What's New heading)")
        if blocks != expected { print("updatecheck:   got \(blocks)") }
        let links = WhatsNew.inline("[ok](https://github.com) [bad](javascript:alert(1)) [file](file:///etc/passwd)")
            .runs.compactMap(\.link).map(\.absoluteString)
        expect(links == ["https://github.com"], "only web links stay links")
        let styled = WhatsNew.inline("**Bold** and `code`")
        expect(styled.runs.contains { $0.inlinePresentationIntent?.contains(.stronglyEmphasized) == true }
               && styled.runs.contains { $0.inlinePresentationIntent?.contains(.code) == true }
               && String(styled.characters) == "Bold and code", "bold and code")

        print(failures.isEmpty ? "updatecheck: ALL OK" : "updatecheck: \(failures.count) FAILED")
        fflush(stdout)
        exit(failures.isEmpty ? 0 : 1)
    }
}
#endif
