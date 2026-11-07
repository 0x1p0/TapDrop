import Foundation

/// Device names, kept in step between this Mac and its phones (PROTOCOL.md → "Names"; the phone's side is
/// android/core/…/Names.kt, with the same rules).
///
/// Each device has one name. A person can change it on the device itself, or from the other side. Every name carries
/// `at`, when a person last set it (epoch ms; 0 for the system name nobody chose). Both sides send
/// `{"t":"names","mac":{…},"phone":{…}}` (their view of both names), and the newest change wins:
/// - an entry about the **sender itself** is the owner speaking: taken when it's at least as new as ours;
/// - an entry about the **receiver** is a rename made on the other side: taken only when strictly newer.
/// A side whose view still differs after taking what's newer answers with its own, so both settle on the same names.
enum Names {
    /// The longest name kept, in characters.
    static let max = 40

    struct Named: Equatable {
        var name: String
        var at: Int64

        var json: JSON { ["name": name, "at": at] }
    }

    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    static func message(mac: Named, phone: Named) -> JSON {
        ["t": "names", "mac": mac.json, "phone": phone.json]
    }

    static func parse(_ m: JSON) -> (mac: Named?, phone: Named?) {
        (entry(m["mac"] as? JSON), entry(m["phone"] as? JSON))
    }

    private static func entry(_ o: JSON?) -> Named? {
        guard let o, let name = clean(o.str("name")) else { return nil }
        return Named(name: name, at: Swift.max(0, o.int("at") ?? 0))
    }

    /// A name as people typed it, made safe to show: no control characters, spaces collapsed, at most `max`
    /// characters (whole characters, so an emoji is never split). Nil when nothing is left.
    static func clean(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let spaced = String(raw.unicodeScalars.map { s -> Character in
            CharacterSet.controlCharacters.contains(s) || s == "\u{2028}" || s == "\u{2029}" ? " " : Character(s)
        })
        let s = spaced.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard !s.isEmpty else { return nil }
        if s.count <= max { return s }
        return String(s.prefix(max)).trimmingCharacters(in: .whitespaces)
    }

    /// The sender's entry about itself: the owner's word, taken unless ours is newer.
    static func takeFromOwner(local: Named, incoming: Named) -> Bool { incoming.at >= local.at && incoming != local }

    /// The sender's entry about us: a rename made over there, taken only when it's newer than ours.
    static func takeFromPeer(local: Named, incoming: Named) -> Bool { incoming.at > local.at }
}

/// This Mac's name, readable from any thread (the server and the setup page run off the main actor).
final class MacNameBox: @unchecked Sendable {
    static let shared = MacNameBox()
    private let lock = NSLock()
    private var value: String?

    var name: String { lock.withLock { value } ?? Store.computerName }

    func set(_ name: String) { lock.withLock { value = name } }
}

import SwiftUI

/// The rename sheet for this Mac or a phone: a native alert with the name in a field, Rename as the default button,
/// and (when [reset] is given) a way back to the system's own name.
struct RenameAlert: ViewModifier {
    let title: String
    let message: String
    @Binding var isPresented: Bool
    let current: String
    var reset: (label: String, action: () -> Void)?
    let save: (String) -> Void
    @State private var draft = ""

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: $isPresented) {
                TextField("Name", text: $draft)
                Button("Rename") {
                    if let n = Names.clean(draft), n != current { save(n) }
                }
                .keyboardShortcut(.defaultAction)
                if let reset { Button(reset.label, action: reset.action) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(message)
            }
            .onChange(of: isPresented) { _, shown in if shown { draft = current } }
    }
}

extension View {
    func renameAlert(_ title: String, message: String, isPresented: Binding<Bool>, current: String,
                     reset: (label: String, action: () -> Void)? = nil, save: @escaping (String) -> Void) -> some View {
        modifier(RenameAlert(title: title, message: message, isPresented: isPresented, current: current, reset: reset, save: save))
    }
}
