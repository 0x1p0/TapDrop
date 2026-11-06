package app.tapdrop.core

import app.tapdrop.core.LightKeyRules.Light
import app.tapdrop.core.LightKeyRules.Round
import app.tapdrop.core.LightKeyRules.Src
import org.json.JSONObject
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * The light key end to end:
 *  1. the phone's rules ([LightKeyRules]) on their own, including which of the Mac's answers count;
 *  2. the Mac's trigger logic against synthetic light traces (its self-test);
 *  3. rounds against headless test Macs this check starts itself (debug build, TAPDROP_TEST=1,
 *     TAPDROP_TEST_AUTOPAIR=1), whose simulated notch answers each key-on per TAPDROP_TEST_KEY:
 *     pass (the light comes and goes with the phone), stay (it comes but doesn't go: a lamp, not the phone),
 *     fail (it never comes), uncover (a bright room's light comes back: the hand that covered the notch left);
 *  4. calibration (TAPDROP_TEST_CALIBRATE=1: the test Mac calibrates the first light-key phone without one, and its
 *     simulated light reads TAPDROP_TEST_PEAK): three holds end in key-fail "calibrating", nothing is sent, the
 *     phone's taps then need clamp(½ × median, 220, 1000), kept per phone across a restart;
 *  5. the live link: a phone's newer link replaces its older one (only the newest gets grabs), and a link the phone
 *     has gone silent on is dropped (TAPDROP_TEST_WATCH_SILENCE shortens the Mac's 45 s).
 *
 *   gradle :core:keyCheck -Pmac=<mac/.build/debug/TapDrop> -Pport=47941   (uses port … port + 5)
 */
fun main(args: Array<String>) {
    val bin = File(args.getOrElse(0) { "../mac/.build/debug/TapDrop" }).absoluteFile
    val port = args.getOrElse(1) { "47941" }.toInt()
    val dir = File(args.getOrElse(2) { "build/keycheck" }).absoluteFile.apply { mkdirs() }
    require(bin.canExecute()) { "No Mac app at $bin — run `swift build` in mac/ first" }

    println("Phone rules")
    rules()
    println("Phone: which answers count")
    answers()

    println("Mac trigger logic (self-test)")
    selfTest(bin, dir, port)

    println("Rounds — the notch sees the light come and go (TAPDROP_TEST_KEY=pass)")
    TestMac(bin, port, dir, "pass").use { passRounds(it) }

    println("Rounds — the light comes but doesn't go (TAPDROP_TEST_KEY=stay)")
    TestMac(bin, port + 1, dir, "stay").use { stayRounds(it) }

    println("Rounds — the notch doesn't see it (TAPDROP_TEST_KEY=fail)")
    TestMac(bin, port + 2, dir, "fail").use { failRounds(it) }

    println("Rounds — a bright room, a hand leaves the notch as the phone lights up elsewhere (TAPDROP_TEST_KEY=uncover)")
    TestMac(bin, port + 3, dir, "uncover", room = 1500).use { uncoverRounds(it) }

    val calibrated = UUID.randomUUID().toString()
    println("Calibration — three holds measure the phone (1480 lux at the notch), then its taps need half that")
    TestMac(bin, port + 4, dir, "pass", calibrate = true, peak = 1480).use { calibrationRounds(it, calibrated) }

    println("Calibration — kept across a restart; a dim phone (420 lux) gets the 220-lux floor; a phone that leaves stops it")
    TestMac(bin, port + 4, dir, "pass", calibrate = true, peak = 420, keepHome = true).use { calibrationKept(it, calibrated) }

    println("Rounds — sun on the notch (B 2000): the phone covers it and its screen reads a steady 650 lux")
    TestMac(bin, port + 6, dir, "pass", room = 2000, peak = 650).use { sunRounds(it) }

    println("Rounds — a dark room (B 0): the phone's screen brightens slowly from its dimmest (TAPDROP_TEST_KEY=ramp)")
    TestMac(bin, port + 7, dir, "ramp", room = 0).use { rampRounds(it, lamp = false) }

    println("Rounds — a dark room: a lamp fades up as the phone knocks, and stays (TAPDROP_TEST_KEY=rampstay)")
    TestMac(bin, port + 8, dir, "rampstay", room = 0).use { rampRounds(it, lamp = true) }

    println("Rounds — the lights go off (every phone declines that round), then a phone knocks (TAPDROP_TEST_KEY=pass)")
    TestMac(bin, port + 9, dir, "pass").use { declinedThenKnock(it) }

    println("Rounds — a phone knocks somewhere else (a thumb on its proximity sensor): the notch never changes (TAPDROP_TEST_KEY=far)")
    TestMac(bin, port + 10, dir, "far").use { farKnock(it) }

    println("Live link — the newest link per phone wins; a silent link is dropped (silence limit 3 s)")
    TestMac(bin, port + 5, dir, "pass", env = mapOf("TAPDROP_TEST_WATCH_SILENCE" to "3")).use { liveness(it) }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  PASS  " else "  FAIL  ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
    if (!ok) failures++
}

private fun now() = System.nanoTime() / 1_000_000

// MARK: 1. phone rules

private fun rules() {
    val t = 100_000L
    val base = LightKeyRules.State(
        now = t, screenOn = true, locked = false, inCall = false, motionSensor = true, lastMotion = t - 400,
        near = false, lastNear = LightKeyRules.NEVER, picking = false, keying = false, lastSent = LightKeyRules.NEVER,
        busy = false, gz = 0.3f,
    )
    check("a moving, unlocked phone takes the grab", LightKeyRules.decline(base) == null)
    val everything = base.copy(screenOn = false, locked = true, inCall = true, lastMotion = t - 9000, picking = true, busy = true)
    val order = listOf(
        everything,
        everything.copy(screenOn = true),
        everything.copy(screenOn = true, locked = false),
        everything.copy(screenOn = true, locked = false, inCall = false),
        everything.copy(screenOn = true, locked = false, inCall = false, lastMotion = t - 100),
        everything.copy(screenOn = true, locked = false, inCall = false, lastMotion = t - 100, picking = false),
    ).map { LightKeyRules.decline(it) }
    check("declines in order: screen-off, locked, in-call, still, cooldown, busy",
        order == listOf("screen-off", "locked", "in-call", "still", "cooldown", "busy"), "$order")
    val desk = base.copy(lastMotion = t - 3500)
    check("still: no motion for 3 s, nothing near — a hand over the notch", LightKeyRules.decline(desk) == "still")
    check("not still while near the notch", LightKeyRules.decline(desk.copy(near = true, lastNear = t - 2000)) == null)
    check("not still within 1 s of being near", LightKeyRules.decline(desk.copy(lastNear = t - 800)) == null)
    check("touching: something tapped on the screen 0.5 s ago → decline (on the notch it faces away from its user)",
        LightKeyRules.decline(base.copy(lastTouch = t - 500)) == "touching" && LightKeyRules.decline(base.copy(lastTouch = t - 900)) == null)
    check("no accelerometer: never 'still'", LightKeyRules.decline(desk.copy(motionSensor = false)) == null)
    check("cooldown: 2.5 s after a tap that sent", LightKeyRules.decline(base.copy(lastSent = t - 2000)) == "cooldown" &&
        LightKeyRules.decline(base.copy(lastSent = t - 2600)) == null)
    check("cooldown: one round at a time on the phone", LightKeyRules.decline(base.copy(keying = true)) == "cooldown")
    check("a tap whose key failed doesn't hold up the next try", LightKeyRules.decline(base) == null)

    check("screen facing the notch (near) → screen", LightKeyRules.choose(base.copy(near = true)) == Light.Use(Src.SCREEN))
    check("near 0.6 s ago, even with the back up → screen", LightKeyRules.choose(base.copy(lastNear = t - 600, gz = 5f)) == Light.Use(Src.SCREEN))
    check("tilted towards the display (gz −3) → screen", LightKeyRules.choose(base.copy(gz = -3f)) == Light.Use(Src.SCREEN))
    check("upright on a vertical display (gz 0.3) → screen", LightKeyRules.choose(base) == Light.Use(Src.SCREEN))
    check("back to the notch (gz 5) → decline 'facing', in any room: only the screen's light counts",
        LightKeyRules.choose(base.copy(gz = 5f)) == Light.Decline("facing"))

    val k = base.copy(lastMotion = t - 300)
    fun knock(s: LightKeyRules.State = k, live: Boolean = true, lastKnock: Long = LightKeyRules.NEVER, farFor: Long = 5000) =
        LightKeyRules.knock(s, live, lastKnock, farFor)
    check("knock: moving, upright, unlocked, live, brought from afar", knock())
    check("no knock lying flat (|gz| 9.8)", !knock(k.copy(gz = 9.8f)))
    check("no knock with the screen facing up (gz 5: held to read, in bed or at a desk)", !knock(k.copy(gz = 5f)))
    check("knock tilted towards a display that leans back (gz −4)", knock(k.copy(gz = -4f)))
    check("no knock within 0.8 s of a touch on the screen (a thumb on the proximity sensor)",
        !knock(k.copy(lastTouch = t - 500)) && knock(k.copy(lastTouch = t - 900)))
    check("no knock face down (gz −9.8)", !knock(k.copy(gz = -9.8f)))
    check("no knock without motion in the last second", !knock(k.copy(lastMotion = t - 1200)))
    check("no knock locked / screen off / in a call", listOf(k.copy(locked = true), k.copy(screenOn = false), k.copy(inCall = true))
        .none { knock(it) })
    check("no knock without a live link", !knock(live = false))
    check("no knock when the sensor read far only briefly (a pocket, a thumb)", !knock(farFor = 300) && knock(farFor = 600))
    check("knocks 1.5 s apart", !knock(lastKnock = t - 1000) && knock(lastKnock = t - 1600))
    check("no knock during a round, while picking, or within 2.5 s of a sent tap",
        !knock(k.copy(keying = true)) && !knock(k.copy(picking = true)) && !knock(k.copy(lastSent = t - 1000)))
}

private fun msg(vararg pairs: Pair<String, Any?>) = JSONObject().apply { for ((k, v) in pairs) put(k, v) }

private fun answers() {
    // The Mac: key-on within 2 s of the grab; the rise until key-on + 900 ms; key-off within 1 s of key-seen; the fall
    // within 450 ms of key-off. The phone's light must outlast each answer it waits for.
    check("the phone's white screen outlasts the Mac's rise window (key-on + 2.5 s while brightening)", LightKeyRules.WHITE_MAX_MS >= 2500 + 300,
        "${LightKeyRules.WHITE_MAX_MS} ms")
    check("the phone's black screen outlasts the Mac's whole echo (key-off ≤ 1 s after key-seen, then the 450 ms fall)",
        LightKeyRules.DARK_MAX_MS >= 1000 + 450 + 100, "${LightKeyRules.DARK_MAX_MS} ms")

    check("a grab from this light key counts", LightKeyRules.grabCurrent(msg("t" to "grab", "id" to 1, "key" to 3)))
    check("a grab without a key, or from an older one, never does",
        !LightKeyRules.grabCurrent(msg("t" to "grab", "id" to 1)) && !LightKeyRules.grabCurrent(msg("t" to "grab", "id" to 1, "key" to 1)))

    var r = Round(7)
    val seen = r.onMac(msg("t" to "key-seen", "id" to 7, "peak" to 1480, "need" to 600))
    r.wentDark()
    val ok = r.onMac(msg("t" to "key-ok", "id" to 7, "peak" to 1480, "need" to 600, "fall" to 2))
    check("key-seen → go dark → key-off → key-ok: passes", seen == Round.Act.GO_DARK && ok == Round.Act.PASS &&
        r.step == Round.Step.PASSED && r.peak == 1480f && r.need == 600f, "$seen $ok ${r.step}")

    r = Round(7)
    check("a key-ok with no key-seen never passes (no echo, no key)",
        r.onMac(msg("t" to "key-ok", "id" to 7)) == Round.Act.FAIL && r.step == Round.Step.FAILED && r.why == "early-ok")

    r = Round(7)
    r.onMac(msg("t" to "key-seen", "id" to 7))
    check("a key-ok before the phone went dark never passes",
        r.onMac(msg("t" to "key-ok", "id" to 7)) == Round.Act.FAIL && r.why == "early-ok")

    r = Round(7)
    check("answers for another round are ignored (a late key-ok for an old round)",
        r.onMac(msg("t" to "key-seen", "id" to 6)) == Round.Act.IGNORE && r.onMac(msg("t" to "key-ok", "id" to 6)) == Round.Act.IGNORE &&
            r.step == Round.Step.LIT)

    r = Round(7)
    r.onMac(msg("t" to "key-seen", "id" to 7))
    r.wentDark()
    val f = r.onMac(msg("t" to "key-fail", "id" to 7, "why" to "stayed-lit", "peak" to 900, "need" to 600))
    check("a key-fail after going dark fails with the Mac's reason", f == Round.Act.FAIL && r.why == "stayed-lit" && r.peak == 900f)
    check("nothing counts once it's over", r.onMac(msg("t" to "key-ok", "id" to 7)) == Round.Act.IGNORE && r.step == Round.Step.FAILED)

    r = Round(7)
    check("a second key-seen is ignored", r.onMac(msg("t" to "key-seen", "id" to 7)) == Round.Act.GO_DARK &&
        r.onMac(msg("t" to "key-seen", "id" to 7)) == Round.Act.IGNORE)
    r.giveUp("timeout")
    check("no answer in time: failed, timeout", r.step == Round.Step.FAILED && r.why == "timeout" && !r.calibrating)

    r = Round(7)
    r.onMac(msg("t" to "key-seen", "id" to 7, "peak" to 1480, "need" to 270))
    r.wentDark()
    val c = r.onMac(msg("t" to "key-fail", "id" to 7, "why" to LightKeyRules.CALIBRATING, "peak" to 1480, "need" to 270, "fall" to 1))
    check("a calibration hold (key-fail calibrating after the echo) ends the round without a send, as a calibration",
        c == Round.Act.FAIL && r.step != Round.Step.PASSED && r.calibrating && r.peak == 1480f)
    check("a key-ok after the calibration hold is ignored", r.onMac(msg("t" to "key-ok", "id" to 7)) == Round.Act.IGNORE && r.calibrating)
}

// MARK: 2. the Mac's self-test

private fun selfTest(bin: File, dir: File, port: Int) {
    val home = File(dir, "home-selftest").apply { deleteRecursively(); mkdirs() }
    val p = ProcessBuilder(bin.path).redirectErrorStream(true).apply {
        environment() += mapOf("TAPDROP_TEST" to "1", "TAPDROP_TEST_SELFTEST" to "1", "TAPDROP_HOME" to home.path, "TAPDROP_PORT" to "$port")
    }.start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor(30, TimeUnit.SECONDS)
    out.lines().filter { it.trimStart().startsWith("PASS") || it.trimStart().startsWith("FAIL") }.forEach { println("      ${it.trim()}") }
    check("Mac detector self-test", p.exitValue() == 0, out.lines().lastOrNull { it.contains("PASSED") || it.contains("FAILED") } ?: out.takeLast(200))
}

// MARK: 3. rounds

/**
 * A headless test Mac. [calibrate]: it calibrates the first light-key phone that connects without a calibration;
 * [peak]: what its simulated notch reads while a phone's light is on; [keepHome]: start from the previous run's
 * folder (pairings and calibrations kept).
 */
private class TestMac(
    bin: File,
    val port: Int,
    dir: File,
    key: String,
    room: Int? = null,
    calibrate: Boolean = false,
    peak: Int? = null,
    keepHome: Boolean = false,
    env: Map<String, String> = emptyMap(),
) : AutoCloseable {
    val home = File(dir, "home-$port").apply { if (!keepHome) deleteRecursively(); mkdirs() }
    val save = File(dir, "save-$port").apply { deleteRecursively(); mkdirs() }
    val log = File(dir, "mac-$port${if (keepHome) "-again" else ""}.log")
    private val proc: Process = ProcessBuilder(bin.path).redirectErrorStream(true).redirectOutput(log).apply {
        environment() += mapOf(
            "TAPDROP_TEST" to "1", "TAPDROP_TEST_AUTOPAIR" to "1", "TAPDROP_TEST_KEY" to key,
            "TAPDROP_HOME" to home.path, "TAPDROP_SAVE_DIR" to save.path, "TAPDROP_PORT" to "$port",
        )
        if (room != null) environment()["TAPDROP_TEST_ROOM"] = "$room"
        if (calibrate) environment()["TAPDROP_TEST_CALIBRATE"] = "1"
        if (peak != null) environment()["TAPDROP_TEST_PEAK"] = "$peak"
        environment() += env
    }.start()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { proc.destroyForcibly() })
        val end = now() + 15_000
        while (!listening()) {
            check(now() < end && proc.isAlive) { "the test Mac on port $port didn't start (see $log)" }
            Thread.sleep(150)
        }
    }

    private fun listening() = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess

    fun signal(sig: String) {
        ProcessBuilder("kill", "-$sig", proc.pid().toString()).start().waitFor()
    }

    fun logText() = log.readText()

    override fun close() {
        proc.destroy()
        if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
}

/** [caps]: the light-key version to announce (null: none, a phone that predates the light key). [id]: its device id. */
private class Phone(name: String, ep: Endpoint, caps: Int?, id: String = UUID.randomUUID().toString()) : AutoCloseable {
    val client = TapDropClient(Identity(id, name))
    val mac: MacKey
    val s: WatchSession
    private val inbox = LinkedBlockingQueue<JSONObject>()

    init {
        val p = client.pair(ep) { _, _ -> }
        mac = MacKey(p.macId, p.key)
        s = client.watch(ep, mac, caps = caps == LightKeyRules.VERSION)
        if (caps != null && caps != LightKeyRules.VERSION) s.send("t" to "caps", "key" to caps)
        thread(isDaemon = true) {
            try {
                while (true) inbox.put(s.next())
            } catch (_: Exception) {
            }
        }
    }

    /** The next [type] message within [ms] (others in between are skipped). */
    fun expect(type: String, ms: Long, where: (JSONObject) -> Boolean = { true }): JSONObject? =
        expectAny(setOf(type), ms, where)

    /** The next message of any of [types] within [ms] (others in between are skipped). */
    fun expectAny(types: Set<String>, ms: Long, where: (JSONObject) -> Boolean = { true }): JSONObject? {
        val end = now() + ms
        while (true) {
            val left = end - now()
            if (left <= 0) return null
            val m = inbox.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (m.optString("t") in types && where(m)) return m
        }
    }

    fun none(type: String, ms: Long) = expect(type, ms) == null

    fun count(type: String, ms: Long): Int {
        val end = now() + ms
        var n = 0
        while (true) {
            val left = end - now()
            if (left <= 0) return n
            if (expect(type, left) != null) n++
        }
    }

    /** What the phone's light key does after key-on: on "key-seen" it goes dark and says key-off. Returns the verdict. */
    fun key(id: Int, ms: Long = 2500): Pair<JSONObject?, JSONObject?> {
        val first = expectAny(setOf("key-seen", "key-ok", "key-fail"), ms) { it.id() == id }
        if (first?.optString("t") != "key-seen") return null to first
        s.keyOff(id)
        return first to expectAny(setOf("key-ok", "key-fail"), ms) { it.id() == id }
    }

    override fun close() = s.close()
}

/** How long a check waits for the Mac's grab. It comes in well under a second; a busy build machine can be slower. */
private const val GRAB_WAIT = 4000L

private fun JSONObject?.id() = this?.optInt("id", -1) ?: -1
private fun JSONObject?.desc() = this?.let { "${it.optString("t")}${it.optString("why").takeIf { w -> w.isNotEmpty() }?.let { w -> " $w" } ?: ""}" } ?: "nothing"

private fun passRounds(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Key Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        Phone("Old Pixel", ep, caps = null).use { old ->
            Phone("Key2 Pixel", ep, caps = 2).use { key2 ->
                val room = p.expect("room", 2500)
                check("caps session gets the room on start", room != null && !room.optBoolean("dark") && room.optDouble("lux") in 100.0..140.0, "$room")

                // DIP: USR1 → grab(key 3) → key-on → key-seen → key-off → key-ok
                Thread.sleep(400)
                var t = now()
                mac.signal("USR1")
                val g = p.expect("grab", GRAB_WAIT)
                check("USR1 (notch covered) → grab with this light key", g != null && g.optInt("key") == LightKeyRules.VERSION && !g.optBoolean("dark"),
                    "$g in ${now() - t} ms")
                val id = g.id()
                p.s.keyOn(id, Src.SCREEN)
                t = now()
                val seen = p.expectAny(setOf("key-seen", "key-ok", "key-fail"), 1500) { it.id() == id }
                check("key-on → key-seen once the notch clearly sees light: min(0.55·E, P + 0.4·E) = 4 + 120 = 124, simulated 900 lux",
                    seen?.optString("t") == "key-seen" && seen.optDouble("peak") >= 900 && seen.optDouble("need") == 124.0, "$seen in ${now() - t} ms")
                check("no key-ok while the phone's light is still on", p.none("key-ok", 300))
                t = now()
                p.s.keyOff(id)
                val ok = p.expectAny(setOf("key-ok", "key-fail"), 1500) { it.id() == id }
                check("key-off → the notch goes dark with it → key-ok (white − black ≥ 0.45·E = 135, black ≤ 0.75·B = 90)",
                    ok?.optString("t") == "key-ok" && ok.optDouble("fall") <= 90 && ok.optDouble("need") == 135.0, "$ok in ${now() - t} ms")
                check("a session without caps gets no grab", old.none("grab", 300))
                check("a session with the previous light key (2) gets no grab", key2.none("grab", 300))
                p.s.grabAck(id, true, "screenshot")
                val png = ByteArrayOutputStream().also {
                    val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(40, 90, 200); fillRect(0, 0, 8, 8) } }
                    ImageIO.write(img, "png", it)
                }.toByteArray()
                val saved = p.client.send(ep, p.mac, listOf(OutItem.File("KeyCheck.png", png.size.toLong(), "image/png") { ByteArrayInputStream(png) }))
                Thread.sleep(300)
                check("after key-ok the phone's send goes through", saved == 1 && File(mac.save, "KeyCheck.png").exists(), "saved=$saved")
                mac.signal("USR2")
                val rel = p.expect("release", 1500) { it.id() == id }
                check("USR2 (phone leaves) → release for that round", rel != null, "$rel")

                // KNOCK: the phone starts the round
                Thread.sleep(1700)
                p.s.knock()
                t = now()
                val gk = p.expect("grab", GRAB_WAIT)
                check("knock → grab with key (a round started by the phone)", gk != null && gk.optInt("key") == LightKeyRules.VERSION, "$gk in ${now() - t} ms")
                p.s.keyOn(gk.id(), Src.SCREEN)
                val (kseen, kok) = p.key(gk.id())
                check("knock round, bright screen content already on the notch (P 300): key-seen at 0.45·E = 135 → key-ok",
                    kseen?.optDouble("need") == 135.0 && kok?.optString("t") == "key-ok", "${kseen.desc()} / ${kok.desc()}")
                mac.signal("USR2")
                check("knock round releases once the phone leaves", p.expect("release", 1500) { it.id() == gk.id() } != null)

                // A torch never counts, covered or not
                Thread.sleep(1700)
                mac.signal("USR1")
                val gt = p.expect("grab", GRAB_WAIT)
                p.s.send("t" to "key-on", "id" to gt.id(), "src" to "torch")
                t = now()
                val tf = p.expectAny(setOf("key-seen", "key-ok", "key-fail"), 1000) { it.id() == gt.id() }
                check("a torch, even over a covered notch → key-fail screen-only, at once", tf.desc() == "key-fail screen-only" && now() - t < 300,
                    "${tf.desc()} in ${now() - t} ms")
                p.s.keyOff(gt.id())
                p.s.grabAck(gt.id(), false, "none", "no-light")
                mac.signal("USR2")
                Thread.sleep(400)

                // No light at all
                Thread.sleep(1700)
                mac.signal("USR1")
                val gn = p.expect("grab", GRAB_WAIT)
                t = now()
                val nl = p.expect("key-fail", 3000) { it.id() == gn.id() }
                val after = now() - t
                check("no key-on within 2 s → key-fail no-light-sent", nl?.optString("why") == "no-light-sent" && after in 1900..2400, "$nl after $after ms")
                p.s.grabAck(gn.id(), false, "none", "no-light")
                mac.signal("USR2")
                Thread.sleep(400)

                // Seen, but the phone never says its light went off
                Thread.sleep(1700)
                mac.signal("USR1")
                val gs = p.expect("grab", GRAB_WAIT)
                p.s.keyOn(gs.id(), Src.SCREEN)
                val s2 = p.expect("key-seen", 1500) { it.id() == gs.id() }
                t = now()
                val nk = p.expectAny(setOf("key-ok", "key-fail"), 2000) { it.id() == gs.id() }
                check("key-seen, then no key-off → key-fail no-key-off after ~1 s (never key-ok)",
                    s2 != null && nk.desc() == "key-fail no-key-off" && now() - t in 900..1300, "${nk.desc()} after ${now() - t} ms")
                p.s.keyOff(gs.id())
                p.s.grabAck(gs.id(), false, "none", "no-light")
                mac.signal("USR2")
                Thread.sleep(400)

                // A declined grab ends the round quietly
                Thread.sleep(1700)
                mac.signal("USR1")
                val gd = p.expect("grab", GRAB_WAIT)
                p.s.grabAck(gd.id(), false, "none", "still")
                check("declined grab ('still') → no verdict sent", gd != null && p.none("key-fail", 2200))
                mac.signal("USR2")
                Thread.sleep(400)

                // Rounds start at least 1.5 s apart, one at a time
                Thread.sleep(1700)
                mac.signal("USR1")
                val g1 = p.expect("grab", GRAB_WAIT)
                p.s.knock()
                check("a knock during a round doesn't start another", g1 != null && p.none("grab", 700))
                p.s.grabAck(g1.id(), false, "none", "still")
                mac.signal("USR2")
                Thread.sleep(400)

                // A key-on nobody asked for is refused at once
                p.s.keyOn(9999, Src.SCREEN)
                val late = p.expect("key-fail", 600) { it.id() == 9999 }
                check("key-on for an unknown round → key-fail late", late?.optString("why") == "late", "$late")

                // Diagnostics: lux readings, at most 8 a second
                p.s.diag(true)
                val n = p.count("lux", 2000)
                check("diag on → lux stream, at most 8 a second", n in 10..16, "$n in 2 s")
                p.s.diag(false)
                // Readings already on their way still arrive (a busy machine can hold one back for a while), so wait
                // for a quiet moment first: then a stream that really stopped stays silent, and one that didn't
                // (8 a second) can't.
                val settle = now() + 2000
                while (now() < settle && !p.none("lux", 400)) Unit
                check("diag off → the stream stops", p.none("lux", 1000))

                // A grab-ack ok without a passed key doesn't open anything on the Mac
                p.s.grabAck(4242, true, "screenshot")
                key2.s.grabAck(4343, true, "screenshot")

                // Two light-key phones light up: the one the notch sees come and go wins, the other is told no
                Phone("Second Pixel", ep, caps = LightKeyRules.VERSION).use { p2 ->
                    p2.expect("room", 2500)
                    Thread.sleep(1700)
                    mac.signal("USR1")
                    val a = p.expect("grab", GRAB_WAIT)
                    val b = p2.expect("grab", GRAB_WAIT)
                    check("DIP asks every light-key phone", a != null && b != null && a.id() == b.id(), "$a / $b")
                    p.s.keyOn(a.id(), Src.SCREEN)
                    p2.s.keyOn(b.id(), Src.SCREEN)
                    val va = arrayOfNulls<JSONObject>(2)
                    val ta = thread { va[0] = p.key(a.id()).second }
                    val tb = thread { va[1] = p2.key(b.id()).second }
                    ta.join()
                    tb.join()
                    val kinds = va.map { it.desc() }
                    check("two phones lit: one key-ok, the other key-fail other-phone", kinds.sorted() == listOf("key-fail other-phone", "key-ok"), "$kinds")
                    mac.signal("USR2")
                    Thread.sleep(400)
                }

                // A phone that drops off while lit doesn't hold the next round up (its light counts as off)
                Phone("Flaky Pixel", ep, caps = LightKeyRules.VERSION).use { f ->
                    f.expect("room", 2500)
                    Thread.sleep(1700)
                    mac.signal("USR1")
                    val a = p.expect("grab", GRAB_WAIT)
                    val b = f.expect("grab", GRAB_WAIT)
                    f.s.keyOn(b.id(), Src.SCREEN)
                    val fs = f.expect("key-seen", 1500)
                    f.close()
                    p.s.grabAck(a.id(), false, "none", "still")
                    Thread.sleep(300)
                    mac.signal("USR2")  // and it's gone from the notch: the round can release
                    Thread.sleep(1400)
                    p.s.knock()
                    val next = p.expect("grab", GRAB_WAIT)
                    check("a phone that drops off mid-round doesn't hold up the next round", fs != null && next != null, "$fs / $next")
                    next?.let { p.s.grabAck(it.id(), false, "none", "still") }
                    mac.signal("USR2")
                    Thread.sleep(400)
                }
            }
        }
    }
    Thread.sleep(300)
    val log = mac.logText()
    check("Mac log: 'Covered 120 → 4 lux · asked Key Pixel'", log.contains("Covered 120 → 4 lux · asked Key Pixel"))
    check("Mac log: older phones need the update", log.contains("needs the TapDrop update") && log.contains("Old Pixel") && log.contains("Key2 Pixel"))
    check("Mac log: 'Light key ✓ screen 4 → 900 lux, dark again at 1 lux: its light 899 (needed 135)'",
        log.contains("Light key ✓ screen 4 → 900 lux, dark again at 1 lux: its light 899 (needed 135)"))
    check("Mac log: 'Key Pixel knocked · notch 300 lux'", log.contains("Key Pixel knocked · notch 300 lux"))
    check("Mac log: a torch doesn't count", log.contains("only the phone's screen counts"))
    check("Mac log: decline with its reason", log.contains("Key Pixel declined: still"))
    check("Mac ignored a grab-ack ok without a passed key (any light-key version)",
        log.contains("acked round 4242 without a light key; ignored") && log.contains("acked round 4343 without a light key; ignored"))
    check("Mac log: a phone that dropped off mid-key", log.contains("Light key ✗ Flaky Pixel disconnected"))
}

