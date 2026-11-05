package app.tapdrop.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.tap.NotchTapService
import app.tapdrop.tap.TapTrace
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.SecondaryButton
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.StatusIndicator
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.HowToTapIllustration
import app.tapdrop.ui.design.StepBadge
import app.tapdrop.ui.design.StepState
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.tdSharedBounds
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max

/**
 * "Test your tap": the Mac's notch light, live (its readings streamed while this screen is open), the room
 * it's in and the gestures that work there, and how each tap went: the light key ✓ or ✗ in plain words,
 * with the numbers behind "Details". Nothing is sent from here: with TapDrop in front, a tap only tests.
 */
@Composable
fun TapTestScreen(onClose: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val haptics = rememberHaptics()
    val tap = rememberTapSetupState()
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    val roomDark by NotchTapService.roomDark.collectAsStateWithLifecycle()
    val lux by NotchTapService.lux.collectAsStateWithLifecycle()
    val lastTap by NotchTapService.lastTap.collectAsStateWithLifecycle()
    val macName = macs.firstOrNull()?.name ?: "your Mac"

    // The Mac streams its notch light to this phone only while this screen is in front.
    LifecycleResumeEffect(Unit) {
        NotchTapService.setDiag(true)
        onPauseOrDispose { NotchTapService.setDiag(false) }
    }

    val trace = remember { LightTrace() }
    LaunchedEffect(Unit) {
        NotchTapService.lux.collect { v -> if (v != null) trace.add(System.currentTimeMillis(), v.first, v.second) }
    }
    // Every tap made while this screen is open, oldest first; the newest is the result shown.
    val openedAt = rememberSaveable { System.currentTimeMillis() }
    val tries = remember { mutableStateListOf<Try>() }
    LaunchedEffect(Unit) {
        NotchTapService.lastTap.collect { t ->
            if (t == null || t.at < openedAt || tries.any { it.trace.at == t.at }) return@collect
            val room = trace.points.lastOrNull()?.room
            tries.add(Try(t, room))
            when (explain(t).outcome) {
                Outcome.Counted -> Unit  // the service already played the tap's own haptic
                Outcome.Calibrating, Outcome.Ignored -> haptics.tick()
                Outcome.Failed -> haptics.reject()
            }
        }
    }

    // How long the link (or its readings) has been missing, to tell "connecting" from "can't reach".
    var waitedLink by remember { mutableStateOf(false) }
    LaunchedEffect(live, tap.ready) {
        waitedLink = false
        if (!live && tap.ready) {
            delay(8000)
            waitedLink = true
        }
    }
    var waitedLight by remember { mutableStateOf(false) }
    LaunchedEffect(live, lux == null) {
        waitedLight = false
        if (live && lux == null) {
            delay(5000)
            waitedLight = true
        }
    }
    val state = when {
        !tap.accessibility -> ScopeState.Off
        tap.stalled -> ScopeState.Stalled
        !live -> if (waitedLink) ScopeState.Unreachable else ScopeState.Connecting
        lux == null -> if (waitedLight) ScopeState.NoReadings else ScopeState.Reading
        else -> ScopeState.Live
    }

    LargeTitleScaffold(
        title = "Test your tap",
        subtitle = "Taps here are only a test: nothing is sent.",
        onBack = onClose,
    ) {
        item("scope") {
            LightCard(state, trace, lux, roomDark, tries, macName)
        }
        when (state) {
            ScopeState.Off -> item("setup") {
                Column(Modifier.padding(top = 16.dp)) {
                    PrimaryButton("Turn on tap to send", onSetup, Modifier.fillMaxWidth())
                }
            }
            ScopeState.Stalled -> item("restart") {
                Column(Modifier.padding(top = 16.dp)) {
                    PrimaryButton("Open Accessibility", { DeviceSupport.openAccessibility(ctx) }, Modifier.fillMaxWidth())
                }
            }
            ScopeState.Unreachable -> if (!DeviceSupport.onWifi(ctx)) item("wifi") {
                Column(Modifier.padding(top = 16.dp)) {
                    SecondaryButton("Wi‑Fi settings", {
                        runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, Modifier.fillMaxWidth())
                }
            }
            else -> Unit
        }
        // Taps can only happen (and show up) while the service runs.
        if (tap.ready) {
            item("resultHeader") {
                SectionHeader("Your tap", action = { if (tries.isNotEmpty()) TriesStrip(tries, Modifier.padding(end = 4.dp)) })
            }
            item("result") {
                ResultCard(
                    tries = tries,
                    earlier = lastTap?.takeIf { it.at < openedAt },
                    canTap = state == ScopeState.Live,
                    lightNow = lux,
                )
            }
        }
        item("howHeader") { SectionHeader("How to tap") }
        item("how") { HowToTap() }
        item("safe") { SafeNote() }
    }
}

// MARK: - model

private enum class ScopeState { Off, Stalled, Connecting, Unreachable, Reading, NoReadings, Live }

/** One tap made on this screen, with the room's light at that moment. */
private class Try(val trace: TapTrace, val room: Float?)

/** The notch's readings, oldest first (wall-clock ms). Bump-only state: drawing reads [version]. */
@Stable
private class LightTrace {
    class Point(val at: Long, val lux: Float, val room: Float)

    val points = ArrayDeque<Point>()
    var version by mutableIntStateOf(0)
        private set

    fun add(at: Long, lux: Float, room: Float) {
        points.addLast(Point(at, lux, room))
        while (points.size > 2 && points[1].at < at - KEEP_MS) points.removeFirst()
        version++
    }

    companion object {
        const val KEEP_MS = 16_000L
    }
}

/** The scope shows the last 12 s. */
private const val WINDOW_MS = 12_000L

/** Lux on a log scale, 0 (dark) … 1 (1500 lux, a phone's light at contact range). */
private fun norm(lux: Float): Float = (ln(1f + lux.coerceAtLeast(0f)) / ln(1f + 1500f)).coerceIn(0f, 1f)

/**
 * How a tap went: it counted; it was a calibration sample (neutral, never an error); it was ignored on purpose
 * (the phone lay still, was locked…); or it didn't count and there's something to do differently.
 */
private enum class Outcome { Counted, Calibrating, Ignored, Failed }

private class Explained(val outcome: Outcome, val title: String, val body: String)

/** A tap's result in plain words (the same words as the Mac's): what happened and what to do. */
private fun explain(t: TapTrace): Explained {
    if (t.ok) return Explained(
        Outcome.Counted, "That counted",
        "Your Mac saw your screen light up and go dark. In any other app, this tap sends what's on screen.",
    )
    return when (t.why) {
        "calibrating" -> Explained(
            Outcome.Calibrating, "Calibrating",
            "Your Mac used that tap to learn your screen's light. Nothing was sent.",
        )
        "not-seen", "no-light-sent" -> Explained(Outcome.Failed, "Your Mac didn't see your screen", "Press the top of the screen flat on the notch.")
        "stayed-lit", "no-key-off", "early-ok" -> Explained(Outcome.Failed, "Almost", TapCopy.KEEP_ON)
        "facing", "screen-only" -> Explained(Outcome.Failed, "Turn your phone around", "Its screen goes against the notch.")
        "still" -> Explained(
            Outcome.Ignored, "Ignored: your phone was still",
            "Something covered the notch while your phone lay still. That's how a hand is kept from sending.",
        )
        "locked" -> Explained(Outcome.Ignored, "Your phone was locked", "Unlock it, then tap.")
        "screen-off" -> Explained(Outcome.Ignored, "Your screen was off", "Turn the screen on, then tap.")
        "in-call" -> Explained(Outcome.Ignored, "Paused during calls", "Taps don't count while you're on a call.")
        "other-phone" -> Explained(Outcome.Failed, "Another phone was first", "Try again.")
        "cooldown" -> Explained(Outcome.Failed, "Too quick", TapCopy.TRY_AGAIN)
        "busy" -> Explained(Outcome.Failed, "TapDrop was busy", TapCopy.TRY_AGAIN)
        "no-light" -> Explained(Outcome.Failed, "Your screen couldn't light up", TapCopy.TRY_AGAIN)
        "timeout" -> Explained(Outcome.Failed, "No answer from your Mac", "Check that your Mac is awake and on the same Wi‑Fi.")
        "update-mac" -> Explained(
            Outcome.Failed, "Update TapDrop on your Mac",
            "This Mac's TapDrop can't check the light yet. Update it to keep tapping.",
        )
        else -> Explained(Outcome.Failed, "That tap didn't count", "Try again.")
    }
}

private fun lightName(src: String?): String = when (src) {
    "screen" -> "Screen"
    null -> "Didn't light up"
    else -> src
}

private fun luxText(v: Float): String = "%,.0f lux".format(v)

// MARK: - the live light card

@Composable
private fun LightCard(
    state: ScopeState,
    trace: LightTrace,
    lux: Pair<Float, Float>?,
    roomDark: Boolean?,
    tries: List<Try>,
    macName: String,
) {
    val c = Td.colors
    val reading = lux?.first ?: 0f
    val room = lux?.second ?: 0f
    val lit = roomDark == false
    val covered = state == ScopeState.Live && lit && room >= 6f && reading <= max(2f, 0.3f * room)
    val bright = state == ScopeState.Live && reading >= max(250f, 3f * room)
    val (title, detail, tone) = when (state) {
        ScopeState.Off -> Triple("Tap to send is off", "Turn it on to test your tap.", StatusTone.Offline)
        ScopeState.Stalled -> Triple("Tap to send stopped", "Android stopped it. Turn it off and on in Accessibility.", StatusTone.Error)
        ScopeState.Connecting -> Triple("Connecting to your Mac…", macName, StatusTone.Searching)
        ScopeState.Unreachable -> Triple("Can't reach your Mac", "Open TapDrop on $macName, on the same Wi‑Fi as this phone.", StatusTone.Offline)
        ScopeState.Reading -> Triple("Reading the notch's light…", macName, StatusTone.Searching)
        ScopeState.NoReadings -> Triple(
            "No light readings from your Mac",
            "Tapping needs a MacBook with a notch, running the latest TapDrop.",
            StatusTone.Offline,
        )
        ScopeState.Live -> Triple(
            when {
                covered -> "Something's over the notch"
                bright -> "Bright light at the notch"
                else -> "Watching for your screen's light"
            },
            "Live from $macName",
            StatusTone.Live,
        )
    }

    // The Mac's display sits inset in the card like a screen (corners concentric with the card's), the
    // caption below it on the card itself.
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(6.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))) {
            NotchScope(
                state = state,
                trace = trace,
                level = norm(reading),
                covered = covered,
                tries = tries,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconChip(
                    Icons.Rounded.Flare, Brand.Blue, size = 44.dp, iconSize = 24.dp,
                    modifier = Modifier.tdSharedBounds(TAP_TEST_CHIP_KEY),
                )
                Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(16.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) {
                    StatusIndicator(tone, modifier = Modifier.size(14.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn(tween(200, delayMillis = 90)) + slideInVertically { it / 3 } togetherWith fadeOut(tween(90)) },
                    label = "lightTitle",
                ) { Text(it, style = TdType.subtitle, color = c.text) }
                Text(detail, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * The Mac's display, up close: the notch, its light (a bloom that follows the reading, a shade when something
 * covers it), and the last 12 s of readings drawn across the screen, with each tap marked where it happened.
 */
@Composable
private fun NotchScope(
    state: ScopeState,
    trace: LightTrace,
    level: Float,
    covered: Boolean,
    tries: List<Try>,
    modifier: Modifier = Modifier,
) {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val active = state == ScopeState.Live
    val glow by animateFloatAsState(
        if (active) level else 0f,
        if (reduced) tween(120) else spring(dampingRatio = 0.9f, stiffness = 380f),
        label = "notchGlow",
    )
    val shade by animateFloatAsState(if (covered) 1f else 0f, if (reduced) tween(120) else spring(stiffness = 300f), label = "shade")
    val dim by animateFloatAsState(if (active) 0f else 1f, Td.spring(), label = "scopeDim")
    // Taller while there's a live trace to show; a calm strip otherwise.
    val aspect by animateFloatAsState(if (active) 1.72f else 2.5f, Td.spring(), label = "scopeAspect")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameMillis { f ->
                // ~30 fps is plenty for light readings that arrive 8 times a second.
                if (f - last >= 32) {
                    last = f
                    now = System.currentTimeMillis()
                }
            }
        }
    }
    // Connecting or waiting for the first reading: a comet of light runs along the idle line.
    val searching = state == ScopeState.Connecting || state == ScopeState.Reading
    val sweep = if (searching && !reduced) {
        rememberInfiniteTransition(label = "scopeSearch")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing)), label = "sweep").value
    } else null
    val marks = tries.map { it.trace.at to explain(it.trace).outcome }
    val success = c.success
    val danger = c.danger
    val neutral = Brand.Sky
    Canvas(
        modifier
            .aspectRatio(aspect)
            .semantics { contentDescription = "Light at your Mac's notch, the last 12 seconds" },
    ) {
        val w = size.width
        val h = size.height
        drawDisplay(dim)
        drawNotch(glow, shade, dim)
        val area = Rect(16.dp.toPx(), h * 0.42f, w - 20.dp.toPx(), h - 18.dp.toPx())
        if (dim > 0.01f) drawIdle(area, dim, sweep)
        if (trace.version >= 0 && dim < 0.99f) {
            drawTrace(trace, now, area, 1f - dim, marks, success, danger, neutral)
        }
    }
}

