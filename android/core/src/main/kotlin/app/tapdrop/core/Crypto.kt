package app.tapdrop.core

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TDCrypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256 (matches CryptoKit's HKDF<SHA256>). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + infoBytes + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** P-256 ECDH with a peer public key in X.509 SubjectPublicKeyInfo DER form. */
    fun ecdh(priv: PrivateKey, peerDer: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerDer))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    fun sessionKey(pairKey: ByteArray, phoneNonce: ByteArray, macNonce: ByteArray): ByteArray =
        hkdf(pairKey, phoneNonce + macNonce, "tapdrop/session/v1", 32)
}

/** Numeric-comparison code + long-term key derived from the pairing ECDH. */
class PairingSecret(shared: ByteArray, phonePub: ByteArray, macPub: ByteArray) {
    val code: String
    val key: ByteArray

    init {
        val salt = TDCrypto.sha256(phonePub, macPub)
        val sas = TDCrypto.hkdf(shared, salt, "tapdrop/sas/v1", 4)
        var n = 0L
        for (b in sas) n = (n shl 8) or (b.toLong() and 0xff)
        code = "%06d".format(n % 1_000_000)
        key = TDCrypto.hkdf(shared, salt, "tapdrop/pair/v1", 32)
    }
}

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
internal fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)