private fun stayRounds(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Key Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        Thread.sleep(400)
        mac.signal("USR1")
        val g = p.expect("grab", GRAB_WAIT)
        p.s.keyOn(g.id(), Src.SCREEN)
        val seen = p.expect("key-seen", 1500) { it.id() == g.id() }
        val t = now()
        p.s.keyOff(g.id())
        val v = p.expectAny(setOf("key-ok", "key-fail"), 1500) { it.id() == g.id() }
        val after = now() - t
        check("bright at key-on, still bright after key-off (a lamp, the sun, a hand that left) → key-fail stayed-lit",
            seen != null && v.desc() == "key-fail stayed-lit" && after in 380..800, "${v.desc()} after $after ms")
        p.s.grabAck(g.id(), false, "none", "no-light")
        check("no release follows a failed key", p.none("release", 600))
        mac.signal("USR2")
        Thread.sleep(500)
    }
    Thread.sleep(300)
    check("nothing was sent", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
    check("Mac log: 'the notch stayed lit'", mac.logText().contains("but the notch stayed lit at 900 lux when the phone went dark"))
}

private fun failRounds(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Key Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        Thread.sleep(400)
        mac.signal("USR1")
        val g = p.expect("grab", GRAB_WAIT)
        p.s.keyOn(g.id(), Src.SCREEN)
        val t = now()
        val f = p.expectAny(setOf("key-seen", "key-ok", "key-fail"), 1500) { it.id() == g.id() }
        val after = now() - t
        check("the notch never sees the light → key-fail not-seen ~900 ms after key-on (no key-seen)",
            f?.optString("why") == "not-seen" && after in 800..1200, "$f after $after ms")
        check("key-fail carries the numbers: peak 6 lux, need 135", f?.optDouble("peak") == 6.0 && f.optDouble("need") == 135.0)
        p.s.keyOff(g.id())
        p.s.grabAck(g.id(), false, "none", "no-light")
        check("no release follows a failed key", p.none("release", 600))
        mac.signal("USR2")
        Thread.sleep(500)
    }
    Thread.sleep(300)
    check("nothing was sent", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
    check("Mac log: 'Light key ✗ screen 4 → 6 lux (needed 135)'", mac.logText().contains("Light key ✗ screen 4 → 6 lux (needed 135)"))
}