/**
 * No readings: a faint flat line, like a scope with nothing plugged in. While connecting, a comet of light
 * runs along it ([sweep] 0…1, null to keep still).
 */
private fun DrawScope.drawIdle(area: Rect, dim: Float, sweep: Float?) {
    val y = area.top + area.height * 0.62f
    drawLine(
        Brush.horizontalGradient(
            0f to Color.Transparent, 0.2f to Color.White.copy(alpha = 0.16f * dim),
            0.8f to Color.White.copy(alpha = 0.16f * dim), 1f to Color.Transparent,
            startX = area.left, endX = area.right,
        ),
        Offset(area.left, y), Offset(area.right, y),
        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())),
    )
    if (sweep == null) return
    // Fades in and out at the ends of its run, so the loop has no seam.
    val a = dim * smoothStep(sweep / 0.15f) * smoothStep((1f - sweep) / 0.15f)
    if (a <= 0.01f) return
    val head = area.left + area.width * sweep
    val tail = area.width * 0.28f
    drawLine(
        Brush.horizontalGradient(
            0f to Color.Transparent, 0.8f to Brand.Violet.copy(alpha = 0.55f * a), 1f to Color.White.copy(alpha = 0.95f * a),
            startX = head - tail, endX = head,
        ),
        Offset(head - tail, y), Offset(head, y),
        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
    )
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * a), Brand.Violet.copy(alpha = 0.4f * a), Color.Transparent), center = Offset(head, y), radius = 10.dp.toPx()),
        10.dp.toPx(), Offset(head, y),
    )
}

