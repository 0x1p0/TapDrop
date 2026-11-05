import AppKit
import QuickLookThumbnailing
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Thumbnail

/// A real QuickLook thumbnail of a file, cached, with a shimmer while it renders and the file's icon
/// as a graceful fallback (missing files are dimmed). Videos get a small play badge.
///
///     Thumbnail(url: file, size: 40)
///     Thumbnail(url: file, size: 64, radius: 14, badge: false)
struct Thumbnail: View {
    let url: URL
    var size: CGFloat = 40
    /// Corner radius; defaults to ~22 % of the size.
    var radius: CGFloat?
    /// Play badge on videos.
    var badge = true

    @State private var image: NSImage?
    @State private var failed = false
    @State private var slow = false

    private var r: CGFloat { radius ?? (size * 0.22).rounded() }
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: r, style: .continuous) }
    private var exists: Bool { FileManager.default.fileExists(atPath: url.path) }

    var body: some View {
        ZStack {
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .interpolation(.high)
                    .aspectRatio(contentMode: .fill)
                    .transition(.opacity)
            } else if failed {
                Image(nsImage: NSWorkspace.shared.icon(forFile: url.path))
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .padding(size * 0.1)
                    .opacity(exists ? 1 : 0.45)
            } else {
                Rectangle().fill(TD.fill).tdShimmer(slow)
            }
        }
        .frame(width: size, height: size)
        .background(TD.fill)
        .clipShape(shape)
        .overlay(shape.strokeBorder(TD.hairline, lineWidth: 1))
        .overlay {
            if badge, image != nil, ThumbnailStore.isMovie(url) {
                Image(systemName: "play.fill")
                    .font(.system(size: size * 0.2, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: size * 0.4, height: size * 0.4)
                    .background(Circle().fill(.black.opacity(0.45)))
                    .background(Circle().fill(.ultraThinMaterial))
            }
        }
        .task(id: "\(url.path)#\(size)") { await load() }
        .accessibilityHidden(true)
    }

    private func load() async {
        let px = size * 2
        if let hit = ThumbnailStore.shared.cached(url, pixels: px) {
            image = hit
            return
        }
        image = nil
        failed = false
        let slowTimer = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(150))
            if !Task.isCancelled { slow = true }
        }
        let result = await ThumbnailStore.shared.thumbnail(url, pixels: px)
        slowTimer.cancel()
        withAnimation(.easeOut(duration: 0.22)) {
            image = result
            failed = result == nil
        }
    }
}

/// QuickLook thumbnails, cached by path + size + modification date (shared by every Thumbnail).
@MainActor
final class ThumbnailStore {
    static let shared = ThumbnailStore()

    private let cache = NSCache<NSString, NSImage>()
    private var inflight: [String: Task<NSImage?, Never>] = [:]

    private init() { cache.countLimit = 200 }

    private func key(_ url: URL, _ px: CGFloat) -> String {
        let mtime = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)?
            .timeIntervalSince1970 ?? 0
        return "\(url.path)#\(Int(px))#\(Int(mtime))"
    }

    func cached(_ url: URL, pixels: CGFloat) -> NSImage? {
        cache.object(forKey: key(url, pixels) as NSString)
    }

    /// nil when QuickLook has nothing better than an icon (the view then shows the file's icon).
    func thumbnail(_ url: URL, pixels: CGFloat) async -> NSImage? {
        let k = key(url, pixels)
        if let hit = cache.object(forKey: k as NSString) { return hit }
        if let running = inflight[k] { return await running.value }
        let task = Task<NSImage?, Never> {
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            let req = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: pixels / 2, height: pixels / 2),
                                                   scale: 2, representationTypes: .thumbnail)
            return try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: req).nsImage
        }
        inflight[k] = task
        let img = await task.value
        inflight[k] = nil
        if let img { cache.setObject(img, forKey: k as NSString) }
        return img
    }

    nonisolated static func isMovie(_ url: URL) -> Bool {
        UTType(filenameExtension: url.pathExtension)?.conforms(to: .movie) == true
    }
}

// MARK: - Dragging files out