private fun uncoverRounds(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Key Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        val room = p.expect("room", 2500)
        Thread.sleep(400)
        mac.signal("USR1")
        val g = p.expect("grab", GRAB_WAIT)
        p.s.keyOn(g.id(), Src.SCREEN)
        val f = p.expectAny(setOf("key-seen", "key-ok", "key-fail"), 1500) { it.id() == g.id() }
        // The room's light coming back looks like light at first (key-seen); when the phone goes black the notch stays
        // as bright as the room, so white − black is nothing and the black isn't blocking anything.
        p.s.keyOff(g.id())
        val v = p.expectAny(setOf("key-ok", "key-fail"), 1500) { it.id() == g.id() }
        check("bright room (B 1500): the hand leaves the covered notch as the phone lights elsewhere → the notch stays " +
            "lit when the phone goes black → key-fail stayed-lit",
            f.desc() == "key-seen" && v.desc() == "key-fail stayed-lit", "room $room, ${f.desc()} $f / ${v.desc()} $v")
        p.s.grabAck(g.id(), false, "none", "no-light")
        mac.signal("USR2")
        Thread.sleep(500)
    }
    Thread.sleep(300)
    check("nothing was sent", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
}

/** Sun on the notch: a covered round's light is the phone's steady level under the room's; the fall still counts. */
private fun sunRounds(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Sun Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        val (seen, v) = hold(p, mac, wait = 600) { gid -> p.s.grabAck(gid, true, "screenshot") }
        check("sun (B 2000), covered: its light 650 in the shade, black blocks the sun → key-ok (needs white − black ≥ 135)",
            seen.desc() == "key-seen" && v.desc() == "key-ok" && v?.optDouble("need") == 135.0, "${seen.desc()} ${seen} / ${v.desc()} $v")
    }
}

