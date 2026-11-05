import AppKit
import ImageIO

/// Visual QA: a demo run photographs its own windows and quits (TAPDROP_SNAP=<dir>).
///
/// `screencapture` needs Screen Recording access, which sandboxed tools often don't have, but an app can
/// always capture its own windows. Each window is shot over a backdrop window of our own that shows the
/// part of a wallpaper that would be behind it, so glass looks the way it does on a real desktop.
///
///     TAPDROP_DEMO=panel:live TAPDROP_APPEARANCE=dark TAPDROP_SNAP=<dir> .build/debug/TapDrop
///
/// Writes `<dir>/<name>_<n>.png`, one per visible window (name: TAPDROP_SNAP_NAME, else the demo mode
/// plus the appearance). TAPDROP_SNAP_WAIT (seconds, default 2.2) lets entrance animations settle;
/// TAPDROP_SNAP_WALLPAPER picks the backdrop image (default: Sonoma, light or dark to match).
///
/// The notch island is shot as its whole top strip (the same frame every time, so shots line up) and has two more
/// switches:
/// - TAPDROP_SNAP_NOTCH=1 paints the real hardware notch (from the screen's notch areas; 208 × 37.5 pt at the top
///   centre without one) in translucent red over the shot, and checks the pixels under it first: nothing the island
///   draws may sit there, since the hardware hides it. Prints `notch: clear …` or `notch: OVERLAP …`. The island's
///   stand-in notch is left out, so the shot shows what is really drawn there.
/// - TAPDROP_SNAP_BURST=<n>@<seconds> shoots it n times, that far apart (with TAPDROP_ISLAND_SLOWMO, its morphs
///   mid-way), as `<name>_<n>_b<i>.png`.
@MainActor
enum DSSnapshot {
    /// TAPDROP_SNAP_NOTCH=1: the notch proof (see above).
    static let notchProof = ProcessInfo.processInfo.environment["TAPDROP_SNAP_NOTCH"] == "1"
        && ProcessInfo.processInfo.environment["TAPDROP_SNAP"] != nil

    private typealias CreateImageFn = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?