private fun DrawScope.drawDisplay(dim: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0B16), Color(0xFF171233)), 0f, h))
    val k = 1f - dim * 0.7f
    fun glow(color: Color, fx: Float, fy: Float, fr: Float, a: Float) {
        val center = Offset(w * fx, h * fy)
        val r = w * fr
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a * k), Color.Transparent), center = center, radius = r), r, center)
    }
    glow(Brand.Blue, 0.08f, 1.05f, 0.62f, 0.30f)
    glow(Brand.Violet, 0.7f, 1.0f, 0.66f, 0.30f)
    glow(Brand.Orchid, 1.02f, 0.62f, 0.38f, 0.14f)
}

private fun DrawScope.drawNotch(glow: Float, shade: Float, dim: Float) {
    val w = size.width
    val notchW = 0.27f * w
    val notchH = 0.085f * w
    val cx = w / 2
    val center = Offset(cx, notchH * 0.62f)
    // The light reaching the sensor: a soft violet glow for a room, white heat for a phone's light up close.
    if (glow > 0.01f) {
        val hot = smoothStep((glow - 0.74f) / 0.2f)
        val keep = 1f - shade * 0.85f
        val r = w * (0.26f + 0.36f * glow + 0.16f * hot)
        drawCircle(
            Brush.radialGradient(
                0f to androidx.compose.ui.graphics.lerp(Color(0xFFB9A6FF), Color.White, hot).copy(alpha = (0.16f + 0.30f * glow + 0.50f * hot) * keep),
                0.28f to Brand.Violet.copy(alpha = (0.08f + 0.24f * glow + 0.22f * hot) * keep),
                0.62f to Brand.Blue.copy(alpha = (0.03f + 0.08f * glow) * keep),
                1f to Color.Transparent,
                center = center, radius = r,
            ),
            radius = r, center = center,
        )
    }
    // Something over the notch: a soft shadow around it.
    if (shade > 0.01f) {
        val r = w * 0.36f
        drawCircle(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.75f * shade), 0.6f to Color.Black.copy(alpha = 0.35f * shade), 1f to Color.Transparent,
                center = center, radius = r,
            ),
            radius = r, center = center,
        )
    }
    // The notch, with its camera and, beside it, the light sensor that does the seeing.
    val nr = notchH * 0.42f
    val rect = Rect(cx - notchW / 2, -1f, cx + notchW / 2, notchH)
    drawPath(
        Path().apply { addRoundRect(RoundRect(rect, CornerRadius.Zero, CornerRadius.Zero, CornerRadius(nr), CornerRadius(nr))) },
        Color.Black,
    )
    val cam = Offset(cx, notchH * 0.46f)
    drawCircle(Color(0xFF1B2333), notchH * 0.15f, cam)
    drawCircle(Color(0xFF3A4A6E).copy(alpha = 0.6f), notchH * 0.05f, Offset(cam.x - notchH * 0.04f, cam.y - notchH * 0.04f))
    val sensor = Offset(cx + notchW * 0.2f, cam.y)
    val eye = (0.25f + 0.75f * glow) * (1f - dim * 0.8f)
    drawCircle(Brand.Violet.copy(alpha = 0.55f * eye), notchH * 0.16f, sensor)
    drawCircle(Color(0xFFD9CCFF).copy(alpha = eye), notchH * 0.065f, sensor)
}