/**
 * A dark room: a knock starts the round, and the light climbs from 8 lux past the first 900 ms (Android ramping a dim
 * screen up to full). The window stretches while it climbs: the phone's light passes; a lamp that stays doesn't.
 */
private fun rampRounds(mac: TestMac, lamp: Boolean) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Ramp Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        Thread.sleep(1700)
        p.s.knock()
        val g = p.expect("grab", GRAB_WAIT)
        p.s.keyOn(g.id(), Src.SCREEN)
        val t = now()
        val (seen, v) = p.key(g.id(), 3500)
        val after = now() - t
        if (lamp) {
            check("dark room, a lamp fading up as the phone knocks: seen, but it stays lit when the phone goes black → " +
                "key-fail stayed-lit", seen.desc() == "key-seen" && v.desc() == "key-fail stayed-lit", "${seen.desc()} / ${v.desc()}")
            p.s.grabAck(g.id(), false, "none", "no-light")
        } else {
            check("dark room, a screen still brightening at 900 ms: the window stretches while it climbs → key-seen, " +
                "then black → key-ok", seen.desc() == "key-seen" && v.desc() == "key-ok" && after > 1000,
                "${seen.desc()} / ${v.desc()} after $after ms")
            p.s.grabAck(g.id(), true, "screenshot")
        }
        mac.signal("USR2")
        Thread.sleep(500)
    }
    Thread.sleep(300)
    if (lamp) check("nothing was sent", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
}

