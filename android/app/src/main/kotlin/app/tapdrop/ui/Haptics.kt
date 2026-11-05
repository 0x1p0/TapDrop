package app.tapdrop.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics for every meaningful state change.
 *
 * - From UI code, use [rememberHaptics]: it goes through the view, so it follows the user's
 *   "touch feedback" setting and feels like the rest of Android.
 * - From services and background work (no view), use the [Haptics] object: rich composition
 *   primitives on Pixels, with predefined effects as a fallback.
 */
object Haptics {
    private data class P(val id: Int, val scale: Float, val delay: Int = 0)

    private fun vibrator(ctx: Context): Vibrator? =
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator?.takeIf { it.hasVibrator() }

    private fun play(ctx: Context, fallback: Int, vararg prims: P) {
        val v = vibrator(ctx) ?: return
        runCatching {
            if (v.areAllPrimitivesSupported(*prims.map { it.id }.toIntArray())) {
                val c = VibrationEffect.startComposition()
                prims.forEach { c.addPrimitive(it.id, it.scale, it.delay) }
                v.vibrate(c.compose())
            } else {
                v.vibrate(VibrationEffect.createPredefined(fallback))
            }
        }
    }

    /** A physical contact (the phone met the notch, a sticker was read): a solid "thunk". */
    fun tap(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_HEAVY_CLICK,
        P(Composition.PRIMITIVE_THUD, 1f), P(Composition.PRIMITIVE_TICK, 0.7f, 70),
    )

    /** Something finished well (transfer done, paired): a rising swell, then a crisp click. */
    fun success(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_DOUBLE_CLICK,
        P(Composition.PRIMITIVE_QUICK_RISE, 0.45f), P(Composition.PRIMITIVE_CLICK, 1f, 20),
    )

    /** Something failed: two even clicks. */
    fun error(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_DOUBLE_CLICK,
        P(Composition.PRIMITIVE_CLICK, 0.9f), P(Composition.PRIMITIVE_CLICK, 0.9f, 100),
    )

    /** A small step (a value crossed a threshold, a list settled). */
    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK, P(Composition.PRIMITIVE_LOW_TICK, 0.8f))

    /** A light press. */
    fun press(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK, P(Composition.PRIMITIVE_CLICK, 0.5f))

    /** Light arriving: a soft spin-up then a click (the light key was accepted). */
    fun lightKey(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_CLICK,
        P(Composition.PRIMITIVE_SLOW_RISE, 0.35f), P(Composition.PRIMITIVE_CLICK, 0.8f, 10),
    )
}

/**
 * View-based haptics for UI. Get one with [rememberHaptics].
 *
 * ```
 * val haptics = rememberHaptics()
 * Button(onClick = { haptics.click(); … })
 * ```
 */
@Stable
class UiHaptics internal constructor(private val view: View) {
    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }

    /** Taps on buttons, tiles and rows. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A tiny detent: segmented choices, sliders, list snapping. */
    fun tick() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
    )

    /** A switch or toggle changed. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 && on -> HapticFeedbackConstants.TOGGLE_ON
            Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        },
    )

    /** A positive outcome the user caused (a step completed, something confirmed). */
    fun confirm() = perform(HapticFeedbackConstants.CONFIRM)

    /** A refusal or error the user caused. */
    fun reject() = perform(HapticFeedbackConstants.REJECT)

    /** A long press or a gesture crossing its commit threshold. */
    fun heavy() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.LONG_PRESS,
    )

    /** A drag started (sheets, reordering). */
    fun dragStart() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.CONTEXT_CLICK,
    )
}

/** View-based haptics for the current screen. See [UiHaptics]. */
@Composable
fun rememberHaptics(): UiHaptics {
    val view = LocalView.current
    return remember(view) { UiHaptics(view) }
}
