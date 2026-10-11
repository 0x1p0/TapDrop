import AppKit
import Vision
import os

/// Apple's on-device text recognition (the engine behind Live Text).
enum OCR {
    private static let queue = DispatchQueue(label: "app.tapdrop.ocr", qos: .userInitiated)

    /// Starts reading an image. The work carries on even if the caller stops waiting for it:
    /// the first read after an install or update makes macOS rebuild its text model (~25 s).
    static func start(_ url: URL) -> Task<String, Never> {
        Task.detached {
            await withCheckedContinuation { (c: CheckedContinuation<String, Never>) in
                queue.async { c.resume(returning: recognize(url)) }
            }
        }
    }

    /// The text, or nil if it isn't ready within `timeout`.
    static func wait(_ read: Task<String, Never>, timeout: TimeInterval) async -> String? {
        await withCheckedContinuation { (cont: CheckedContinuation<String?, Never>) in
            let done = OSAllocatedUnfairLock(initialState: false)
            @Sendable func finish(_ v: String?) {
                let first = done.withLock { was in defer { was = true }; return !was }
                if first { cont.resume(returning: v) }
            }
            Task { finish(await read.value) }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(nil) }
        }
    }

    /// Loads the accurate model once in the background (a phone just connected), so the first
    /// real "send text" doesn't pay for it. Cheap: a tiny image, low priority, once per launch.
    static func prewarm() {
        guard !warmed else { return }
        warmed = true
        DispatchQueue.global(qos: .utility).async {
            let ctx = CGContext(data: nil, width: 64, height: 24, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
            ctx?.setFillColor(gray: 1, alpha: 1)
            ctx?.fill(CGRect(x: 0, y: 0, width: 64, height: 24))
            guard let img = ctx?.makeImage() else { return }
            _ = lines(in: img, level: .accurate)
        }
    }
    nonisolated(unsafe) private static var warmed = false

    static func recognize(_ url: URL) -> String {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil)
        else { return "" }
        return lines(in: img, level: .accurate)
    }

    private static func lines(in img: CGImage, level: VNRequestTextRecognitionLevel) -> String {
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = level
        // Language correction costs a ~27 s model load on first use and changes nothing for screen text.
        req.usesLanguageCorrection = false
        // Pinned language: auto-detect turns "₹" into kana on English receipts, and asking Vision
        // which languages it supports can itself trigger a slow model load.
        req.recognitionLanguages = ["en-US"]
        req.automaticallyDetectsLanguage = false
        try? VNImageRequestHandler(cgImage: img).perform([req])
        let lines = (req.results ?? [])
            // drop the phone's status bar (clock, battery) and gesture bar
            .filter { $0.boundingBox.midY < 0.965 && $0.boundingBox.midY > 0.02 }
            .sorted { a, b in
                abs(a.boundingBox.midY - b.boundingBox.midY) > 0.008
                    ? a.boundingBox.midY > b.boundingBox.midY
                    : a.boundingBox.minX < b.boundingBox.minX
            }
            .compactMap { $0.topCandidates(1).first?.string }
        return lines.joined(separator: "\n")
    }
}

/// Clipboard writes for secrets: clipboard managers are told not to keep them,
/// and the clipboard clears itself after a while if nothing else was copied.
enum SecureClipboard {
    static func set(_ s: String, clearAfter seconds: TimeInterval = 120) {
        guard !TestMode.on else { return }
        let pb = NSPasteboard.general
        let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")
        let transient = NSPasteboard.PasteboardType("org.nspasteboard.TransientType")
        pb.clearContents()
        pb.declareTypes([.string, concealed, transient], owner: nil)
        pb.setString(s, forType: .string)
        pb.setString("", forType: concealed)
        pb.setString("", forType: transient)
        let stamp = pb.changeCount
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            if pb.changeCount == stamp { pb.clearContents() }
        }
    }

    static func copy(_ s: String) {
        guard !TestMode.on else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }
}
