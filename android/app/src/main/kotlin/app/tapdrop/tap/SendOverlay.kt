package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.tapdrop.R

/**
 * Accessibility overlay shown on top of whatever app is open:
 * the screen "lifts" off like a screenshot, then flies up into the Mac's notch,
 * and a pill drops in to confirm. Never touchable, never focusable.
 */
class SendOverlay(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val dm = svc.resources.displayMetrics
    private var root: FrameLayout? = null
    private var pill: Pill? = null
    private var removeRunnable: Runnable? = null

    private fun dp(v: Float) = v * dm.density

    private val brandA = Color.parseColor("#409EFF")
    private val brandB = Color.parseColor("#8561FF")
    private val green = Color.parseColor("#2EBF73")
    private val red = Color.parseColor("#F0525A")

    private fun ensureRoot(): FrameLayout {
        root?.let { return it }
        val r = FrameLayout(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        runCatching { wm.addView(r, lp) }.onFailure { return r }
        root = r
        return r
    }

    private fun scheduleRemove(afterMs: Long) {
        val r = root ?: return
        removeRunnable?.let { r.removeCallbacks(it) }
        val run = Runnable {
            root?.let { v -> runCatching { wm.removeView(v) } }
            root = null
            pill = null
        }
        removeRunnable = run
        r.postDelayed(run, afterMs)
    }

    /** Screenshot lifts off the screen and flies up into the Mac. */
    fun flyScreenshot(shot: Bitmap, pillText: String): Pill {
        val r = ensureRoot()
        removeRunnable?.let { r.removeCallbacks(it) }
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        // Camera-style flash
        val flash = View(svc).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        r.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ObjectAnimator.ofFloat(flash, View.ALPHA, 0f, 0.55f, 0f).apply {
            duration = 260
            addListener(onEnd { r.removeView(flash) })
            start()
        }

        val radius = dp(34f)
        val card = ImageView(svc).apply {
            setImageBitmap(shot)
            scaleType = ImageView.ScaleType.FIT_XY
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            elevation = dp(24f)
            pivotX = w / 2
            pivotY = h / 2
        }
        r.addView(card, FrameLayout.LayoutParams(w.toInt(), h.toInt()))

        // 1) lift: shrink to a floating card with a soft overshoot
        val lift = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 0.56f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 0.56f),
            )
            duration = 380
            interpolator = OvershootInterpolator(1.1f)
        }
        // 2) fly: up into the notch, shrinking to a dot
        val fly = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, 0f, -h / 2 - dp(20f)),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 0.56f, 0.06f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 0.56f, 0.06f),
                ObjectAnimator.ofFloat(card, View.ALPHA, 1f, 0.2f),
                ObjectAnimator.ofFloat(card, View.ROTATION, 0f, -4f),
            )
            duration = 420
            startDelay = 160
            interpolator = PathInterpolator(0.55f, 0f, 0.85f, 0.35f)
        }
        AnimatorSet().apply {
            playSequentially(lift, fly)
            addListener(onEnd { r.removeView(card) })
            start()
        }

        return showPill(R.drawable.ic_arrow_up, pillText, gradient = true, delayMs = 420)
    }

    /** The picked part of the screen lifts from exactly where it was, then flies up into the Mac. */
    fun flyRegion(crop: Bitmap, rect: android.graphics.Rect, pillText: String): Pill {
        val r = ensureRoot()
        removeRunnable?.let { r.removeCallbacks(it) }
        val radius = dp(18f)
        val card = ImageView(svc).apply {
            setImageBitmap(crop)
            scaleType = ImageView.ScaleType.FIT_XY
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            elevation = dp(2f)
        }
        val lp = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            leftMargin = rect.left
            topMargin = rect.top
        }
        r.addView(card, lp)
        val targetY = -(rect.top + rect.height() / 2f) + dp(30f)
        val targetX = dm.widthPixels / 2f - (rect.left + rect.width() / 2f)
        val lift = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, 1.07f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, 1.07f),
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Z, 0f, dp(26f)),
            )
            duration = 260
            interpolator = OvershootInterpolator(2f)
        }
        val fly = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, 0f, targetY),
                ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0f, targetX),
                ObjectAnimator.ofFloat(card, View.SCALE_X, 1.07f, 0.08f),
                ObjectAnimator.ofFloat(card, View.SCALE_Y, 1.07f, 0.08f),
                ObjectAnimator.ofFloat(card, View.ALPHA, 1f, 0.2f),
            )
            duration = 420
            startDelay = 120
            interpolator = PathInterpolator(0.55f, 0f, 0.85f, 0.35f)
        }
        AnimatorSet().apply {
            playSequentially(lift, fly)
            addListener(onEnd { r.removeView(card) })
            start()
        }
        return showPill(R.drawable.ic_arrow_up, pillText, gradient = true, delayMs = 300)
    }

    /** A chip (link, text) pops in the middle, then flies up into the Mac. */
    fun flyChip(icon: Int, chipText: String, pillText: String): Pill {
        val r = ensureRoot()
        removeRunnable?.let { r.removeCallbacks(it) }
        val chip = chip(icon, chipText)
        r.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        popAndFly(chip)
        return showPill(icon, pillText, gradient = true, delayMs = 380)
    }

    /** A photo card pops in the middle, then flies up (used for "latest photo"). */
    fun flyCard(image: Bitmap, pillText: String): Pill {
        val r = ensureRoot()
        removeRunnable?.let { r.removeCallbacks(it) }
        val side = (dm.widthPixels * 0.62f).toInt()
        val aspect = image.height.toFloat() / image.width
        val card = ImageView(svc).apply {
            setImageBitmap(image)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(26f))
            }
            clipToOutline = true
            elevation = dp(24f)
        }
        r.addView(card, FrameLayout.LayoutParams(side, (side * aspect.coerceIn(0.6f, 1.6f)).toInt(), Gravity.CENTER))
        popAndFly(card)
        return showPill(R.drawable.ic_arrow_up, pillText, gradient = true, delayMs = 380)
    }

    private fun popAndFly(v: View) {
        val r = root ?: return
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        val pop = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.4f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.4f, 1f),
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
            )
            duration = 360
            interpolator = OvershootInterpolator(1.6f)
        }
        val fly = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -dm.heightPixels / 2f),
                ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 0.12f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 0.12f),
                ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0f),
            )
            duration = 400
            startDelay = 240
            interpolator = AccelerateInterpolator(1.6f)
        }
        AnimatorSet().apply {
            playSequentially(pop, fly)
            addListener(onEnd { r.removeView(v) })
            start()
        }
    }

    /** One-line status pill (sending off, blocked, …). */
    fun notice(icon: Int, text: String, error: Boolean = false): Pill {
        val p = showPill(icon, text, gradient = !error, delayMs = 0)
        if (error) p.tint(red)
        p.dismissAfter(2600)
        return p
    }

    /** A one-time code just went to the Mac. */
    fun code(text: String): Pill = showPill(R.drawable.ic_key, text, gradient = true, delayMs = 0)

    /** Something arrived from the Mac while you were in another app. */
    fun received(title: String, thumb: Bitmap?) {
        val p = showPill(R.drawable.ic_arrow_down, title, gradient = true, delayMs = 0, thumb = thumb)
        p.finish(ok = true, text = title, holdMs = 2600)
    }

    private fun chip(icon: Int, text: String): LinearLayout = LinearLayout(svc).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(20f).toInt(), dp(12f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(26f)
            colors = intArrayOf(brandA, brandB)
            orientation = GradientDrawable.Orientation.TL_BR
        }
        elevation = dp(16f)
        addView(ImageView(svc).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(26f).toInt(), dp(26f).toInt()))
        addView(TextView(svc).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10f).toInt(), 0, 0, 0)
            maxLines = 1
        })
    }

    private fun showPill(icon: Int, text: String, gradient: Boolean, delayMs: Long, thumb: Bitmap? = null): Pill {
        val r = ensureRoot()
        pill?.view?.let { r.removeView(it) }
        val p = Pill(icon, text, thumb)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        lp.topMargin = dp(44f).toInt()
        r.addView(p.view, lp)
        p.view.translationY = -dp(120f)
        p.view.alpha = 0f
        p.view.animate().translationY(0f).alpha(1f).setStartDelay(delayMs).setDuration(520)
            .setInterpolator(OvershootInterpolator(1.3f)).start()
        pill = p
        scheduleRemove(15_000)
        return p
    }

    inner class Pill(icon: Int, text: String, thumb: Bitmap?) {
        private val badge: ImageView
        private val label: TextView
        private val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(brandA, brandB)
            orientation = GradientDrawable.Orientation.TL_BR
        }
        val view: LinearLayout = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(20f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(30f)
                setColor(Color.parseColor("#F21A1B22"))
                setStroke(dp(1f).toInt(), Color.parseColor("#22FFFFFF"))
            }
            elevation = dp(18f)
        }

        init {
            if (thumb != null) {
                view.addView(ImageView(svc).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10f))
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt()).apply { marginEnd = dp(6f).toInt() })
            }
            badge = ImageView(svc).apply {
                setImageResource(icon)
                background = badgeBg
                val pad = dp(7f).toInt()
                setPadding(pad, pad, pad, pad)
            }
            view.addView(badge, LinearLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt()))
            label = TextView(svc).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(12f).toInt(), 0, 0, 0)
                maxLines = 1
                maxWidth = (dm.widthPixels * 0.62f).toInt()
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            view.addView(label)
            // gentle breathing while in flight
            ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = 520
                repeatMode = ValueAnimator.REVERSE
                repeatCount = 5
                interpolator = DecelerateInterpolator()
                addUpdateListener { badge.scaleX = it.animatedValue as Float; badge.scaleY = it.animatedValue as Float }
                start()
            }
        }

        fun update(text: String) {
            view.post { if (label.text != text) label.text = text }
        }

        fun tint(color: Int) {
            badgeBg.colors = intArrayOf(color, color)
        }

        fun dismissAfter(ms: Long) {
            view.postDelayed({
                view.animate().translationY(-dp(140f)).alpha(0f).setDuration(360)
                    .setInterpolator(AccelerateInterpolator(1.4f))
                    .withEndAction { scheduleRemove(0) }
                    .start()
            }, ms)
        }

        /** Morph to ✓ (or ✕), hold, then slide away. */
        fun finish(ok: Boolean, text: String, holdMs: Long = 1500) {
            view.post {
                badge.animate().cancel()
                badge.scaleX = 1f
                badge.scaleY = 1f
                badge.setImageResource(if (ok) R.drawable.ic_check else R.drawable.ic_close)
                badgeBg.colors = if (ok) intArrayOf(green, Color.parseColor("#1FA463")) else intArrayOf(red, Color.parseColor("#D63A4A"))
                badge.animate().scaleX(1.25f).scaleY(1.25f).setDuration(140).withEndAction {
                    badge.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
                label.animate().alpha(0f).setDuration(120).withEndAction {
                    label.text = text
                    label.animate().alpha(1f).setDuration(180).start()
                }.start()
                view.animate().translationY(-dp(140f)).alpha(0f).setStartDelay(holdMs).setDuration(360)
                    .setInterpolator(AccelerateInterpolator(1.4f))
                    .withEndAction { scheduleRemove(0) }
                    .start()
            }
        }
    }

    private fun onEnd(block: () -> Unit) = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = block()
    }
}
