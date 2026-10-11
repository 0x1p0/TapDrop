import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Network
import SwiftUI

/// Tiny HTTP server that hands the Android APK to the phone (scan QR → download → install).
final class InstallServer: @unchecked Sendable {
    static let shared = InstallServer()
    static let port: UInt16 = 47914

    private let queue = DispatchQueue(label: "app.tapdrop.install")
    private var listener: NWListener?

    var apkURL: URL? {
        if let p = ProcessInfo.processInfo.environment["TAPDROP_APK"] { return URL(fileURLWithPath: p) }
        return Bundle.main.url(forResource: "TapDrop", withExtension: "apk")
    }

    var pageURL: String? { Net.lanIPv4().map { "http://\($0):\(Self.port)/" } }

    func start() {
        queue.async { [self] in
            guard listener == nil, let port = NWEndpoint.Port(rawValue: Self.port) else { return }
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            guard let l = try? NWListener(using: params, on: port) else { return }
            l.newConnectionHandler = { [weak self] nw in
                guard let self else { return }
                nw.start(queue: self.queue)
                let c = Conn(nw)
                Task { await self.serve(c) }
            }
            l.stateUpdateHandler = { [weak self] s in
                if case .failed = s { self?.queue.async { self?.listener = nil } }
            }
            l.start(queue: queue)
            listener = l
        }
    }

    func stop() {
        queue.async { [self] in
            listener?.cancel()
            listener = nil
        }
    }

