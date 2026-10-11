package app.tapdrop.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/** Rich haptics using the Pixel's composition primitives, with sensible fallbacks. */
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

    /** NFC sticker detected: a solid "thunk". */
    fun tap(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_HEAVY_CLICK,
        P(Composition.PRIMITIVE_THUD, 1f), P(Composition.PRIMITIVE_TICK, 0.7f, 70),
    )

    /** Transfer finished: rising swell then a crisp click. */
    fun success(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_DOUBLE_CLICK,
        P(Composition.PRIMITIVE_QUICK_RISE, 0.45f), P(Composition.PRIMITIVE_CLICK, 1f, 20),
    )

    fun error(ctx: Context) = play(
        ctx, VibrationEffect.EFFECT_DOUBLE_CLICK,
        P(Composition.PRIMITIVE_CLICK, 0.9f), P(Composition.PRIMITIVE_CLICK, 0.9f, 100),
    )

    fun tick(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK, P(Composition.PRIMITIVE_LOW_TICK, 0.8f))

    fun press(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK, P(Composition.PRIMITIVE_CLICK, 0.5f))
}
