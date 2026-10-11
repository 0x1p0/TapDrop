import CryptoKit
import Foundation
import Network

typealias JSON = [String: Any]

/// async/await wrapper around NWConnection with length-prefixed framing.
final class Conn: @unchecked Sendable {
    static let maxFrame = 4 * 1024 * 1024

    let nw: NWConnection

    init(_ nw: NWConnection) { self.nw = nw }

    var remoteHost: String {
        if case .hostPort(let host, _) = nw.endpoint {
            return "\(host)".components(separatedBy: "%").first ?? "\(host)"
        }
        return "?"
    }

    func read(_ n: Int) async throws -> Data {
        if n == 0 { return Data() }
        return try await withCheckedThrowingContinuation { cont in
            nw.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, _, error in
                if let error {
                    cont.resume(throwing: error)
                } else if let data, data.count == n {
                    cont.resume(returning: data)
                } else {
                    cont.resume(throwing: TDError.closed)
                }
            }
        }
    }

    func readSome(max: Int = 64 * 1024) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            nw.receive(minimumIncompleteLength: 1, maximumLength: max) { data, _, _, error in
                if let error {
                    cont.resume(throwing: error)
                } else if let data, !data.isEmpty {
                    cont.resume(returning: data)
                } else {
                    cont.resume(throwing: TDError.closed)
                }
            }
        }
    }

    func write(_ d: Data) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            nw.send(content: d, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }

    func readFrame() async throws -> Data {
        let h = [UInt8](try await read(4))
        let n = Int(h[0]) << 24 | Int(h[1]) << 16 | Int(h[2]) << 8 | Int(h[3])
        guard n <= Conn.maxFrame else { throw TDError.badFrame }
        return try await read(n)
    }

    /// Fire-and-forget framed send; NWConnection keeps call order, so callers serialize with a lock.
    func sendFrameNow(_ payload: Data) {
        let n = UInt32(payload.count)
        var out = Data([UInt8(n >> 24), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)])
        out.append(payload)
        nw.send(content: out, completion: .contentProcessed { _ in })
    }

    func writeFrame(_ payload: Data) async throws {
        let n = UInt32(payload.count)
        var out = Data([UInt8(n >> 24), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)])
        out.append(payload)
        try await write(out)
    }

    func readJSON() async throws -> JSON {
        let f = try await readFrame()
        guard let obj = try? JSONSerialization.jsonObject(with: f) as? JSON else { throw TDError.badFrame }
        return obj
    }

    func writeJSON(_ obj: JSON) async throws {
        try await writeFrame(try JSONSerialization.data(withJSONObject: obj))
    }

    func close() { nw.cancel() }
}

/// AES-256-GCM channel. Nonce = u32 direction || u64 counter, both implicit.
final class SecureChannel: @unchecked Sendable {
    enum Msg {
        case json(JSON)
        case data(Data)
    }

    static let chunk = 256 * 1024
    static let dirPhoneToMac: UInt32 = 1
    static let dirMacToPhone: UInt32 = 2

    let conn: Conn
    private let key: SymmetricKey
    private let sendDir: UInt32
    private let recvDir: UInt32
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    init(conn: Conn, key: SymmetricKey, sendDir: UInt32, recvDir: UInt32) {
        self.conn = conn
        self.key = key
        self.sendDir = sendDir
        self.recvDir = recvDir
    }

    private static func nonce(_ dir: UInt32, _ counter: UInt64) -> AES.GCM.Nonce {
        var b = [UInt8](repeating: 0, count: 12)
        for i in 0..<4 { b[i] = UInt8(dir >> (24 - 8 * i) & 0xff) }
        for i in 0..<8 { b[4 + i] = UInt8(counter >> (56 - 8 * i) & 0xff) }
        return try! AES.GCM.Nonce(data: b)
    }

    private func send(type: UInt8, body: Data) async throws {
        var plain = Data(capacity: body.count + 1)
        plain.append(type)
        plain.append(body)
        let box = try AES.GCM.seal(plain, using: key, nonce: Self.nonce(sendDir, sendCounter))
        sendCounter += 1
        var frame = box.ciphertext
        frame.append(box.tag)
        try await conn.writeFrame(frame)
    }

    private let sendLock = NSLock()

    /// Synchronous send for long-lived watch channels, safe to call from any thread.
    func sendJSONNow(_ obj: JSON) {
        guard let body = try? JSONSerialization.data(withJSONObject: obj) else { return }
        sendLock.lock()
        defer { sendLock.unlock() }
        var plain = Data([0x4A])
        plain.append(body)
        guard let box = try? AES.GCM.seal(plain, using: key, nonce: Self.nonce(sendDir, sendCounter)) else { return }
        sendCounter += 1
        var frame = box.ciphertext
        frame.append(box.tag)
        conn.sendFrameNow(frame)
    }

    func sendJSON(_ obj: JSON) async throws {
        try await send(type: 0x4A, body: try JSONSerialization.data(withJSONObject: obj))
    }

    func sendData(_ d: Data) async throws {
        try await send(type: 0x44, body: d)
    }

    func recv() async throws -> Msg {
        let f = try await conn.readFrame()
        guard f.count >= 17 else { throw TDError.badFrame }
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: Self.nonce(recvDir, recvCounter),
                ciphertext: f.prefix(f.count - 16),
                tag: f.suffix(16))
            plain = try AES.GCM.open(box, using: key)
        } catch {
            throw TDError.decrypt
        }
        recvCounter += 1
        let body = Data(plain.dropFirst())
        switch plain.first {
        case 0x4A:
            guard let obj = try? JSONSerialization.jsonObject(with: body) as? JSON else { throw TDError.badFrame }
            return .json(obj)
        case 0x44:
            return .data(body)
        default:
            throw TDError.badFrame
        }
    }

    func recvJSON() async throws -> JSON {
        guard case .json(let obj) = try await recv() else { throw TDError.proto("expected a control message") }
        return obj
    }
}

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? { self[k] as? String }
    func int(_ k: String) -> Int64? { (self[k] as? NSNumber)?.int64Value }
    func bool(_ k: String) -> Bool { (self[k] as? NSNumber)?.boolValue ?? false }
    var type: String { (self["t"] as? String) ?? "" }
}
