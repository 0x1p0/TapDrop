package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.text.StaticLayout
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Videocam
import app.tapdrop.ui.TdType
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The freeze-frame shown right after a tap. The screen freezes into a card (slightly scaled, rounded, softly
 * vignetted) over a blurred copy of itself, and the island grows out of the camera hole saying what will go
 * ("Sends the page link"). Once the phone leaves the notch, a ring around the arrow drains for a few seconds and the
 * plan goes. Touching lights up the thing under the finger (a spotlight, the element lifts on a glowing outline);
 * lifting the finger sends just that, and dragging marks an area. Cancel sits at the bottom, in thumb reach.
 */
class PickOverlay(private val svc: AccessibilityService) {
    sealed interface Result {
        data object Timeout : Result
        data object Cancel : Result
        data class Tap(val x: Int, val y: Int) : Result
        data class Region(val rect: Rect) : Result
    }

    /** What the countdown sends. */
    enum class Kind { LINK, TEXT, FILE, PHOTO, VIDEO, SCREEN, SCREEN_TEXT }

    /**
     * The thing under the finger: its box in screenshot px, and its words or file name when it has them (so the
     * island can say exactly what lifting the finger sends).
     */
    data class Target(val rect: Rect, val text: String? = null, val file: String? = null)

    /**
     * What goes if nothing is picked: [detail] is the page's host, "212 words", "Report.pdf · 2 MB", the photo's
     * name; [note] explains a fallback ("Couldn't find this photo in your gallery"); [finding] while the original
     * is still being looked up.
     */
    data class Plan(
        val kind: Kind,
        val detail: String? = null,
        val thumb: Bitmap? = null,
        val note: String? = null,
        val finding: Boolean = false,
    )

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val kit = OverlayKit(svc)
    private var root: FrameLayout? = null
    private var view: PickView? = null

    /**
     * Shows the picker and suspends until the user picks, cancels, or the countdown ends.
     * [highlight] maps a screenshot point to the element that would be picked there (for the live outline).
     */
    suspend fun run(
        shot: Bitmap,
        released: Deferred<Unit>,
        highlight: (Int, Int) -> Target?,
        windowMs: Long = WINDOW_MS,
    ): Result = suspendCancellableCoroutine { cont ->
        val v = PickView(shot, windowMs, highlight, cont)
        val r = FrameLayout(svc)
        r.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        r.addView(v.cancel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        r.setOnApplyWindowInsetsListener { _, insets ->
            v.applyInsets(insets)
            insets
        }
        try {
            wm.addView(r, overlayParams(touchable = true).apply { title = "TapDrop picker" })
        } catch (_: Exception) {
            cont.resume(Result.Timeout)
            return@suspendCancellableCoroutine
        }
        root = r
        view = v
        Handoff.picker = v.handoff
        // The countdown starts when the phone leaves the notch (or after 1.5 s at the latest).
        released.invokeOnCompletion { v.post { v.release() } }
        v.after(1.5f) { v.release() }
        cont.invokeOnCancellation { v.post { remove() } }
    }

    /** What goes if nothing is picked. Updated live. */
    fun preview(plan: Plan) {
        view?.let { v -> v.post { v.setPlan(plan) } }
    }

    /** Keep the countdown waiting (e.g. while the original is still being looked up). */
    fun hold(on: Boolean) {
        view?.let { v -> v.post { v.hold(on) } }
    }

    /**
     * Removes the frozen frame. After a send took it over, the window goes as soon as the send overlay is on
     * screen (so nothing flashes); otherwise the screen un-freezes back into the live app first.
     */
    fun remove() {
        val v = view ?: return
        view = null
        if (Handoff.picker === v.handoff) Handoff.picker = null
        val r = root
        root = null
        var gone = false
        val detach = {
            if (!gone) {
                gone = true
                v.stop()
                r?.let { runCatching { wm.removeViewImmediate(it) } }
            }
        }
        if (v.handoff.taken) {
            v.stop()
            v.handoff.whenStageShown { detach() }
            v.postDelayed({ detach() }, 300)
        } else {
            v.exit { detach() }
            v.postDelayed({ detach() }, 900)
        }
    }

    // MARK: - the view

    @SuppressLint("ViewConstructor")
    private inner class PickView(
        private val shot: Bitmap,
        windowMs: Long,
        private val highlightAt: (Int, Int) -> Target?,
        private val cont: CancellableContinuation<Result>,
    ) : View(svc) {
        private val screenW: Int
        private val screenH: Int
        private val pad = kit.dp(6f)
        val frozen: FrozenScreen
        val island: Island
        val handoff: PickerHandoff
        val cancel = CancelButton()

        private val slop = ViewConfiguration.get(svc).scaledTouchSlop
        private var navInset = 0
        private var islandDown = false
        private val windowSec = windowMs / 1000f * (if (kit.touchExploration) 6f else 1f)
        private val ticker = Ticker { dt, real -> frame(dt, real) }
        private var time = 0f
        private val timers = mutableListOf<Pair<Float, () -> Unit>>()
        private var started = false
        private var exiting = false
        private var onExited: (() -> Unit)? = null

        // countdown
        private var countFrom = -1f
        private var held = false
        private var heldSince = 0f
        private var touching = false
        private var done = false

        // plan
        private var plan = Plan(Kind.SCREEN)

        // touch, hover, area
        private var downX = 0f
        private var downY = 0f
        private var outside = false
        private var dragging = false
        private var hover: Rect? = null
        private var hoverTarget: Target? = null
        private var hoverCrop: Bitmap? = null
        /** What was picked: an element under the finger, or an area (its crop shows in the island). */
        private var pickedCrop: Bitmap? = null
        private var pickedArea = false
        private val hoverOn = Spring(0f, Motion.snappy, 0.002f)
        private val grow = Spring(0f, Motion.snappy, 0.2f)
        private val lift = Spring(0f, Motion.emphasized, 0.001f)
        private val sel = RectF()
        /** The area being dragged, in screenshot px ([sel] is where it is on screen now). */
        private val selShot = RectF()
        private var downSX = 0
        private var downSY = 0
        private val selOn = Spring(0f, Motion.snappy, 0.002f)
        private var spin = 0f

        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(2.5f) }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = kit.dp(7f)
            maskFilter = BlurMaskFilter(kit.dp(9f), BlurMaskFilter.Blur.NORMAL)
        }
        private val sweep = SweepGradient(0f, 0f, Ink.ring, null)
        private val sm = Matrix()
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
        private val tmp = RectF()
        private val src = RectF()

