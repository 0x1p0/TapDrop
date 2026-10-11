// Renders the TapDrop app icon at every macOS size into an .iconset, then iconutil → .icns
import AppKit

let out = CommandLine.arguments[1]  // path/to/AppIcon.iconset
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let s = CGFloat(px)
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8,
                               samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext

    // macOS icon grid: 824/1024 body with ~22.5% corner radius
    let inset = s * 100 / 1024
    let body = CGRect(x: inset, y: inset, width: s - inset * 2, height: s - inset * 2)
    let radius = body.width * 0.225
    let path = CGPath(roundedRect: body, cornerWidth: radius, cornerHeight: radius, transform: nil)

    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -s * 0.012), blur: s * 0.03, color: NSColor.black.withAlphaComponent(0.35).cgColor)
    ctx.addPath(path); ctx.setFillColor(NSColor.black.cgColor); ctx.fillPath()
    ctx.restoreGState()

    ctx.saveGState()
    ctx.addPath(path); ctx.clip()
    let cs = CGColorSpaceCreateDeviceRGB()
    let g = CGGradient(colorsSpace: cs, colors: [
        NSColor(red: 0.20, green: 0.66, blue: 1.00, alpha: 1).cgColor,
        NSColor(red: 0.40, green: 0.45, blue: 1.00, alpha: 1).cgColor,
        NSColor(red: 0.60, green: 0.32, blue: 0.98, alpha: 1).cgColor,
    ] as CFArray, locations: [0, 0.55, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: body.minX, y: body.maxY), end: CGPoint(x: body.maxX, y: body.minY), options: [])
    // soft top sheen
    let sheen = CGGradient(colorsSpace: cs, colors: [NSColor.white.withAlphaComponent(0.28).cgColor, NSColor.white.withAlphaComponent(0).cgColor] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(sheen, start: CGPoint(x: 0, y: body.maxY), end: CGPoint(x: 0, y: body.midY), options: [])

    // glyph: center dot + two pairs of arcs (radio waves)
    let c = CGPoint(x: body.midX, y: body.midY)
    let w = body.width
    ctx.setStrokeColor(NSColor.white.cgColor)
    ctx.setFillColor(NSColor.white.cgColor)
    ctx.setLineCap(.round)
    ctx.setLineWidth(w * 0.058)
    ctx.setShadow(offset: CGSize(width: 0, height: -w * 0.01), blur: w * 0.03, color: NSColor(red: 0.15, green: 0.1, blue: 0.5, alpha: 0.45).cgColor)
    let dot = w * 0.075
    ctx.fillEllipse(in: CGRect(x: c.x - dot, y: c.y - dot, width: dot * 2, height: dot * 2))
    for (r, a) in [(w * 0.19, 0.62), (w * 0.31, 0.56)] {
        ctx.addArc(center: c, radius: r, startAngle: .pi - a, endAngle: .pi + a, clockwise: false)
        ctx.strokePath()
        ctx.addArc(center: c, radius: r, startAngle: -a, endAngle: a, clockwise: false)
        ctx.strokePath()
    }
    ctx.restoreGState()
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

for (name, px) in [("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64), ("128x128", 128),
                   ("128x128@2x", 256), ("256x256", 256), ("256x256@2x", 512), ("512x512", 512), ("512x512@2x", 1024)] {
    try! render(px).write(to: URL(fileURLWithPath: "\(out)/icon_\(name).png"))
}
// also a 1024 PNG for other uses
try! render(1024).write(to: URL(fileURLWithPath: "\(out)/../icon-1024.png"))
