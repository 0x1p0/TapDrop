import Foundation

/// The page the phone opens from the setup QR code: download TapDrop, then the three steps.
/// Lumen, like the apps: night, the Notchline lit at the top, Instrument Serif and Geist (served by this Mac).
enum InstallPage {
    /// Font files the page asks for, by path (served from the Mac app's own fonts).
    static let fonts: [String: String] = [
        "/f/serif.ttf": "InstrumentSerif-Regular.ttf",
        "/f/serif-italic.ttf": "InstrumentSerif-Italic.ttf",
        "/f/geist.ttf": "Geist.ttf",
        "/f/mono.ttf": "GeistMono.ttf",
    ]

    static func html(macName: String, apkSize: Int64?, version: String? = nil) -> String {
        let mac = escape(macName)
        let size = apkSize.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }
        let install = size != nil
            ? "Open <b>TapDrop.apk</b> from your downloads. If Android asks, allow your browser to install apps."
            : "Install TapDrop for Android on this phone, then open it."
        let download: String
        if let size {
            download = """
            <a class="btn" href="/TapDrop.apk" download="TapDrop.apk" id="get">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m0 0-4.5-4.5M12 15l4.5-4.5M5 19.5h14"/></svg>
              Download TapDrop
            </a>
            <p class="meta">\(version.map { "Version \(escape($0)) · " } ?? "")Android 13 or newer · \(size)</p>
            <p class="next" role="status" aria-live="polite">When the download finishes, open <b>TapDrop.apk</b> to install it.</p>
            """
        } else {
            download = """
            <div class="notice" role="note">
              <b>The Android app isn't included in this copy of TapDrop for Mac.</b>
              Install TapDrop on your phone another way, then come back to pair.
            </div>
            """
        }
        return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
        <meta name="color-scheme" content="dark">
        <meta name="theme-color" content="#08080A">
        <title>Get TapDrop</title>
        <link rel="icon" href="data:image/svg+xml,\(iconDataURI)">
        <style>
        @font-face { font-family: "Instrument Serif"; src: url(/f/serif.ttf) format("truetype"); font-display: swap; }
        @font-face { font-family: "Instrument Serif"; font-style: italic; src: url(/f/serif-italic.ttf) format("truetype"); font-display: swap; }
        @font-face { font-family: "Geist"; src: url(/f/geist.ttf) format("truetype"); font-weight: 100 900; font-display: swap; }
        @font-face { font-family: "Geist Mono"; src: url(/f/mono.ttf) format("truetype"); font-weight: 100 900; font-display: swap; }
        :root {
          --bg: #08080A; --surface: #111114; --raised: #17171A; --hair: rgba(255,255,255,.08);
          --text: #F4F3EF; --text2: #A2A2A8; --text3: #6E6E75; --light: #D6DEFF;
          --sans: Geist, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
          --serif: "Instrument Serif", ui-serif, Georgia, serif;
          --mono: "Geist Mono", ui-monospace, "SF Mono", Menlo, monospace;
        }
        * { box-sizing: border-box; }
        html { background: var(--bg); -webkit-text-size-adjust: 100%; }
        body {
          margin: 0; min-height: 100vh; min-height: 100svh; color: var(--text); background: var(--bg);
          font: 400 16px/24px var(--sans); -webkit-font-smoothing: antialiased; -webkit-tap-highlight-color: transparent; overflow-x: hidden;
        }
        .spill { position: fixed; left: 50%; top: -38vh; width: 150vw; height: 80vh; margin-left: -75vw; pointer-events: none; z-index: 0;
          background: radial-gradient(closest-side, rgba(214,222,255,.16), rgba(214,222,255,.05) 55%, transparent 100%);
          animation: breathe 7s ease-in-out 2 alternate; }
        @keyframes breathe { to { opacity: .72; } }
        main { position: relative; z-index: 1; max-width: 460px; margin: 0 auto;
          padding: calc(40px + env(safe-area-inset-top)) 22px calc(28px + env(safe-area-inset-bottom)); }
        .icon { width: 84px; height: 84px; border-radius: 22px; display: block;
          box-shadow: 0 24px 60px -18px rgba(0,0,0,.9), 0 0 44px -10px rgba(214,222,255,.35), inset 0 0 0 1px rgba(255,255,255,.1); }
        .for { display: inline-flex; align-items: center; gap: 8px; margin-top: 28px; padding: 6px 12px 6px 10px; border-radius: 999px;
          background: rgba(255,255,255,.05); border: 1px solid var(--hair); color: var(--text2); font-size: 13px; line-height: 18px; font-weight: 500; }
        .for svg { width: 16px; height: 16px; stroke: var(--text2); }
        .for b { color: var(--text); font-weight: 600; }
        h1 { margin: 16px 0 10px; font: 400 48px/46px var(--serif); letter-spacing: -0.01em; }
        h1 em { font-style: italic; }
        .lede { margin: 0; color: var(--text2); font-size: 17px; line-height: 25px; }
        .btn { margin-top: 30px; display: flex; align-items: center; justify-content: center; gap: 10px; height: 58px; border-radius: 999px;
          color: #08080A; text-decoration: none; font-weight: 600; font-size: 17px; letter-spacing: -0.005em;
          background: linear-gradient(#FFFFFF, #E2E0D9);
          box-shadow: inset 0 -1px 0 rgba(0,0,0,.12), 0 0 34px -6px rgba(214,222,255,.45), 0 1px 2px rgba(0,0,0,.5);
          transition: transform .18s cubic-bezier(.3,.7,.4,1), box-shadow .18s, filter .18s; }
        .btn:active { transform: scale(.97); filter: brightness(.94); box-shadow: inset 0 -1px 0 rgba(0,0,0,.12), 0 0 18px -6px rgba(214,222,255,.3), 0 1px 2px rgba(0,0,0,.5); }
        .btn:focus-visible { outline: 2px solid var(--light); outline-offset: 4px; }
        .btn svg { width: 22px; height: 22px; fill: none; stroke: #08080A; stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
        .meta { margin: 12px 0 0; text-align: center; color: var(--text3); font: 500 12px/18px var(--mono); letter-spacing: .02em; }
        .next { display: none; margin: 14px 0 0; padding: 12px 14px; border-radius: 16px; text-align: center;
          background: rgba(244,243,239,.06); border: 1px solid var(--hair); color: var(--text); font-size: 14px; line-height: 20px; }
        .started .next { display: block; animation: rise .4s cubic-bezier(.3,.7,.4,1); }
        @keyframes rise { from { opacity: 0; transform: translateY(6px); } }
        .notice { margin-top: 28px; padding: 16px 18px; border-radius: 20px; background: rgba(255,176,32,.08);
          border: 1px solid rgba(255,176,32,.24); color: var(--text2); font-size: 15px; line-height: 22px; }
        .notice b { display: block; color: #FFD58A; font-weight: 600; margin-bottom: 4px; }
        h2 { margin: 40px 4px 12px; font: 500 11px/16px var(--mono); letter-spacing: .14em; text-transform: uppercase; color: var(--text3); }
        .card { background: var(--surface); border: 1px solid var(--hair); border-radius: 24px; padding: 4px 18px; }
        .step { display: grid; grid-template-columns: 32px 1fr; gap: 14px; padding: 16px 0; }
        .step + .step { border-top: 1px solid var(--hair); }
        .n { width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center; font: 500 13px/1 var(--mono);
          color: var(--text); background: rgba(244,243,239,.06); box-shadow: inset 0 0 0 1px rgba(255,255,255,.12); margin-top: 2px; }
        .step b { display: block; font-size: 16px; line-height: 22px; font-weight: 600; margin: 4px 0 3px; }
        .step span { display: block; color: var(--text2); font-size: 14px; line-height: 20px; }
        .step span b { display: inline; font-size: inherit; margin: 0; color: var(--text); font-weight: 600; }
        .tap { margin-top: 12px; display: grid; grid-template-columns: 76px 1fr; gap: 16px; align-items: center;
          background: var(--surface); border: 1px solid var(--hair); border-radius: 24px; padding: 16px 18px; overflow: hidden; }
        .art { position: relative; width: 76px; height: 84px; border-radius: 16px; overflow: hidden;
          background: radial-gradient(80% 60% at 50% 0%, rgba(214,222,255,.22), transparent 70%), #0B0B0E; box-shadow: inset 0 0 0 1px var(--hair); }
        .art .lid { position: absolute; left: 6px; right: 6px; top: 10px; height: 1px; background: rgba(255,255,255,.22); }
        .art .notch { position: absolute; left: 50%; top: 10px; width: 24px; height: 7px; margin-left: -12px; background: #000; border-radius: 0 0 4px 4px; }
        .art .glow { position: absolute; left: 50%; top: 2px; width: 60px; height: 28px; margin-left: -30px; border-radius: 50%;
          background: radial-gradient(closest-side, rgba(255,255,255,.95), rgba(214,222,255,.45), transparent); filter: blur(3px); }
        .art .phone { position: absolute; left: 50%; top: 18px; width: 32px; height: 70px; margin-left: -16px; border-radius: 8px;
          background: linear-gradient(#FFFFFF, #E8EBF7 45%, #C9CEDD); box-shadow: 0 0 0 2px #0C0C10, 0 0 0 2.6px rgba(255,255,255,.28), 0 0 20px rgba(214,222,255,.5); }
        .tap b { display: block; font-size: 16px; line-height: 22px; font-weight: 600; }
        .tap span { display: block; margin-top: 4px; color: var(--text2); font-size: 14px; line-height: 20px; }
        .tip { margin: 22px 4px 0; display: flex; gap: 10px; color: var(--text2); font-size: 14px; line-height: 20px; }
        .tip svg { flex: none; width: 18px; height: 18px; margin-top: 1px; stroke: var(--text2); fill: none; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
        footer { margin: 36px auto 0; max-width: 320px; color: var(--text3); font-size: 12px; line-height: 17px; font-weight: 500; text-align: center; }
        footer svg { display: inline-block; width: 12px; height: 12px; margin: 0 5px -1px 0; stroke: var(--text3); fill: none; stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
        @media (prefers-reduced-motion: reduce) { .spill, .started .next { animation: none; } .btn { transition: none; } }
        </style>
        </head>
        <body>
        <div class="spill" aria-hidden="true"></div>
        <main>
          \(iconSVG)
          <div class="for">
            <svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="5" width="16" height="11" rx="1.6"/><path d="M2 19h20"/></svg>
            <span>For <b>\(mac)</b></span>
          </div>
          <h1>Get <em>TapDrop.</em></h1>
          <p class="lede">Send photos, links and files between this phone and your Mac with a touch.</p>
          \(download)

          <h2>Next</h2>
          <section class="card" aria-label="Steps">
            <div class="step"><div class="n">1</div><div><b>Install it</b><span>\(install)</span></div></div>
            <div class="step"><div class="n">2</div><div><b>Pair with your Mac</b><span>Open TapDrop, tap <b>Pair with your Mac</b> and choose <b>\(mac)</b>. Check that the codes match.</span></div></div>
            <div class="step"><div class="n">3</div><div><b>Turn on tap to send</b><span>Tap <b>Turn on tap to send</b>, then turn TapDrop on in Accessibility. If Android says it's a restricted setting, TapDrop shows you how to allow it.</span></div></div>
          </section>

          <section class="tap" aria-label="How to tap">
            <div class="art" aria-hidden="true"><div class="glow"></div><div class="lid"></div><div class="phone"></div><div class="notch"></div></div>
            <div><b>Touch the top of your phone's screen to the notch.</b><span>Hold it there until your phone buzzes.</span></div>
          </section>

          <p class="tip">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3h10v18H7z"/><path d="M11 18h2"/></svg>
            <span>On Samsung, Xiaomi, OnePlus, Oppo or vivo, let TapDrop run in the background when it asks, so it's ready when you tap.</span>
          </p>

          <footer>
            <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/></svg>
            <span>Served by \(mac) on your Wi‑Fi. Nothing leaves your network.</span>
          </footer>
        </main>
        <script>
          var g = document.getElementById('get');
          if (g) g.addEventListener('click', function () { setTimeout(function () { document.body.classList.add('started'); }, 600); });
        </script>
        </body>
        </html>
        """
    }

    /// The Notchline: the notch's outline traced in one line (a 100-unit square, as the app icon).
    private static let notchline = "M16 38H34C37 38 38.5 39.5 38.5 42.5V43.5C38.5 47 41 49.5 44.5 49.5H55.5C59 49.5 61.5 47 61.5 43.5V42.5C61.5 39.5 63 38 66 38H84"

    /// The app icon: the lit Notchline on a night tile.
    private static var iconSVG: String {
        """
        <svg class="icon" viewBox="0 0 100 100" role="img" aria-label="TapDrop"><defs>\
        <radialGradient id="sp" cx="50" cy="40" r="46" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff" stop-opacity=".34"/>\
        <stop offset=".45" stop-color="#D6DEFF" stop-opacity=".1"/><stop offset="1" stop-color="#D6DEFF" stop-opacity="0"/></radialGradient>\
        <filter id="gl" x="-30%" y="-80%" width="160%" height="260%"><feGaussianBlur stdDeviation="2.6"/></filter></defs>\
        <rect width="100" height="100" rx="23" fill="#111114"/><rect width="100" height="100" rx="23" fill="url(#sp)"/>\
        <path d="\(notchline)" fill="none" stroke="#fff" stroke-opacity=".75" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" filter="url(#gl)"/>\
        <path d="\(notchline)" fill="none" stroke="#fff" stroke-width="3.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
        """
    }

    private static var iconDataURI: String {
        let svg = """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><rect width='100' height='100' rx='23' fill='#111114'/>\
        <path d='\(notchline)' fill='none' stroke='#fff' stroke-width='6' stroke-linecap='round' stroke-linejoin='round'/></svg>
        """
        return svg.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~ ='/:"))) ?? ""
    }

    private static func escape(_ s: String) -> String {
        var out = ""
        for c in s {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.append(c)
            }
        }
        return out
    }
}
