package app.tapdrop.tap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max

/**
 * The frozen screen after a tap: the screenshot as a card (slightly scaled, rounded, softly vignetted, lifted on
 * a shadow) over a blurred, dimmed copy of itself. The picker draws it; when something is sent, the send overlay
 * takes the same object over, so the screen flies on without a seam.
 */
internal class FrozenScreen(private val kit: OverlayKit, val shot: Bitmap, val width: Int, val height: Int) {
    val deviceRadius = kit.displayCornerRadius()

    // The card's pose, in window px.
    val cx = Spring(width / 2f, Motion.emphasized, 0.3f)
    val cy = Spring(height / 2f, Motion.emphasized, 0.3f)
    val scale = Spring(1f, Motion.emphasized, 0.0004f)
    val radius = Spring(deviceRadius, Motion.emphasized, 0.3f)
    /** The blurred, dimmed backdrop (0 = the live app shows around the card). */
    val backdrop = Spring(0f, Motion.standard, 0.002f)
    /** Vignette and shadow strength. */
    val depth = Spring(0f, Motion.standard, 0.002f)
    /** The card itself (0 once it has flown off or faded). */
    val cardAlpha = Spring(1f, Motion.standard, 0.002f)

    private val blur: Bitmap by lazy { blurredCopy(shot) }
    private val dark = kit.dark