private fun DrawScope.drawTrace(
    trace: LightTrace,
    now: Long,
    area: Rect,
    alpha: Float,
    marks: List<Pair<Long, Outcome>>,
    success: Color,
    danger: Color,
    neutral: Color,
) {
    val pts = trace.points
    if (pts.isEmpty()) return
    fun x(t: Long) = area.right - (now - t).toFloat() / WINDOW_MS * area.width
    fun y(v: Float) = area.bottom - norm(v) * area.height
    // A reading holds until the next one (repeats aren't sent again); each change is a quick S-curve.
    val ramp = 160f / WINDOW_MS * area.width
    val before = pts.indexOfLast { it.at <= now - WINDOW_MS }
    val path = Path()
    val startX: Float
    var cur: Float
    var lastX: Float
    if (before >= 0) {
        cur = y(pts[before].lux)
        startX = area.left - ramp
    } else {
        cur = y(pts[0].lux)
        startX = x(pts[0].at)
    }
    path.moveTo(startX, cur)
    lastX = startX
    for (i in (if (before >= 0) before + 1 else 1) until pts.size) {
        val ny = y(pts[i].lux)
        if (kotlin.math.abs(ny - cur) < 0.5f) continue
        val px = x(pts[i].at)
        val sx = max(px - ramp / 2, lastX)
        val ex = max(px + ramp / 2, sx + 1f)
        val mid = (sx + ex) / 2
        path.lineTo(sx, cur)
        path.cubicTo(mid, cur, mid, ny, ex, ny)
        cur = ny
        lastX = ex
    }
    path.lineTo(max(area.right, lastX), cur)
    val head = Offset(area.right, cur)
    // Where the trace begins: the left edge, or mid-way when readings started less than 12 s ago.
    val fadeFrom = max(area.left, startX)
    val fadeTo = fadeFrom + area.width * 0.22f
    val bounds = Rect(0f, 0f, size.width, size.height)
    val dstIn = androidx.compose.ui.graphics.BlendMode.DstIn
    // The room's level, dashed, fading in from the left like the time it stands for.
    val roomY = y(pts.last().room)
    drawLine(
        Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.White.copy(alpha = 0.26f * alpha), startX = area.left, endX = area.left + area.width * 0.22f),
        Offset(area.left, roomY), Offset(area.right, roomY),
        strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
    )
    // The light under the line, soft at both ends.
    drawIntoCanvas { it.saveLayer(bounds, androidx.compose.ui.graphics.Paint()) }
    val fill = Path().apply {
        addPath(path)
        lineTo(max(area.right, lastX), area.bottom)
        lineTo(startX, area.bottom)
        close()
    }
    clipRect(area.left - 2f, 0f, area.right, size.height) {
        drawPath(fill, Brush.verticalGradient(listOf(Brand.Violet.copy(alpha = 0.24f * alpha), Brand.Violet.copy(alpha = 0f)), area.top, area.bottom))
    }
    // (Stops kept in order even when the trace has only just begun near the right edge.)
    val inEnd = kotlin.math.min(fadeTo, area.right - area.width * 0.08f - 1f).coerceAtLeast(fadeFrom + 0.5f)
    val outStart = max(area.right - area.width * 0.08f, inEnd + 0.5f)
    val outEnd = max(area.right, outStart + 0.5f)
    drawRect(
        Brush.horizontalGradient(
            fadeFrom / size.width to Color.Transparent,
            inEnd / size.width to Color.Black,
            outStart / size.width to Color.Black,
            outEnd / size.width to Color.Transparent,
            startX = 0f, endX = size.width,
        ),
        blendMode = dstIn,
    )
    drawIntoCanvas { it.restore() }
    // Everything in the trace fades out toward the left, where its time runs out.
    drawIntoCanvas { it.saveLayer(bounds, androidx.compose.ui.graphics.Paint()) }
    clipRect(area.left - 2f, 0f, area.right + 14.dp.toPx(), size.height) {
        // Taps, where they happened.
        marks.forEach { (at, outcome) ->
            val mx = x(at)
            if (mx < area.left - 4f || mx > area.right + 4f) return@forEach
            val color = when (outcome) {
                Outcome.Counted -> success
                Outcome.Calibrating -> Brand.Violet
                Outcome.Ignored -> neutral
                Outcome.Failed -> danger
            }
            val top = area.top - 2.dp.toPx()
            drawLine(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.7f * alpha), color.copy(alpha = 0f)), top, area.bottom),
                Offset(mx, top), Offset(mx, area.bottom), strokeWidth = 1.5.dp.toPx(),
            )
            drawCircle(color.copy(alpha = 0.25f * alpha), 8.dp.toPx(), Offset(mx, top))
            drawCircle(color.copy(alpha = alpha), 4.dp.toPx(), Offset(mx, top))
        }
        // The line itself, glowing.
        glowStroke(path, Brand.Violet.copy(alpha = 0.7f * alpha), 3.dp.toPx(), 6.dp.toPx())
        drawPath(
            path,
            Brush.horizontalGradient(listOf(Brand.Blue.copy(alpha = alpha), Color(0xFFC9B8FF).copy(alpha = alpha)), area.left, area.right),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
    drawRect(
        Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = fadeFrom, endX = fadeTo),
        blendMode = dstIn,
    )
    drawIntoCanvas { it.restore() }
    // Now: a bead of light at the end of the line.
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = alpha), Brand.Violet.copy(alpha = 0.5f * alpha), Color.Transparent), center = head, radius = 9.dp.toPx()),
        9.dp.toPx(), head,
    )
    drawCircle(Color.White.copy(alpha = alpha), 2.6.dp.toPx(), head)
}

