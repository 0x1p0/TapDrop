package app.tapdrop.core

import org.json.JSONObject

/**
 * The phone's half of the light key (PROTOCOL.md → "Light key").
 *
 * A tap only sends when the Mac's notch light sensor sees this phone's own screen: right after the phone says its
 * screen is white at full brightness ("key-on") the notch must read a level only a screen pressed on it gives
 * ("key-seen"), and right after the phone says the screen went black ("key-off") the notch must go dark with it
 * ("key-ok"). A hand or a shadow can darken the sensor but can't make light; a lamp, the sun or a hand leaving the
 * notch can't go dark on cue; a phone held a few centimetres away can't reach the level. Only the screen's light
 * counts (a torch reaches the sensor from across a desk).
 *
 * These are the phone's rules: when to knock, when to decline a grab, whether to light up, and which of the Mac's
 * answers count. Pure logic, so `gradle :core:keyCheck` checks them on the JVM. Times are
 * `SystemClock.uptimeMillis()`.
 */
object LightKeyRules {
    /**
     * Sent as `{"t":"caps","key":3}` right after a watch session opens; the Mac only asks phones with this version,
     * and its grabs carry it (`"key":3`). Version 3 is the light key with its dark echo.
     */
    const val VERSION = 3

    /** High-pass acceleration (m/s²) that counts as the phone moving. */
    const val MOTION = 1.4f
    /** Gravity low-pass time constant (s). */
    const val GRAVITY_TAU = 0.35f
    /** The proximity sensor reads near below min(5 cm, its range). */
    const val NEAR_CM = 5f

    /** A knock needs motion within the last second… */
    const val KNOCK_MOTION_MS = 1000L
    /** …the phone upright, not lying flat (|gravity z| below 8 m/s²)… */
    const val UPRIGHT_MAX_Z = 8f
    /**
     * …its screen not facing upwards (gravity z below 3 m/s², about 18° above level): the notch faces the user from a
     * display that leans back, so a screen on it faces level or a little down. One facing the ceiling (a phone held
     * to read, in bed or at a desk) can't be on the notch…
     */
    const val KNOCK_MAX_Z = 3f
    /**
     * …and nothing touched on the screen for 0.8 s: on the notch the screen faces away from its user. A thumb
     * reaching for the top of the screen covers the proximity sensor too, and must never start a round.
     */
    const val TOUCH_QUIET_MS = 800L
    /**
     * …the proximity sensor reading far for at least half a second first (a phone brought to the notch, not one
     * jostling in a pocket or under a thumb)…
     */
    const val KNOCK_FAR_MS = 500L
    /** …and 1.5 s since the last knock (the Mac starts rounds at least 1.5 s apart). */
    const val KNOCK_GAP_MS = 1500L

    /** "still": no motion for 3 s and nothing near for 1 s — a phone lying on the desk while a hand covers the notch. */
    const val STILL_MS = 3000L
    const val NEAR_RECENT_MS = 1000L
    /**
     * "cooldown": a round is already running on this phone, the picker is up, or the last tap *sent* less than 2.5 s
     * ago. A tap whose key failed doesn't hold up the next try.
     */
    const val COOLDOWN_MS = 2500L

    /** The screen faces the notch: near now, near within 700 ms, or tilted towards the display (gravity z < −1.5). */
    const val FACING_NEAR_MS = 700L
    const val FACING_Z = -1.5f
    /** The back faces the notch: not facing and gravity z > 1.5 (screen towards the user). */
    const val BACK_Z = 1.5f

    /**
     * The white screen stays on at most this long after key-on without an answer from the Mac (its rise window ends
     * 900 ms after key-on, or up to 2.5 s while a dim screen is still brightening)…
     */
    const val WHITE_MAX_MS = 3000L
    /**
     * …and the black one at most this long after key-off. It covers the Mac's whole echo with room to spare: key-off
     * may arrive up to 1 s after key-seen, and the fall is judged within 450 ms of it.
     */
    const val DARK_MAX_MS = 1600L

    /** The phone's light. Only the screen: a torch can light the notch from across a desk. */
    enum class Src(val wire: String) { SCREEN("screen") }

    /** What the phone knows when it judges a knock or a grab. */
    data class State(
        val now: Long,
        val screenOn: Boolean,
        val locked: Boolean,
        val inCall: Boolean,
        /** The accelerometer is delivering (without one, "still" can't be told). */
        val motionSensor: Boolean,
        val lastMotion: Long,
        val near: Boolean,
        /** Last time the proximity sensor read near, or stopped reading near. */
        val lastNear: Long,
        val picking: Boolean,
        /** A light-key round is running on this phone (lit, dark, or waiting for the Mac's verdict). */
        val keying: Boolean,
        /** Last time a tap's key passed and it went on to send. */
        val lastSent: Long,
        val busy: Boolean,
        /** Gravity along the screen's normal (m/s²): +9.8 face up on a table, −9.8 face down. */
        val gz: Float,
        /** Last time the user touched something on the screen (a click, a long press, typing). */
        val lastTouch: Long = NEVER,
    )