/**
 * The room goes dark around the notch (the lights going off look like a cover): the phone isn't moving, so it
 * declines. That round would wait up to 6 s for the room's light to come back, which it never does in the dark; a
 * knock meanwhile is a phone on the notch, and must start its own round at once.
 */
private fun declinedThenKnock(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Knock Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        Thread.sleep(400)
        mac.signal("USR1")
        val g = p.expect("grab", GRAB_WAIT)
        p.s.grabAck(g.id(), false, "none", "still")
        Thread.sleep(1800)
        p.s.knock()
        val gk = p.expect("grab", GRAB_WAIT)
        check("lights off, the phone declined; a knock 1.8 s later ends that round and starts its own → grab",
            gk != null && gk.id() != g.id(), "$gk")
        if (gk != null) {
            p.s.keyOn(gk.id(), Src.SCREEN)
            val (_, ok) = p.key(gk.id())
            check("…and the knock's tap passes → key-ok", ok.desc() == "key-ok", ok.desc())
            p.s.grabAck(gk.id(), true, "screenshot")
        }
        mac.signal("USR2")
        Thread.sleep(500)
    }
}

/**
 * A knock from a phone that isn't on the notch (in bed, a thumb or a pillow over its proximity sensor): the notch
 * reads the room as before, so no grab ever goes out, and the phone's screen never lights up.
 */