private fun smoothStep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun DrawScope.glowStroke(path: Path, color: Color, width: Float, blur: Float) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            this.color = color.toArgb()
            maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
}

// MARK: - result

@Composable
private fun ResultCard(tries: List<Try>, earlier: TapTrace?, canTap: Boolean, lightNow: Pair<Float, Float>?) {
    val c = Td.colors
    val latest = tries.lastOrNull()
    var details by rememberSaveable { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (latest != null) 4.dp else 18.dp)) {
        AnimatedContent(
            targetState = latest,
            contentKey = { it?.trace?.at },
            transitionSpec = {
                (fadeIn(tween(240, 80)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
            },
            label = "result",
        ) { t ->
            if (t == null) Waiting(canTap, earlier) else TapOutcome(t.trace)
        }
        if (latest != null) {
            val rot by animateFloatAsState(if (details) 180f else 0f, Td.spring(), label = "detailsChevron")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(min = 48.dp)
                    .pressable(RoundedCornerShape(14.dp), pressedScale = 0.98f, onClickLabel = if (details) "Hide details" else "Show details") { details = !details },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Details", style = TdType.label, color = c.accentText, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.accentText, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rot })
            }
            AnimatedVisibility(
                visible = details,
                enter = fadeIn() + expandVertically(Td.spring()),
                exit = fadeOut(tween(100)) + shrinkVertically(Td.spring()),
            ) {
                DetailsTable(latest, lightNow)
            }
        }
    }
}

