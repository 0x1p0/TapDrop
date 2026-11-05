import AppKit
import SwiftUI

/// Every design-system component on one page, in the current appearance (TAPDROP_DEMO=gallery).
/// A reference for building screens and a quick visual check after changing a component.
@MainActor
enum DSGallery {
    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1060, height: 820),
                             styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
            w.titlebarAppearsTransparent = true
            w.titleVisibility = .hidden
            w.title = "TapDrop Design System"
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: GalleryView())
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }
}

private struct GalleryView: View {
    @State private var on = true

    var body: some View {
        VStack(alignment: .leading, spacing: TD.Space.xl) {
            HStack(spacing: TD.Space.m) {
                AppGlyph(size: 40, glow: true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("TapDrop design system").tdFont(.display).foregroundStyle(TD.text)
                    Text("Tokens, glass, controls and components for the Mac app.").tdFont(.body).foregroundStyle(TD.textSecondary)
                }
            }
            HStack(alignment: .top, spacing: TD.Space.xxxl) {
                left.frame(width: 470, alignment: .leading)
                right.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(TD.Space.xxxl)
        .padding(.top, TD.Space.s)
        .frame(width: 1060, height: 820, alignment: .topLeading)
        .background(TD.bg)
    }

    private var left: some View {
        VStack(alignment: .leading, spacing: TD.Space.xl) {
            section("Buttons") {
                HStack(spacing: TD.Space.s) {
                    Button("Primary", systemImage: "plus") {}.buttonStyle(.tdPrimary)
                    Button("Secondary") {}.buttonStyle(.tdSecondary)
                    Button("Destructive") {}.buttonStyle(.tdDestructive)
                    Button("Quiet") {}.buttonStyle(.tdQuiet)
                }
                HStack(spacing: TD.Space.s) {
                    Button("Small") {}.buttonStyle(.td(.primary, size: .small))
                    Button("Small") {}.buttonStyle(.td(.secondary, size: .small))
                    Button("Large") {}.buttonStyle(.td(.primary, size: .large))
                    Button("Disabled") {}.buttonStyle(.tdPrimary).disabled(true)
                }
                HStack(spacing: TD.Space.s) {
                    Button("Send Files", systemImage: "plus") {}.buttonStyle(.td(.primary, fullWidth: true))
                    Button("Send Clipboard", systemImage: "doc.on.clipboard") {}.buttonStyle(.td(.secondary, fullWidth: true))
                }
                HStack(spacing: TD.Space.s) {
                    Button {} label: { Label("Settings", systemImage: "gearshape") }.buttonStyle(.tdIcon)
                    Button {} label: { Label("Add", systemImage: "plus") }.buttonStyle(.tdIcon(prominent: true))
                    Button {} label: { Label("Folder", systemImage: "folder") }.buttonStyle(.tdIcon(size: 32))
                    Button {} label: { Label("Close", systemImage: "xmark") }.buttonStyle(.tdIcon(size: 26, quiet: true))
                    KeyHint("⌘O")
                }
            }

            section("Status") {
                HStack(spacing: TD.Space.s) {
                    StatusPill(.live, "Android phone")
                    StatusPill(.searching, "Waiting")
                    StatusPill(.offline, "Not connected")
                    StatusPill(.error, "Offline")
                }
            }

            section("Icon chips") {
                HStack(spacing: TD.Space.s) {
                    IconChip("hand.tap.fill")
                    IconChip("iphone.gen3", tint: TDTint.green)
                    IconChip("folder.fill", tint: TDTint.blue)
                    IconChip("speaker.wave.2.fill", tint: TDTint.pink)
                    IconChip("lock.fill", tint: TDTint.orange)
                    IconChip("power", tint: TDTint.gray)
                    IconChip("link", size: 32, style: .tinted)
                    IconChip("key.fill", tint: TDTint.orange, size: 32, style: .tinted)
                    IconChip("tray", size: 32, style: .plain)
                }
            }

            section("Progress and success") {
                HStack(spacing: TD.Space.l) {
                    ProgressRing(progress: 0.42).frame(width: 36, height: 36)
                    ProgressRing(progress: nil).frame(width: 36, height: 36)
                    TransferGlyph(phase: .progress(0.66), symbol: "photo.fill").frame(width: 36, height: 36)
                    TransferGlyph(phase: .success).frame(width: 36, height: 36)
                    TransferGlyph(phase: .failure).frame(width: 36, height: 36)
                    CheckBurst(tint: TD.violet).frame(width: 44, height: 44)
                }
            }

            section("Empty, loading, thumbnails") {
                HStack(alignment: .top, spacing: TD.Space.l) {
                    EmptyState("tray", title: "Nothing yet", message: "Photos, links and files you share show up here.", compact: true)
                        .frame(width: 190)
                    VStack(alignment: .leading, spacing: 6) {
                        Shimmer().frame(width: 110, height: 11)
                        Shimmer().frame(width: 70, height: 11)
                        Shimmer(radius: 9).frame(width: 40, height: 40)
                    }
                    VStack(alignment: .leading, spacing: TD.Space.s) {
                        HStack(spacing: TD.Space.s) {
                            ForEach(demoFiles.prefix(2), id: \.self) { Thumbnail(url: $0, size: 44) }
                        }
                        HStack(spacing: TD.Space.s) {
                            ForEach(demoFiles.dropFirst(2).prefix(2), id: \.self) { Thumbnail(url: $0, size: 44) }
                        }
                    }
                }
            }
        }
    }

    private var right: some View {
        VStack(alignment: .leading, spacing: TD.Space.xl) {
            section("Device hero") {
                HStack(alignment: .top, spacing: TD.Space.s) {
                    ForEach([DeviceHero.Link.live, .connected, .searching, .offline, .error, .setup], id: \.self) { l in
                        DeviceHero(link: l, animated: false)
                            .frame(width: 76, height: 88)
                            .background(AuroraBackdrop(intensity: l == .live ? 1 : 0.4, drift: 0))
                            .clipShape(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: TD.Radius.row, style: .continuous).strokeBorder(TD.hairline))
                    }
                }
            }

            section("Cards") {
                HStack(alignment: .top, spacing: TD.Space.s) {
                    GlassCard(.glass, padding: TD.Space.m) { cardText("Glass", "Over anything") }
                    GlassCard(.surface, padding: TD.Space.m) { cardText("Surface", "Opaque windows") }
                    GlassCard(.inset, padding: TD.Space.m) { cardText("Inset", "Inside glass") }
                }
            }

            section("Settings") {
                SettingsGroup("Received files", footer: "Codes are hidden from clipboard managers and cleared after 2 minutes.") {
                    SettingsRow("folder.fill", tint: TDTint.blue, title: "Save to", subtitle: "~/Downloads") {
                        Button("Change…") {}.buttonStyle(.td(.secondary, size: .small))
                    }
                    SettingsRow("link", title: "Open links automatically", subtitle: "A page sent from your phone opens in your browser") {
                        Toggle("", isOn: $on).tdSwitch()
                    }
                    SettingsRow("iphone.gen3", tint: TDTint.green, title: "Phones", action: {})
                }
                SectionHeader("Recent", subtitle: "SectionHeader with a trailing accessory") {
                    Button("Downloads") {}.buttonStyle(.tdQuiet)
                }
            }
        }
    }

    private var demoFiles: [URL] {
        (ProcessInfo.processInfo.environment["TAPDROP_DEMO_FILES"] ?? "").split(separator: ":").prefix(4)
            .map { URL(fileURLWithPath: String($0)) }
    }

    private func cardText(_ title: String, _ sub: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).tdFont(.headline).foregroundStyle(TD.text)
            Text(sub).tdFont(.callout).foregroundStyle(TD.textSecondary)
        }
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: TD.Space.m) {
            Text(title).tdFont(.title2).foregroundStyle(TD.text)
            content()
        }
    }
}