private fun farKnock(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Far Pixel", ep, caps = LightKeyRules.VERSION).use { p ->
        p.expect("room", 2500)
        Thread.sleep(1700)
        p.s.knock()
        check("a knock the notch doesn't see → no grab, no light", p.none("grab", 2000))
        Thread.sleep(1700)
        p.s.knock()
        check("…every time", p.none("grab", 2000))
    }
    Thread.sleep(300)
    check("Mac log: 'Far Pixel knocked, but nothing was on the notch (120 lux, unchanged): no flash'",
        mac.logText().contains("Far Pixel knocked, but nothing was on the notch (120 lux, unchanged): no flash"))
}

// MARK: 4. calibration

/** Calibration takes three holds. */
private const val HOLDS = 3

/**
 * One round started by a notch cover (USR1), answered like a real phone (key-on, then key-off on key-seen), acked
 * by [ack], then the phone leaves (USR2). Returns (key-seen, verdict).
 */
private fun hold(p: Phone, mac: TestMac, wait: Long = 1700, ack: (Int) -> Unit): Pair<JSONObject?, JSONObject?> {
    Thread.sleep(wait)
    mac.signal("USR1")
    val g = p.expect("grab", GRAB_WAIT) ?: return null to null
    p.s.keyOn(g.id(), Src.SCREEN)
    val r = p.key(g.id())
    ack(g.id())
    mac.signal("USR2")
    Thread.sleep(400)
    return r
}