    /** Why a grab is declined before any light, checked in this order; null to go ahead. */
    fun decline(s: State): String? = when {
        !s.screenOn -> "screen-off"
        s.locked -> "locked"
        s.inCall -> "in-call"
        s.now - s.lastTouch < TOUCH_QUIET_MS -> "touching"
        s.motionSensor && s.now - s.lastMotion > STILL_MS && !s.near && s.now - s.lastNear > NEAR_RECENT_MS -> "still"
        s.picking || s.keying || s.now - s.lastSent < COOLDOWN_MS -> "cooldown"
        s.busy -> "busy"
        else -> null
    }

    sealed interface Light {
        data class Use(val src: Src) : Light
        data class Decline(val why: String) : Light
    }

    /**
     * Which way the phone faces decides whether it lights up. Only the screen's light counts, so with its back to
     * the notch the phone declines ("facing": the Mac hints to turn it around) instead of flashing at its user.
     * Any other way — facing the notch, or upright either way on a near-vertical display — it lights the screen.
     */
    fun choose(s: State): Light {
        val facingMac = s.near || s.now - s.lastNear <= FACING_NEAR_MS || s.gz < FACING_Z
        val backToMac = !facingMac && s.gz > BACK_Z
        return if (backToMac) Light.Decline("facing") else Light.Use(Src.SCREEN)
    }

    /**
     * A proximity far → near transition becomes `{"t":"knock"}` when all of these hold. [farFor] is how long the
     * proximity sensor had read far before this transition.
     */
    fun knock(s: State, live: Boolean, lastKnock: Long, farFor: Long): Boolean =
        s.now - s.lastMotion <= KNOCK_MOTION_MS &&
            farFor >= KNOCK_FAR_MS &&
            s.gz > -UPRIGHT_MAX_Z && s.gz < KNOCK_MAX_Z &&
            s.now - s.lastTouch >= TOUCH_QUIET_MS &&
            s.screenOn && !s.locked && !s.inCall && live &&
            !s.picking && !s.keying &&
            s.now - lastKnock >= KNOCK_GAP_MS &&
            s.now - s.lastSent >= COOLDOWN_MS

    /** A grab this phone can answer: one that carries this light key's version. Anything older never sends. */
    fun grabCurrent(grab: JSONObject): Boolean = grab.optInt("key", 0) >= VERSION

    /**
     * One light-key round as the phone sees it, fed with the Mac's answers for it (on this session, for this id).
     *
     * LIT (white screen, key-on sent) → "key-seen" → SEEN (turn the screen black, then report key-off) → DARK
     * (key-off sent) → "key-ok" → PASSED. Anything else ends in FAILED: a "key-fail" at any point, a "key-ok" that
     * comes before the phone went dark (no echo, no key), or a timeout. Only PASSED may send.
     */
    class Round(val id: Int) {
        enum class Step { LIT, SEEN, DARK, PASSED, FAILED }

        /** What the phone does next. */
        enum class Act { GO_DARK, PASS, FAIL, IGNORE }

        var step = Step.LIT
            private set
        /** The Mac's numbers (lux), once it sent any. */
        var peak: Float? = null
            private set
        var need: Float? = null
            private set
        /** Why it failed: the Mac's key-fail reason, "early-ok", or "timeout". */
        var why: String? = null
            private set

        val done: Boolean get() = step == Step.PASSED || step == Step.FAILED

        /**
         * The Mac ended the round as one of its calibration holds ([CALIBRATING]): the light came and went like a
         * tap's, the Mac measured it, and nothing is sent. Not an error: no error haptic, no hint.
         */
        val calibrating: Boolean get() = step == Step.FAILED && why == CALIBRATING

        /** A message from the Mac for this round. */
        fun onMac(m: JSONObject): Act {
            if (done || m.optInt("id", -1) != id) return Act.IGNORE
            m.optDouble("peak").takeIf { !it.isNaN() }?.let { peak = it.toFloat() }
            m.optDouble("need").takeIf { !it.isNaN() }?.let { need = it.toFloat() }
            return when (m.optString("t")) {
                "key-seen" -> if (step == Step.LIT) {
                    step = Step.SEEN
                    Act.GO_DARK
                } else Act.IGNORE
                "key-ok" -> if (step == Step.DARK) {
                    step = Step.PASSED
                    Act.PASS
                } else fail("early-ok")  // the Mac never saw the light go away with this phone
                "key-fail" -> fail(m.optString("why").ifEmpty { "key-fail" })
                else -> Act.IGNORE
            }
        }

        /** The screen is black and key-off went out. */
        fun wentDark() {
            if (step == Step.SEEN) step = Step.DARK
        }

        /** No answer in time, the link went away, or the light couldn't be shown. */
        fun giveUp(reason: String) {
            if (!done) fail(reason)
        }

        private fun fail(reason: String): Act {
            step = Step.FAILED
            why = reason
            return Act.FAIL
        }
    }

    /**
     * The key-fail reason for a calibration hold: while the Mac calibrates this phone (Settings → Tap to Send → Test
     * your tap → Calibrate), each round's light key ends in `{"t":"key-fail","why":"calibrating"}` once the notch has
     * seen the light come and go. Nothing is sent; the phone only ticks.
     */
    const val CALIBRATING = "calibrating"

    /** "Never" for the uptime timestamps above. */
    const val NEVER = Long.MIN_VALUE / 4
}
