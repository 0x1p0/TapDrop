import Foundation

/// The page the phone opens from the setup QR code: download TapDrop, then the three steps.
/// Dark, with the aurora accents and type of the apps (Geist where the phone has it, else the system sans).
enum InstallPage {
    static func html(macName: String, apkSize: Int64?) -> String {
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
            <p class="meta">Android 13 or newer · \(size)</p>
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
        <meta name="theme-color" content="#0A0A0F">
        <title>Get TapDrop</title>
        <link rel="icon" href="data:image/svg+xml,\(iconDataURI)">
        <style>
        :root {
          --bg: #0A0A0F; --surface: #15151C; --raised: #1C1C25; --hair: rgba(255,255,255,.08);
          --text: #F5F5FA; --text2: #A0A2B4; --text3: #6B6D80;
          --blue: #409EFF; --violet: #8561FF; --orchid: #F26BD9; --green: #2ED17A;
        }
        * { box-sizing: border-box; }
        html { background: var(--bg); -webkit-text-size-adjust: 100%; }
        body {
          margin: 0; min-height: 100vh; min-height: 100svh; color: var(--text); background: var(--bg);
          font: 400 16px/24px Geist, "Geist Sans", Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
          -webkit-font-smoothing: antialiased; -webkit-tap-highlight-color: transparent; overflow-x: hidden;
        }
        .aurora { position: fixed; inset: -30vh -30vw auto -30vw; height: 95vh; pointer-events: none; z-index: 0;
          background:
            radial-gradient(38% 42% at 28% 42%, rgba(64,158,255,.38), transparent 70%),
            radial-gradient(40% 46% at 70% 38%, rgba(133,97,255,.44), transparent 72%),
            radial-gradient(30% 34% at 56% 70%, rgba(242,107,217,.16), transparent 70%);
          filter: blur(28px); animation: drift 18s ease-in-out 2 alternate; }
        @keyframes drift { to { transform: translate3d(4vw, 3vh, 0) scale(1.06); } }
        main { position: relative; z-index: 1; max-width: 460px; margin: 0 auto;
          padding: calc(44px + env(safe-area-inset-top)) 22px calc(28px + env(safe-area-inset-bottom)); }
        .icon { width: 88px; height: 88px; border-radius: 24px; display: grid; place-items: center;
          background: linear-gradient(135deg, #33A8FF 0%, #6673FF 55%, #9952FA 100%);
          box-shadow: 0 22px 60px -14px rgba(133,97,255,.75), inset 0 1px 0 rgba(255,255,255,.35), inset 0 0 0 1px rgba(255,255,255,.14); }
        .icon svg { width: 46px; height: 46px; }
        .for { display: inline-flex; align-items: center; gap: 8px; margin-top: 26px; padding: 6px 12px 6px 10px; border-radius: 999px;
          background: rgba(255,255,255,.06); border: 1px solid var(--hair); color: var(--text2); font-size: 13px; line-height: 18px; font-weight: 500; }
        .for svg { width: 16px; height: 16px; stroke: var(--text2); }
        .for b { color: var(--text); font-weight: 600; }
        h1 { margin: 14px 0 8px; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.02em; }
        .lede { margin: 0; color: var(--text2); font-size: 17px; line-height: 25px; }
        .btn { margin-top: 28px; display: flex; align-items: center; justify-content: center; gap: 10px; height: 58px; border-radius: 999px;
          color: #fff; text-decoration: none; font-weight: 600; font-size: 17px; letter-spacing: -0.005em;
          background: linear-gradient(90deg, var(--blue), var(--violet));
          box-shadow: 0 16px 36px -12px rgba(133,97,255,.85), inset 0 1px 0 rgba(255,255,255,.28);
          transition: transform .18s cubic-bezier(.3,.7,.4,1), box-shadow .18s; }
        .btn:active { transform: scale(.97); box-shadow: 0 8px 20px -10px rgba(133,97,255,.85), inset 0 1px 0 rgba(255,255,255,.28); }
        .btn:focus-visible { outline: 3px solid rgba(133,97,255,.6); outline-offset: 3px; }
        .btn svg { width: 22px; height: 22px; fill: none; stroke: #fff; stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
        .meta { margin: 10px 0 0; text-align: center; color: var(--text3); font-size: 13px; line-height: 18px; font-weight: 500; }
        .next { display: none; margin: 14px 0 0; padding: 12px 14px; border-radius: 16px; text-align: center;
          background: rgba(46,209,122,.10); border: 1px solid rgba(46,209,122,.22); color: #BFF5D6; font-size: 14px; line-height: 20px; }
        .started .next { display: block; animation: rise .4s cubic-bezier(.3,.7,.4,1); }
        @keyframes rise { from { opacity: 0; transform: translateY(6px); } }
        .notice { margin-top: 28px; padding: 16px 18px; border-radius: 20px; background: rgba(255,176,32,.08);
          border: 1px solid rgba(255,176,32,.24); color: var(--text2); font-size: 15px; line-height: 22px; }
        .notice b { display: block; color: #FFD58A; font-weight: 600; margin-bottom: 4px; }
        h2 { margin: 36px 4px 10px; font-size: 14px; line-height: 20px; font-weight: 600; color: var(--text2); }
        .card { background: var(--surface); border: 1px solid var(--hair); border-radius: 26px; padding: 4px 18px; }
        .step { display: grid; grid-template-columns: 34px 1fr; gap: 14px; padding: 16px 0; }
        .step + .step { border-top: 1px solid var(--hair); }
        .n { width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; font-weight: 600; font-size: 15px;
          font-variant-numeric: tabular-nums; color: #CFC3FF; background: rgba(133,97,255,.16); box-shadow: inset 0 0 0 1px rgba(133,97,255,.25); }
        .step b { display: block; font-size: 16px; line-height: 22px; font-weight: 600; margin: 5px 0 3px; }
        .step span { display: block; color: var(--text2); font-size: 14px; line-height: 20px; }
        .step span b { display: inline; font-size: inherit; margin: 0; color: var(--text); font-weight: 600; }
        .tap { margin-top: 12px; display: grid; grid-template-columns: 76px 1fr; gap: 16px; align-items: center;
          background: var(--surface); border: 1px solid var(--hair); border-radius: 26px; padding: 16px 18px; overflow: hidden; }
        .art { position: relative; width: 76px; height: 84px; border-radius: 16px; overflow: hidden;
          background: radial-gradient(80% 70% at 50% 0%, rgba(133,97,255,.45), transparent 70%), #101018; box-shadow: inset 0 0 0 1px var(--hair); }
        .art .lid { position: absolute; left: 6px; right: 6px; top: 10px; height: 1px; background: rgba(255,255,255,.28); }
        .art .notch { position: absolute; left: 50%; top: 10px; width: 24px; height: 7px; margin-left: -12px; background: #000; border-radius: 0 0 4px 4px; }
        .art .glow { position: absolute; left: 50%; top: 4px; width: 60px; height: 26px; margin-left: -30px; border-radius: 50%;
          background: radial-gradient(closest-side, rgba(255,255,255,.9), rgba(133,97,255,.65), transparent); filter: blur(3px); }
        .art .phone { position: absolute; left: 50%; top: 18px; width: 32px; height: 70px; margin-left: -16px; border-radius: 8px;
          background: linear-gradient(#8CC6FF, var(--blue) 30%, var(--violet) 75%, #5B34D6); box-shadow: 0 0 0 2px #0C0C10, 0 0 0 2.6px rgba(255,255,255,.3), 0 0 18px rgba(133,97,255,.7); }
        .tap b { display: block; font-size: 16px; line-height: 22px; font-weight: 600; }
        .tap span { display: block; margin-top: 4px; color: var(--text2); font-size: 14px; line-height: 20px; }
        .tip { margin: 22px 4px 0; display: flex; gap: 10px; color: var(--text2); font-size: 14px; line-height: 20px; }
        .tip svg { flex: none; width: 18px; height: 18px; margin-top: 1px; stroke: var(--violet); fill: none; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
        footer { margin: 34px auto 0; max-width: 320px; color: var(--text3); font-size: 12px; line-height: 17px; font-weight: 500; text-align: center; }
        footer svg { display: inline-block; width: 12px; height: 12px; margin: 0 5px -1px 0; stroke: var(--text3); fill: none; stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
        @media (prefers-reduced-motion: reduce) { .aurora, .started .next { animation: none; } .btn { transition: none; } }
        </style>
        </head>
        <body>
        <div class="aurora" aria-hidden="true"></div>
        <main>
          <div class="icon" role="img" aria-label="TapDrop">\(glyphSVG)</div>
          <div class="for">
            <svg viewBox="0 0 24 24" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="4" y="5" width="16" height="11" rx="1.6"/><path d="M2 19h20"/></svg>
            <span>For <b>\(mac)</b></span>
          </div>
          <h1>Get TapDrop</h1>
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

    /// The app glyph: a dot with two pairs of radio waves.
    private static let glyphSVG = """
    <svg viewBox="0 0 48 48" fill="none" stroke="#fff" stroke-width="3.6" stroke-linecap="round" aria-hidden="true">\
    <circle cx="24" cy="24" r="3.6" fill="#fff" stroke="none"/>\
    <path d="M16.2 16.2a11 11 0 0 0 0 15.6M31.8 16.2a11 11 0 0 1 0 15.6"/>\
    <path d="M10.6 10.6a19 19 0 0 0 0 26.8M37.4 10.6a19 19 0 0 1 0 26.8"/></svg>
    """

    private static var iconDataURI: String {
        let svg = """
        <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 48 48'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>\
        <stop offset='0' stop-color='#33A8FF'/><stop offset='.55' stop-color='#6673FF'/><stop offset='1' stop-color='#9952FA'/></linearGradient></defs>\
        <rect width='48' height='48' rx='13' fill='url(#g)'/><circle cx='24' cy='24' r='3.6' fill='#fff'/>\
        <path d='M16.2 16.2a11 11 0 0 0 0 15.6M31.8 16.2a11 11 0 0 1 0 15.6M10.6 10.6a19 19 0 0 0 0 26.8M37.4 10.6a19 19 0 0 1 0 26.8' \
        fill='none' stroke='#fff' stroke-width='3.6' stroke-linecap='round'/></svg>
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