private fun JSONObject?.need() = this?.optDouble("need") ?: -1.0

private fun calibrationRounds(mac: TestMac, id: String) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Cal Pixel", ep, caps = LightKeyRules.VERSION, id = id).use { p ->
        p.expect("room", 2500)
        for (n in 1..HOLDS) {
            // A phone that goes ahead anyway (grab-ack ok) must not be taken for a passed key.
            val (seen, v) = hold(p, mac, wait = if (n == 1) 600 else 1700) { gid -> p.s.grabAck(gid, true, "screenshot") }
            // (The simulated cover reading nudges B by a lux or so before the round freezes it.)
            check("hold $n of 3: rise to max(B + 150, 200) ≈ 270, the echo, then key-fail calibrating (never key-ok)",
                seen.need() in 268.0..272.0 && v.desc() == "key-fail calibrating" && v?.optDouble("peak") == 1480.0 &&
                    v.optDouble("fall") <= 72.0,
                "${seen.desc()} need ${seen.need()} / $v")
        }
        val (seen, ok) = hold(p, mac) { gid -> p.s.grabAck(gid, false, "none", "self") }
        check("after three holds (its light 1479 lux) this phone's taps need 0.45 × 1479 = 666 → key-ok",
            ok.desc() == "key-ok" && ok?.optDouble("need") == 666.0, "${seen.desc()} need ${seen.need()} / ${ok.desc()} $ok")
    }
    Thread.sleep(300)
    val saved = runCatching { JSONObject(File(mac.home, "calibration.json").readText()).optJSONObject(id) }.getOrNull()
    check("kept per phone in the app's folder: contact 1479, taps need 666, three samples",
        saved?.optDouble("contactLux") == 1479.0 && saved.optDouble("screenMin") == 666.0 && saved.optJSONArray("samples")?.length() == 3,
        "$saved")
    check("nothing was sent while calibrating", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
    val log = mac.logText()
    check("the Mac ignored the grab-ack ok after each calibration hold", (1..3).all { log.contains("acked round $it without a light key; ignored") })
    check("Mac log: 'Calibrated Cal Pixel · 1479 lux on the notch · taps now need 666'",
        log.contains("Calibrated Cal Pixel · 1479 lux on the notch · taps now need 666"))
}

