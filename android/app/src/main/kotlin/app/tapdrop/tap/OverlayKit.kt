package app.tapdrop.tap

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.TextStyle
import androidx.core.content.res.ResourcesCompat
import app.tapdrop.R
import app.tapdrop.ui.Brand
import app.tapdrop.ui.TdMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Shared plumbing for the overlays the tap service draws over other apps: the island, the picker and the
// fly-to-Mac animation. They are Views drawn on a Canvas, so they are on screen the frame after a tap and hold
// 60 fps, and they take their type, colors, glyphs and springs from the app's design system (Theme.kt) rather
// than keeping a second style.

/** Sizes, type and settings for overlay drawing. */
internal class OverlayKit(val ctx: Context) {
    private val dm get() = ctx.resources.displayMetrics
    val density: Float get() = dm.density

    fun dp(v: Float): Float = v * density
    fun dp(v: Int): Float = v * density

    /** Scaled by the user's font size, so text in the overlays grows with it (and layouts make room). */
    fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    val geist: android.graphics.Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.geist) }.getOrNull()
        ?: android.graphics.Typeface.DEFAULT
    val geistMono: android.graphics.Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.geist_mono) }.getOrNull()
        ?: android.graphics.Typeface.MONOSPACE

    /** The system is in dark mode (the picker's chrome follows it; the island is black either way). */
    val dark: Boolean
        get() = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Settings → Accessibility → Remove animations: springs become quick fades, nothing flies. */
    val reducedMotion: Boolean
        get() = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    /** TalkBack (or another touch-exploring service) is on: timed steps wait much longer. */
    val touchExploration: Boolean
        get() = ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /**
     * A paint for one of the design system's text styles ([app.tapdrop.ui.TdType]): Geist (or Geist Mono) at the
     * style's size and weight, scaled with the user's font size. [size] overrides the size in sp.
     */
    fun text(style: TextStyle, color: Int, size: Float? = null, mono: Boolean = false, tabular: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            val sizeSp = size ?: style.fontSize.value
            typeface = if (mono) geistMono else geist
            textSize = sp(sizeSp)
            fontVariationSettings = "'wght' ${style.fontWeight?.weight ?: 400}"
            val ls = style.letterSpacing
            letterSpacing = when {
                ls.isEm -> ls.value
                ls.isSp && sizeSp > 0f -> ls.value / sizeSp
                else -> 0f
            }
            if (tabular || style.fontFeatureSettings?.contains("tnum") == true) fontFeatureSettings = "tnum"
            this.color = color
        }

    /** Line height of a style in px (scaled with font size), never less than the font's own. */
    fun lineHeight(style: TextStyle, paint: TextPaint): Float =
        max(sp(style.lineHeight.value.takeIf { !it.isNaN() } ?: (style.fontSize.value * 1.3f)), paint.fontSpacing)

    /** The display's corner radius (what the frozen screen starts from), in px. */
    fun displayCornerRadius(): Float {
        val d = display()
        val r = d?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        return if (r > 0) r.toFloat() else dp(16f)
    }

    fun display(): Display? = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    /** The status bar's height (only used when there is no camera cutout to grow from). */
    @android.annotation.SuppressLint("InternalInsetResource", "DiscouragedApi")
    fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(28f).toInt()
    }
}

/**
 * Colors for overlay drawing. Brand hues come straight from [Brand]. The island and the picker's controls are
 * pure black "like hardware" in both appearances (DESIGN.md: they grow out of the camera hole as pure black),
 * so text on them uses DESIGN.md's dark neutrals.
 */
internal object Ink {
    val blue = Brand.Blue.toArgb()
    val violet = Brand.Violet.toArgb()
    val orchid = Brand.Orchid.toArgb()
    val success = Brand.Success.toArgb()
    val warning = Brand.Warning.toArgb()
    val danger = Brand.Danger.toArgb()
    val teal = Brand.Teal.toArgb()
    val sky = Brand.Sky.toArgb()

    const val island = 0xFF000000.toInt()
    const val text = 0xFFF5F5FA.toInt()
    const val textSecondary = 0xFFA0A2B4.toInt()
    const val textTertiary = 0xFF6B6D80.toInt()

