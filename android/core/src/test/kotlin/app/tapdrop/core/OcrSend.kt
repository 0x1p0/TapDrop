package app.tapdrop.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Sends an image tagged for OCR to a test Mac, [n] times in a row (reproduces "Reading text…" hangs). */
fun main(args: Array<String>) {
    val ep = Endpoint(args[0], args[1].toInt())
    val img = File(args[2]).readBytes()
    val n = args.getOrElse(3) { "5" }.toInt()
    val client = TapDropClient(Identity(UUID.randomUUID().toString(), "OCR Test"))
    val p = client.pair(ep) { _, _ -> }
    val mac = MacKey(p.macId, p.key)
    repeat(n) { i ->
        val t0 = System.currentTimeMillis()
        client.send(ep, mac, listOf(OutItem.File("Photos test $i.png", img.size.toLong(), "image/png", clip = true, ocr = true) { ByteArrayInputStream(img) }))
        println("sent #$i in ${System.currentTimeMillis() - t0} ms")
        Thread.sleep(args.getOrElse(4) { "300" }.toLong())
    }
}
