package app.tapdrop.core

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class TapDropException(message: String) : IOException(message)
class ProtocolException(message: String) : TapDropException(message)
class DecryptException : TapDropException("Could not decrypt — the Mac may have forgotten this phone")
class UnpairedException : TapDropException("This phone isn't paired with that Mac anymore")
class UnreachableException(val endpoint: Endpoint, cause: IOException) :
    TapDropException("Can't reach your Mac at ${endpoint.host}") {
    init { initCause(cause) }
}
class PairRejectedException : TapDropException("Pairing was declined on the Mac")

/** Length-prefixed frames over a TCP socket. */
class FrameIO(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 17))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 17))

    fun readFrame(): ByteArray {
        val n = input.readInt()
        if (n < 0 || n > MAX_FRAME) throw ProtocolException("bad frame length $n")
        return ByteArray(n).also { input.readFully(it) }
    }

    fun writeFrame(b: ByteArray, off: Int = 0, len: Int = b.size) {
        output.writeInt(len)
        output.write(b, off, len)
        output.flush()
    }

    fun readJson(): JSONObject = JSONObject(String(readFrame(), Charsets.UTF_8))

    fun writeJson(o: JSONObject) = writeFrame(o.toString().toByteArray(Charsets.UTF_8))

    override fun close() {
        runCatching { output.flush() }
        runCatching { socket.close() }
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}

/** AES-256-GCM channel; nonce = u32 direction || u64 counter (implicit on both ends). */
class SecureChannel(
    private val io: FrameIO,
    key: ByteArray,
    private val sendDir: Int,
    private val recvDir: Int,
) {
    sealed interface Msg {
        class Json(val obj: JSONObject) : Msg
        class Data(val buf: ByteArray, val off: Int, val len: Int) : Msg
    }

    private val keySpec = SecretKeySpec(key, "AES")
    // Separate ciphers so one thread can send while another receives (watch sessions).
    private val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var sendCounter = 0L
    private var recvCounter = 0L

    private fun nonce(dir: Int, counter: Long): ByteArray {
        val b = ByteArray(12)
        for (i in 0 until 4) b[i] = (dir ushr (24 - 8 * i)).toByte()
        for (i in 0 until 8) b[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return b
    }

    @Synchronized
    private fun send(type: Byte, body: ByteArray, off: Int, len: Int) {
        encCipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(sendDir, sendCounter++)))
        val out = ByteArray(encCipher.getOutputSize(len + 1))
        var n = encCipher.update(byteArrayOf(type), 0, 1, out, 0)
        n += encCipher.update(body, off, len, out, n)
        n += encCipher.doFinal(out, n)
        io.writeFrame(out, 0, n)
    }

    fun sendJson(o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        send(TYPE_JSON, b, 0, b.size)
    }

    fun sendData(b: ByteArray, off: Int, len: Int) = send(TYPE_DATA, b, off, len)

    fun recv(): Msg {
        val frame = io.readFrame()
        if (frame.size < 17) throw ProtocolException("short frame")
        val plain = try {
            decCipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce(recvDir, recvCounter++)))
            decCipher.doFinal(frame)
        } catch (e: AEADBadTagException) {
            throw DecryptException()
        }
        return when (plain[0]) {
            TYPE_JSON -> Msg.Json(JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8)))
            TYPE_DATA -> Msg.Data(plain, 1, plain.size - 1)
            else -> throw ProtocolException("unknown message type ${plain[0]}")
        }
    }

    fun recvJson(): JSONObject =
        (recv() as? Msg.Json)?.obj ?: throw ProtocolException("expected a control message")

    companion object {
        const val CHUNK = 256 * 1024
        const val DIR_PHONE_TO_MAC = 1
        const val DIR_MAC_TO_PHONE = 2
        private const val TYPE_JSON: Byte = 0x4A
        private const val TYPE_DATA: Byte = 0x44
    }
}

internal val JSONObject.type: String get() = optString("t")

internal fun json(vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }
