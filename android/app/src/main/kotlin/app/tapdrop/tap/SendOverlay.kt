package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.StaticLayout
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.ui.graphics.vector.ImageVector
import app.tapdrop.R
import app.tapdrop.TapDropApp
import app.tapdrop.net.Received
import app.tapdrop.ui.Haptics
import app.tapdrop.ui.MainActivity
import app.tapdrop.ui.TdType
import app.tapdrop.ui.openLink
import app.tapdrop.ui.openUri
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What the tap service shows over other apps once something is on its way: the screen (or the picked part, or a
 * chip for a link or text) lifts and flies up into the island, which grows out of the camera hole and tells how
 * the send went. Also the island for one-time codes, for things arriving from the Mac (tap to open) and for
 * notices. Never focusable; only an island that can be opened takes touches, and only inside its outline.
 */
class SendOverlay(private val svc: AccessibilityService) {
    /** What a chip flying to the Mac stands for. */
    enum class Chip { LINK, TEXT, FILE }

    internal enum class PillKind { SEND, CODE, NOTICE, RECEIVED }

    private val wm = svc.getSystemService(WindowManager::class.java)
    private val kit = OverlayKit(svc)
    private val main = Handler(Looper.getMainLooper())
    private var stage: Stage? = null
    private var touch: View? = null
    private var serial = 0

    init {
        // Debug builds add a receiver that drives every overlay state for screenshots (src/debug); release has none.
        runCatching {
            Class.forName("app.tapdrop.tap.OverlayLab")
                .getMethod("install", AccessibilityService::class.java)
                .invoke(null, svc)
        }
    }

    // MARK: - sending (these take over the picker's frozen screen when it is up)

    /** The whole screen lifts off and flies up into the island. */
    fun flyScreenshot(shot: Bitmap, pillText: String): Pill {
        val s = stage()
        val h = s.adopt()
        val content = sendingContent(Lead.Image(shot), pillText)
        if (kit.reducedMotion) return s.present(content, PillKind.SEND)
        val from = RectF()
        val radius: Float
        if (h != null) {
            h.frozen.cardRect(from)
            radius = h.frozen.radius.value
            h.frozen.cardAlpha.snap(0f)
        } else {
            from.set(0f, 0f, s.width.toFloat(), s.height.toFloat())
            radius = kit.displayCornerRadius()
            s.dimPulse()
        }
        val f = Flyer(shot, null, from, radius)
        f.lift(scale = if (h != null) 0.95f else 0.8f, drop = kit.dp(if (h != null) 6f else 16f), hold = if (h != null) 0.06f else 0.17f)
        return s.launch(f, content)
    }

    /**
     * The picked part of the screen lifts from exactly where it was, then flies up into the island. When the
     * pick is words ([text]), they lift as a chip instead: a sliver of text squeezed into a thumbnail would only
     * show a fragment of a word.
     */
    fun flyRegion(crop: Bitmap, rect: Rect, pillText: String, text: String? = null): Pill {
        val words = text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        if (words != null) return flyWords(words, rect, pillText)
        val s = stage()
        val h = s.adopt()
        val content = sendingContent(Lead.Image(crop), pillText)
        // The screen un-freezes back into the app while the picked piece flies off it.
        h?.frozen?.unfreeze(Motion.standard)
        if (kit.reducedMotion) return s.present(content, PillKind.SEND)
        val from = RectF()
        val radius: Float
        val pick = h?.pick
        val lifted = h != null && pick != null && h.pickLift > 0.5f
        when {
            h != null && pick != null -> {
                from.set(pick)
                radius = h.pickRadius
            }
            h != null -> {
                h.frozen.toScreen(rect, from)
                radius = kit.dp(12f)
            }
            else -> {
                from.set(rect)
                radius = kit.dp(12f)
            }
        }
        val f = Flyer(crop, null, from, radius)
        f.lift(scale = if (lifted) 1f else 1.05f, drop = 0f, hold = if (lifted) 0.04f else 0.15f)
        return s.launch(f, content)
    }

    /** Words picked off the screen: a chip lifts from right where they were, then melts into the island. */
    private fun flyWords(words: String, rect: Rect, pillText: String): Pill {
        val s = stage()
        val h = s.adopt()
        val content = sendingContent(Lead.Symbol(Icons.AutoMirrored.Rounded.Notes, Ink.violet), pillText)
        h?.frozen?.unfreeze(Motion.standard)
        if (kit.reducedMotion) return s.present(content, PillKind.SEND)
        val look = ChipLook(Icons.AutoMirrored.Rounded.Notes, Ink.violet, "\u201C$words\u201D")
        val size = look.size()
        val at = RectF()
        val pick = h?.pick
        when {
            pick != null -> at.set(pick)
            h != null -> h.frozen.toScreen(rect, at)
            else -> at.set(rect)
        }
        val m = kit.dp(16f)
        val cx = at.centerX().coerceIn(m + size.x / 2, max(m + size.x / 2, s.width - m - size.x / 2))
        val cy = at.centerY()
        val f = Flyer(null, look, RectF(cx - size.x / 2, cy - size.y / 2, cx + size.x / 2, cy + size.y / 2), size.y / 2)
        f.pop(hold = 0.24f)
        return s.launch(f, content)
    }

    /** A chip (the page's address, the words, a file name) pops up, then melts into the island. */
    fun flyChip(kind: Chip, chipText: String, pillText: String): Pill {
        val s = stage()
        val h = s.adopt()
        val (icon, tint) = when (kind) {
            Chip.LINK -> Icons.Rounded.Language to Ink.sky
            Chip.TEXT -> Icons.AutoMirrored.Rounded.Notes to Ink.violet
            Chip.FILE -> Icons.Rounded.Description to Ink.blue
        }
        val content = sendingContent(Lead.Symbol(icon, tint), pillText)
        h?.frozen?.unfreeze(Motion.standard)
        if (kit.reducedMotion) return s.present(content, PillKind.SEND)
        val look = ChipLook(icon, tint, chipText)
        val size = look.size()
        val cx = s.width / 2f
        val cy = s.height * 0.46f
        val from = RectF(cx - size.x / 2, cy - size.y / 2, cx + size.x / 2, cy + size.y / 2)
        val f = Flyer(null, look, from, size.y / 2)
        f.pop(hold = 0.38f)
        return s.launch(f, content)
    }

    /** A photo card pops up in the middle, then flies into the island (when there was no screenshot). */
    fun flyCard(image: Bitmap, pillText: String): Pill {
        val s = stage()
        val h = s.adopt()
        h?.frozen?.unfreeze(Motion.standard)
        val content = sendingContent(Lead.Image(image), pillText)
        if (kit.reducedMotion) return s.present(content, PillKind.SEND)
        val side = s.width * 0.6f
        val aspect = (image.height.toFloat() / max(1, image.width)).coerceIn(0.6f, 1.5f)
        val cx = s.width / 2f
        val cy = s.height * 0.45f
        val from = RectF(cx - side / 2, cy - side * aspect / 2, cx + side / 2, cy + side * aspect / 2)
        val f = Flyer(image, null, from, kit.dp(26f))
        f.pop(hold = 0.3f)
        return s.launch(f, content)
    }

    /** The island alone, for a send with nothing to show flying (e.g. a photo whose preview couldn't load). */
    fun sending(text: String, thumb: Bitmap? = null): Pill {
        val s = stage()
        s.adopt()?.frozen?.unfreeze(Motion.standard)
        val lead = thumb?.let { Lead.Image(it) } ?: Lead.Symbol(Icons.Rounded.ArrowUpward, Ink.violet)
        return s.present(sendingContent(lead, text), PillKind.SEND)
    }

    // MARK: - codes, arrivals, notices

    /** A one-time code is on its way to the Mac's clipboard: big digits, then "Copied". */
    fun code(code: String, source: String): Pill {
        val s = stage()
        val pretty = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code
        val content = IslandContent(
            lead = Lead.Symbol(Icons.Rounded.Key, Ink.violet),
            title = pretty,
            subtitle = "From $source",
            trail = Trail.Progress,
            code = true,
            spoken = "Code ${code.toCharArray().joinToString(" ")} from $source",
        )
        Haptics.tick(svc)
        val pill = s.present(content, PillKind.CODE)
        pill.dismissAfter(12_000)
        return pill
    }

    /**
     * Something arrived from the Mac while another app was open. The island shows it; tapping it opens it (a
     * photo or file in its viewer, a link in the browser, several things in TapDrop); a flick up puts it away.
     */
    fun received(title: String, subtitle: String?, thumb: Bitmap?, items: List<Received> = emptyList()) {
        val s = stage()
        val one = items.singleOrNull()
        val action: Pair<String, () -> Unit>? = when {
            one != null && one.isLink -> "Open" to { openLink(svc, one.text!!.trim()) }
            one != null && one.uri != null -> "Open" to { openUri(svc, one.uri, one.mime) }
            items.size > 1 -> "Show" to { openApp() }
            else -> null
        }
        val lead = thumb?.let { Lead.Image(it, stack = if (items.size > 1) min(3, items.size) else 1, badge = true) } ?: Lead.Symbol(
            when {
                one?.isLink == true -> Icons.Rounded.Language
                one?.text != null -> Icons.AutoMirrored.Rounded.Notes
                items.size == 1 -> Icons.Rounded.Description
                else -> Icons.Rounded.Download
            },
            if (one?.isLink == true) Ink.sky else Ink.blue,
        )
        // Say what came in plain words; a link shows where it goes rather than the whole address.
        val (head, sub) = when {
            one != null && one.isLink -> title to shortUrl(one.text!!.trim())
            one != null && one.text == null && !one.clip ->
                (if (one.isVideo) "Video received" else if (one.isImage) "Photo received" else "File received") to one.name
            else -> title to subtitle
        }
        val content = IslandContent(
            lead = lead,
            title = head,
            subtitle = sub?.takeIf { it.isNotBlank() },
            trail = action?.let { Trail.Button(it.first) } ?: Trail.Done,
            spoken = listOfNotNull(head, sub).joinToString(". ") +
                (if (action != null) ". Double-tap to ${action.first.lowercase()}" else ""),
        )
        val pill = s.present(content, PillKind.RECEIVED)
        s.island.popLead()
        val open = action?.second ?: { openApp() }
        s.after(0.45f) {
            if (pill.current) touchTarget(s, content.speech) { pill.dismiss(); open() }
        }
        pill.dismissAfter(5200)
    }

    /** A notice (sending is off, nothing to send, blocked). [error] makes its cross red and shakes the island. */
    fun notice(icon: Int, text: String, error: Boolean = false): Pill {
        val s = stage()
        val cut = text.indexOf(" — ")
        val title = if (cut > 0) text.substring(0, cut) else text
        val sub = if (cut > 0) text.substring(cut + 3).replaceFirstChar { it.uppercase() } else null
        val glyph = when (icon) {
            R.drawable.ic_arrow_up -> Icons.Rounded.ArrowUpward
            R.drawable.ic_arrow_down -> Icons.Rounded.ArrowDownward
            R.drawable.ic_check -> Icons.Rounded.Check
            R.drawable.ic_key -> Icons.Rounded.Key
            R.drawable.ic_globe -> Icons.Rounded.Language
            R.drawable.ic_text -> Icons.AutoMirrored.Rounded.Notes
            else -> Icons.Rounded.Close
        }
        val content = IslandContent(
            lead = Lead.Symbol(glyph, if (error) Ink.danger else Color.WHITE, solid = error),
            title = title,
            subtitle = sub,
        )
        val pill = s.present(content, PillKind.NOTICE)
        if (error) s.after(0.2f) { if (pill.current) s.island.refuse() }
        pill.dismissAfter(if (sub != null || title.length > 34) 3600 else 2800)
        return pill
    }

    /** "https://www.example.com/a/b?c" → "example.com/a/b", or just the host when the rest is long. */
    private fun shortUrl(url: String): String {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return url
        val host = u.host?.removePrefix("www.") ?: return url
        val path = u.path?.trimEnd('/').orEmpty()
        return if (path.isEmpty() || host.length + path.length > 34) host else host + path
    }

    /** Debug lab: everything off the screen at once. */
    internal fun clearNow() {
        stage?.abort()
    }

