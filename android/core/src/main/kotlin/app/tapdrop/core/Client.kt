package app.tapdrop.core

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

data class Identity(val deviceId: String, val deviceName: String)

data class Endpoint(val host: String, val port: Int)

/** What the phone remembers about a paired Mac. */
class MacKey(val id: String, val key: ByteArray)

class PairResult(val macId: String, val macName: String, val key: ByteArray)

class Pong(val name: String, val pending: Int)

sealed interface OutItem {
    /** [ocr]: the Mac should read the text in this image onto its clipboard instead of saving it. */
    class File(
        val name: String,
        val size: Long,
        val mime: String,
        val clip: Boolean = false,
        val ocr: Boolean = false,
        val open: () -> InputStream,
    ) : OutItem

    /** [otpSource] marks a one-time code (e.g. "HDFC Bank") so the Mac shows it in the notch. */
    class Text(val text: String, val clip: Boolean = false, val otpSource: String? = null) : OutItem
}

/** Receives a batch coming from the Mac. */
interface BatchSink {
    fun begin(count: Int, totalBytes: Long, from: String) {}
    fun openFile(name: String, size: Long, mime: String, clip: Boolean): IncomingFile
    fun text(text: String, clip: Boolean)
}

interface IncomingFile {
    val out: OutputStream
    fun commit()
    fun abort()
}

fun interface Progress {
    fun update(done: Long, total: Long, current: String)
}

/**
 * Phone side of the TapDrop protocol (see PROTOCOL.md). Blocking; call from a background thread.
 */
class TapDropClient(private val identity: Identity) {

