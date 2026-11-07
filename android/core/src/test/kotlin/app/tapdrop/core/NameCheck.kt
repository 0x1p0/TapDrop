package app.tapdrop.core

import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Names, both ways (PROTOCOL.md → "Names"), against a headless test Mac it starts itself:
 *   gradle :core:nameCheck -Pmac=../mac/.build/debug/TapDrop -Pport=47961
 * The Mac is called "MacBook Air" (TAPDROP_TEST_NAME) and renames any phone called "Rename…" to "Pocket" a second
 * after its live link opens (TAPDROP_TEST_RENAME_LIVE).
 */
fun main(args: Array<String>) {
    val bin = args.getOrElse(0) { "../mac/.build/debug/TapDrop" }
    val port = args.getOrElse(1) { "47961" }.toInt()
    val home = File.createTempFile("tapdrop-names", "").apply { delete(); mkdirs() }
    val mac = ProcessBuilder(File(bin).absolutePath)
        .apply {
            environment().putAll(
                mapOf(
                    "TAPDROP_TEST" to "1", "TAPDROP_TEST_AUTOPAIR" to "1", "TAPDROP_TEST_NAME" to "MacBook Air",
                    "TAPDROP_TEST_RENAME_LIVE" to "Pocket", "TAPDROP_HOME" to home.absolutePath,
                    "TAPDROP_SAVE_DIR" to home.absolutePath, "TAPDROP_PORT" to port.toString(), "TAPDROP_NO_STATUS" to "1",
                )
            )
            redirectErrorStream(true)
            redirectOutput(File(home, "mac.log"))
        }
        .start()
    var failures = 0
    try {
        val ep = Endpoint("127.0.0.1", port)
        repeat(50) { if (runCatching { java.net.Socket(ep.host, ep.port).close() }.isSuccess) return@repeat; Thread.sleep(200) }

        fun check(name: String, ok: Boolean, detail: String = "") {
            println((if (ok) "  PASS  " else "  FAIL  ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
            if (!ok) failures++
        }
        fun named(name: String, at: Long) = Names.Named(name, at)
        fun Pair<Names.Named?, Names.Named?>.show() = "mac ${first?.name}@${first?.at}, phone ${second?.name}@${second?.at}"

        /** A live link's messages, readable with a timeout. */
        class Link(val w: WatchSession) {
            private val q = LinkedBlockingQueue<JSONObject>()
            init { thread(isDaemon = true) { runCatching { while (true) q.put(w.next()) } } }
            fun names(ms: Long, where: (Pair<Names.Named?, Names.Named?>) -> Boolean = { true }): Pair<Names.Named?, Names.Named?>? {
                val end = System.currentTimeMillis() + ms
                while (true) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) return null
                    val m = q.poll(left, TimeUnit.MILLISECONDS) ?: return null
                    if (m.optString("t") == "names") Names.parse(m).let { if (where(it)) return it }
                }
            }
        }

        val t0 = System.currentTimeMillis()

        // 1. Pairing and the live link: both names arrive, as they were (nobody has named anything yet).
        val a = TapDropClient(Identity(UUID.randomUUID().toString(), "Name Pixel A"))
        val pa = a.pair(ep) { _, _ -> }
        val ka = MacKey(pa.macId, pa.key)
        check("pairing names the Mac", pa.macName == "MacBook Air", pa.macName)
        val la = Link(a.watch(ep, ka))
        val first = la.names(3000)
        check("the live link opens with both names", first?.first == named("MacBook Air", 0) && first.second == named("Name Pixel A", 0),
            first?.show() ?: "none")

        // 2. The phone renames itself: the Mac takes it (the owner's word).
        val t1 = t0 + 1000
        var r = Names.parse(a.names(ep, ka, Names.message(named("MacBook Air", 0), named("Work phone", t1))))
        check("a phone's new name reaches the Mac", r.second == named("Work phone", t1), r.show())

        // 3. The phone renames the Mac: the Mac takes it as its own name, and the live link hears it.
        val t2 = t0 + 2000
        la.w.send(Names.message(named("Studio Mac", t2), named("Work phone", t1)))
        val heard = la.names(3000) { it.first?.name == "Studio Mac" }
        check("renaming the Mac from the phone: the Mac takes it and says so", heard?.first == named("Studio Mac", t2), heard?.show() ?: "none")
        check("…and answers to it from then on", a.ping(ep, ka).name == "Studio Mac")

        // 4. Older renames lose, whichever side they come from.
        r = Names.parse(a.names(ep, ka, Names.message(named("Old Mac", t2 - 1000), named("Work phone", t1))))
        check("an older rename of the Mac doesn't win", r.first == named("Studio Mac", t2), r.show())
        r = Names.parse(a.names(ep, ka, Names.message(named("Studio Mac", t2), named("Stale phone", t1 - 5))))
        check("an older name for the phone doesn't win", r.second == named("Work phone", t1), r.show())

        // 5. Names are cleaned: no control characters, spaces collapsed, at most 40 characters.
        val messy = "  Tab\there \u0007  " + "x".repeat(60)
        r = Names.parse(a.names(ep, ka, Names.message(named("Studio Mac", t2), named(messy, t0 + 3000))))
        val name = r.second?.name.orEmpty()
        check("names are cleaned and capped", name == Names.clean(messy) && name.length <= Names.MAX && '\t' !in name, "\"$name\"")

        // 6. A phone renamed in Android itself (still "not set", at 0) reaches the Mac too: the owner wins ties.
        val b = TapDropClient(Identity(UUID.randomUUID().toString(), "Name Pixel B"))
        val pb = b.pair(ep) { _, _ -> }
        val kb = MacKey(pb.macId, pb.key)
        r = Names.parse(b.names(ep, kb, Names.message(named("Studio Mac", t2), named("Renamed in Android", 0))))
        check("a phone's system name change reaches the Mac", r.second == named("Renamed in Android", 0), r.show())

        // 7. Renamed on the Mac: the phone hears its new name down its live link.
        val c = TapDropClient(Identity(UUID.randomUUID().toString(), "Rename me"))
        val pc = c.pair(ep) { _, _ -> }
        val lc = Link(c.watch(ep, MacKey(pc.macId, pc.key)))
        val renamed = lc.names(4000) { it.second?.name == "Pocket" }
        check("renaming a phone on the Mac reaches the phone", renamed?.second?.name == "Pocket" && (renamed.second?.at ?: 0) > 0,
            renamed?.show() ?: "none")

        // 8. Every live phone hears the Mac's new name, wherever it was renamed.
        val t4 = System.currentTimeMillis() + 5000
        a.names(ep, ka, Names.message(named("Desk Mac", t4), named(name, t0 + 3000)))
        val other = lc.names(3000) { it.first?.name == "Desk Mac" }
        check("another phone hears the Mac's new name at once", other?.first == named("Desk Mac", t4), other?.show() ?: "none")

        // 9. A rename to the computer's own name is a rename too (back to "MacBook Air", newer than before).
        val t5 = t4 + 1000
        r = Names.parse(a.names(ep, ka, Names.message(named("MacBook Air", t5), named(name, t0 + 3000))))
        check("back to the computer's name", r.first == named("MacBook Air", t5), r.show())

        // 10. Unchanged views settle: an exchange that agrees gets no further answer on the live link.
        val now = la.names(1500) { it.first?.name == "MacBook Air" }
        la.w.send(Names.message(named("MacBook Air", t5), named(name, t0 + 3000)))
        val extra = la.names(1200)
        check("views that agree settle (no ping-pong)", now != null && extra == null, extra?.show() ?: "quiet")

        la.w.close()
        lc.w.close()
    } finally {
        mac.destroy()
        mac.waitFor(3, TimeUnit.SECONDS)
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}