    private fun sendingContent(lead: Lead, text: String) = IslandContent(
        lead = lead,
        title = text,
        subtitle = macName()?.let { "To $it" },
        trail = Trail.Progress,
    )

    private fun macName(): String? = runCatching { TapDropApp.instance.prefs.macs.value.firstOrNull()?.name }.getOrNull()

    private fun openApp() {
        runCatching {
            svc.startActivity(
                Intent(svc, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    // MARK: - the pill handle

    /** One island on screen. Calls after a newer island replaced it do nothing. */
    inner class Pill internal constructor(
        private val id: Int,
        private var content: IslandContent,
        internal val kind: PillKind,
    ) {
        private var finished = false
        private var lastPct = -1
        private var lastPctAt = 0f

        internal val current: Boolean get() = stage?.let { it.alive && it.id == id } == true

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
        }

        /** New wording while it's on its way. */
        fun update(text: String) = onMain {
            val s = stage ?: return@onMain
            if (!current || finished || text == content.title) return@onMain
            content = content.copy(title = text)
            if (content.shape == IslandShape.Expanded) {
                s.island.show(content)
                s.view.contentDescription = content.speech
            }
        }

        /** Transfer progress, 0..1 (null: unknown, the ring spins). A long transfer folds the island to one row. */
        fun progress(p: Float?) = onMain {
            val s = stage ?: return@onMain
            if (!current || finished) return@onMain
            s.island.progress(p)
            s.keepAlive()
            if (p == null) return@onMain
            val pct = (p * 100).toInt().coerceIn(0, 100)
            val folded = content.shape == IslandShape.Compact
            if (!folded && s.flown && s.age(id) > 2.2f) {
                content = content.copy(shape = IslandShape.Compact, right = "$pct%")
                lastPct = pct
                lastPctAt = s.time
                s.island.show(content)
            } else if (folded && pct != lastPct && s.time - lastPctAt > 0.25f) {
                content = content.copy(right = "$pct%")
                lastPct = pct
                lastPctAt = s.time
                s.island.show(content)
            }
            s.view.stateDescription = "$pct%"
        }

        /** How it went: the ring closes into a check (or a cross, with a shake), holds, then goes back into the hole. */
        fun finish(ok: Boolean, text: String, holdMs: Long = if (ok) 1700 else 3400) = onMain {
            val s = stage ?: return@onMain
            if (!current || finished) return@onMain
            finished = true
            s.whenFlown(minShown = if (kind == PillKind.SEND) 0.55f else 0.35f) {
                if (!current) return@whenFlown
                val next = when (kind) {
                    PillKind.CODE -> content.copy(
                        subtitle = if (ok) "Copied to your Mac" else text,
                        trail = if (ok) Trail.Done else Trail.Failed,
                        spoken = if (ok) "Copied to your Mac" else text,
                    )
                    else -> if (ok) {
                        content.copy(
                            shape = IslandShape.Expanded, title = text, subtitle = macName(),
                            trail = Trail.Done, right = null, spoken = text,
                        )
                    } else {
                        content.copy(
                            shape = IslandShape.Expanded, title = "Couldn't send", subtitle = text,
                            trail = Trail.Failed, right = null, spoken = "Couldn't send. $text",
                        )
                    }
                }
                content = next
                s.island.show(next)
                s.view.stateDescription = null
                s.view.contentDescription = next.speech
                if (!ok) s.after(0.12f) { if (current) s.island.refuse() }
                s.after(holdMs / 1000f + 0.35f) { if (current) s.dismiss() }
            }
        }

        internal fun dismissAfter(ms: Long) = onMain {
            val s = stage ?: return@onMain
            if (!current) return@onMain
            s.after(ms / 1000f) { if (current) s.dismiss() }
        }

        internal fun dismiss() = onMain {
            if (current) stage?.dismiss()
        }
    }

    // MARK: - the stage: one full-screen, untouchable window while anything shows

    private fun stage(): Stage = stage?.takeIf { it.alive } ?: Stage().also { stage = it }

    private inner class Stage {
        val view = StageView()
        var alive = true
        val width: Int
        val height: Int
        private val hole: Hole
        var island: Island
            private set
        var id = 0
        var time = 0f
        private val addedAt = SystemClock.uptimeMillis()
        private val shownAt = HashMap<Int, Float>()
        private val timers = mutableListOf<Pair<Float, () -> Unit>>()
        private val flyers = mutableListOf<Flyer>()
        private val flownWaiters = mutableListOf<Triple<Float, Float, () -> Unit>>()
        private var frozen: FrozenScreen? = null
        private var handoff: PickerHandoff? = null
        private val spot = Spring(0f, Motion.standard, 0.002f)
        private val dim = Spring(0f, Motion.standard, 0.002f)
        private var lastAlive = 0f
        private var dismissed = false
        private val ticker = Ticker { dt, real -> frame(dt, real) }
        private val tmp = RectF()

        init {
            val size = screenSize()
            width = size.first
            height = size.second
            hole = Hole.of(kit, width, kit.statusBarHeight())
            island = Island(kit, hole, width)
            island.showHole = OverlayLabFlags.showHole
            try {
                wm.addView(view, overlayParams(touchable = false).apply { title = "TapDrop island" })
            } catch (_: Exception) {
                alive = false
            }
        }

        val flown: Boolean get() = flyers.isEmpty()

        fun age(pill: Int): Float = time - (shownAt[pill] ?: time)

        /** Takes over the picker's frozen screen and island, if a picker is on screen. */
        fun adopt(): PickerHandoff? {
            val h = Handoff.picker ?: return null
            if (h.taken) return null
            h.taken = true
            handoff = h
            frozen = h.frozen
            if (island !== h.island) {
                island.hide()
                island = h.island
            }
            island.resize(hole, width)
            island.showHole = OverlayLabFlags.showHole
            if (kit.reducedMotion) {
                h.frozen.backdrop.to(0f, Motion.quick)
                h.frozen.cardAlpha.to(0f, Motion.quick)
            } else {
                // Hold the picker's last frame a beat (it is still underneath), then let the app show through.
                after(0.08f) {
                    h.frozen.backdrop.to(0f, Motion.standard)
                    h.frozen.depth.to(0f, Motion.standard)
                }
            }
            if (h.pick != null) {
                spot.snap(h.spot)
                after(0.06f) { spot.to(0f, Motion.standard) }
            }
            // This window has to sit above the picker's.
            if (addedAt < h.createdAt && view.isAttachedToWindow) {
                runCatching { wm.removeViewImmediate(view) }
                runCatching { wm.addView(view, overlayParams(touchable = false).apply { title = "TapDrop island" }) }
            }
            view.viewTreeObserver.registerFrameCommitCallback { h.markStageShown() }
            view.invalidate()
            ticker.start()
            return h
        }

        fun dimPulse() {
            dim.snap(0f)
            dim.to(0.3f, Motion.standard)
            after(0.24f) { dim.to(0f, Motion(1f, 110f)) }
        }

        private fun begin(): Int {
            id = ++serial
            shownAt[id] = time
            dismissed = false
            lastAlive = time
            removeTouch()
            return id
        }

        fun present(content: IslandContent, kind: PillKind): Pill {
            val pid = begin()
            island.leadPending = false
            island.show(content)
            view.contentDescription = content.speech
            ticker.start()
            view.invalidate()
            return Pill(pid, content, kind)
        }

        fun launch(f: Flyer, content: IslandContent): Pill {
            val pid = begin()
            flyers += f
            island.leadPending = true
            island.show(content)
            view.contentDescription = content.speech
            ticker.start()
            view.invalidate()
            return Pill(pid, content, PillKind.SEND)
        }

        fun keepAlive() {
            lastAlive = time
        }

        fun after(seconds: Float, block: () -> Unit) {
            timers += (time + seconds) to block
            ticker.start()
        }

        /** Runs [block] once nothing flies any more and the island has shown its content for [minShown] s. */
        fun whenFlown(minShown: Float, block: () -> Unit) {
            flownWaiters += Triple(time, minShown, block)
            ticker.start()
        }

        fun dismiss() {
            if (dismissed) return
            dismissed = true
            removeTouch()
            island.collapse()
            ticker.start()
        }

        private fun frame(dt: Float, real: Float): Boolean {
            // [time] is wall-clock (how long things stay up); springs step by [dt].
            time += real
            var moving = island.step(dt)
            frozen?.let { moving = it.step(dt) or moving }
            moving = spot.step(dt) or moving
            moving = dim.step(dt) or moving
            val it = flyers.iterator()
            while (it.hasNext()) {
                val f = it.next()
                f.step(dt, island)
                moving = true
                if (f.done) {
                    it.remove()
                    island.leadPending = false
                    island.impact(if (f.chip != null) 0.7f else 1f)
                    Haptics.tick(svc)
                    shownAt[id] = time
                }
            }
            if (flyers.isEmpty() && flownWaiters.isNotEmpty()) {
                val ready = flownWaiters.filter { (since, min, _) -> age(id) >= min || time - since > 3f }
                flownWaiters.removeAll(ready)
                ready.forEach { (_, _, b) -> b() }
            }
            if (timers.isNotEmpty()) {
                val due = timers.filter { it.first <= time }
                timers.removeAll(due)
                due.forEach { it.second() }
            }
            // A send whose result never comes (a stuck transfer, the service went away) folds away after a while.
            if (island.visible && !dismissed && time - lastAlive > 25f) dismiss()
            val fr = frozen
            if (fr != null && fr.backdrop.value < 0.003f && fr.cardAlpha.value < 0.003f && !fr.backdrop.moving) frozen = null
            if (moving) view.invalidate()
            val idle = !island.visible && flyers.isEmpty() && timers.isEmpty() && flownWaiters.isEmpty() &&
                frozen == null && dim.value < 0.003f && !dim.moving
            if (idle) {
                teardown()
                return false
            }
            return true
        }

        fun abort() {
            flyers.clear()
            timers.clear()
            flownWaiters.clear()
            frozen = null
            island.hide()
            dim.snap(0f)
            spot.snap(0f)
            teardown()
        }

        private fun teardown() {
            ticker.stop()
            alive = false
            removeTouch()
            runCatching { wm.removeView(view) }
            if (stage === this) stage = null
        }

        fun draw(c: Canvas) {
            val fr = frozen
            fr?.drawBackdrop(c)
            if (dim.value > 0.003f) c.drawColor(Color.argb((255 * dim.value).toInt(), 0, 0, 0))
            fr?.drawCard(c)
            val h = handoff
            if (fr != null && h != null && spot.value > 0.003f) {
                val pick = h.pickShot?.let { fr.toScreen(it, tmp) } ?: h.pick
                if (pick != null) fr.drawSpotlight(c, pick, h.pickRadius * fr.scale.value / max(0.01f, h.pickScale), spot.value)
            }
            val chip = flyers.firstOrNull { it.chip != null }
            if (chip != null) {
                chip.rect(tmp)
                // A chip melts into the island once it's fully there; while it pops in it fades on its own.
                if (island.visible && chip.opacity > 0.995f) {
                    island.draw(c, tmp, chip.radius.value, chip.goo())
                } else {
                    island.draw(c)
                    chip.drawSilhouette(c)
                }
            } else {
                island.draw(c)
            }
            for (f in flyers) f.draw(c)
            island.drawCamera(c)
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull()
        if (b != null && b.width() > 0) return b.width() to b.height()
        val dm = svc.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    // MARK: - touch target (an island you can open)

    @SuppressLint("ClickableViewAccessibility")
    private fun touchTarget(s: Stage, description: String, onTap: () -> Unit) {
        removeTouch()
        val r = s.island.targetBounds()
        if (r.width() < 4f) return
        val slop = ViewConfiguration.get(svc).scaledTouchSlop
        val v = object : View(svc) {
            private var x0 = 0f
            private var y0 = 0f

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x0 = e.rawX
                        y0 = e.rawY
                        s.island.press(true)
                        s.keepAlive()
                    }
                    MotionEvent.ACTION_UP -> {
                        s.island.press(false)
                        val mx = e.rawX - x0
                        val my = e.rawY - y0
                        when {
                            my < -kit.dp(16f) -> if (s.alive) s.dismiss()
                            abs(mx) < slop * 2 && abs(my) < slop * 2 -> performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> s.island.press(false)
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onTap()
                return true
            }
        }
        v.contentDescription = description
        val lp = overlayParams(touchable = true, width = r.width().toInt(), height = r.height().toInt()).apply {
            x = r.left.toInt()
            y = r.top.toInt()
            title = "TapDrop island (open)"
        }
        runCatching { wm.addView(v, lp) }.onSuccess { touch = v }
    }

    private fun removeTouch() {
        touch?.let { runCatching { wm.removeView(it) } }
        touch = null
    }

    // MARK: - the view

    @SuppressLint("ViewConstructor")
    private inner class StageView : View(svc) {
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }

        override fun onDraw(canvas: Canvas) {
            stage?.takeIf { it.view === this }?.draw(canvas)
        }
    }

    // MARK: - flyers

    /** A chip that flies to the Mac: a black capsule with a symbol and a word or two. */
    private inner class ChipLook(val icon: ImageVector, val tint: Int, text: String) {
        val label: StaticLayout
        val pad = kit.dp(8f)
        val disc = kit.dp(38f)

        init {
            val paint = kit.text(TdType.subtitle, Ink.text)
            val max = (svc.resources.displayMetrics.widthPixels * 0.6f).toInt()
            val w = min(textWidth(text, paint).toInt() + 2, max)
            label = textLayout(text, paint, w, 1)
        }

        fun size() = PointF(pad + disc + kit.dp(12f) + label.width + kit.dp(20f), disc + pad * 2)
    }

    /**
     * Something on its way into the island. It is picked up (or pops in), then flies on springs that home in on
     * the island's lead slot while the island grows: the vertical spring is stiffer than the horizontal one, so
     * the path curves, and it tilts with its sideways speed.
     */
    private inner class Flyer(val bitmap: Bitmap?, val chip: ChipLook?, from: RectF, fromRadius: Float) {
        private val fx = Motion(0.9f, 150f)
        private val fy = Motion(0.84f, 205f)
        private val fs = Motion(0.92f, 185f)
        val cx = Spring(from.centerX(), fx, 0.3f)
        val cy = Spring(from.centerY(), fy, 0.3f)
        val w = Spring(from.width(), fs, 0.3f)
        val hgt = Spring(from.height(), fs, 0.3f)
        val radius = Spring(fromRadius, fs, 0.3f)
        private val rot = Spring(0f, Motion.standard, 0.02f)
        private val alpha = Spring(1f, Motion.quick, 0.002f)
        private val shadow = Spring(0f, Motion.standard, 0.002f)
        private var flying = false
        private var hold = 0f
        private var t = 0f
        private val w0 = from.width()
        var done = false
            private set

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = kit.dp(1f); color = 0x22FFFFFF
        }
        private val hairAlpha = if (kit.dark) 0x40 else 0x22
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.island }
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        private val m = Matrix()
        private val r = RectF()
        private val slot = RectF()
        private val dark = kit.dark

        /** Picks it up: scales by [scale] on a growing shadow, dips by [drop], and flies after [hold] s. */
        fun lift(scale: Float, drop: Float, hold: Float) {
            this.hold = hold
            w.to(w.value * scale, Motion.emphasized)
            hgt.to(hgt.value * scale, Motion.emphasized)
            cy.to(cy.value + drop, Motion.emphasized)
            radius.to(max(radius.value, kit.dp(22f)), Motion.emphasized)
            shadow.to(1f)
        }

        /** Pops in where it is (from 60 %), holds, then flies. */
        fun pop(hold: Float) {
            this.hold = hold
            val tw = w.value
            val th = hgt.value
            w.snap(tw * 0.6f)
            hgt.snap(th * 0.6f)
            w.to(tw, Motion.emphasized)
            hgt.to(th, Motion.emphasized)
            alpha.snap(0f)
            alpha.to(1f, Motion.quick)
            shadow.snap(0f)
            shadow.to(1f)
        }

        fun rect(out: RectF): RectF {
            out.set(cx.value - w.value / 2, cy.value - hgt.value / 2, cx.value + w.value / 2, cy.value + hgt.value / 2)
            return out
        }

        /** How far it has turned into the island's lead (0 → 1); a chip melts in by this much. */
        fun goo(): Float = if (!flying || slot.width() <= 0f) 0f else ease(1f - (w.value - slot.width()) / max(1f, w0 - slot.width()))

        fun step(dt: Float, isl: Island) {
            t += dt
            if (!flying && t >= hold) flying = true
            if (flying) {
                val rr = isl.leadRect(slot)
                cx.to(slot.centerX(), fx)
                cy.to(slot.centerY(), fy)
                w.to(slot.width(), fs)
                hgt.to(slot.height(), fs)
                radius.to(rr, fs)
                // A picture tilts with its sideways speed; a chip stays level, since it melts into the island.
                rot.to(if (chip != null) 0f else (cx.velocity / kit.dp(95f)).coerceIn(-9f, 9f))
                shadow.to(0f)
                val near = hypot(cx.value - slot.centerX(), cy.value - slot.centerY()) < kit.dp(1.6f) &&
                    abs(w.value - slot.width()) < kit.dp(1.6f) && abs(hgt.value - slot.height()) < kit.dp(1.6f)
                if ((near && isl.contentRevealed) || t > hold + 1.6f) {
                    done = true
                    return
                }
            }
            for (s in arrayOf(cx, cy, w, hgt, radius, rot, alpha, shadow)) s.step(dt)
        }

        val opacity: Float get() = alpha.value

        fun drawSilhouette(c: Canvas) {
            rect(r)
            black.alpha = (255 * alpha.value.coerceIn(0f, 1f)).toInt()
            c.drawRoundRect(r, radius.value, radius.value, black)
        }

        fun draw(c: Canvas) {
            rect(r)
            val a = alpha.value
            if (a <= 0.003f || r.width() < 1f) return
            c.save()
            c.rotate(rot.value, r.centerX(), r.centerY())
            val rad = min(radius.value, min(r.width(), r.height()) / 2)
            if (bitmap != null && shader != null) {
                val sh = shadow.value
                if (sh > 0.003f) {
                    shade.color = Color.BLACK
                    shade.setShadowLayer(kit.dp(22f) * sh, 0f, kit.dp(10f) * sh, Color.argb(((if (dark) 150 else 90) * sh * a).toInt(), 0, 0, 0))
                    c.drawRoundRect(r, rad, rad, shade)
                }
                centerCrop(m, bitmap.width, bitmap.height, r, focusY = 0.35f)
                shader.setLocalMatrix(m)
                paint.shader = shader
                paint.alpha = (255 * a).toInt()
                c.drawRoundRect(r, rad, rad, paint)
                // a light rim keeps a dark picture apart from a dark app while it flies
                hair.alpha = (hairAlpha * a).toInt()
                c.drawRoundRect(r, rad, rad, hair)
            } else if (chip != null) {
                // The symbol slides from the chip's left into the island's lead circle while the label fades.
                val k = goo()
                val d = lerp(chip.disc, min(r.width(), r.height()), k)
                val dcx = lerp(r.left + chip.pad + chip.disc / 2, r.centerX(), k)
                val dcy = r.centerY()
                discPaint.color = Ink.alpha(chip.tint, 0.22f)
                discPaint.alpha = (Color.alpha(discPaint.color) * a).toInt()
                c.drawCircle(dcx, dcy, d / 2, discPaint)
                glyph.color = chip.tint
                glyph.alpha = (255 * a).toInt()
                Glyphs.draw(c, chip.icon, dcx, dcy, d * 0.52f, glyph)
                val la = (1f - k * 1.8f).coerceIn(0f, 1f) * a
                if (la > 0.003f) {
                    c.save()
                    c.clipRect(r)
                    val lp = chip.label.paint
                    lp.alpha = (255 * la).toInt()
                    c.translate(r.left + chip.pad + chip.disc + kit.dp(12f), r.centerY() - chip.label.height / 2f)
                    chip.label.draw(c)
                    c.restore()
                }
            }
            c.restore()
        }
    }
}