    /** The brand sweep used by progress rings (blue → violet → orchid → blue), as in ProgressRing. */
    val ring = intArrayOf(blue, violet, orchid, blue)

    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((((color ushr 24) * a.coerceIn(0f, 1f)).toInt()) shl 24)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * u).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

// MARK: - motion

/** A spring's damping ratio and stiffness (mass 1). The presets are the design system's [TdMotion] springs. */
internal class Motion(val damping: Float, val stiffness: Float) {
    companion object {
        private val fallback = Motion(1f, 400f)

        private fun of(spec: FiniteAnimationSpec<Float>): Motion =
            (spec as? SpringSpec<Float>)?.let { Motion(it.dampingRatio, it.stiffness) } ?: fallback

        /** Most state changes (0.38 / 0.86). */
        val standard = of(TdMotion.standard())
        /** Arrivals and hero moments (0.5 / 0.72). */
        val emphasized = of(TdMotion.emphasized())
        /** Presses and quick reactions. */
        val snappy = of(TdMotion.snappy())
        /** Ambient changes, no overshoot. */
        val gentle = of(TdMotion.gentle())
        /** DESIGN.md's island spring (response 0.42, damping 0.78), the same one the Mac's notch island uses. */
        val island = Motion(0.78f, 223.8f)
        /** Reduced motion: settles in about 140 ms without overshoot (TdMotion.quick's length). */
        val quick = Motion(1f, 1500f)
    }
}

/**
 * A damped spring (semi-implicit Euler in small steps, so retargeting mid-flight keeps its velocity). Values are
 * in whatever unit the caller uses; [precision] is "close enough to stop" in that unit.
 */
internal class Spring(initial: Float, var motion: Motion = Motion.standard, private val precision: Float = 0.5f) {
    var value = initial
    var target = initial
    var velocity = 0f
    val moving: Boolean get() = value != target || velocity != 0f

