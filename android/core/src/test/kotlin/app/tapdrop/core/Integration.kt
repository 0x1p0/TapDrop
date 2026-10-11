package app.tapdrop.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.system.exitProcess

/**
 * End-to-end check of the phone-side protocol against a running Mac app
 * (started with TAPDROP_TEST=1 TAPDROP_TEST_AUTOPAIR=1).
 */
fun main(args: Array<String>) {
    val ep = Endpoint(args.getOrElse(0) { "127.0.0.1" }, args.getOrElse(1) { "47913" }.toInt())
    val outDir = File(args.getOrElse(2) { "build/integration" }).apply { mkdirs() }
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "  PASS  " else "  FAIL  ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
        if (!ok) failures++
    }

    val client = TapDropClient(Identity(UUID.randomUUID().toString(), "Integration Pixel"))

    // 1. pairing
    var shownCode = ""
    val paired = client.pair(ep) { code, macName -> shownCode = code; println("  code $code from $macName") }
    check("pair", paired.key.size == 32 && shownCode.length == 6, "mac=${paired.macName} id=${paired.macId}")
    val mac = MacKey(paired.macId, paired.key)

    // 2. ping
    val pong = client.ping(ep, mac)
    check("ping", pong.name == paired.macName, "pending=${pong.pending}")

    // 3. phone → Mac: a 5.3 MB file + a text
    val payload = TDCrypto.random(5_300_000)
    val saved = client.send(
        ep, mac,
        listOf(
            OutItem.File("integration test.bin", payload.size.toLong(), "application/octet-stream") { ByteArrayInputStream(payload) },
            OutItem.File("unknown-size.txt", -1, "text/plain") { ByteArrayInputStream("hello mac".toByteArray()) },
            OutItem.Text("hello from the integration test"),
        ),
    ) { _, _, _ -> }
    check("send batch", saved == 3, "saved=$saved")
    File(outDir, "sent.sha256").writeText(sha(payload))

    // 4. Mac → phone: inbox (pre-seeded by the test harness)
    val inbox = CollectingSink()
    val n = client.pullInbox(ep, mac, inbox)
    check("inbox", n == inbox.files.size + inbox.texts.size, "items=$n files=${inbox.files.keys} texts=${inbox.texts}")
    inbox.files.forEach { (name, bytes) -> File(outDir, "inbox-$name").writeBytes(bytes) }
    val pong2 = client.ping(ep, mac)
    check("inbox dequeued", pong2.pending == 0, "pending=${pong2.pending}")

    // 5. Mac clipboard → phone
    val clip = CollectingSink()
    val c = client.getClipboard(ep, mac, clip)
    check("getclip", c >= 0, "items=$c files=${clip.files.keys} texts=${clip.texts.map { it.take(30) }}")

    // 6. a stranger must be refused
    val stranger = TapDropClient(Identity(UUID.randomUUID().toString(), "Stranger"))
    val refused = runCatching { stranger.ping(ep, mac) }.exceptionOrNull()
    check("unpaired device refused", refused is UnpairedException, refused.toString())

    // 7. a paired id with the wrong key must fail closed
    val wrongKey = runCatching { client.ping(ep, MacKey(mac.id, TDCrypto.random(32))) }.exceptionOrNull()
    check("wrong key rejected", wrongKey != null, wrongKey.toString())

    // 8. throughput
    val big = TDCrypto.random(64 * 1024 * 1024)
    val t0 = System.nanoTime()
    client.send(ep, mac, listOf(OutItem.File("speed.bin", big.size.toLong(), "application/octet-stream") { ByteArrayInputStream(big) }))
    val secs = (System.nanoTime() - t0) / 1e9
    check("64 MB transfer", true, "%.1f MB/s".format(64 / secs))
    File(outDir, "speed.sha256").writeText(sha(big))

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private class CollectingSink : BatchSink {
    val files = LinkedHashMap<String, ByteArray>()
    val texts = mutableListOf<String>()

    override fun openFile(name: String, size: Long, mime: String, clip: Boolean): IncomingFile {
        val bos = ByteArrayOutputStream()
        return object : IncomingFile {
            override val out: OutputStream = bos
            override fun commit() { files[name] = bos.toByteArray() }
            override fun abort() {}
        }
    }

    override fun text(text: String, clip: Boolean) { texts += text }
}
