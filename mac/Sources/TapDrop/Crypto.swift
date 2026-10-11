import CryptoKit
import Foundation
import Security

enum TDError: Error, LocalizedError {
    case closed
    case badFrame
    case decrypt
    case unpaired
    case rejected
    case timeout
    case proto(String)

    var errorDescription: String? {
        switch self {
        case .closed: return "Connection closed"
        case .badFrame: return "Malformed frame"
        case .decrypt: return "Could not decrypt (wrong key?)"
        case .unpaired: return "Device is not paired"
        case .rejected: return "Rejected"
        case .timeout: return "Timed out"
        case .proto(let m): return "Protocol error: \(m)"
        }
    }
}

enum TDCrypto {
    static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        let ok = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        precondition(ok == errSecSuccess)
        return d
    }

    static func hkdf(_ ikm: Data, salt: Data, info: String, count: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: Data(info.utf8),
            outputByteCount: count)
        return key.withUnsafeBytes { Data($0) }
    }

    static func sha256(_ d: Data) -> Data { Data(SHA256.hash(data: d)) }

    static func sessionKey(pairKey: Data, phoneNonce: Data, macNonce: Data) -> SymmetricKey {
        SymmetricKey(data: hkdf(pairKey, salt: phoneNonce + macNonce, info: "tapdrop/session/v1", count: 32))
    }
}

/// Result of the pairing key agreement: the 6-digit comparison code and the long-term key.
struct PairingSecret {
    let code: String
    let key: Data

    init(shared: Data, phonePub: Data, macPub: Data) {
        let salt = TDCrypto.sha256(phonePub + macPub)
        let sas = TDCrypto.hkdf(shared, salt: salt, info: "tapdrop/sas/v1", count: 4)
        let n = sas.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        code = String(format: "%06d", n)
        key = TDCrypto.hkdf(shared, salt: salt, info: "tapdrop/pair/v1", count: 32)
    }
}