    fun to(t: Float, m: Motion = motion) {
        target = t
        motion = m
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun kick(v: Float) {
        velocity += v
    }

    /** Advances by [dt] seconds; false once it has settled. */
    fun step(dt: Float): Boolean {
        if (!moving) return false
        if (dt <= 0f) return true
        val k = motion.stiffness
        val c = 2f * motion.damping * sqrt(k)
        var left = dt
        while (left > 0f) {
            val h = min(left, 1f / 240f)
            val a = -k * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            left -= h
        }
        if (abs(value - target) < precision && abs(velocity) < precision * 12f) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}

/** Overlay time. Debug builds can slow it down or stop it to photograph a single frame of an animation. */
internal object OverlayClock {
    @Volatile var scale = 1f
}

/** Debug-build switches (set by the overlay lab in src/debug; always off in release). */
internal object OverlayLabFlags {
    /** Outline the camera hole: the emulator's screenshots don't show it. */
    @Volatile var showHole = false
}

/**
 * Calls [frame] once per display frame while it returns true. Starts again with [start]. It gets two steps: `dt`
 * for springs, capped so a dropped frame slows motion rather than making it jump, and `real`, the time that
 * actually passed, for anything the user waits on (a countdown, how long a result stays up).
 */
internal class Ticker(private val frame: (dt: Float, real: Float) -> Boolean) : Choreographer.FrameCallback {
    private var last = 0L
    var running = false
        private set

    fun start() {
        if (running) return
        running = true
        last = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val real = if (last == 0L) 1f / 60f else ((frameTimeNanos - last) / 1e9f).coerceIn(0f, 0.25f)
        last = frameTimeNanos
        val s = OverlayClock.scale
        if (frame(min(real, 1f / 20f) * s, real * s)) Choreographer.getInstance().postFrameCallback(this) else running = false
    }
}

// MARK: - glyphs

/** Material Symbols from `Icons.Rounded` (the app's icon set), drawn straight onto a Canvas. */
internal object Glyphs {
    private val cache = HashMap<ImageVector, Path>()

    private fun path(v: ImageVector): Path = cache.getOrPut(v) {
        val out = Path()
        fun walk(g: VectorGroup) {
            for (n in g) when (n) {
                is VectorPath -> out.addPath(n.pathData.toPath().asAndroidPath())
                is VectorGroup -> walk(n)
            }
        }
        walk(v.root)
        out
    }

    /**
     * Draws [v] centred on ([cx], [cy]). Icons made to mirror (`Icons.AutoMirrored`, e.g. lines of text) are
     * flipped in right-to-left languages, as Compose would.
     */
    fun draw(c: Canvas, v: ImageVector, cx: Float, cy: Float, size: Float, paint: Paint) {
        val p = path(v)
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        if (v.autoMirror && rtl) {
            c.translate(size, 0f)
            c.scale(-1f, 1f)
        }
        c.scale(size / v.viewportWidth, size / v.viewportHeight)
        c.drawPath(p, paint)
        c.restore()
    }

    private val rtl: Boolean
        get() = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == android.view.View.LAYOUT_DIRECTION_RTL
}

// MARK: - the camera hole

/**
 * Where the island grows from: the front camera's hole read from the display cutout (center and radius in
 * window px). Without a top cutout (or in landscape) it falls back to a point at the top center.
 */
internal data class Hole(val cx: Float, val cy: Float, val r: Float, val real: Boolean) {
    val top: Float get() = cy - r
    val bottom: Float get() = cy + r

    companion object {
        fun of(kit: OverlayKit, width: Int, statusBar: Int): Hole {
            val cut = runCatching { kit.display()?.cutout }.getOrNull()
            val top = cut?.boundingRectTop
            if (cut != null && top != null && !top.isEmpty) {
                val b = RectF(top)
                cut.cutoutPath?.let { p ->
                    val pb = RectF()
                    p.computeBounds(pb, true)
                    // The path can hold several cutouts; trust it only when it is the top one.
                    if (!pb.isEmpty && pb.top >= b.top - 2 && pb.bottom <= b.bottom + 2) b.set(pb)
                }
                val r = min(b.width(), b.height()) / 2f
                // A wide notch rather than a hole: grow from its middle, a little under its bottom edge.
                return if (b.width() > b.height() * 1.6f) {
                    Hole(b.centerX(), b.centerY(), b.height() / 2f, true)
                } else {
                    Hole(b.centerX(), b.centerY(), r, true)
                }
            }
            val cy = max(statusBar / 2f, kit.dp(14f))
            return Hole(width / 2f, cy, kit.dp(5f), false)
        }
    }
}

// MARK: - windows, text, bitmaps

/** Layout params for an accessibility overlay that covers the whole display (camera cutout included). */
internal fun overlayParams(
    touchable: Boolean,
    width: Int = WindowManager.LayoutParams.MATCH_PARENT,
    height: Int = WindowManager.LayoutParams.MATCH_PARENT,
): WindowManager.LayoutParams = WindowManager.LayoutParams(
    width,
    height,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
        (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    setFitInsetsTypes(0)
}

/** A left-aligned text block, ellipsized after [maxLines]. */
internal fun textLayout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int = 1): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .build()

/** Width the text needs on one line (capped). */
internal fun textWidth(text: CharSequence, paint: TextPaint): Float = paint.measureText(text, 0, text.length)

/** Scales and moves a bitmap shader so the bitmap covers [dst] (center crop). */
internal fun centerCrop(m: Matrix, bw: Int, bh: Int, dst: RectF, focusY: Float = 0.5f) {
    val s = max(dst.width() / bw, dst.height() / bh)
    m.setScale(s, s)
    val dy = (dst.height() - bh * s) * focusY
    m.postTranslate(dst.centerX() - bw * s / 2f, dst.top + dy)
}

/** A small, softly blurred copy of [src] (a few box-blur passes on a 1/[factor] downscale). Cheap: ~30k pixels. */
internal fun blurredCopy(src: Bitmap, factor: Int = 12, radius: Int = 5): Bitmap {
    val w = max(1, src.width / factor)
    val h = max(1, src.height / factor)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    repeat(3) {
        boxBlur(px, tmp, w, h, radius, horizontal = true)
        boxBlur(tmp, px, w, h, radius, horizontal = false)
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val k = i.coerceIn(0, len - 1)
            return if (horizontal) src[line * w + k] else src[k * w + line]
        }
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val c = at(i)
            sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        for (i in 0 until len) {
            val idx = if (horizontal) line * w + i else i * w + line
            dst[idx] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val out = at(i - r)
            val inn = at(i + r + 1)
            sa += (inn ushr 24) - (out ushr 24)
            sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            sb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** Smoothstep on [0, 1]. */
internal fun ease(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
