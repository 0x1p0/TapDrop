package app.tapdrop.tap

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.ui.graphics.vector.ImageVector
import app.tapdrop.ui.TdType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The island's left side: what is being sent or received. */
internal sealed interface Lead {
    /**
     * A picture (screenshot, photo, crop). Portrait pictures keep a portrait slot, like a little phone screen.
     * [stack] > 1 fans a couple of cards out behind it (several things arrived).
     */
    class Image(val bitmap: Bitmap, val stack: Int = 1, val badge: Boolean = false) : Lead

    /** A symbol on a tinted disc; [solid] fills the disc and draws the symbol white. */
    data class Symbol(val icon: ImageVector, val tint: Int, val solid: Boolean = false) : Lead
}

/** The island's right side. */
internal sealed interface Trail {
    data object None : Trail
    /** Transfer progress on the brand ring (set the value with [Island.progress]; null spins). */
    data object Progress : Trail
    /** The picker's countdown: the ring drains around an up arrow (see [Island.countdown]). */
    data object Countdown : Trail
    /** The ring closes into a green disc and a check draws itself. */
    data object Done : Trail
    /** A red disc with a cross; the island shakes. */
    data object Failed : Trail
    /** A gradient capsule ("Open"). */
    data class Button(val label: String) : Trail
}

internal enum class IslandShape {
    /** One row around the camera: picture, camera, ring. For long transfers. */
    Compact,
    /** A card hanging from the camera: picture, title and subtitle under the camera, ring or button. */
    Expanded,
}

internal data class IslandContent(
    val lead: Lead?,
    val title: String,
    val subtitle: String? = null,
    val trail: Trail = Trail.None,
    val shape: IslandShape = IslandShape.Expanded,
    /** The title is a one-time code: big monospaced digits. */
    val code: Boolean = false,
    /** Compact only: a short value right of the camera ("42%"). */
    val right: String? = null,
    /** What TalkBack says (defaults to title and subtitle). */
    val spoken: String? = null,
) {
    val speech: String get() = spoken ?: listOfNotNull(title, subtitle).joinToString(". ")
}

/**
 * The Dynamic-Island-style pill: a pure black shape that grows out of the camera hole, morphs between contents
 * on springs, and shrinks back into the hole. Not a View: the overlay that owns it steps it every frame
 * ([step]) and draws it ([draw]), which lets the picker hand its island to the send overlay mid-animation.
 */
internal class Island(private val kit: OverlayKit, hole: Hole, screenW: Int) {
    var hole: Hole = hole
        private set
    private var screenW = screenW

    // MARK: geometry (window px)

    private val l = Spring(hole.cx - hole.r, Motion.island)
    private val t = Spring(hole.top, Motion.island)
    private val r = Spring(hole.cx + hole.r, Motion.island)
    private val b = Spring(hole.bottom, Motion.island)
    private val corner = Spring(hole.r, Motion.island)
    private val alpha = Spring(0f, Motion.quick, 0.002f)
    private val contentAlpha = Spring(0f, Motion.standard, 0.002f)
    private val squashX = Spring(1f, Motion(0.42f, 560f), 0.0004f)
    private val squashY = Spring(1f, Motion(0.42f, 560f), 0.0004f)
    private val shake = Spring(0f, Motion(0.2f, 1300f), 0.15f)
    private val pressScale = Spring(1f, Motion.snappy, 0.0005f)

    /** Showing (anything other than tucked into the hole). */
    var visible = false
        private set
    var content: IslandContent? = null
        private set
    private var slots: Slots? = null
    private var collapsing = false
    private var onCollapsed: (() -> Unit)? = null
    private var revealWait = 0f
    private var revealed = false
    private var reduced = kit.reducedMotion
    private val box = RectF()

