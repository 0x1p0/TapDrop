package app.tapdrop.core

import java.util.UUID
import kotlin.system.exitProcess

/**
 * Forgetting, both ways, against a headless test Mac started with
 * TAPDROP_TEST=1 TAPDROP_TEST_AUTOPAIR=1 TAPDROP_TEST_FORGET_LIVE=1 (it forgets a phone a second after its live link
 * opens):
 *   gradle :core:forgetCheck -Phost=127.0.0.1 -Pport=47951
 */
fun main(args: Array<String>) {
    val ep = Endpoint(args.getOrElse(0) { "127.0.0.1" }, args.getOrElse(1) { "47951" }.toInt())
    var failures = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        println((if (ok) "  PASS  " else "  FAIL  ") + name + if (detail.isNotEmpty()) "  ($detail)" else "")
        if (!ok) failures++
    }
    fun caught(block: () -> Unit): Throwable? = try { block(); null } catch (e: Throwable) { e }

    // 1. The Mac forgets a linked phone: it says so down the live link (encrypted), then again on every reconnect.
    run {
        val phone = TapDropClient(Identity(UUID.randomUUID().toString(), "Forget Pixel A"))
        val p = phone.pair(ep) { _, _ -> }
        val mac = MacKey(p.macId, p.key)
        check("ping while paired", caught { phone.ping(ep, mac) } == null)
        val w = phone.watch(ep, mac)
        val live = caught { while (true) w.next() }
        w.close()
        check("forgotten down the live link", live is ForgottenException, "${live?.javaClass?.simpleName}")
        val again = caught { phone.ping(ep, mac) }
        check("forgotten on reconnect", again is ForgottenException, "${again?.javaClass?.simpleName}")
        val watchAgain = caught { phone.watch(ep, mac).close() }
        check("forgotten on a new live link", watchAgain is ForgottenException, "${watchAgain?.javaClass?.simpleName}")
        // The phone forgets the Mac too and says so: the Mac (which already forgot it) just agrees.
        check("unpair after being forgotten", caught { phone.unpair(ep, mac) } == null)
    }

    // 2. The phone forgets the Mac: the Mac forgets the phone too, and has nothing to tell it afterwards.
    run {
        val phone = TapDropClient(Identity(UUID.randomUUID().toString(), "Forget Pixel B"))
        val p = phone.pair(ep) { _, _ -> }
        val mac = MacKey(p.macId, p.key)
        check("unpair", caught { phone.unpair(ep, mac) } == null)
        val after = caught { phone.ping(ep, mac) }
        check("unknown after unpair (not 'forgotten')", after is UnpairedException && after !is ForgottenException,
            "${after?.javaClass?.simpleName}")
        // Another Mac answering at the same address isn't this Mac turning the phone away.
        val other = caught { phone.ping(ep, MacKey("0000000000000000", p.key)) }
        check("another Mac's 'unpaired' isn't ours", other is ProtocolException, "${other?.javaClass?.simpleName}: ${other?.message}")
    }

    // 3. A wrong key never reads "forgotten" (it can't decrypt anything).
    run {
        val phone = TapDropClient(Identity(UUID.randomUUID().toString(), "Forget Pixel C"))
        val p = phone.pair(ep) { _, _ -> }
        val w = phone.watch(ep, MacKey(p.macId, p.key))
        runCatching { while (true) w.next() }
        w.close()
        val wrong = caught { phone.ping(ep, MacKey(p.macId, TDCrypto.random(32))) }
        check("a wrong key can't hear 'forgotten'", wrong is DecryptException || (wrong != null && wrong !is ForgottenException),
            "${wrong?.javaClass?.simpleName}")
    }

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}