    private func serve(_ c: Conn) async {
        defer { c.close() }
        var buf = Data()
        let end = Data("\r\n\r\n".utf8)
        while buf.range(of: end) == nil {
            guard buf.count < 32 * 1024, let chunk = try? await c.readSome() else { return }
            buf.append(chunk)
        }
        let requestLine = String(decoding: buf.prefix { $0 != 13 }, as: UTF8.self)
        let parts = requestLine.split(separator: " ")
        let path = parts.count > 1 ? String(parts[1]) : "/"

        if path.hasPrefix("/TapDrop.apk"), let apk = apkURL, let h = try? FileHandle(forReadingFrom: apk) {
            defer { try? h.close() }
            let size = FileNames.size(of: apk)
            let head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n"
                + "Content-Length: \(size)\r\nContent-Disposition: attachment; filename=\"TapDrop.apk\"\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            do {
                try await c.write(Data(head.utf8))
                while let d = try h.read(upToCount: 256 * 1024), !d.isEmpty { try await c.write(d) }
            } catch {}
            return
        }
        let body = Data(Self.page(hasApk: apkURL != nil).utf8)
        let head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.count)\r\n"
            + "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        try? await c.write(Data(head.utf8) + body)
    }

    private static func page(hasApk: Bool) -> String {
        let name = Store.computerName.replacingOccurrences(of: "<", with: "")
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TapDrop</title><style>
        :root{color-scheme:light dark}
        body{margin:0;font-family:system-ui,-apple-system,Roboto,sans-serif;background:#0b0d17;color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center}
        .card{padding:32px 24px;max-width:420px;text-align:center}
        .logo{width:88px;height:88px;border-radius:28px;margin:0 auto 20px;background:linear-gradient(135deg,#389eff,#7d5cff);display:flex;align-items:center;justify-content:center;box-shadow:0 12px 40px #7d5cff66;animation:float 3s ease-in-out infinite}
        .logo svg{width:46px;height:46px}
        @keyframes float{50%{transform:translateY(-6px)}}
        h1{font-size:28px;margin:0 0 8px}p{color:#aab;line-height:1.5;margin:0 0 24px}
        .req{font-size:13px;color:#889;margin:14px 0 0}.tip{font-size:13px;color:#99a;text-align:left;margin-top:18px;line-height:1.55}
        a.btn{display:block;padding:18px;border-radius:18px;background:linear-gradient(135deg,#389eff,#7d5cff);color:#fff;font-weight:700;font-size:18px;text-decoration:none;box-shadow:0 8px 30px #389eff55}
        ol{text-align:left;color:#ccd;line-height:1.7;padding-left:22px;margin-top:28px}
        </style></head><body><div class="card">
        <div class="logo"><svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><path d="M12 3v12m0 0l-5-5m5 5l5-5"/><path d="M5 20h14"/></svg></div>
        <h1>TapDrop</h1><p>AirDrop-style sharing between your Android phone and <b>\(name)</b>.</p>
        \(hasApk ? "<a class=\"btn\" href=\"/TapDrop.apk\">Download for Android</a>" : "<p><b>The APK isn't bundled in this build.</b></p>")
        <p class="req">Needs Android 13 or newer · any brand</p>
        <ol><li>Tap <b>Download</b>, then open <b>TapDrop.apk</b>.</li><li>If asked, allow your browser to install apps.</li>
        <li>Open TapDrop and tap your Mac to pair.</li><li>Tap <b>Turn on</b> to hold your phone to the Mac's notch to send.</li></ol>
        <p class="tip"><b>Samsung, Xiaomi, OnePlus, Oppo, Vivo:</b> when TapDrop asks, allow it to run in the background, or the phone may stop it to save battery.</p>
        </div></body></html>
        """
    }
}

enum QR {
    static func image(for string: String, size: CGFloat) -> NSImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(string.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let scale = size / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let ctx = CIContext()
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: size, height: size))
    }
}

@MainActor
final class SetupWindow: NSObject, NSWindowDelegate {
    static let shared = SetupWindow()
    private var window: NSWindow?

    func show() {
        InstallServer.shared.start()
        if let window {
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            return
        }
        let w = GlassWindow.make(size: NSSize(width: 620, height: 500), root: SetupView(url: InstallServer.shared.pageURL))
        w.delegate = self
        w.center()
        window = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        InstallServer.shared.stop()
        window = nil
    }
}

struct SetupView: View {
    let url: String?
    @ObservedObject var store = Store.shared
    @State private var appeared = false

    var body: some View {
        HStack(spacing: 28) {
            VStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .fill(Brand.gradient)
                        .blur(radius: appeared ? 22 : 8)
                        .opacity(0.55)
                        .scaleEffect(appeared ? 1.02 : 0.9)
                        .animation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true), value: appeared)
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(.white)
                    if let url, let img = QR.image(for: url, size: 400) {
                        Image(nsImage: img).interpolation(.none).resizable().padding(16)
                    } else {
                        Text("Connect to Wi-Fi").foregroundStyle(.black)
                    }
                }
                .frame(width: 210, height: 210)
                .scaleEffect(appeared ? 1 : 0.85)
                .opacity(appeared ? 1 : 0)
                .animation(.spring(response: 0.6, dampingFraction: 0.7), value: appeared)
                Text(url ?? "")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.45))
                    .textSelection(.enabled)
            }

            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Set up your phone").font(.system(size: 26, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    Text("Any Android phone on Android 13 or newer, on the same Wi-Fi as this Mac.")
                        .font(.system(size: 13)).foregroundStyle(.white.opacity(0.55))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Step(n: 1, title: "Scan the code", text: "Point your phone's camera (or Google Lens) at it, download TapDrop and install it. Allow your browser to install apps if asked.", delay: 0.1, on: appeared)
                Step(n: 2, title: "Pair", text: "Open TapDrop and tap this Mac. Check the 6-digit code matches, then click Pair here.", delay: 0.2, on: appeared)
                Step(n: 3, title: "Turn on tap to send", text: "In the app, tap Turn on (Android opens Accessibility). Then let it find its tap spot on this Mac (it feels the magnets inside).", delay: 0.3, on: appeared)
                Button {
                    SettingsWindow.shared.show(.compatibility)
                } label: {
                    HStack(spacing: 4) {
                        Text("Supported phones and what they need")
                        Image(systemName: "arrow.up.right")
                    }
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Brand.a)
                }
                .buttonStyle(.plain)
                .opacity(appeared ? 1 : 0)
                .animation(.easeOut.delay(0.4), value: appeared)
                if let d = store.devices.last {
                    Label("Paired with \(d.name)", systemImage: "checkmark.circle.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.green)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(width: 300)
        }
        .padding(34)
        .frame(width: 620, height: 500)
        .background(alignment: .topLeading) {
            Ellipse().fill(Brand.gradient).frame(width: 320, height: 260).blur(radius: 90).opacity(0.4).offset(x: -40, y: 40)
        }
        .environment(\.colorScheme, .dark)
        .animation(.spring(response: 0.5, dampingFraction: 0.7), value: store.devices.count)
        .onAppear { appeared = true }
    }
}

private struct Step: View {
    let n: Int
    let title: String
    let text: String
    let delay: Double
    let on: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(n)")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Brand.gradient))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
                Text(text).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).fixedSize(horizontal: false, vertical: true)
            }
        }
        .opacity(on ? 1 : 0)
        .offset(x: on ? 0 : 20)
        .animation(.spring(response: 0.5, dampingFraction: 0.8).delay(delay), value: on)
    }
}