    private val shotShader = BitmapShader(shot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = shotShader }
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrim = Paint()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1f) }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // A soft vignette pushes the frozen screen back; lighter over light content, where it would read as grime.
    private val vignette = RadialGradient(
        0f, 0f, 1f,
        if (kit.dark) intArrayOf(0x00000000, 0x00000000, 0x14000000, 0x3D000000) else intArrayOf(0x00000000, 0x00000000, 0x0A0A0A1E, 0x1F0A0A1E),
        floatArrayOf(0f, 0.55f, 0.8f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val vm = Matrix()
    private val gm = Matrix()

    /** Where the light comes from: the camera. A soft cool glow sits behind the island and the card's top. */
    var glowX = width / 2f
    var glowY = 0f
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(Ink.alpha(Ink.light, if (kit.dark) 0.30f else 0.5f), Ink.alpha(Ink.light, if (kit.dark) 0.10f else 0.18f), Ink.alpha(Ink.light, 0f)),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val card = RectF()
    private val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
    private val clip = Path()

    private var fadeWhenFull = false

    fun step(dt: Float): Boolean {
        var moving = false
        for (s in arrayOf(cx, cy, scale, radius, backdrop, depth, cardAlpha)) moving = s.step(dt) or moving
        // Un-freezing: once the card is back at full size it matches the live app, so it can simply go.
        if (fadeWhenFull && scale.value > 0.985f && cardAlpha.target != 0f) {
            cardAlpha.to(0f, Motion.quick)
            moving = true
        }
        return moving
    }

    /**
     * The frozen screen grows back to fill the display (where it lines up with the live app), then fades. With
     * reduced motion it doesn't move: it just fades away where it is.
     */
    fun unfreeze(m: Motion) {
        if (kit.reducedMotion) {
            cardAlpha.to(0f, Motion.quick)
            backdrop.to(0f, Motion.quick)
            depth.to(0f, Motion.quick)
            return
        }
        cx.to(width / 2f, m)
        cy.to(height / 2f, m)
        scale.to(1f, m)
        radius.to(deviceRadius, m)
        backdrop.to(0f, m)
        depth.to(0f, m)
        fadeWhenFull = true
    }

    /** Where the card is now (window px). */
    fun cardRect(out: RectF = RectF()): RectF {
        val w = width * scale.value
        val h = height * scale.value
        out.set(cx.value - w / 2f, cy.value - h / 2f, cx.value + w / 2f, cy.value + h / 2f)
        return out
    }

    /** A rect in screenshot px → where it is on screen now. */
    fun toScreen(r: Rect, out: RectF = RectF()): RectF {
        cardRect(card)
        val sx = card.width() / shot.width
        val sy = card.height() / shot.height
        out.set(card.left + r.left * sx, card.top + r.top * sy, card.left + r.right * sx, card.top + r.bottom * sy)
        return out
    }

    fun toScreen(r: RectF, out: RectF = RectF()): RectF {
        cardRect(card)
        val sx = card.width() / shot.width
        val sy = card.height() / shot.height
        out.set(card.left + r.left * sx, card.top + r.top * sy, card.left + r.right * sx, card.top + r.bottom * sy)
        return out
    }

    /** Screen x → screenshot x. */
    fun shotX(x: Float): Float {
        cardRect(card)
        return (x - card.left) * shot.width / card.width()
    }

    fun shotY(y: Float): Float {
        cardRect(card)
        return (y - card.top) * shot.height / card.height()
    }

    /** Scale from screenshot px to screen px right now. */
    val shotScale: Float get() = width * scale.value / shot.width

    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean {
        cardRect(card)
        return x >= card.left - slop && x <= card.right + slop && y >= card.top - slop && y <= card.bottom + slop
    }

    // MARK: drawing

    /** The blurred, dimmed screen behind the card. */
    fun drawBackdrop(c: Canvas) {
        val a = backdrop.value
        if (a <= 0.003f) return
        blurPaint.alpha = (255 * a).toInt()
        c.drawBitmap(blur, null, full, blurPaint)
        if (dark) {
            scrim.color = Color.argb((150 * a).toInt(), 0, 0, 0)
            c.drawRect(full, scrim)
        } else {
            scrim.color = Color.argb((70 * a).toInt(), 255, 255, 255)
            c.drawRect(full, scrim)
            scrim.color = Color.argb((46 * a).toInt(), 10, 10, 30)
            c.drawRect(full, scrim)
        }
        val gr = kit.dp(320f)
        gm.setScale(gr, gr * 0.75f)
        gm.postTranslate(glowX, glowY)
        glow.setLocalMatrix(gm)
        glowPaint.shader = glow
        glowPaint.alpha = (255 * a).toInt()
        c.drawRect(full, glowPaint)
    }

    /** The card: shadow, screenshot, vignette, hairline. */
    fun drawCard(c: Canvas) {
        val a = cardAlpha.value
        if (a <= 0.003f) return
        cardRect(card)
        val rad = radius.value
        val d = depth.value
        if (d > 0.003f) {
            shadow.color = if (dark) Color.BLACK else 0xFF1A1440.toInt()
            shadow.setShadowLayer(kit.dp(28f), 0f, kit.dp(12f), Color.argb(((if (dark) 150 else 70) * d * a).toInt(), 0, 0, if (dark) 0 else 40))
            shadow.alpha = (255 * a).toInt()
            c.drawRoundRect(card, rad, rad, shadow)
        }
        m.setScale(card.width() / shot.width, card.height() / shot.height)
        m.postTranslate(card.left, card.top)
        shotShader.setLocalMatrix(m)
        cardPaint.alpha = (255 * a).toInt()
        c.drawRoundRect(card, rad, rad, cardPaint)
        if (d > 0.003f) {
            val rr = hypot(card.width(), card.height()) / 2f
            vm.setScale(rr, rr)
            vm.postTranslate(card.centerX(), card.centerY())
            vignette.setLocalMatrix(vm)
            vignettePaint.shader = vignette
            vignettePaint.alpha = (255 * d * a).toInt()
            c.drawRoundRect(card, rad, rad, vignettePaint)
            hairline.color = if (dark) 0x26FFFFFF else 0x1A000000
            hairline.alpha = (Color.alpha(hairline.color) * d * a).toInt()
            c.drawRoundRect(card, rad, rad, hairline)
        }
    }

    /** Dims the card except [hole] (a spotlight on the picked element or area). */
    fun drawSpotlight(c: Canvas, hole: RectF, holeRadius: Float, strength: Float) {
        if (strength <= 0.003f) return
        cardRect(card)
        c.save()
        clip.reset()
        clip.addRoundRect(hole, holeRadius, holeRadius, Path.Direction.CW)
        c.clipOutPath(clip)
        spot.color = if (dark) Color.argb((120 * strength.coerceIn(0f, 1f)).toInt(), 0, 0, 0)
        else Color.argb((84 * strength.coerceIn(0f, 1f)).toInt(), 18, 16, 40)
        c.drawRoundRect(card, radius.value, radius.value, spot)
        c.restore()
    }

    /** Part of the screenshot ([src], screenshot px) drawn at [dst] on screen, e.g. a lifted element. */
    fun drawRegion(c: Canvas, src: RectF, dst: RectF, rad: Float, alpha: Float, lift: Float) {
        if (alpha <= 0.003f || dst.width() < 1f) return
        if (lift > 0.003f) {
            shadow.color = if (dark) Color.BLACK else 0xFF1A1440.toInt()
            shadow.setShadowLayer(kit.dp(18f) * lift, 0f, kit.dp(8f) * lift, Color.argb(((if (dark) 170 else 80) * lift * alpha).toInt(), 0, 0, if (dark) 0 else 40))
            shadow.alpha = (255 * alpha).toInt()
            c.drawRoundRect(dst, rad, rad, shadow)
        }
        val sx = dst.width() / max(1f, src.width())
        val sy = dst.height() / max(1f, src.height())
        m.setScale(sx, sy)
        m.postTranslate(dst.left - src.left * sx, dst.top - src.top * sy)
        shotShader.setLocalMatrix(m)
        cardPaint.alpha = (255 * alpha).toInt()
        c.drawRoundRect(dst, rad, rad, cardPaint)
    }
}

/** What the picker hands to the send overlay when a pick (or the countdown) commits. */
internal class PickerHandoff(val frozen: FrozenScreen, val island: Island) {
    /** When the picker's window went up (the send overlay's window must go above it). */
    val createdAt = SystemClock.uptimeMillis()

    /** The picked element or area on screen (window px, as drawn: lifted), or null when the plan goes. */
    var pick: RectF? = null
    /** How dim the rest of the screen was around the pick. */
    var spot = 0f
    /** The same pick in screenshot px. */
    var pickShot: RectF? = null
    var pickRadius = 0f
    var pickLift = 0f
    /** The card's scale when it was picked (the pick's radius scales with the card from there). */
    var pickScale = 1f

    /** The send overlay owns the frozen screen and the island now; the picker only waits to be removed. */
    @Volatile var taken = false

    /** The send overlay's first frame is on screen (the picker can go without a flash). */
    var stageShown = false
        private set
    private val waiting = mutableListOf<() -> Unit>()

    fun whenStageShown(block: () -> Unit) {
        if (stageShown) block() else waiting += block
    }

    fun markStageShown() {
        if (stageShown) return
        stageShown = true
        waiting.toList().forEach { it() }
        waiting.clear()
    }
}

/** The picker on screen right now, if any (main thread). */
internal object Handoff {
    var picker: PickerHandoff? = null
}
