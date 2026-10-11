package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Freeze-frame picker shown right after a notch tap. The screen freezes (it's a screenshot) with a glowing
 * edge. A pill previews exactly what will be sent; once the phone leaves the notch a 1-second countdown
 * runs around it. Touching highlights the thing under the finger; tap it or drag a box to send just that.
 */
class PickOverlay(private val svc: AccessibilityService) {
    sealed interface Result {
        data object Timeout : Result
        data object Cancel : Result
        data class Tap(val x: Int, val y: Int) : Result
        data class Region(val rect: Rect) : Result
    }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private var view: PickView? = null

    /**
     * Shows the picker and suspends until the user picks, cancels, or the countdown ends.
     * [highlight] maps a touch point to the element that would be picked there (for the live outline).
     */
    suspend fun run(
        shot: Bitmap,
        released: Deferred<Unit>,
        highlight: (Int, Int) -> Rect?,
        windowMs: Long = 1000,
    ): Result = suspendCancellableCoroutine { cont ->
        val v = PickView(svc, shot, windowMs, highlight, cont)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        runCatching { wm.addView(v, lp) }.onFailure {
            cont.resume(Result.Timeout)
            return@suspendCancellableCoroutine
        }
        view = v
        // The countdown starts when the phone leaves the notch (or after 1.5 s at the latest).
        released.invokeOnCompletion { v.post { v.startCountdown() } }
        v.postDelayed({ v.startCountdown() }, 1500)
        cont.invokeOnCancellation { v.post { remove() } }
    }

    /** What goes if nothing is picked: e.g. ("Trip.mp4", "Video · the original file", thumbnail). Updated live. */
    fun preview(title: String, detail: String, thumb: Bitmap?, busy: Boolean = false) {
        view?.let { v -> v.post { v.setPreview(title, detail, thumb, busy) } }
    }

    /** Keep the countdown waiting (e.g. while the original is still being looked up). */
    fun hold(on: Boolean) {
        view?.let { v -> v.post { v.held = on } }
    }

    /** Remove the frozen frame (call once the send animation is on screen, so nothing flashes). */
    fun remove() {
        view?.let { v ->
            v.stop()
            runCatching { wm.removeView(v) }
        }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class PickView(
        ctx: Context,
        private val shot: Bitmap,
        private val windowMs: Long,
        private val highlightAt: (Int, Int) -> Rect?,
        private val cont: CancellableContinuation<Result>,
    ) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private fun dp(v: Float) = v * d
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        private val brand = intArrayOf(Color.parseColor("#409EFF"), Color.parseColor("#8561FF"), Color.parseColor("#F26BD9"), Color.parseColor("#409EFF"))
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val glowWide = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(14f) }
        private val glowThin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3.5f) }
        private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 64, 158, 255) }
        private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f) }
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F21A1B22") }
        private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.parseColor("#2EFFFFFF") }
        private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(14.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#B3FFFFFF"); textSize = dp(12f) }
        private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.parseColor("#33FFFFFF") }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
        private val xBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26FFFFFF") }
        private val thumbBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33FFFFFF") }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); color = Color.WHITE }

        private val shown = SystemClock.uptimeMillis()
        private var countdownStart = 0L
        private var paused = false
        var held = false
        private var done = false
        private var angle = 0f
        private var lastTouch = 0L

        private var title = "Screenshot"
        private var detail = "The whole screen, as you see it"
        private var thumb: Bitmap? = null
        private var thumbPaint: Paint? = null
        private var busy = false

        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var touching = false
        private var onCancelButton = false
        private var hover: Rect? = null
        private var hoverAnim = 0f
        private val sel = RectF()
        private val pill = RectF()
        private val xBtn = RectF()

        private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val band = dp(200f).toInt()
            val mid = (bottom - top) / 2
            systemGestureExclusionRects = listOf(
                Rect(0, mid - band, dp(40f).toInt(), mid + band),
                Rect(right - left - dp(40f).toInt(), mid - band, right - left, mid + band),
            )
        }

        fun setPreview(t: String, dt: String, th: Bitmap?, b: Boolean) {
            title = t
            detail = dt
            busy = b
            thumb = th
            thumbPaint = th?.let { Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) } }
        }

        fun startCountdown() {
            if (countdownStart == 0L && !done) countdownStart = SystemClock.uptimeMillis()
        }

        fun stop() = ticker.cancel()

        private fun finish(r: Result) {
            if (done) return
            done = true
            if (cont.isActive) cont.resume(r)
        }

        private fun tick() {
            angle = (angle + 2.2f) % 360f
            val now = SystemClock.uptimeMillis()
            hoverAnim = if (hover != null && touching && !dragging) min(1f, hoverAnim + 0.18f) else max(0f, hoverAnim - 0.2f)
            if (paused && now - lastTouch > 4000) {
                paused = false
                touching = false
            }
            // While the original is still being looked up, the countdown waits (up to ~2.5 s).
            if (held && countdownStart > 0 && now - countdownStart < 2500) countdownStart = min(countdownStart + 16, now)
            if (!paused && countdownStart > 0 && now - countdownStart >= windowMs) finish(Result.Timeout)
            if (now - shown > 8000) finish(Result.Timeout)
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawBitmap(shot, null, RectF(0f, 0f, w, h), bmpPaint)
            val enter = ((SystemClock.uptimeMillis() - shown) / 220f).coerceIn(0f, 1f)
            c.drawColor(Color.argb((40 * enter).toInt(), 0, 0, 0))

            val g = SweepGradient(w / 2, h / 2, brand, null)
            g.setLocalMatrix(Matrix().apply { setRotate(angle, w / 2, h / 2) })
            glowWide.shader = g
            glowThin.shader = g
            glowWide.alpha = (70 * enter).toInt()
            glowThin.alpha = (255 * enter).toInt()
            val r = dp(44f)
            val edge = RectF(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
            c.drawRoundRect(edge, r, r, glowWide)
            c.drawRoundRect(edge, r, r, glowThin)

            // live outline of what's under the finger
            hover?.let { hr ->
                if (hoverAnim > 0f) {
                    val grow = dp(6f) * hoverAnim
                    val rr = RectF(hr.left - grow, hr.top - grow, hr.right + grow, hr.bottom + grow)
                    selStroke.shader = g
                    selStroke.alpha = (255 * hoverAnim).toInt()
                    selFill.alpha = (46 * hoverAnim).toInt()
                    c.drawRoundRect(rr, dp(14f), dp(14f), selFill)
                    c.drawRoundRect(rr, dp(14f), dp(14f), selStroke)
                }
            }
            if (dragging) {
                selStroke.shader = g
                selStroke.alpha = 255
                selFill.alpha = 46
                c.drawRoundRect(sel, dp(16f), dp(16f), selFill)
                c.drawRoundRect(sel, dp(16f), dp(16f), selStroke)
            }

            // pill: [thumbnail inside the countdown ring]  title / detail  [✕]
            val headline = when {
                dragging -> "Release to send this area"
                touching && hover != null -> "Release to send this"
                else -> title
            }
            val sub = when {
                dragging || touching -> "Text comes through as text"
                countdownStart == 0L -> "Pull back · then tap anything to send just that"
                else -> detail
            }
            val ph = dp(62f)
            val maxText = w - dp(48f) - dp(64f + 50f)
            val textW = min(maxText, max(titlePaint.measureText(headline), subPaint.measureText(sub)))
            val pw = textW + dp(64f + 50f)
            val top = dp(44f) - (1 - enter) * dp(60f)
            pill.set(w / 2 - pw / 2, top, w / 2 + pw / 2, top + ph)
            c.drawRoundRect(pill, ph / 2, ph / 2, pillPaint)
            c.drawRoundRect(pill, ph / 2, ph / 2, pillStroke)

            val cx = pill.left + dp(33f)
            val cy = pill.centerY()
            val rr = dp(19f)
            val inner = dp(14.5f)
            val tp = thumbPaint
            val tb = thumb
            if (tp != null && tb != null) {
                val s = (inner * 2) / min(tb.width, tb.height).toFloat()
                tp.shader.setLocalMatrix(Matrix().apply {
                    setScale(s, s)
                    postTranslate(cx - tb.width * s / 2, cy - tb.height * s / 2)
                })
                c.drawCircle(cx, cy, inner, tp)
            } else {
                c.drawCircle(cx, cy, inner, thumbBg)
                val gw = dp(9f)
                val gh = dp(13f)
                c.drawRoundRect(RectF(cx - gw / 2, cy - gh / 2, cx + gw / 2, cy + gh / 2), dp(2f), dp(2f), glyph)
            }
            c.drawCircle(cx, cy, rr, ringTrack)
            val arc = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            if (busy || countdownStart == 0L || held) {
                c.drawArc(arc, angle * 2, 90f, false, ringPaint)
            } else if (!paused) {
                val p = ((SystemClock.uptimeMillis() - countdownStart) / windowMs.toFloat()).coerceIn(0f, 1f)
                c.drawArc(arc, -90f, 360f * (1 - p), false, ringPaint)
            }
            val tx = pill.left + dp(64f)
            c.drawText(TextUtils.ellipsize(headline, titlePaint, maxText, TextUtils.TruncateAt.MIDDLE).toString(), tx, cy - dp(3f), titlePaint)
            c.drawText(TextUtils.ellipsize(sub, subPaint, maxText, TextUtils.TruncateAt.END).toString(), tx, cy + dp(15f), subPaint)

            val xr = dp(14f)
            val xcx = pill.right - dp(28f)
            xBtn.set(xcx - xr - dp(8f), cy - xr - dp(8f), xcx + xr + dp(8f), cy + xr + dp(8f))
            c.drawCircle(xcx, cy, xr, xBg)
            val k = dp(4.5f)
            c.drawLine(xcx - k, cy - k, xcx + k, cy + k, xPaint)
            c.drawLine(xcx + k, cy - k, xcx - k, cy + k, xPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (done) return true
            lastTouch = SystemClock.uptimeMillis()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    onCancelButton = xBtn.contains(e.x, e.y)
                    paused = true
                    touching = !onCancelButton
                    hover = if (onCancelButton) null else highlightAt(e.x.toInt(), e.y.toInt())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!onCancelButton && (dragging || abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        dragging = true
                        hover = null
                        sel.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    when {
                        onCancelButton && xBtn.contains(e.x, e.y) -> finish(Result.Cancel)
                        dragging && sel.width() > dp(24f) && sel.height() > dp(16f) ->
                            finish(Result.Region(Rect(sel.left.toInt(), sel.top.toInt(), sel.right.toInt(), sel.bottom.toInt())))
                        !onCancelButton -> finish(Result.Tap(e.x.toInt(), e.y.toInt()))
                        else -> paused = false
                    }
                    dragging = dragging && !done
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    touching = false
                    hover = null
                    paused = false
                    countdownStart = SystemClock.uptimeMillis()
                }
            }
            return true
        }
    }
}