    private fun connect(ep: Endpoint, readTimeoutMs: Int): FrameIO {
        val s = Socket()
        s.tcpNoDelay = true
        s.sendBufferSize = 1 shl 20
        s.receiveBufferSize = 1 shl 20
        try {
            s.connect(InetSocketAddress(ep.host, ep.port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw UnreachableException(ep, e)
        }
        s.soTimeout = readTimeoutMs
        return FrameIO(s)
    }

    /**
     * Starts pairing. [onCode] fires with the 6-digit code as soon as the Mac answers;
     * the call then blocks until the user approves or declines on the Mac.
     */
    fun pair(
        ep: Endpoint,
        onConnected: ((java.io.Closeable) -> Unit)? = null,
        onCode: (code: String, macName: String) -> Unit,
    ): PairResult {
        connect(ep, PAIR_TIMEOUT_MS).use { io ->
            onConnected?.invoke(io)
            val kp = TDCrypto.newKeyPair()
            val myPub = kp.public.encoded
            io.writeJson(
                json(
                    "t" to "pair", "v" to 1, "device" to identity.deviceId,
                    "name" to identity.deviceName, "pub" to myPub.b64(),
                )
            )
            val wait = io.readJson()
            if (wait.type != "pair-wait") throw ProtocolException("unexpected ${wait.type}")
            val macPub = wait.getString("pub").unb64()
            val secret = PairingSecret(TDCrypto.ecdh(kp.private, macPub), myPub, macPub)
            onCode(secret.code, wait.optString("name"))
            val verdict = io.readJson()
            return when (verdict.type) {
                "pair-ok" -> PairResult(wait.getString("mac"), wait.optString("name"), secret.key)
                "pair-no" -> throw PairRejectedException()
                else -> throw ProtocolException("unexpected ${verdict.type}")
            }
        }
    }

    private fun <T> session(ep: Endpoint, mac: MacKey, readTimeoutMs: Int = READ_TIMEOUT_MS, block: (SecureChannel) -> T): T =
        connect(ep, readTimeoutMs).use { io -> block(handshake(io, ep, mac)) }

    /**
     * Opens a long-lived "watch" session: the Mac pushes events (e.g. {"t":"grab"} when the phone is
     * held over the notch, {"t":"inbox"} when files are queued) and the phone can reply at any time.
     */
    fun watch(ep: Endpoint, mac: MacKey, readTimeoutMs: Int = 45_000): WatchSession {
        val io = connect(ep, readTimeoutMs)
        try {
            val ch = handshake(io, ep, mac)
            ch.sendJson(json("t" to "watch"))
            val first = ch.recvJson()
            if (first.type != "watching") throw ProtocolException("unexpected ${first.type}")
            return WatchSession(io, ch, first.optInt("pending"))
        } catch (e: Exception) {
            io.close()
            throw e
        }
    }

    private fun handshake(io: FrameIO, ep: Endpoint, mac: MacKey): SecureChannel {
        run {
            val nonce = TDCrypto.random(16)
            io.writeJson(json("t" to "hello", "v" to 1, "device" to identity.deviceId, "nonce" to nonce.b64()))
            val hello = io.readJson()
            when (hello.type) {
                "hello" -> Unit
                "error" -> if (hello.optString("code") == "unpaired") throw UnpairedException()
                    else throw ProtocolException(hello.optString("code"))
                else -> throw ProtocolException("unexpected ${hello.type}")
            }
            if (hello.optString("mac") != mac.id) throw ProtocolException("A different Mac answered at ${ep.host}")
            val key = TDCrypto.sessionKey(mac.key, nonce, hello.getString("nonce").unb64())
            return SecureChannel(io, key, SecureChannel.DIR_PHONE_TO_MAC, SecureChannel.DIR_MAC_TO_PHONE)
        }
    }

    fun ping(ep: Endpoint, mac: MacKey): Pong = session(ep, mac, readTimeoutMs = 4000) { ch ->
        ch.sendJson(json("t" to "ping"))
        val r = ch.recvJson()
        if (r.type != "pong") throw ProtocolException("unexpected ${r.type}")
        Pong(r.optString("name"), r.optInt("pending"))
    }

    /** Sends items to the Mac. Returns how many the Mac saved. */
    fun send(ep: Endpoint, mac: MacKey, items: List<OutItem>, progress: Progress? = null): Int =
        session(ep, mac) { ch ->
            val files = items.filterIsInstance<OutItem.File>()
            val total = if (files.any { it.size < 0 }) -1L else files.sumOf { it.size }
            ch.sendJson(
                json(
                    "t" to "send", "count" to items.size, "total" to total, "from" to identity.deviceName,
                )
            )
            val buf = ByteArray(SecureChannel.CHUNK)
            var done = 0L
            for (item in items) {
                when (item) {
                    is OutItem.File -> {
                        ch.sendJson(
                            json(
                                "t" to "file", "name" to item.name, "size" to item.size,
                                "mime" to item.mime, "clip" to item.clip, "ocr" to item.ocr,
                            )
                        )
                        item.open().use { input ->
                            while (true) {
                                val n = input.readFully(buf)
                                if (n <= 0) break
                                ch.sendData(buf, 0, n)
                                done += n
                                progress?.update(done, total, item.name)
                            }
                        }
                        ch.sendJson(json("t" to "eof"))
                    }
                    is OutItem.Text -> ch.sendJson(
                        json("t" to "text", "text" to item.text, "clip" to item.clip, "otp" to item.otpSource)
                    )
                }
            }
            ch.sendJson(json("t" to "done"))
            val ack = ch.recvJson()
            if (ack.type != "ok") throw ProtocolException("Mac did not confirm")
            ack.optInt("saved", items.size)
        }

    /** Pulls the Mac's clipboard (text, image or copied files). Returns item count. */
    fun getClipboard(ep: Endpoint, mac: MacKey, sink: BatchSink, progress: Progress? = null): Int =
        session(ep, mac) { ch ->
            ch.sendJson(json("t" to "getclip"))
            receiveBatch(ch, sink, progress)
        }

    /** Collects everything queued on the Mac for this phone. Returns item count. */
    fun pullInbox(ep: Endpoint, mac: MacKey, sink: BatchSink, progress: Progress? = null): Int =
        session(ep, mac) { ch ->
            ch.sendJson(json("t" to "inbox"))
            receiveBatch(ch, sink, progress)
        }

    private fun receiveBatch(ch: SecureChannel, sink: BatchSink, progress: Progress?): Int {
        val head = ch.recvJson()
        if (head.type != "send") throw ProtocolException("unexpected ${head.type}")
        val total = head.optLong("total", -1)
        sink.begin(head.optInt("count"), total, head.optString("from"))
        var count = 0
        var done = 0L
        while (true) {
            val m = ch.recvJson()
            when (m.type) {
                "file" -> {
                    val name = m.optString("name", "file")
                    val f = sink.openFile(name, m.optLong("size", -1), m.optString("mime", ""), m.optBoolean("clip"))
                    try {
                        while (true) {
                            when (val msg = ch.recv()) {
                                is SecureChannel.Msg.Data -> {
                                    f.out.write(msg.buf, msg.off, msg.len)
                                    done += msg.len
                                    progress?.update(done, total, name)
                                }
                                is SecureChannel.Msg.Json -> {
                                    if (msg.obj.type != "eof") throw ProtocolException("expected eof")
                                    break
                                }
                            }
                        }
                        f.commit()
                    } catch (e: Exception) {
                        f.abort()
                        throw e
                    }
                    count++
                }
                "text" -> {
                    sink.text(m.optString("text"), m.optBoolean("clip"))
                    count++
                }
                "done" -> break
                else -> throw ProtocolException("unexpected ${m.type}")
            }
        }
        ch.sendJson(json("t" to "ok", "saved" to count))
        return count
    }

    companion object {
        const val DEFAULT_PORT = 47913
        const val SERVICE_TYPE = "_tapdrop._tcp."
        private const val CONNECT_TIMEOUT_MS = 3500
        private const val READ_TIMEOUT_MS = 30_000
        private const val PAIR_TIMEOUT_MS = 130_000
    }
}

/** Fills [buf] as far as possible; returns bytes read, or -1 at end of stream. */
private fun InputStream.readFully(buf: ByteArray): Int {
    var n = 0
    while (n < buf.size) {
        val r = read(buf, n, buf.size - n)
        if (r < 0) break
        n += r
    }
    return if (n == 0) -1 else n
}

@Suppress("unused")
private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null

/** A live, bidirectional encrypted channel to the Mac. Read from one thread; send from any. */
class WatchSession internal constructor(
    private val io: FrameIO,
    private val ch: SecureChannel,
    val pendingAtStart: Int,
) : java.io.Closeable {
    fun next(): JSONObject = ch.recvJson()
    fun send(o: JSONObject) = ch.sendJson(o)
    fun send(vararg pairs: Pair<String, Any?>) = ch.sendJson(json(*pairs))
    override fun close() = io.close()
}