@Composable
private fun Waiting(canTap: Boolean, earlier: TapTrace?) {
    val c = Td.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (canTap) Ripples(Modifier.fillMaxSize(), color = c.accent, rings = 2, periodMs = 2400, start = 0.6f)
            IconChip(Icons.Rounded.TouchApp, c.accent, size = 40.dp, shape = TdShapes.Circle, iconSize = 22.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                if (canTap) "Waiting for your tap" else "Ready when your Mac is",
                style = TdType.subtitle, color = c.text,
            )
            Text(
                if (canTap) "${TapCopy.PRIMARY} ${TapCopy.HOLD}"
                else "Your taps show up here once the notch's light is live.",
                style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp),
            )
            if (earlier != null) {
                Text(
                    "Earlier, ${agoText(earlier.at)}: ${if (earlier.ok) "counted" else "didn't count"}",
                    style = TdType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TapOutcome(t: TapTrace) {
    val c = Td.colors
    val e = explain(t)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            when (e.outcome) {
                Outcome.Counted -> CheckBurst(visible = true, size = 38.dp)
                Outcome.Failed -> FailMark()
                Outcome.Calibrating -> IconChip(Icons.Rounded.Tune, c.accent, size = 44.dp, shape = TdShapes.Cookie9, iconSize = 22.dp)
                Outcome.Ignored -> IconChip(Icons.Rounded.Shield, Brand.Sky, size = 44.dp, shape = TdShapes.Cookie6, iconSize = 22.dp)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(e.title, style = TdType.subtitle, color = c.text)
            Text(e.body, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            Text(agoText(t.at).replaceFirstChar { it.uppercase() }, style = TdType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** A red disc with a cross that pops in and shakes once (still under reduced motion). */
@Composable
private fun FailMark() {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val pop = remember { Animatable(if (reduced) 1f else 0.4f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
        for (x in listOf(-6f, 5f, -3f, 2f, 0f)) shake.animateTo(x, tween(55))
    }
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = pop.value; scaleY = pop.value
                translationX = shake.value.dp.toPx()
            }
            .background(c.danger, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

/**
 * This screen's taps at a glance, for the section header: a dot per tap (newest last; green counted, red
 * didn't, blue ignored on purpose) and "2 of 3 counted".
 */
@Composable
private fun TriesStrip(tries: List<Try>, modifier: Modifier = Modifier) {
    val c = Td.colors
    val shown = tries.takeLast(8)
    val counted = tries.count { it.trace.ok }
    // Calibration samples aren't tries that should have counted.
    val scored = tries.count { explain(it.trace).outcome != Outcome.Calibrating }
    val summary = if (scored == 0) "Calibrating" else "$counted of $scored counted"
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = if (scored == 0) "Calibrating" else "$counted of $scored taps counted"
        },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEachIndexed { i, t ->
            val color = when (explain(t.trace).outcome) {
                Outcome.Counted -> c.success
                Outcome.Calibrating -> c.accent
                Outcome.Ignored -> Brand.Sky
                Outcome.Failed -> c.danger
            }
            val newest = i == shown.lastIndex
            Box(
                Modifier
                    .size(8.dp)
                    .background(color.copy(alpha = if (newest) 1f else 0.6f), CircleShape),
            )
        }
        Text(
            summary,
            style = TdType.numeric, color = c.textSecondary,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun DetailsTable(t: Try, lightNow: Pair<Float, Float>?) {
    val c = Td.colors
    val time = remember(t.trace.at) { DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(t.trace.at)) }
    val rows = buildList {
        add("Light used" to lightName(t.trace.src))
        t.trace.peak?.let { add("Brightest at the notch" to luxText(it)) }
        t.trace.need?.let { if (it > 0f) add("Needed" to luxText(it)) }
        t.room?.let { add("Room light" to luxText(it)) }
        lightNow?.let { add("Light now" to luxText(it.first)) }
        add("Time" to time)
        t.trace.why?.let { add("Reason" to it) }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(
                    v,
                    style = if (k == "Reason") TdType.mono.copy(fontSize = TdType.bodySmall.fontSize) else TdType.numeric,
                    color = c.text,
                )
            }
        }
    }
}

// MARK: - how to tap

/** The one gesture, drawn from your seat (tap the drawing to play it again), and its two steps. */
@Composable
private fun HowToTap() {
    var replay by remember { mutableIntStateOf(0) }
    val inner = RoundedCornerShape(22.dp)
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .pressable(inner, pressedScale = 0.985f, onClickLabel = "Play the gesture again") { replay++ },
        ) {
            key(replay) { HowToTapIllustration(loops = 1) }
        }
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HowStep(1, TapCopy.PRIMARY)
            HowStep(2, TapCopy.HOLD)
        }
    }
}

@Composable
private fun HowStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepBadge(n, StepState.Current)
        Text(text, style = TdType.body, color = Td.colors.text, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun SafeNote() {
    val c = Td.colors
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 24.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Shield, null, tint = c.successText, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Text(TapCopy.SAFE, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp))
    }
}