    // CGWindowListCreateImage is marked obsolete in the SDK but still works for the caller's own windows.
    private static let createImage: CreateImageFn? = {
        guard let h = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_NOW),
              let s = dlsym(h, "CGWindowListCreateImage") else { return nil }
        return unsafeBitCast(s, to: CreateImageFn.self)
    }()

    private static let onScreenBelowWindow: UInt32 = 1 << 2
    private static let includingWindow: UInt32 = 1 << 3
    private static let boundsIgnoreFraming: UInt32 = 1 << 0
    private static let bestResolution: UInt32 = 1 << 3

    /// Called by `Demo.run` once the demo screen is up.
    static func scheduleIfRequested(mode: String) {
        let env = ProcessInfo.processInfo.environment
        guard let dir = env["TAPDROP_SNAP"] else { return }
        let wait = env["TAPDROP_SNAP_WAIT"].flatMap(Double.init) ?? 2.2
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) {
            let dark = NSApp.effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let name = env["TAPDROP_SNAP_NAME"] ?? "\(mode.replacingOccurrences(of: ":", with: "_"))_\(dark ? "dark" : "light")"
            Task { @MainActor in
                await capture(dir: dir, name: name, dark: dark)
                exit(0)
            }
        }
    }

    static func capture(dir: String, name: String, dark: Bool) async {
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        let wallpaper = loadWallpaper(dark: dark)
        let targets = NSApp.windows
            .filter { w in
                w.isVisible && w.alphaValue > 0.01 && w.frame.width > 60 && w.frame.height > 30
                    && !w.className.contains("StatusBar")
            }
            .sorted { $0.level.rawValue > $1.level.rawValue }
        let env = ProcessInfo.processInfo.environment
        let burst = env["TAPDROP_SNAP_BURST"].map { b -> (Int, Double) in
            let p = b.split(separator: "@")
            return (max(1, Int(p.first ?? "") ?? 1), p.count > 1 ? Double(p[1]) ?? 0.25 : 0.25)
        } ?? (1, 0)
        var n = 0
        for w in targets {
            let island = w is IslandPanel
            // Controls draw their active look only in the key window of the active app. (The island is never key:
            // it doesn't need the app active, so shooting it takes focus from nothing.)
            if !island {
                NSApp.activate(ignoringOtherApps: true)
                if w.canBecomeKey { w.makeKeyAndOrderFront(nil) }
                try? await Task.sleep(for: .milliseconds(250))
            }
            guard let crop = island ? islandStrip(w) : contentRect(of: w) else { continue }
            let backdrop = makeBackdrop(cgRect: crop, below: w, wallpaper: wallpaper)
            try? await Task.sleep(for: .milliseconds(island ? 120 : 400))
            n += 1
            let shots = island ? burst.0 : 1
            for i in 0..<shots {
                if i > 0 { try? await Task.sleep(for: .seconds(burst.1)) }
                guard var img = createImage?(crop, onScreenBelowWindow | includingWindow, UInt32(w.windowNumber), bestResolution)?
                    .takeRetainedValue() else { continue }
                let path = shots > 1 ? "\(dir)/\(name)_\(n)_b\(String(format: "%02d", i)).png" : "\(dir)/\(name)_\(n).png"
                if island && notchProof {
                    let notch = hardwareNotch()
                    let verdict = w.isVisible ? checkNotch(img, notch: notch, crop: crop) : "island away"
                    print("notch: \(verdict)  \(path)")
                    img = markNotch(img, notch: notch, crop: crop) ?? img
                }
                if let png = NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:]) {
                    try? png.write(to: URL(fileURLWithPath: path))
                    print("snap: \(path)")
                    if i == 0 { print("info: \(w.className) key=\(w.isKeyWindow) appActive=\(NSApp.isActive)") }
                }
            }
            backdrop.orderOut(nil)
        }
        if n == 0 {
            print("snap: nothing on screen")
            for w in NSApp.windows {
                print("snap:   \(w.className) visible=\(w.isVisible) alpha=\(w.alphaValue) frame=\(w.frame) key=\(w.isKeyWindow)")
            }
        }
    }

    // MARK: geometry

    private static var primaryTop: CGFloat { NSScreen.screens.first?.frame.maxY ?? 0 }

    /// The window's frame in CoreGraphics (top-left origin) coordinates.
    private static func cgFrame(_ w: NSWindow) -> CGRect {
        CGRect(x: w.frame.minX, y: primaryTop - w.frame.maxY, width: w.frame.width, height: w.frame.height)
    }

    private static func cocoaRect(_ r: CGRect) -> NSRect {
        NSRect(x: r.minX, y: primaryTop - r.maxY, width: r.width, height: r.height)
    }

    /// The island's window is far bigger than the island: shoot its top strip (room for the tallest island and its
    /// shadow), the same frame for every state.
    private static func islandStrip(_ w: NSWindow) -> CGRect {
        let f = cgFrame(w)
        return CGRect(x: f.minX, y: f.minY, width: f.width, height: min(f.height, 190)).integral
    }

    /// The hardware notch (CoreGraphics coordinates): between the screen's two notch areas, as tall as its top
    /// safe area. Without a notch: a 208 × 37.5 pt one at the top centre of the main screen (a 15-inch MacBook Air's).
    static func hardwareNotch() -> CGRect {
        let notched = NSScreen.screens.first { $0.safeAreaInsets.top > 0 && $0.auxiliaryTopLeftArea != nil }
        guard let s = notched ?? NSScreen.screens.first else { return CGRect(x: 0, y: 0, width: 208, height: 37.5) }
        let top = primaryTop - s.frame.maxY
        if s.safeAreaInsets.top > 0, let l = s.auxiliaryTopLeftArea, let r = s.auxiliaryTopRightArea {
            return CGRect(x: l.maxX, y: top, width: r.minX - l.maxX, height: s.safeAreaInsets.top)
        }
        return CGRect(x: s.frame.midX - 104, y: top, width: 208, height: 37.5)
    }

    /// Looks for anything lit under the notch. The hardware's bottom corners are round (about 10 pt), so their
    /// outer corners, and a pixel along the edges, are left out of the check.
    private static func checkNotch(_ img: CGImage, notch: CGRect, crop: CGRect) -> String {
        let w = img.width, h = img.height
        let scale = CGFloat(w) / crop.width
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let ok = data.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return "unreadable" }
        let r = CGRect(x: (notch.minX - crop.minX) * scale, y: (notch.minY - crop.minY) * scale,
                       width: notch.width * scale, height: notch.height * scale).insetBy(dx: 2, dy: 0)
        let corner = 10 * scale
        let shape = CGPath(roundedRect: CGRect(x: r.minX, y: r.minY - corner, width: r.width, height: r.height + corner - 2),
                           cornerWidth: corner, cornerHeight: corner, transform: nil)
        var lit = 0, brightest = 0
        var box = CGRect.null
        for y in max(0, Int(r.minY))..<min(h, Int(r.maxY)) {
            for x in max(0, Int(r.minX))..<min(w, Int(r.maxX)) where shape.contains(CGPoint(x: CGFloat(x) + 0.5, y: CGFloat(y) + 0.5)) {
                let i = (y * w + x) * 4
                let v = max(data[i], data[i + 1], data[i + 2])
                brightest = max(brightest, Int(v))
                if v > 40 {
                    lit += 1
                    box = box.union(CGRect(x: x, y: y, width: 1, height: 1))
                }
            }
        }
        if lit == 0 { return "clear (brightest \(brightest)/255)" }
        let pt = CGRect(x: box.minX / scale, y: box.minY / scale, width: box.width / scale, height: box.height / scale)
        return String(format: "OVERLAP %d px, brightest %d/255, in x %.0f…%.0f y %.0f…%.0f pt", lit, brightest,
                      pt.minX, pt.maxX, pt.minY, pt.maxY)
    }

    /// The notch in translucent red, outlined, over a shot of the island.
    private static func markNotch(_ img: CGImage, notch: CGRect, crop: CGRect) -> CGImage? {
        let w = img.width, h = img.height
        let scale = CGFloat(w) / crop.width
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        // CoreGraphics draws bottom-up
        let r = CGRect(x: (notch.minX - crop.minX) * scale, y: CGFloat(h) - (notch.maxY - crop.minY) * scale,
                       width: notch.width * scale, height: notch.height * scale)
        ctx.setFillColor(CGColor(red: 1, green: 0.1, blue: 0.15, alpha: 0.36))
        ctx.fill(r)
        ctx.setStrokeColor(CGColor(red: 1, green: 0.2, blue: 0.2, alpha: 0.95))
        ctx.setLineWidth(scale)
        ctx.stroke(r.insetBy(dx: scale / 2, dy: scale / 2))
        return ctx.makeImage()
    }

    /// The painted part of the window (transparent margins trimmed), plus room for its shadow.
    private static func contentRect(of w: NSWindow) -> CGRect? {
        let frame = cgFrame(w)
        guard let img = createImage?(.null, includingWindow, UInt32(w.windowNumber), boundsIgnoreFraming | bestResolution)?
            .takeRetainedValue(), img.width > 0
        else { return nil }
        let scale = CGFloat(img.width) / frame.width
        guard let box = opaqueBounds(img) else { return nil }
        let margin: CGFloat = 30
        var r = CGRect(x: frame.minX + box.minX / scale - margin, y: frame.minY + box.minY / scale - margin,
                       width: box.width / scale + margin * 2, height: box.height / scale + margin * 2)
        if let s = w.screen ?? NSScreen.screens.first {
            r = r.intersection(CGRect(x: s.frame.minX, y: primaryTop - s.frame.maxY, width: s.frame.width, height: s.frame.height))
        }
        return r.integral
    }

    /// Bounding box (pixels, top-left origin) of pixels that aren't fully transparent.
    private static func opaqueBounds(_ img: CGImage) -> CGRect? {
        let w = img.width, h = img.height
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let ok = data.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard ok else { return nil }
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in stride(from: 0, to: h, by: 2) {
            let row = y * w * 4
            for x in stride(from: 0, to: w, by: 2) where data[row + x * 4 + 3] > 6 {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX >= minX, maxY >= minY else { return nil }
        return CGRect(x: minX, y: minY, width: maxX - minX + 2, height: maxY - minY + 2)
    }

    // MARK: backdrop

    private static func makeBackdrop(cgRect: CGRect, below target: NSWindow, wallpaper: CGImage?) -> NSWindow {
        let rect = cocoaRect(cgRect)
        let b = BackdropWindow(contentRect: rect, styleMask: .borderless, backing: .buffered, defer: false)
        b.isReleasedWhenClosed = false
        b.ignoresMouseEvents = true
        b.hasShadow = false
        b.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
        b.level = target.level
        let screen = NSScreen.screens.first(where: { $0.frame.intersects(rect) }) ?? NSScreen.screens.first
        b.contentView = WallpaperView(image: wallpaper, screen: screen?.frame ?? rect, window: rect)
        b.order(.below, relativeTo: target.windowNumber)
        return b
    }

    /// The desktop picture, light or dark variant (dynamic HEICs carry both; pick by brightness).
    private static func loadWallpaper(dark: Bool) -> CGImage? {
        let path = ProcessInfo.processInfo.environment["TAPDROP_SNAP_WALLPAPER"] ?? "/System/Library/Desktop Pictures/Sonoma.heic"
        guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
        let frames = (0..<CGImageSourceGetCount(src)).compactMap { i -> (CGImage, Int)? in
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                         kCGImageSourceThumbnailMaxPixelSize: 3000]
            guard let img = CGImageSourceCreateThumbnailAtIndex(src, i, opts as CFDictionary) else { return nil }
            return (img, brightness(img))
        }
        let sorted = frames.sorted { $0.1 < $1.1 }
        return (dark ? sorted.first : sorted.last)?.0
    }

    private static func brightness(_ img: CGImage) -> Int {
        var px = [UInt8](repeating: 0, count: 4)
        px.withUnsafeMutableBytes { buf in
            guard let ctx = CGContext(data: buf.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            ctx.interpolationQuality = .medium
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        return (Int(px[0]) + Int(px[1]) + Int(px[2])) / 3
    }

    /// May cover the menu bar (normal windows get pushed below it).
    private final class BackdropWindow: NSWindow {
        override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect { frameRect }
    }

    /// Draws the slice of an aspect-filled, screen-sized wallpaper that lies under this window.
    private final class WallpaperView: NSView {
        private let image: CGImage?
        private let screenRect: NSRect
        private let windowRect: NSRect

        init(image: CGImage?, screen: NSRect, window: NSRect) {
            self.image = image
            self.screenRect = screen
            self.windowRect = window
            super.init(frame: NSRect(origin: .zero, size: window.size))
        }

        required init?(coder: NSCoder) { nil }

        override func draw(_ dirtyRect: NSRect) {
            guard let ctx = NSGraphicsContext.current?.cgContext else { return }
            guard let image else {
                NSGradient(colors: [NSColor(tdHex: 0x2B3A8C), NSColor(tdHex: 0x6B3FA0)])?.draw(in: bounds, angle: -60)
                return
            }
            let iw = CGFloat(image.width), ih = CGFloat(image.height)
            let s = max(screenRect.width / iw, screenRect.height / ih)
            let drawn = CGSize(width: iw * s, height: ih * s)
            let origin = CGPoint(x: screenRect.minX + (screenRect.width - drawn.width) / 2 - windowRect.minX,
                                 y: screenRect.minY + (screenRect.height - drawn.height) / 2 - windowRect.minY)
            ctx.interpolationQuality = .high
            ctx.draw(image, in: CGRect(origin: origin, size: drawn))
        }
    }
}
