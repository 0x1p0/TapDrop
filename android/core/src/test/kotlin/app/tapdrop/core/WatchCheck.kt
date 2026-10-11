package app.tapdrop.core

import java.util.UUID
import kotlin.concurrent.thread

/** Opens a watch session against a test Mac and prints every event for N seconds. */
fun main(args: Array<String>) {
    val ep = Endpoint(args[0], args[1].toInt())
    val secs = args.getOrElse(2) { "30" }.toLong()
    val client = TapDropClient(Identity(UUID.randomUUID().toString(), "Watch Test"))
    val p = client.pair(ep) { _, _ -> }
    val mac = MacKey(p.macId, p.key)
    val s = client.watch(ep, mac, readTimeoutMs = 45_000)
    val t0 = System.currentTimeMillis()
    fun ts() = "%5.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
    println("${ts()} watching (pending=${s.pendingAtStart})")
    thread(isDaemon = true) {
        while (true) {
            Thread.sleep(5000)
            runCatching { s.send("t" to "ka"); println("${ts()} -> ka") }.onFailure { println("${ts()} send failed: $it") }
        }
    }
    thread(isDaemon = true) {
        try {
            while (true) println("${ts()} <- ${s.next()}")
        } catch (e: Exception) {
            println("${ts()} READ ENDED: $e")
        }
    }
    Thread.sleep(secs * 1000)
    s.close()
    println("${ts()} done")
}