        init {
            val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
            screenW = b?.width()?.takeIf { it > 0 } ?: svc.resources.displayMetrics.widthPixels
            screenH = b?.height()?.takeIf { it > 0 } ?: svc.resources.displayMetrics.heightPixels
            frozen = FrozenScreen(kit, shot, screenW, screenH)
            val hole = Hole.of(kit, screenW, kit.statusBarHeight())
            frozen.glowX = hole.cx
            frozen.glowY = hole.cy + kit.dp(36f)
            island = Island(kit, hole, screenW)
            island.showHole = OverlayLabFlags.showHole
            handoff = PickerHandoff(frozen, island)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            // The first frame is the screen exactly as it was; the freeze starts from there.
            viewTreeObserver.registerFrameCommitCallback { post { enter() } }
            refreshIsland()
            ticker.start()
        }

        fun applyInsets(insets: WindowInsets) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            navInset = nav
            (cancel.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                val m = nav + kit.dp(CANCEL_BOTTOM).toInt()
                if (lp.bottomMargin != m) {
                    lp.bottomMargin = m
                    cancel.layoutParams = lp
                }
            }
        }

        private fun enter() {
            if (started) return
            started = true
            if (kit.reducedMotion) {
                for (s in arrayOf(frozen.scale, frozen.cy, frozen.radius)) s.motion = Motion.quick
            }
            island.show(content())
            // The screen sinks back into the space between the island and Cancel, so the three never overlap.
            val top = island.targetBounds().bottom + kit.dp(12f)
            // (Cancel's view has 8 dp of room around the pill for its shadow.)
            val bottom = screenH - navInset - kit.dp(CANCEL_BOTTOM + 8f + CANCEL_H + 12f)
            val k = min((bottom - top) / screenH, (screenW - kit.dp(40f)) / screenW).coerceIn(0.55f, 0.92f)
            frozen.scale.to(k)
            frozen.cy.to(if (bottom - top >= screenH * k) (top + bottom) / 2f else top + screenH * k / 2f)
            frozen.radius.to(kit.dp(26f))
            frozen.backdrop.to(1f)
            frozen.depth.to(1f)
            cancel.show()
            ticker.start()
            // If nobody answers (the service went away), the picker cancels itself rather than freeze the screen.
            postDelayed({ if (!done) finish(Result.Cancel) }, 30_000)
        }

        fun after(seconds: Float, block: () -> Unit) {
            timers += (time + seconds) to block
            ticker.start()
        }

        fun release() {
            if (countFrom < 0f && !done) {
                countFrom = time
                refreshIsland()
            }
        }

        fun hold(on: Boolean) {
            if (on && !held) heldSince = time
            held = on
            refreshIsland()
        }

        fun setPlan(p: Plan) {
            plan = p
            refreshIsland()
        }

        fun stop() {
            ticker.stop()
        }

        private fun finish(r: Result) {
            if (done) return
            done = true
            cancel.hide()
            island.press(false)
            if (cont.isActive) cont.resume(r)
            refreshIsland()
            ticker.start()
            // The service removes the picker once it knows what to send; this only catches a picker it forgot.
            postDelayed({ if (view === this) remove() }, 12_000)
        }

        fun exit(then: () -> Unit) {
            if (handoff.taken) return then()
            exiting = true
            onExited = then
            done = true
            frozen.unfreeze(Motion.standard)
            hoverOn.to(0f)
            selOn.to(0f)
            lift.to(0f)
            island.collapse()
            cancel.hide()
            ticker.start()
        }

        // MARK: frame

        private fun frame(dt: Float, real: Float): Boolean {
            if (handoff.taken) return false
            // [time] is wall-clock: the countdown lasts its seconds however busy the phone is.
            time += real
            var moving = frozen.step(dt)
            moving = island.step(dt) or moving
            for (s in arrayOf(hoverOn, grow, lift, selOn)) moving = s.step(dt) or moving
            moving = cancel.step(dt) or moving
            if (hoverOn.value > 0.01f || selOn.value > 0.01f) {
                spin = (spin + dt * 80f) % 360f
                moving = true
            }
            if (timers.isNotEmpty()) {
                val due = timers.filter { it.first <= time }
                timers.removeAll(due)
                due.forEach { it.second() }
                moving = true
            }
            if (exiting) {
                if (frozen.cardAlpha.value < 0.01f && !island.visible) {
                    exiting = false
                    onExited?.invoke()
                    onExited = null
                    return false
                }
                invalidate()
                return true
            }
            if (!done && started) {
                // The countdown waits while the original is looked up (up to 2.5 s), and while a finger is down.
                if (held && countFrom >= 0f && time - heldSince < 2.5f) countFrom += real
                if (touching && countFrom >= 0f) countFrom += real
                if (countFrom >= 0f) {
                    val left = 1f - (time - countFrom) / windowSec
                    island.countdown(left.coerceIn(0f, 1f), paused = touching)
                    if (left <= 0f) finish(Result.Timeout)
                } else {
                    island.countdown(null, paused = false)
                }
                // However the original search goes, the plan goes in the end (unless a finger is still choosing).
                if (!touching && time > max(8f, windowSec + 6f)) finish(Result.Timeout)
                moving = true
            }
            invalidate()
            return moving || !done
        }

        // MARK: island

        private fun refreshIsland() {
            if (!started && !island.visible) return
            if (handoff.taken || exiting) return
            island.show(content())
            contentDescription = content().speech + if (!done) ". Double-tap to send it now." else ""
        }

        private fun leadFor(p: Plan): Lead = when (p.kind) {
            Kind.LINK -> Lead.Symbol(Icons.Rounded.Language, Ink.sky)
            Kind.TEXT, Kind.SCREEN_TEXT -> Lead.Symbol(Icons.AutoMirrored.Rounded.Notes, Ink.violet)
            Kind.FILE -> Lead.Symbol(Icons.Rounded.Description, Ink.blue)
            Kind.PHOTO -> p.thumb?.let { Lead.Image(it) } ?: Lead.Symbol(Icons.Rounded.Image, Ink.orchid)
            Kind.VIDEO -> p.thumb?.let { Lead.Image(it) } ?: Lead.Symbol(Icons.Rounded.Videocam, Ink.orchid)
            Kind.SCREEN -> Lead.Image(shot)
        }

        private fun content(): IslandContent {
            val p = plan
            val trail = when {
                done -> Trail.Progress
                p.finding -> Trail.Progress
                else -> Trail.Countdown
            }
            if (done && pickedArea) {
                val lead = pickedCrop?.let { Lead.Image(it) } ?: Lead.Symbol(Icons.Rounded.Crop, Ink.violet)
                return IslandContent(lead, "Sending this area", null, trail)
            }
            if (dragging) {
                return IslandContent(Lead.Symbol(Icons.Rounded.Crop, Ink.violet), "Sends this area", "Any words in it arrive as text", trail)
            }
            if (touching || (done && hover != null)) return hoverContent(trail)
            if (p.finding) {
                val what = when (p.kind) {
                    Kind.VIDEO -> "the original video"
                    Kind.FILE -> p.detail ?: "the file"
                    else -> "the original photo"
                }
                return IslandContent(leadFor(p), "Finding $what…", if (p.kind == Kind.FILE) "Looking on your phone" else "Looking in your gallery", trail)
            }
            val verb = if (done) "Sending" else "Sends"
            val title = when (p.kind) {
                Kind.LINK -> "$verb the page link"
                Kind.TEXT, Kind.SCREEN_TEXT -> "$verb the text on screen"
                Kind.FILE -> "$verb this file"
                Kind.PHOTO -> "$verb the original photo"
                Kind.VIDEO -> "$verb the original video"
                Kind.SCREEN -> "$verb the whole screen"
            }
            val sub = p.note ?: p.detail ?: when (p.kind) {
                Kind.SCREEN -> if (done) null else "Or tap something to send just that"
                Kind.SCREEN_TEXT -> "Your Mac reads it from a screenshot"
                else -> null
            }
            return IslandContent(leadFor(p), title, sub, trail)
        }

        /** The island while a finger rests on something: what it is, in its own words where it has them. */
        private fun hoverContent(trail: Trail): IslandContent {
            val t = hoverTarget
            val words = t?.text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            val file = t?.file
            return when {
                file != null -> IslandContent(
                    Lead.Symbol(Icons.Rounded.Description, Ink.blue),
                    file,
                    if (done) "Sending the file" else "Lift your finger to send this file",
                    trail,
                )
                words != null -> IslandContent(
                    Lead.Symbol(Icons.AutoMirrored.Rounded.Notes, Ink.violet),
                    "\u201C" + (if (words.length > 44) words.take(42).trimEnd() + "\u2026" else words) + "\u201D",
                    if (done) "Copying it to your Mac" else "Lift your finger to copy it to your Mac",
                    trail,
                    spoken = (if (done) "Copying " else "Copies ") + words,
                )
                else -> {
                    // A picture-like thing shows itself; a sliver (a divider, a long bar) would only be a smudge.
                    val crop = hoverCrop
                    val aspect = crop?.let { it.width.toFloat() / max(1, it.height) } ?: 0f
                    val lead = if (crop != null && aspect in 0.45f..2.2f) Lead.Image(crop) else Lead.Symbol(Icons.Rounded.TouchApp, Ink.violet)
                    IslandContent(lead, if (done) "Sending this" else "Sends this", if (done) null else "Lift your finger to send it", trail)
                }
            }
        }

        // MARK: drawing

        /** The last frame drawn before the send overlay took over (see [onDraw]). */
        private val lastFrame = android.graphics.RenderNode("TapDrop picker")

        override fun onDraw(c: Canvas) {
            // Once the send overlay owns the frozen screen and the island, this window only waits to be removed:
            // it keeps showing its last frame, so nothing flickers while the send overlay's window comes up.
            if (handoff.taken && lastFrame.hasDisplayList() && c.isHardwareAccelerated) {
                c.drawRenderNode(lastFrame)
                return
            }
            if (!c.isHardwareAccelerated) return drawScene(c)
            lastFrame.setPosition(0, 0, width, height)
            val rc = lastFrame.beginRecording(width, height)
            try {
                drawScene(rc)
            } finally {
                lastFrame.endRecording()
            }
            c.drawRenderNode(lastFrame)
        }

        private fun drawScene(c: Canvas) {
            frozen.drawBackdrop(c)
            frozen.drawCard(c)
            val hv = hoverOn.value
            val sv = selOn.value
            val h = hover
            if (h != null && hv > 0.003f) {
                hoverRect(h, tmp)
                val rad = kit.dp(12f) * frozen.shotScale + grow.value * 0.4f
                frozen.drawSpotlight(c, tmp, rad, hv * 0.85f)
                val g = (pad + grow.value) / frozen.shotScale
                src.set(h.left - g, h.top - g, h.right + g, h.bottom + g)
                if (lift.value > 0.003f) frozen.drawRegion(c, src, tmp, rad, hv, lift.value)
                drawOutline(c, tmp, rad, hv)
            }
            if (!selShot.isEmpty) frozen.toScreen(selShot, sel)
            if (sv > 0.003f && sel.width() > 1f) {
                val rad = kit.dp(14f)
                frozen.drawSpotlight(c, sel, rad, sv)
                if (lift.value > 0.003f && dragging.not() && done) {
                    src.set(frozen.shotX(sel.left), frozen.shotY(sel.top), frozen.shotX(sel.right), frozen.shotY(sel.bottom))
                    frozen.drawRegion(c, src, sel, rad, sv, lift.value)
                }
                drawOutline(c, sel, rad, sv)
                if (!done) drawHandles(c, sel, sv)
            }
            island.draw(c)
            island.drawCamera(c)
        }

        /** The hovered element on screen: its box (plus a little air), growing in, lifted when picked. */
        private fun hoverRect(h: Rect, out: RectF) {
            frozen.toScreen(h, out)
            val g = pad + grow.value
            out.inset(-g, -g)
            val k = 1f + 0.035f * lift.value
            val cx = out.centerX()
            val cy = out.centerY()
            out.set(cx - out.width() / 2 * k, cy - out.height() / 2 * k, cx + out.width() / 2 * k, cy + out.height() / 2 * k)
        }

        private fun drawOutline(c: Canvas, r: RectF, rad: Float, a: Float) {
            sm.setRotate(spin - 90f)
            sm.postTranslate(r.centerX(), r.centerY())
            sweep.setLocalMatrix(sm)
            glow.shader = sweep
            glow.alpha = (150 * a).toInt()
            c.drawRoundRect(r, rad, rad, glow)
            outline.shader = sweep
            outline.alpha = (255 * a).toInt()
            c.drawRoundRect(r, rad, rad, outline)
        }

        private fun drawHandles(c: Canvas, r: RectF, a0: Float) {
            // The corners only make sense once there is an area: they fade in as it opens up.
            val a = a0 * ((min(r.width(), r.height()) - kit.dp(14f)) / kit.dp(22f)).coerceIn(0f, 1f)
            if (a <= 0.003f) return
            val hr = kit.dp(5f)
            handle.alpha = (255 * a).toInt()
            handleRing.alpha = (60 * a).toInt()
            for (x in floatArrayOf(r.left, r.right)) for (y in floatArrayOf(r.top, r.bottom)) {
                c.drawCircle(x, y, hr + kit.dp(1.5f), handleRing)
                c.drawCircle(x, y, hr, handle)
            }
        }

        // MARK: touch

        override fun performClick(): Boolean {
            super.performClick()
            // TalkBack's double-tap: send the plan now.
            if (!done) finish(Result.Timeout)
            return true
        }

        /** Screen point → screenshot point, kept on the screenshot. */
        private fun shotPoint(x: Float, y: Float): Pair<Int, Int> =
            frozen.shotX(x).toInt().coerceIn(0, shot.width - 1) to frozen.shotY(y).toInt().coerceIn(0, shot.height - 1)

        /** Sends what's under [sx], [sy] (the element there, or that spot). */
        private fun tapAt(sx: Int, sy: Int) {
            setHover(highlightAt(sx, sy)?.takeIf { it.rect.width() > 2 && it.rect.height() > 2 })
            val h = hover
            if (h != null) {
                hoverRect(h, tmp)
                commit(RectF(tmp).also { liftedRect(it) }, kit.dp(12f) * frozen.shotScale + grow.target * 0.4f)
            }
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            finish(Result.Tap(sx, sy))
        }

        /**
         * Touch rules, so a pick never goes unnoticed:
         * - a finger anywhere holds the countdown;
         * - lifting without sliding sends what's under the finger, and so does a short slide (a sloppy tap);
         * - sliding further marks an area, even from just outside the screen or along a single line of text;
         * - only a plain tap well away from the screen (or Cancel) cancels.
         */
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (done) return true
            if (!started) enter()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    dragging = false
                    selShot.setEmpty()
                    islandDown = island.bounds(tmp).contains(e.x, e.y)
                    if (islandDown) {
                        // The island is the plan: touching it sends that now.
                        island.press(true)
                        performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                        return true
                    }
                    touching = true
                    val (sx, sy) = shotPoint(e.x, e.y)
                    downSX = sx
                    downSY = sy
                    outside = !frozen.contains(e.x, e.y, slop = kit.dp(EDGE))
                    if (!outside) {
                        setHover(highlightAt(sx, sy)?.takeIf { it.rect.width() > 2 && it.rect.height() > 2 })
                        performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    }
                    refreshIsland()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (islandDown) {
                        island.press(island.bounds(tmp).contains(e.x, e.y))
                        return true
                    }
                    if (!touching) return true
                    if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        dragging = true
                        setHover(null)
                        selOn.snap(0f)
                        selOn.to(1f)
                        performHapticFeedback(HapticFeedbackConstants.DRAG_START)
                        refreshIsland()
                    }
                    if (dragging) {
                        val (sx, sy) = shotPoint(e.x, e.y)
                        selShot.set(min(downSX, sx).toFloat(), min(downSY, sy).toFloat(), max(downSX, sx).toFloat(), max(downSY, sy).toFloat())
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (islandDown) {
                        islandDown = false
                        island.press(false)
                        if (island.bounds(tmp).contains(e.x, e.y)) {
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            finish(Result.Timeout)
                        }
                        return true
                    }
                    if (!touching) return true
                    touching = false
                    if (dragging) {
                        dragging = false
                        val k = frozen.shotScale
                        if (max(selShot.width(), selShot.height()) * k >= kit.dp(AREA_MIN)) {
                            // A slide along a line of text is thin: the area is at least a fingertip tall and wide.
                            val m = kit.dp(AREA_SIDE) / k
                            if (selShot.width() < m) selShot.inset((selShot.width() - m) / 2f, 0f)
                            if (selShot.height() < m) selShot.inset(0f, (selShot.height() - m) / 2f)
                            selShot.offset(max(0f, -selShot.left) - max(0f, selShot.right - shot.width), max(0f, -selShot.top) - max(0f, selShot.bottom - shot.height))
                            selShot.intersect(0f, 0f, shot.width.toFloat(), shot.height.toFloat())
                            val r = Rect(selShot.left.toInt(), selShot.top.toInt(), selShot.right.toInt(), selShot.bottom.toInt())
                            frozen.toScreen(selShot, sel)
                            commit(RectF(sel), kit.dp(14f))
                            pickedArea = true
                            pickedCrop = crop(r)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            finish(Result.Region(r))
                            return true
                        }
                        // Too short to be an area: it was a tap that slid a little.
                        selShot.setEmpty()
                        selOn.to(0f)
                        if (outside) {
                            refreshIsland()
                            return true
                        }
                        tapAt(downSX, downSY)
                        return true
                    }
                    if (outside) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        finish(Result.Cancel)
                        return true
                    }
                    tapAt(downSX, downSY)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    dragging = false
                    islandDown = false
                    island.press(false)
                    setHover(null)
                    selShot.setEmpty()
                    selOn.to(0f)
                    if (countFrom >= 0f) countFrom = time
                    refreshIsland()
                }
            }
            return true
        }

        /** The rect as it will look once fully lifted (what the send overlay takes over). */
        private fun liftedRect(r: RectF) {
            val k = (1f + 0.035f) / (1f + 0.035f * lift.value)
            val cx = r.centerX()
            val cy = r.centerY()
            r.set(cx - r.width() / 2 * k, cy - r.height() / 2 * k, cx + r.width() / 2 * k, cy + r.height() / 2 * k)
        }

        private fun commit(screen: RectF, radius: Float) {
            lift.to(1f)
            handoff.pickShot = RectF(frozen.shotX(screen.left), frozen.shotY(screen.top), frozen.shotX(screen.right), frozen.shotY(screen.bottom))
            handoff.pickScale = frozen.scale.value
            handoff.pick = screen
            handoff.pickRadius = radius
            handoff.pickLift = 1f
            handoff.spot = 0.85f
        }

        private fun crop(b: Rect): Bitmap? {
            val c = Rect(b)
            if (!c.intersect(0, 0, shot.width, shot.height) || c.width() <= 8 || c.height() <= 8) return null
            return runCatching { Bitmap.createBitmap(shot, c.left, c.top, c.width(), c.height()) }.getOrNull()
        }

        fun cancelNow() {
            if (!done) finish(Result.Cancel)
        }

        private fun setHover(t: Target?) {
            val r = t?.rect
            if (r == hover) return
            hover = r
            hoverTarget = t
            hoverCrop = r?.let { crop(it) }
            if (r != null) {
                hoverOn.snap(0f)
                hoverOn.to(1f)
                grow.snap(kit.dp(12f))
                grow.to(0f)
            } else {
                hoverOn.to(0f)
            }
            invalidate()
        }
    }

    // MARK: - cancel

    /** A black capsule at the bottom: ✕ Cancel. */
    @SuppressLint("ViewConstructor")
    private inner class CancelButton : View(svc) {
        private val label: StaticLayout
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.island }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1f); color = 0x1FFFFFFF }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.text }
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val y = Spring(kit.dp(40f), Motion.emphasized, 0.3f)
        private val a = Spring(0f, Motion.standard, 0.002f)
        private val press = Spring(1f, Motion.snappy, 0.0005f)
        private val h = kit.dp(48f)
        private val r = RectF()

        init {
            val p = kit.text(TdType.label, Ink.text)
            label = textLayout("Cancel", p, textWidth("Cancel", p).toInt() + 2, 1)
            contentDescription = "Cancel"
            isClickable = true
            alpha = 0f
            translationY = y.value
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = kit.dp(18f) + kit.dp(20f) + kit.dp(8f) + label.width + kit.dp(22f)
            setMeasuredDimension((w + kit.dp(16f)).toInt(), (h + kit.dp(16f)).toInt())
        }

        fun show() {
            y.to(0f)
            a.to(1f)
        }

        fun hide() {
            y.to(kit.dp(30f), Motion.standard)
            a.to(0f)
            isEnabled = false
        }

        fun step(dt: Float): Boolean {
            var m = y.step(dt)
            m = a.step(dt) or m
            m = press.step(dt) or m
            translationY = y.value
            alpha = a.value
            scaleX = press.value
            scaleY = press.value
            return m
        }

        override fun onDraw(c: Canvas) {
            val inset = kit.dp(8f)
            r.set(inset, inset, width - inset, height - inset)
            shadow.setShadowLayer(kit.dp(12f), 0f, kit.dp(4f), if (kit.dark) 0x99000000.toInt() else 0x40000000)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, shadow)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, edge)
            val gx = r.left + kit.dp(18f) + kit.dp(10f)
            Glyphs.draw(c, Icons.Rounded.Close, gx, r.centerY(), kit.dp(20f), glyph)
            c.save()
            c.translate(gx + kit.dp(10f) + kit.dp(8f), r.centerY() - label.height / 2f)
            label.draw(c)
            c.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!isEnabled) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> press.to(0.94f)
                MotionEvent.ACTION_UP -> {
                    press.to(1f)
                    if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick()
                }
                MotionEvent.ACTION_CANCEL -> press.to(1f)
            }
            (parent as? View)?.invalidate()
            view?.let { it.invalidate() }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            view?.cancelNow()
            return true
        }
    }

    private companion object {
        /** Cancel's height and its distance from the navigation bar, in dp. */
        const val CANCEL_H = 48f
        const val CANCEL_BOTTOM = 10f
        /** Time to pick once the phone leaves the notch (paused while a finger is down). */
        const val WINDOW_MS = 2500L
        /** Touches this close to the screen (dp) still count as on it. */
        const val EDGE = 24f
        /** A slide this long (dp) marks an area; shorter is a tap. */
        const val AREA_MIN = 24f
        /** An area is at least this wide and tall (dp). */
        const val AREA_SIDE = 36f
    }
}