    /** Debug builds: draw where the camera hole is (screenshots don't show it). */
    var showHole = false

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.island }
    private val clip = Path()
    private val goo: RuntimeShader? = runCatching { RuntimeShader(GOO) }.getOrNull()
    private val gooPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gooBounds = RectF()

    // MARK: elements

    private val titlePaint = kit.text(TdType.subtitle, Ink.text)
    private val subPaint = kit.text(TdType.bodySmall, Ink.textSecondary)
    private val codePaint = kit.text(TdType.mono, Ink.text, size = 30f, mono = true).apply { letterSpacing = 0.06f }
    private val rightPaint = kit.text(TdType.label, Ink.textSecondary, tabular = true)
    private val buttonPaint = kit.text(TdType.label, Ink.night)

    private val lead = LeadSlot()
    private val title = TextSlot()
    private val subtitle = TextSlot()
    private val right = TextSlot()
    private val trail = TrailSlot()

    /** Set while something is flying into the lead slot: the slot stays empty until it lands. */
    var leadPending = false
        set(v) {
            field = v
            if (!v) lead.landed()
        }

    fun resize(h: Hole, w: Int) {
        if (h == hole && w == screenW) return
        hole = h
        screenW = w
        content?.let { place(it, animate = false) }
    }

    // MARK: API

    /** Grows out of the hole (or morphs from what is showing) into [c]. */
    fun show(c: IslandContent, animate: Boolean = !kit.reducedMotion) {
        reduced = !animate
        val wasVisible = visible && !collapsing
        collapsing = false
        onCollapsed = null
        visible = true
        if (!wasVisible) {
            revealed = false
            revealWait = 0f
            if (alpha.value < 0.05f) {
                snapToHole()
                contentAlpha.snap(0f)
            }
            alpha.to(1f, Motion.quick)
        }
        content = c
        place(c, animate)
        if (!animate) {
            snapGeometry()
            alpha.snap(1f)
            contentAlpha.to(1f, Motion.quick)
            revealed = true
        }
    }

    /** Shrinks back into the hole; [done] runs once it's gone. */
    fun collapse(done: (() -> Unit)? = null) {
        if (!visible) {
            done?.invoke()
            return
        }
        collapsing = true
        onCollapsed = done
        contentAlpha.to(0f, if (reduced) Motion.quick else Motion(1f, 900f))
        if (reduced) alpha.to(0f, Motion.quick)
    }

    /** Transfer progress (0..1), or null to spin. */
    fun progress(p: Float?) = trail.setProgress(p)

    /** The picker's countdown: [left] is the time left (1 → 0), null while it waits to start. */
    fun countdown(left: Float?, paused: Boolean) = trail.setCountdown(left, paused)

    /** Something landed in the island: a little squash, like catching it. */
    fun impact(strength: Float = 1f) {
        if (reduced) return
        squashY.kick(1.6f * strength)
        squashX.kick(-0.7f * strength)
    }

    /** A "no" shake. */
    fun refuse() {
        if (reduced) return
        shake.kick(kit.dp(520f))
    }

    /** The island's current outline in window px (for touch targets). */
    fun bounds(out: RectF = RectF()): RectF = out.apply { set(l.value, t.value, r.value, b.value) }

    /** Where the island will settle. */
    fun targetBounds(out: RectF = RectF()): RectF = out.apply { set(l.target, t.target, r.target, b.target) }

    /** Where the lead picture or symbol is right now (window px) and its corner radius. */
    fun leadRect(out: RectF = RectF()): Float {
        out.set(l.value + lead.x.value, t.value + lead.y.value, l.value + lead.x.value + lead.w.value, t.value + lead.y.value + lead.h.value)
        return lead.radius.value
    }

    /** The lead slot once everything settles. */
    fun leadTarget(out: RectF = RectF()): Float {
        val s = slots ?: return 0f.also { out.set(hole.cx, hole.cy, hole.cx, hole.cy) }
        val lr = s.lead ?: return 0f.also { out.set(hole.cx, hole.cy, hole.cx, hole.cy) }
        out.set(s.rect.left + lr.left, s.rect.top + lr.top, s.rect.left + lr.right, s.rect.top + lr.bottom)
        return s.leadRadius
    }

    val settled: Boolean get() = !l.moving && !r.moving && !t.moving && !b.moving

    /** The content has started to show (the shape has grown enough). */
    val contentRevealed: Boolean get() = revealed && !collapsing

    /** Gone at once, no animation (another overlay took over the spot). */
    fun hide() {
        visible = false
        collapsing = false
        content = null
        slots = null
        onCollapsed = null
        alpha.snap(0f)
        contentAlpha.snap(0f)
    }

    /** Pressed (an island you can open): it gives a little. */
    fun press(on: Boolean) {
        pressScale.to(if (on) 0.965f else 1f, Motion.snappy)
    }

    /** The lead picture or symbol arrives with a pop (something came from the Mac). */
    fun popLead() {
        if (!reduced) lead.pop()
    }

    // MARK: layout

    private class Slots(
        val rect: RectF,
        val radius: Float,
        /** Relative to the island's left/top. */
        val lead: RectF?,
        val leadRadius: Float,
        val textX: Float,
        val titleY: Float,
        val subY: Float,
        val textW: Int,
        val titleLines: Int,
        val subLines: Int,
        /** Trail centre: from the island's right edge, and from its top. */
        val trailFromRight: Float,
        val trailY: Float,
        val trailSize: Float,
        val trailW: Float,
        val rightLabelFromRight: Float,
        val rightLabelY: Float,
    )

    private fun leadAspect(lead: Lead?): Float = when (lead) {
        is Lead.Image -> (lead.bitmap.width.toFloat() / max(1, lead.bitmap.height)).coerceIn(0.62f, 1.3f)
        else -> 1f
    }

    private fun layout(c: IslandContent): Slots {
        fun dp(v: Float) = kit.dp(v)
        val margin = dp(8f)
        val maxW = screenW - margin * 2
        if (c.shape == IslandShape.Compact) {
            val h = max(dp(36f), hole.r * 2 + dp(12f))
            val top = max(dp(3f), hole.cy - h / 2)
            val inner = h - dp(12f)
            val leadW = if (c.lead == null) 0f else inner * leadAspect(c.lead)
            val trailSize = if (c.trail == Trail.None) 0f else h - dp(14f)
            val rightW = c.right?.let { textWidth(it, rightPaint) } ?: 0f
            val leftExtent = dp(6f) + leadW + dp(12f) + hole.r
            val rightExtent = hole.r + dp(12f) + (if (rightW > 0) rightW + dp(8f) else 0f) + trailSize + dp(7f)
            val half = max(max(leftExtent, rightExtent), dp(64f))
            val rect = centered(half * 2, top, h)
            val leadRect = if (c.lead == null) null else RectF(dp(6f), (h - inner) / 2, dp(6f) + leadW, (h + inner) / 2)
            val leadRadius = if (c.lead is Lead.Image) dp(8f) else inner / 2
            return Slots(
                rect, h / 2, leadRect, leadRadius,
                textX = 0f, titleY = 0f, subY = 0f, textW = 1, titleLines = 1, subLines = 1,
                trailFromRight = dp(7f) + trailSize / 2, trailY = h / 2, trailSize = trailSize, trailW = trailSize,
                rightLabelFromRight = dp(7f) + trailSize + dp(8f), rightLabelY = h / 2,
            )
        }

        // Expanded: a band that holds the camera (like the Mac's island under its notch), then a row with the
        // content. It spans the screen like an expanded Dynamic Island, so it never half-covers the clock.
        val pl = dp(14f)
        val pr = dp(14f)
        val pb = dp(14f)
        val gap = dp(12f)
        val top = if (hole.real) max(dp(4f), hole.top - dp(6f)) else dp(8f)
        val rowTop = if (hole.real) hole.bottom + dp(7f) else top + dp(14f)

        val leadH = when (c.lead) {
            null -> 0f
            is Lead.Image -> dp(48f)
            is Lead.Symbol -> dp(44f)
        }
        val leadW = leadH * leadAspect(c.lead)
        val (trailW, trailH) = when (val tr = c.trail) {
            Trail.None -> 0f to 0f
            is Trail.Button -> (textWidth(tr.label, buttonPaint) + dp(30f)) to dp(34f)
            else -> dp(32f) to dp(32f)
        }
        val tp = if (c.code) codePaint else titlePaint
        // a fanned stack needs room for the cards that peek out behind the picture
        val fan = if ((c.lead as? Lead.Image)?.stack ?: 1 > 1) dp(14f) else 0f
        val leadSpace = if (leadW > 0) leadW + gap + fan else 0f
        val trailSpace = if (trailW > 0) trailW + gap else 0f
        val width = min(maxW, dp(440f))
        val textW = (width - pl - pr - leadSpace - trailSpace).toInt().coerceAtLeast(1)

        val titleLayout = textLayout(c.title, tp, textW, if (c.code) 1 else 2)
        val subLayout = c.subtitle?.let { textLayout(it, subPaint, textW, 2) }
        val titleH = titleLayout.height.toFloat()
        val subGap = if (c.code) dp(0f) else dp(1f)
        val textH = titleH + (subLayout?.let { subGap + it.height } ?: 0f)
        val rowH = max(max(leadH, textH), trailH)
        val rowY = rowTop - top
        val h = rowY + rowH + pb
        val rect = centered(width, top, h)
        val leadRect = if (c.lead == null) null else RectF(pl, rowY + (rowH - leadH) / 2, pl + leadW, rowY + (rowH + leadH) / 2)
        val leadRadius = if (c.lead is Lead.Image) dp(12f) else leadH / 2
        val textTop = rowY + (rowH - textH) / 2
        return Slots(
            rect, min(dp(36f), h / 2), leadRect, leadRadius,
            textX = pl + leadSpace,
            titleY = textTop,
            subY = textTop + titleH + subGap,
            textW = textW,
            titleLines = if (c.code) 1 else 2,
            subLines = 2,
            trailFromRight = pr + trailW / 2,
            trailY = rowY + rowH / 2,
            trailSize = trailH,
            trailW = trailW,
            rightLabelFromRight = 0f,
            rightLabelY = 0f,
        )
    }

    /** A rect of [w] centred on the camera, kept on screen. */
    private fun centered(w: Float, top: Float, h: Float): RectF {
        val m = kit.dp(8f)
        val left = (hole.cx - w / 2).coerceIn(m, max(m, screenW - m - w))
        return RectF(left, top, left + w, top + h)
    }

    private fun place(c: IslandContent, animate: Boolean) {
        val s = layout(c)
        slots = s
        val m = if (animate) Motion.island else Motion.quick
        l.to(s.rect.left, m); t.to(s.rect.top, m); r.to(s.rect.right, m); b.to(s.rect.bottom, m)
        corner.to(s.radius, m)

        lead.set(c.lead, s.lead, s.leadRadius, animate && revealedOnce())
        if (c.shape == IslandShape.Expanded) {
            title.set(c.title, if (c.code) codePaint else titlePaint, s.textW, s.titleLines, s.textX, s.titleY, animate && revealedOnce())
            subtitle.set(c.subtitle, subPaint, s.textW, s.subLines, s.textX, s.subY, animate && revealedOnce())
            right.set(null, rightPaint, 1, 1, 0f, 0f, animate)
        } else {
            title.set(null, titlePaint, 1, 1, 0f, 0f, animate)
            subtitle.set(null, subPaint, 1, 1, 0f, 0f, animate)
            val rw = c.right?.let { textWidth(it, rightPaint).toInt() + 2 } ?: 1
            right.set(c.right, rightPaint, rw, 1, 0f, s.rightLabelY, animate, instant = right.hasText)
            right.fromRight = s.rightLabelFromRight + rw
        }
        trail.set(c.trail, s, animate && revealedOnce())
    }

    private fun revealedOnce() = revealed

    private fun snapToHole() {
        l.snap(hole.cx - hole.r); r.snap(hole.cx + hole.r); t.snap(hole.top); b.snap(hole.bottom)
        corner.snap(hole.r)
        squashX.snap(1f); squashY.snap(1f); shake.snap(0f)
    }

    private fun snapGeometry() {
        l.snap(l.target); r.snap(r.target); t.snap(t.target); b.snap(b.target); corner.snap(corner.target)
        lead.snapAll(); title.snapAll(); subtitle.snapAll(); right.snapAll(); trail.snapAll()
    }

    // MARK: frame

    /** Advances everything by [dt] seconds; true while anything still moves. */
    fun step(dt: Float): Boolean {
        if (!visible) return false
        var moving = false
        for (s in arrayOf(l, t, r, b, corner, alpha, contentAlpha, squashX, squashY, shake, pressScale)) moving = s.step(dt) or moving

        if (!collapsing && !revealed) {
            revealWait += dt
            val s = slots
            val grown = if (s == null) 1f else {
                val w0 = hole.r * 2
                ((r.value - l.value) - w0) / max(1f, s.rect.width() - w0)
            }
            if (grown > 0.55f || revealWait > 0.3f) {
                revealed = true
                contentAlpha.to(1f, Motion.standard)
                moving = true
            }
        }
        if (collapsing && contentAlpha.value < 0.35f && l.target != hole.cx - hole.r) {
            val m = Motion.standard
            l.to(hole.cx - hole.r, m); r.to(hole.cx + hole.r, m); t.to(hole.top, m); b.to(hole.bottom, m)
            corner.to(hole.r, m)
            moving = true
        }
        if (collapsing && !reduced && abs(r.value - l.value - hole.r * 2) < kit.dp(3f) && alpha.target != 0f) {
            alpha.to(0f, Motion.quick)
            moving = true
        }
        if (collapsing && alpha.target == 0f && alpha.value < 0.01f) {
            visible = false
            collapsing = false
            content = null
            slots = null
            val done = onCollapsed
            onCollapsed = null
            done?.invoke()
            return false
        }
        moving = lead.step(dt) or moving
        moving = title.step(dt) or moving
        moving = subtitle.step(dt) or moving
        moving = right.step(dt) or moving
        moving = trail.step(dt) or moving
        return moving
    }

    // MARK: drawing

    /**
     * Draws the island. [blob] is another black shape (a chip flying in) that melts into the island as it
     * gets close (a smooth union of the two shapes).
     */
    fun draw(c: Canvas, blob: RectF? = null, blobRadius: Float = 0f, blobInk: Float = 1f) {
        if (!visible) return
        val a = alpha.value
        if (a <= 0.003f) return
        val rect = box
        rect.set(l.value, t.value, r.value, b.value)
        if (rect.width() < 0.5f || rect.height() < 0.5f) return
        val rad = min(corner.value, min(rect.width(), rect.height()) / 2f)
        c.save()
        c.translate(shake.value, 0f)
        c.scale(squashX.value, squashY.value, rect.centerX(), rect.top)
        if (pressScale.value != 1f) c.scale(pressScale.value, pressScale.value, rect.centerX(), rect.centerY())

        val g = goo
        if (blob != null && g != null && c.isHardwareAccelerated) {
            val k = kit.dp(22f) * blobInk
            g.setFloatUniform("a", rect.left, rect.top, rect.right, rect.bottom)
            g.setFloatUniform("ar", rad)
            g.setFloatUniform("b", blob.left, blob.top, blob.right, blob.bottom)
            g.setFloatUniform("br", blobRadius)
            g.setFloatUniform("k", k)
            g.setColorUniform("ink", Color.argb((255 * a).toInt(), 0, 0, 0))
            gooPaint.shader = g
            gooBounds.set(rect)
            gooBounds.union(blob)
            gooBounds.inset(-k - 4f, -k - 4f)
            c.drawRect(gooBounds, gooPaint)
        } else {
            shapePaint.alpha = (255 * a).toInt()
            c.drawRoundRect(rect, rad, rad, shapePaint)
            if (blob != null) c.drawRoundRect(blob, blobRadius, blobRadius, shapePaint)
        }

        val ca = contentAlpha.value * a
        if (ca > 0.003f && slots != null) {
            c.save()
            clip.reset()
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            lead.draw(c, rect, ca)
            title.draw(c, rect, ca)
            subtitle.draw(c, rect, ca)
            right.draw(c, rect, ca)
            trail.draw(c, rect, ca)
            c.restore()
        }
        c.restore()
    }

    private val lensBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0F.toInt() }
    private val lensGlass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensGlint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Debug builds: draws the front camera where the display cutout says it is, so screenshots (which never show
     * the real lens) look like the phone does. Owners call it last, over everything.
     */
    fun drawCamera(c: Canvas) {
        if (!showHole || !hole.real) return
        val r = hole.r
        c.drawCircle(hole.cx, hole.cy, r, lensBody)
        lensGlass.shader = RadialGradient(
            hole.cx - r * 0.12f, hole.cy - r * 0.12f, r * 0.62f,
            intArrayOf(0xFF26304F.toInt(), 0xFF111628.toInt(), 0xFF040507.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(hole.cx, hole.cy, r * 0.6f, lensGlass)
        lensGlint.color = 0x59FFFFFF
        c.drawCircle(hole.cx - r * 0.2f, hole.cy - r * 0.22f, r * 0.13f, lensGlint)
        lensGlint.color = 0x335B6CFF
        c.drawCircle(hole.cx + r * 0.2f, hole.cy + r * 0.2f, r * 0.09f, lensGlint)
    }

    // MARK: - lead slot

    private inner class LeadSlot {
        val x = Spring(0f, Motion.island)
        val y = Spring(0f, Motion.island)
        val w = Spring(0f, Motion.island)
        val h = Spring(0f, Motion.island)
        val radius = Spring(0f, Motion.island)
        private val fade = Spring(1f, Motion.standard, 0.002f)
        private val shown = Spring(1f, Motion.snappy, 0.002f)
        private var cur: Lead? = null
        private var prev: Lead? = null
        private var inited = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1f); color = 0x2EFFFFFF }
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.island }
        private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.success }
        private val badgeMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ink.night
        }
        private val badgePath = Path()
        private val m = Matrix()
        private val shaders = HashMap<Bitmap, BitmapShader>()
        private val tmp = RectF()
        private val back = RectF()

        /** A small green check on the picture's corner: it's saved on this phone (as on the Mac's island). */
        private fun drawBadge(c: Canvas, rect: RectF, a: Float) {
            val r = kit.dp(9f)
            val cx = rect.right - kit.dp(2f)
            val cy = rect.bottom - kit.dp(2f)
            cut.alpha = (255 * a).toInt()
            c.drawCircle(cx, cy, r + kit.dp(2.5f), cut)
            badgeFill.alpha = (255 * a).toInt()
            c.drawCircle(cx, cy, r, badgeFill)
            badgePath.reset()
            badgePath.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            badgePath.lineTo(cx - r * 0.1f, cy + r * 0.34f)
            badgePath.lineTo(cx + r * 0.45f, cy - r * 0.3f)
            badgeMark.strokeWidth = r * 0.24f
            badgeMark.alpha = (255 * a).toInt()
            c.drawPath(badgePath, badgeMark)
        }

        fun set(next: Lead?, rect: RectF?, rad: Float, animate: Boolean) {
            if (!same(next, cur)) {
                prev = if (animate) cur else null
                cur = next
                if (animate && prev != null) fade.snap(0f) else fade.snap(1f)
                fade.to(1f)
            }
            if (rect == null) return
            if (!inited || !animate) {
                x.snap(rect.left); y.snap(rect.top); w.snap(rect.width()); h.snap(rect.height()); radius.snap(rad)
                inited = true
            } else {
                x.to(rect.left); y.to(rect.top); w.to(rect.width()); h.to(rect.height()); radius.to(rad)
            }
            if (leadPending) shown.snap(0f)
        }

        fun landed() {
            shown.snap(1f)
        }

        fun pop() {
            shown.snap(0f)
            shown.to(1f, Motion.emphasized)
        }

        private fun same(a: Lead?, b: Lead?): Boolean = when {
            a is Lead.Image && b is Lead.Image -> a.bitmap === b.bitmap && a.stack == b.stack && a.badge == b.badge
            else -> a == b
        }

        fun snapAll() {
            for (s in arrayOf(x, y, w, h, radius)) s.snap(s.target)
        }

        fun step(dt: Float): Boolean {
            var moving = false
            for (s in arrayOf(x, y, w, h, radius, fade, shown)) moving = s.step(dt) or moving
            if (!fade.moving) prev = null
            return moving
        }

        fun draw(c: Canvas, island: RectF, alpha: Float) {
            if (leadPending) return
            val sh = shown.value.coerceIn(0f, 1.2f)
            val a = alpha * sh.coerceAtMost(1f)
            tmp.set(island.left + x.value, island.top + y.value, island.left + x.value + w.value, island.top + y.value + h.value)
            if (sh < 0.999f || sh > 1.001f) {
                val k = 0.55f + 0.45f * sh
                val cx = tmp.centerX()
                val cy = tmp.centerY()
                tmp.set(cx - tmp.width() / 2 * k, cy - tmp.height() / 2 * k, cx + tmp.width() / 2 * k, cy + tmp.height() / 2 * k)
            }
            prev?.let { drawLead(c, it, tmp, a * (1f - fade.value)) }
            cur?.let { drawLead(c, it, tmp, a * fade.value) }
        }

        private fun drawLead(c: Canvas, lead: Lead, rect: RectF, a: Float) {
            if (a <= 0.003f || rect.width() < 1f) return
            val rad = radius.value
            when (lead) {
                is Lead.Image -> {
                    val bmp = lead.bitmap
                    if (bmp.isRecycled) return
                    val sh = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                    // Several things: a couple of darker cards fan out behind, pivoting on the bottom edge.
                    val backs = min(2, lead.stack - 1)
                    for (k in backs downTo 1) {
                        val s = if (k == 2) 0.86f else 0.93f
                        c.save()
                        c.translate(kit.dp(if (k == 2) 6f else 3f), -kit.dp(if (k == 2) 4f else 2f))
                        c.rotate(if (k == 2) 13f else 6.5f, rect.centerX(), rect.bottom)
                        back.set(
                            rect.centerX() - rect.width() / 2 * s, rect.bottom - rect.height() * s,
                            rect.centerX() + rect.width() / 2 * s, rect.bottom,
                        )
                        centerCrop(m, bmp.width, bmp.height, back, focusY = 0.35f)
                        sh.setLocalMatrix(m)
                        paint.shader = sh
                        paint.alpha = (255 * a).toInt()
                        c.drawRoundRect(back, rad * s, rad * s, paint)
                        dim.alpha = ((if (k == 2) 150 else 95) * a).toInt()
                        c.drawRoundRect(back, rad * s, rad * s, dim)
                        c.restore()
                    }
                    if (backs > 0) {
                        // a hairline of island black keeps the front card crisp against the ones behind
                        back.set(rect)
                        back.inset(-kit.dp(1.5f), -kit.dp(1.5f))
                        cut.alpha = (255 * a).toInt()
                        c.drawRoundRect(back, rad + kit.dp(1.5f), rad + kit.dp(1.5f), cut)
                    }
                    centerCrop(m, bmp.width, bmp.height, rect, focusY = 0.35f)
                    sh.setLocalMatrix(m)
                    paint.shader = sh
                    paint.alpha = (255 * a).toInt()
                    c.drawRoundRect(rect, rad, rad, paint)
                    hair.alpha = (46 * a).toInt()
                    c.drawRoundRect(rect, rad, rad, hair)
                    if (lead.badge) drawBadge(c, rect, a)
                }
                is Lead.Symbol -> {
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val d = min(rect.width(), rect.height())
                    disc.color = if (lead.solid) lead.tint else Ink.alpha(lead.tint, if (lead.tint == Color.WHITE) 0.14f else 0.22f)
                    disc.alpha = (Color.alpha(disc.color) * a).toInt()
                    c.drawCircle(cx, cy, d / 2, disc)
                    glyph.color = if (lead.solid) Color.WHITE else lead.tint
                    glyph.alpha = (255 * a).toInt()
                    Glyphs.draw(c, lead.icon, cx, cy, d * 0.52f, glyph)
                }
            }
        }
    }

    // MARK: - text slots

    private inner class TextSlot {
        private var cur: StaticLayout? = null
        private var curText: String? = null
        private var prev: StaticLayout? = null
        private var prevPaint: TextPaint? = null
        private var curPaint: TextPaint? = null
        val x = Spring(0f, Motion.island)
        val y = Spring(0f, Motion.island)
        private val fade = Spring(1f, Motion.standard, 0.002f)
        private var inited = false
        /** For right-anchored labels: distance of the label's left edge from the island's right edge. */
        var fromRight = -1f

        val hasText: Boolean get() = curText != null

        fun set(text: String?, paint: TextPaint, width: Int, maxLines: Int, px: Float, py: Float, animate: Boolean, instant: Boolean = false) {
            if (text == null) {
                // Fade out where it stands.
                if (curText != null) {
                    if (animate && cur != null) {
                        prev = cur
                        prevPaint = curPaint
                        fade.snap(0f)
                        fade.to(1f)
                    } else {
                        prev = null
                    }
                }
                cur = null
                curText = null
                return
            }
            val changed = text != curText
            if (changed) {
                if (instant && cur != null) {
                    // a number ticking over (a percentage): it just changes, tabular digits keep it still
                    prev = null
                    fade.snap(1f)
                } else if (animate && cur != null) {
                    prev = cur
                    prevPaint = curPaint
                    fade.snap(0f)
                } else {
                    prev = null
                    fade.snap(if (animate) 0f else 1f)
                }
                fade.to(1f)
                curText = text
            }
            val cp = TextPaint(paint)
            curPaint = cp
            cur = textLayout(text, cp, width, maxLines)
            if (!inited || !animate || (changed && prev == null)) {
                x.snap(px); y.snap(py)
                inited = true
            } else {
                x.to(px); y.to(py)
            }
            fromRight = -1f
        }

        fun snapAll() {
            x.snap(x.target); y.snap(y.target)
        }

        fun step(dt: Float): Boolean {
            var moving = x.step(dt)
            moving = y.step(dt) or moving
            moving = fade.step(dt) or moving
            if (!fade.moving) prev = null
            return moving
        }

        fun draw(c: Canvas, island: RectF, alpha: Float) {
            val slide = kit.dp(7f)
            val px = if (fromRight >= 0f) island.right - fromRight else island.left + x.value
            val f = fade.value
            prev?.let { lay ->
                val p = prevPaint ?: return@let
                drawLayout(c, lay, p, px, island.top + y.value - slide * f - (if (fromRight >= 0f) lay.height / 2f else 0f), alpha * (1f - f))
            }
            cur?.let { lay ->
                val p = curPaint ?: return@let
                drawLayout(c, lay, p, px, island.top + y.value + slide * (1f - f) - (if (fromRight >= 0f) lay.height / 2f else 0f), alpha * f)
            }
        }

        private fun drawLayout(c: Canvas, lay: StaticLayout, p: TextPaint, x: Float, y: Float, a: Float) {
            if (a <= 0.003f) return
            val base = p.color
            p.alpha = (Color.alpha(base).coerceAtLeast(1) * a).toInt().coerceIn(0, 255)
            c.save()
            c.translate(x, y)
            lay.draw(c)
            c.restore()
            p.color = base
        }
    }

    // MARK: - trail: ring, countdown, check, cross, button

    private inner class TrailSlot {
        private var kind: Trail = Trail.None
        val cxr = Spring(0f, Motion.island)   // from the island's right edge
        val cy = Spring(0f, Motion.island)
        val size = Spring(0f, Motion.island)
        private val ringA = Spring(0f, Motion.standard, 0.002f)
        private val buttonA = Spring(0f, Motion.standard, 0.002f)
        private val value = Spring(0f, Motion.gentle, 0.0005f)
        private var spinning = true
        private var spin = 0f
        private val disc = Spring(0f, Motion(0.5f, 420f), 0.001f)
        private var markT = -1f
        private var burstT = -1f
        private var success = true
        private var countdown = false
        private var paused = false
        private var inited = false
        private var button: StaticLayout? = null
        private var buttonW = 0f
        private val buttonText = TextPaint(buttonPaint)

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x29FFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val sweep = SweepGradient(0f, 0f, Ink.ring, null).also {
            it.setLocalMatrix(Matrix().apply { setRotate(-90f) })
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val burst = Paint(Paint.ANTI_ALIAS_FLAG)
        private val btn = Paint(Paint.ANTI_ALIAS_FLAG)
        private val btnGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ink.light
            maskFilter = BlurMaskFilter(kit.dp(10f), BlurMaskFilter.Blur.NORMAL)
        }
        private val markPath = Path()
        private val seg = Path()
        private val pm = PathMeasure()
        private val tmp = RectF()

        fun set(next: Trail, s: Slots, animate: Boolean) {
            val prevKind = kind
            kind = next
            when (next) {
                Trail.Progress -> {
                    countdown = false
                    if (prevKind != Trail.Progress) {
                        spinning = true
                        if (prevKind != Trail.Countdown) value.snap(0f)
                        clearMark()
                    }
                    ringA.to(1f); buttonA.to(0f)
                }
                Trail.Countdown -> {
                    countdown = true
                    if (prevKind != Trail.Countdown) clearMark()
                    ringA.to(1f); buttonA.to(0f)
                }
                Trail.Done, Trail.Failed -> {
                    success = next == Trail.Done
                    countdown = false
                    if (prevKind != next) {
                        // the ring closes, then the disc pops and the mark draws itself
                        spinning = false
                        value.to(1f, Motion.snappy)
                        disc.snap(0f)
                        markT = if (animate) 0f else 1f
                        burstT = if (animate && success) 0f else -1f
                        if (!animate) disc.snap(1f)
                    }
                    ringA.to(1f); buttonA.to(0f)
                }
                is Trail.Button -> {
                    button = textLayout(next.label, buttonText, textWidth(next.label, buttonText).toInt() + 2, 1)
                    buttonW = s.trailW
                    ringA.to(0f); buttonA.to(1f)
                    disc.to(0f)
                }
                Trail.None -> {
                    ringA.to(0f); buttonA.to(0f); disc.to(0f)
                }
            }
            if (next == Trail.None && prevKind != Trail.None) return  // fade out where it is
            if (!inited || !animate) {
                cxr.snap(s.trailFromRight); cy.snap(s.trailY); size.snap(s.trailSize)
                inited = true
            } else {
                cxr.to(s.trailFromRight); cy.to(s.trailY); size.to(s.trailSize)
            }
            if (!animate) { ringA.snap(ringA.target); buttonA.snap(buttonA.target) }
        }

        /** A check or cross left over from an earlier result goes (the ring takes its place). */
        private fun clearMark() {
            disc.to(0f)
            markT = -1f
            burstT = -1f
        }

        fun setProgress(p: Float?) {
            if (kind != Trail.Progress) return
            if (p == null) {
                spinning = true
            } else {
                spinning = false
                value.to(p.coerceIn(0f, 1f))
            }
        }

        fun setCountdown(left: Float?, paused: Boolean) {
            if (kind != Trail.Countdown) return
            this.paused = paused
            if (left == null) {
                spinning = false
                value.snap(1f)
            } else {
                spinning = false
                value.snap(left.coerceIn(0f, 1f))
            }
        }

        fun snapAll() {
            cxr.snap(cxr.target); cy.snap(cy.target); size.snap(size.target)
        }

        fun step(dt: Float): Boolean {
            var moving = false
            for (s in arrayOf(cxr, cy, size, ringA, buttonA, value, disc)) moving = s.step(dt) or moving
            if (spinning && ringA.value > 0.01f && kind == Trail.Progress) {
                spin = (spin + dt * 360f) % 360f
                moving = true
            }
            if (markT >= 0f && markT < 1f) {
                // the disc pops once the ring has (nearly) closed
                if (value.value > 0.97f || !value.moving) {
                    if (disc.target != 1f) disc.to(1f)
                    markT = min(1f, markT + dt / 0.42f)
                }
                moving = true
            }
            if (burstT in 0f..0.999f) {
                if (disc.value > 0.3f) burstT = min(1f, burstT + dt / 0.7f)
                moving = true
            }
            return moving
        }

        fun draw(c: Canvas, island: RectF, alpha: Float) {
            val sz = size.value
            if (sz < 1f) return
            val x = island.right - cxr.value
            val y = island.top + cy.value
            val rr = sz / 2f
            val ra = ringA.value * alpha
            if (ra > 0.003f) drawRing(c, x, y, rr, ra)
            val ba = buttonA.value * alpha
            if (ba > 0.003f) drawButton(c, x, y, ba)
        }

        private fun drawRing(c: Canvas, x: Float, y: Float, rr: Float, a: Float) {
            val stroke = max(kit.dp(3f), rr * 0.2f)
            val ring = rr - stroke / 2f
            val dim = if (countdown && paused) 0.45f else 1f
            c.save()
            c.translate(x, y)
            val d = disc.value
            // burst glow behind the disc
            if (burstT in 0f..0.999f) {
                val k = ease(burstT)
                val gr = rr * (1f + 0.9f * k)
                burst.shader = RadialGradient(0f, 0f, max(1f, gr), Ink.alpha(Ink.success, 0.55f * (1f - k)), Ink.alpha(Ink.success, 0f), Shader.TileMode.CLAMP)
                burst.alpha = (255 * a).toInt()
                c.drawCircle(0f, 0f, gr, burst)
            }
            if (d < 0.999f) {
                track.strokeWidth = stroke
                track.alpha = (0x29 * a * (1f - d)).toInt()
                c.drawCircle(0f, 0f, ring, track)
                arc.strokeWidth = stroke
                arc.shader = if (kind == Trail.Done || kind == Trail.Failed) null else sweep
                if (arc.shader == null) arc.color = if (success) Ink.success else Ink.danger
                arc.alpha = (255 * a * dim * (1f - d)).toInt()
                tmp.set(-ring, -ring, ring, ring)
                if (spinning && kind == Trail.Progress) {
                    c.drawArc(tmp, spin - 90f, 100f, false, arc)
                } else {
                    val sweepDeg = 360f * value.value
                    if (sweepDeg > 0.5f) c.drawArc(tmp, -90f, sweepDeg, false, arc)
                }
            }
            if (countdown) {
                glyph.alpha = (235 * a * dim).toInt()
                Glyphs.draw(c, Icons.Rounded.ArrowUpward, 0f, 0f, rr * 1.05f, glyph)
            }
            if (d > 0.001f) {
                fill.color = if (success) Ink.success else Ink.danger
                fill.alpha = (255 * a).toInt()
                c.drawCircle(0f, 0f, rr * d, fill)
            }
            if (markT > 0f) {
                val p = ease(markT)
                markPath.reset()
                if (success) {
                    markPath.moveTo(-rr * 0.42f, rr * 0.02f)
                    markPath.lineTo(-rr * 0.12f, rr * 0.32f)
                    markPath.lineTo(rr * 0.44f, -rr * 0.30f)
                } else {
                    markPath.moveTo(-rr * 0.32f, -rr * 0.32f)
                    markPath.lineTo(rr * 0.32f, rr * 0.32f)
                    markPath.moveTo(rr * 0.32f, -rr * 0.32f)
                    markPath.lineTo(-rr * 0.32f, rr * 0.32f)
                }
                mark.strokeWidth = rr * 0.17f
                // Night on the bone disc (it worked), white on red (it didn't).
                mark.color = if (success) Ink.night else Color.WHITE
                mark.alpha = (255 * a).toInt()
                seg.reset()
                pm.setPath(markPath, false)
                var len = 0f
                do { len += pm.length } while (pm.nextContour())
                pm.setPath(markPath, false)
                var left = len * p
                do {
                    val l0 = pm.length
                    if (left <= 0f) break
                    pm.getSegment(0f, min(l0, left), seg, true)
                    left -= l0
                } while (pm.nextContour())
                c.drawPath(seg, mark)
            }
            c.restore()
        }

        private fun drawButton(c: Canvas, x: Float, y: Float, a: Float) {
            val lay = button ?: return
            val w = buttonW
            val h = kit.dp(34f)
            tmp.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            // A soft glow of light under it, like the Mac island's Open button.
            btnGlow.alpha = (70 * a).toInt()
            tmp.offset(0f, kit.dp(3f))
            c.drawRoundRect(tmp, h / 2, h / 2, btnGlow)
            tmp.offset(0f, -kit.dp(3f))
            btn.shader = null
            btn.color = Ink.bone
            btn.alpha = (255 * a).toInt()
            c.drawRoundRect(tmp, h / 2, h / 2, btn)
            buttonText.alpha = (255 * a).toInt()
            c.save()
            c.translate(tmp.centerX() - lay.getLineWidth(0) / 2f, tmp.centerY() - lay.height / 2f)
            lay.draw(c)
            c.restore()
        }
    }

    private companion object {
        /** Signed-distance union of two rounded rects, smoothed by k: a shape melting into the island. */
        const val GOO = """
            uniform float4 a;
            uniform float ar;
            uniform float4 b;
            uniform float br;
            uniform float k;
            layout(color) uniform half4 ink;

            float box(float2 p, float4 r, float rad) {
                float2 c = (r.xy + r.zw) * 0.5;
                float2 h = (r.zw - r.xy) * 0.5;
                float rr = min(rad, min(h.x, h.y));
                float2 q = abs(p - c) - h + rr;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rr;
            }

            half4 main(float2 p) {
                float d1 = box(p, a, ar);
                float d2 = box(p, b, br);
                float kk = max(k, 0.001);
                float h = clamp(0.5 + 0.5 * (d2 - d1) / kk, 0.0, 1.0);
                float d = mix(d2, d1, h) - kk * h * (1.0 - h);
                return ink * clamp(0.5 - d, 0.0, 1.0);
            }
        """
    }
}