private fun calibrationKept(mac: TestMac, id: String) {
    val ep = Endpoint("127.0.0.1", mac.port)
    Phone("Cal Pixel", ep, caps = LightKeyRules.VERSION, id = id).use { p ->
        p.expect("room", 2500)
        Thread.sleep(600)
        mac.signal("USR1")
        val g = p.expect("grab", GRAB_WAIT)
        p.s.keyOn(g.id(), Src.SCREEN)
        val f = p.expectAny(setOf("key-seen", "key-ok", "key-fail"), 1500) { it.id() == g.id() }
        check("after a restart the calibrated phone still needs 666 (a 420-lux light isn't seen)",
            f.desc() == "key-fail not-seen" && f.need() == 666.0, "${f.desc()} $f")
        p.s.keyOff(g.id())
        p.s.grabAck(g.id(), false, "none", "no-light")
        mac.signal("USR2")
        Thread.sleep(400)
    }
    Phone("Dim Pixel", ep, caps = LightKeyRules.VERSION).use { d ->
        d.expect("room", 2500)
        val holds = (1..HOLDS).map { hold(d, mac) { gid -> d.s.grabAck(gid, false, "none", "no-light") }.second }
        check("a dim phone's three holds (420 lux) are calibration holds",
            holds.all { it.desc() == "key-fail calibrating" && it?.optDouble("peak") == 420.0 }, holds.joinToString { it.desc() })
        val (seen, ok) = hold(d, mac) { gid -> d.s.grabAck(gid, false, "none", "self") }
        check("a dim phone (its light 419) needs 0.45 × 419 = 189 → key-ok",
            ok.desc() == "key-ok" && ok?.optDouble("need") == 189.0, "${seen.desc()} need ${seen.need()} / ${ok.desc()} $ok")
    }
    // A phone that connects (so its calibration starts) and goes away stops the calibration.
    Phone("Gone Pixel", ep, caps = LightKeyRules.VERSION).use { it.expect("room", 2500) }
    Thread.sleep(5000)
    val log = mac.logText()
    check("a phone that leaves mid-calibration stops it (after a grace period)", log.contains("Calibration stopped · Gone Pixel · Gone Pixel disconnected"),
        log.lines().filter { "Calibration" in it }.joinToString(" | ").takeLast(300))
    val all = runCatching { JSONObject(File(mac.home, "calibration.json").readText()) }.getOrNull()
    check("each phone keeps its own calibration (Cal Pixel 666, Dim Pixel 189)",
        all?.optJSONObject(id)?.optDouble("screenMin") == 666.0 && all.keySet().size == 2 &&
            all.keySet().any { all.optJSONObject(it)?.optDouble("screenMin") == 189.0 }, "$all")
    check("nothing was sent", mac.save.listFiles().isNullOrEmpty(), "${mac.save.list()?.toList()}")
}

// MARK: 5. the live link

/** A raw watch session and what arrives on it, plus whether the Mac closed it. */
private class Link(val s: WatchSession) {
    val inbox = LinkedBlockingQueue<JSONObject>()
    val closed = CountDownLatch(1)

    init {
        thread(isDaemon = true) {
            try {
                while (true) inbox.put(s.next())
            } catch (_: Exception) {
                closed.countDown()
            }
        }
    }

    fun grabs(ms: Long): List<JSONObject> {
        val end = now() + ms
        val out = mutableListOf<JSONObject>()
        while (true) {
            val left = end - now()
            if (left <= 0) return out
            val m = inbox.poll(left, TimeUnit.MILLISECONDS) ?: return out
            if (m.optString("t") == "grab") out += m
        }
    }

    fun closedWithin(ms: Long) = closed.await(ms, TimeUnit.MILLISECONDS)
}

private fun liveness(mac: TestMac) {
    val ep = Endpoint("127.0.0.1", mac.port)
    val client = TapDropClient(Identity(UUID.randomUUID().toString(), "Twin Pixel"))
    val paired = client.pair(ep) { _, _ -> }
    val key = MacKey(paired.macId, paired.key)

    // The phone's Wi-Fi blinks: it opens a new link while the Mac still holds the old one.
    val old = Link(client.watch(ep, key, caps = true))
    Thread.sleep(400)
    val fresh = Link(client.watch(ep, key, caps = true))
    check("a phone's new link closes its older one on the Mac", old.closedWithin(1500))
    fresh.s.send("t" to "ka")
    Thread.sleep(300)
    mac.signal("USR1")
    val got = fresh.grabs(1500)
    val stale = old.inbox.count { it.optString("t") == "grab" }
    check("a cover asks that phone once, on its newest link", got.size == 1 && stale == 0,
        "${got.size} grab(s) on the new link, $stale on the old")
    got.firstOrNull()?.let { fresh.s.grabAck(it.optInt("id"), false, "none", "still") }
    mac.signal("USR2")

    // A link that keeps talking stays; one gone silent past the limit (3 s here, 45 s in the app) is dropped.
    val quiet = Link(client.watch(ep, key, caps = true))
    check("(the newer link replaced the last one again)", fresh.closedWithin(1500))
    val chatty = TapDropClient(Identity(UUID.randomUUID().toString(), "Chatty Pixel")).let { c ->
        val p = c.pair(ep) { _, _ -> }
        Link(c.watch(ep, MacKey(p.macId, p.key), caps = true))
    }
    val t = now()
    var kept = true
    while (now() - t < 5500) {
        runCatching { chatty.s.send("t" to "ka") }.onFailure { kept = false }
        if (chatty.closedWithin(1000)) kept = false
    }
    check("a silent link is dropped after the silence limit", quiet.closedWithin(1500), "quiet for ${now() - t} ms")
    check("a link that sends ka keeps going", kept && chatty.closed.count == 1L)
    Thread.sleep(300)
    val log = mac.logText()
    check("Mac log: the old link closed", log.contains("Twin Pixel opened a new live link; closing its old one"))
    check("Mac log: the silent link dropped", log.contains("nothing from Twin Pixel for 3 s; dropping its live link"))
    runCatching { chatty.s.close() }
}