extension View {
    /// Lets the user drag these files out of the view (to Finder, Mail, anywhere). Works alongside
    /// clicks: a drag only starts once the pointer has moved a few points.
    func tdFileDrag(_ urls: [URL], preview: NSImage? = nil) -> some View {
        modifier(FileDragModifier(urls: urls, preview: preview))
    }
}

private struct FileDragModifier: ViewModifier {
    let urls: [URL]
    let preview: NSImage?
    @State private var anchor = DragAnchor.Ref()
    @State private var started = false

    func body(content: Content) -> some View {
        content
            .background(DragAnchor(ref: anchor))
            .simultaneousGesture(
                DragGesture(minimumDistance: 5)
                    .onChanged { _ in
                        guard !started else { return }
                        started = true
                        FileDragSource.shared.begin(urls.filter { FileManager.default.fileExists(atPath: $0.path) },
                                                    from: anchor.view, preview: preview)
                    }
                    .onEnded { _ in started = false })
            .onHover { if $0 { started = false } }
    }
}

/// Finds the hosting NSView so an AppKit dragging session can start from SwiftUI.
private struct DragAnchor: NSViewRepresentable {
    final class Ref {
        weak var view: NSView?
    }

    let ref: Ref

    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        ref.view = v
        return v
    }

    func updateNSView(_ v: NSView, context: Context) { ref.view = v }
}

@MainActor
final class FileDragSource: NSObject, NSDraggingSource {
    static let shared = FileDragSource()

    /// A drag that started in TapDrop is in flight (drop targets in our own windows ignore it).
    private(set) var dragging = false

    func begin(_ urls: [URL], from view: NSView?, preview: NSImage?) {
        guard !urls.isEmpty, let view, let event = NSApp.currentEvent,
              event.type == .leftMouseDragged || event.type == .leftMouseDown
        else { return }
        let start = view.convert(event.locationInWindow, from: nil)
        let items = urls.enumerated().map { i, url -> NSDraggingItem in
            let item = NSDraggingItem(pasteboardWriter: url as NSURL)
            let img = (i == 0 ? preview : nil) ?? NSWorkspace.shared.icon(forFile: url.path)
            let side: CGFloat = 48
            let offset = CGFloat(i) * 6
            item.setDraggingFrame(NSRect(x: start.x - side / 2 + offset, y: start.y - side / 2 - offset, width: side, height: side),
                                  contents: img)
            return item
        }
        dragging = true
        view.beginDraggingSession(with: items, event: event, source: self)
    }

    nonisolated func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        [.copy, .generic]
    }

    nonisolated func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        MainActor.assumeIsolated { dragging = false }
    }
}

// MARK: - Content tiles

/// A tile for things that aren't files: links, text, codes.
struct ContentTile: View {
    enum Kind {
        case link
        case text(String)
        case code
    }

    let kind: Kind
    var size: CGFloat = 38

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: (size * 0.24).rounded(), style: .continuous)
        ZStack {
            switch kind {
            case .link:
                shape.fill(LinearGradient(colors: [TD.blue.opacity(0.22), TD.violet.opacity(0.22)],
                                          startPoint: .topLeading, endPoint: .bottomTrailing))
                Image(systemName: "link")
                    .font(.system(size: size * 0.4, weight: .semibold))
                    .foregroundStyle(TD.aurora)
            case .text(let t):
                shape.fill(TD.fill)
                VStack(alignment: .leading, spacing: size * 0.06) {
                    Image(systemName: "text.quote")
                        .font(.system(size: size * 0.24, weight: .bold))
                        .foregroundStyle(TD.aurora)
                    Text(t.prefix(40))
                        .font(.system(size: max(5.5, size * 0.14), weight: .medium))
                        .foregroundStyle(TD.textSecondary)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(size * 0.15)
            case .code:
                shape.fill(TD.warning.opacity(0.16))
                Image(systemName: "key.fill")
                    .font(.system(size: size * 0.38, weight: .semibold))
                    .foregroundStyle(TD.warning)
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .overlay(shape.strokeBorder(TD.hairline, lineWidth: 1))
        .accessibilityHidden(true)
    }
}
