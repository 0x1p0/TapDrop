// Renders TapDrop's app icon (the Notchline, lit, on a night squircle) as PNGs.
//   swift mac/icon/render-icon.swift <out-dir>   → <out-dir>/AppIcon.iconset (for iconutil), icon-1024.png, tile-1024.png
// The squircle follows macOS's icon grid (824 of 1024, centred). The same mark as the Android launcher icon and the
// website's favicon.
import AppKit
import CoreGraphics

func notchline(_ ctx: CGContext, left: CGFloat, top: CGFloat, width: CGFloat) {
    let k = width / 68
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: left + (x - 16) * k, y: top + (y - 38) * k) }
    ctx.move(to: p(16, 38))
    ctx.addLine(to: p(34, 38))
    ctx.addCurve(to: p(38.5, 42.5), control1: p(37, 38), control2: p(38.5, 39.5))
    ctx.addLine(to: p(38.5, 43.5))
    ctx.addCurve(to: p(44.5, 49.5), control1: p(38.5, 47), control2: p(41, 49.5))
    ctx.addLine(to: p(55.5, 49.5))
    ctx.addCurve(to: p(61.5, 43.5), control1: p(59, 49.5), control2: p(61.5, 47))
    ctx.addLine(to: p(61.5, 42.5))
    ctx.addCurve(to: p(66, 38), control1: p(61.5, 39.5), control2: p(63, 38))
    ctx.addLine(to: p(84, 38))
}

func render(_ px: Int, full: Bool) -> Data {
    let s = CGFloat(px)
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // Top-left origin, like the SVG.
    ctx.translateBy(x: 0, y: s)
    ctx.scaleBy(x: 1, y: -1)
    let inset = full ? 0 : s * 100 / 1024
    let w = s - inset * 2
    let tile = CGRect(x: inset, y: inset, width: w, height: w)
    let r = w * (full ? 0.23 : 0.225)
    let tilePath = CGPath(roundedRect: tile, cornerWidth: r, cornerHeight: r, transform: nil)
    // A soft drop shadow under the tile (grid icons only).
    if !full {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -w * 0.012), blur: w * 0.03, color: CGColor(gray: 0, alpha: 0.35))
        ctx.addPath(tilePath)
        ctx.setFillColor(CGColor(srgbRed: 0x11 / 255, green: 0x11 / 255, blue: 0x14 / 255, alpha: 1))
        ctx.fillPath()
        ctx.restoreGState()
    }
    ctx.saveGState()
    ctx.addPath(tilePath)
    ctx.clip()
    ctx.setFillColor(CGColor(srgbRed: 0x11 / 255, green: 0x11 / 255, blue: 0x14 / 255, alpha: 1))
    ctx.fill(tile)
    // The light spilling below the line.
    let spill = CGGradient(colorsSpace: cs, colors: [
        CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0.34),
        CGColor(srgbRed: 0xD6 / 255, green: 0xDE / 255, blue: 1, alpha: 0.10),
        CGColor(srgbRed: 0xD6 / 255, green: 0xDE / 255, blue: 1, alpha: 0),
    ] as CFArray, locations: [0, 0.45, 1])!
    let c = CGPoint(x: tile.minX + w * 0.5, y: tile.minY + w * 0.40)
    ctx.drawRadialGradient(spill, startCenter: c, startRadius: 0, endCenter: c, endRadius: w * 0.46, options: [])
    // A faint sheen at the top.
    let sheen = CGGradient(colorsSpace: cs, colors: [CGColor(gray: 1, alpha: 0.07), CGColor(gray: 1, alpha: 0)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(sheen, start: CGPoint(x: 0, y: tile.minY), end: CGPoint(x: 0, y: tile.minY + w * 0.5), options: [])
    // The Notchline: two passes of glow, then the core.
    let lw = w * 0.034
    let left = tile.minX + w * 0.16, top = tile.minY + w * 0.38, lineW = w * 0.68
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)
    for (blur, alpha, width) in [(w * 0.05, 0.85, lw * 2.2), (w * 0.018, 0.9, lw * 1.4)] {
        ctx.saveGState()
        ctx.setShadow(offset: .zero, blur: blur, color: CGColor(gray: 1, alpha: alpha))
        notchline(ctx, left: left, top: top, width: lineW)
        ctx.setStrokeColor(CGColor(gray: 1, alpha: 0.55))
        ctx.setLineWidth(width)
        ctx.strokePath()
        ctx.restoreGState()
    }
    notchline(ctx, left: left, top: top, width: lineW)
    ctx.setStrokeColor(CGColor(gray: 1, alpha: 1))
    ctx.setLineWidth(lw)
    ctx.strokePath()
    ctx.restoreGState()
    // The 1 px edge.
    ctx.addPath(tilePath)
    ctx.setStrokeColor(CGColor(gray: 1, alpha: 0.12))
    ctx.setLineWidth(max(1, w / 400))
    ctx.strokePath()
    let img = ctx.makeImage()!
    return NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])!
}

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
for (name, px) in [("icon_16x16", 16), ("icon_16x16@2x", 32), ("icon_32x32", 32), ("icon_32x32@2x", 64),
                   ("icon_128x128", 128), ("icon_128x128@2x", 256), ("icon_256x256", 256), ("icon_256x256@2x", 512),
                   ("icon_512x512", 512), ("icon_512x512@2x", 1024)] {
    let set = out.appendingPathComponent("AppIcon.iconset")
    try? FileManager.default.createDirectory(at: set, withIntermediateDirectories: true)
    try! render(px, full: false).write(to: set.appendingPathComponent("\(name).png"))
}
try! render(1024, full: false).write(to: out.appendingPathComponent("icon-1024.png"))
try! render(1024, full: true).write(to: out.appendingPathComponent("tile-1024.png"))
print("ok")
