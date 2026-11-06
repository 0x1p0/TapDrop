package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** The link between this phone and the Mac, as [DeviceHero] draws it. */
enum class LinkState {
    /** Looking for the Mac: a dashed thread with light running along it. */
    Searching,
    /** Reachable, tap to send not live: a quiet steady thread, the notch faintly lit. */
    Connected,
    /** Tap to send is live: the thread glows and breathes, the notch is lit. */
    Live,
    /** Not reachable: a broken gray thread, both devices dimmed. */
    Offline,
    /** Sending to the Mac: light fills the thread from the phone to the notch. */
    Sending,
    /** Receiving from the Mac: light fills the thread from the notch to the phone. */
    Receiving,
    /** Just sent: the notch grows an island with a check. */
    Sent,
    /** Just received: a check bursts on the phone. */
    Received,
    /** Something failed: the thread flashes red. */
    Failed,
}

private data class MacPalette(
    val lidTop: Color,
    val lidBottom: Color,
    val lidEdge: Color,
    val baseTop: Color,
    val baseBottom: Color,
    val scoop: Color,
    val wallTop: Color,
    val wallBottom: Color,
    val phoneBody: Color,
    val phoneEdge: Color,
    val shadow: Color,
)

@Composable
private fun macPalette(): MacPalette {
    val dark = Td.colors.isDark
    return if (dark) MacPalette(
        lidTop = Color(0xFF34353A), lidBottom = Color(0xFF232327), lidEdge = Color.White.copy(alpha = 0.16f),
        baseTop = Color(0xFF4A4B51), baseBottom = Color(0xFF1E1E22), scoop = Color(0xFF151518),
        wallTop = Color(0xFF0B0B0E), wallBottom = Color(0xFF16161A),
        phoneBody = Color(0xFF26262B), phoneEdge = Color.White.copy(alpha = 0.18f),
        shadow = Color.Black.copy(alpha = 0.55f),
    ) else MacPalette(
        lidTop = Color(0xFFEAE9E5), lidBottom = Color(0xFFD0CEC8), lidEdge = Color.White,
        baseTop = Color(0xFFF5F4F0), baseBottom = Color(0xFFC1BFB9), scoop = Color(0xFFACAAA4),
        wallTop = Color(0xFF111114), wallBottom = Color(0xFF1C1C21),
        phoneBody = Color(0xFF2A2A2F), phoneEdge = Color.White.copy(alpha = 0.35f),
        shadow = Color(0xFF1A1814).copy(alpha = 0.22f),
    )
}

/**
 * The hero illustration: a MacBook (drawn, not an icon) with its notch, this phone beside it, and the link
 * between them drawn as a thread of light. The notch glows when tap to send is live; transfers run light
 * along the thread ([progress] 0…1, or null for indeterminate); [LinkState.Sent] grows an island out of the
 * notch, [LinkState.Received] bursts a check on the phone. Aspect ratio 1.6 : 1, fill the width you give it.
 *
 * Only the live breathing and transfers animate continuously; everything else settles.
 */
@Composable
fun DeviceHero(
    state: LinkState,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    contentDescription: String? = null,
) {
    val c = Td.colors
    val pal = macPalette()
    val reduced = Td.reducedMotion
    val glow by animateFloatAsState(
        when (state) {
            LinkState.Live, LinkState.Sent -> 1f
            LinkState.Sending, LinkState.Receiving, LinkState.Received -> 0.85f
            LinkState.Connected -> 0.42f
            LinkState.Searching -> 0.2f
            LinkState.Failed -> 0.25f
            LinkState.Offline -> 0f
        },
        TdMotion.gentle(), label = "notchGlow",
    )
    val dim by animateFloatAsState(if (state == LinkState.Offline) 1f else 0f, TdMotion.gentle(), label = "dim")
    val island by animateFloatAsState(if (state == LinkState.Sent) 1f else 0f, TdMotion.emphasized(), label = "island")
    val glyph by animateFloatAsState(
        if (state == LinkState.Received || state == LinkState.Searching) 0f else 1f,
        TdMotion.standard(), label = "glyph",
    )
    val thread by animateFloatAsState(
        when (state) {
            LinkState.Offline -> 0f
            LinkState.Searching -> 0.55f
            LinkState.Connected -> 0.6f
            LinkState.Failed -> 0.8f
            else -> 1f
        },
        TdMotion.standard(), label = "thread",
    )
    val threadColor by animateColorAsState(if (state == LinkState.Failed) c.danger else Brand.Light, TdMotion.standard(), label = "threadColor")
    val fill by animateFloatAsState(
        when (state) {
            LinkState.Sending, LinkState.Receiving -> (progress ?: 0f).coerceIn(0f, 1f)
            LinkState.Sent, LinkState.Received -> 1f
            else -> 0f
        },
        TdMotion.standard(), label = "fill",
    )

    // Continuous motion only where it means something: live, searching and indeterminate transfers.
    val moving = !reduced && (state == LinkState.Live || state == LinkState.Searching ||
        ((state == LinkState.Sending || state == LinkState.Receiving) && progress == null))
    val loop = if (moving) {
        val t = rememberInfiniteTransition(label = "hero")
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (state == LinkState.Live) 3200 else 1600, easing = LinearEasing)),
            label = "loop",
        ).value
    } else 0f

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val wDp = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val g = HeroGeometry(size.width)
            drawTableShadow(g, pal)
            val breath = if (state == LinkState.Live && !reduced) 0.82f + 0.18f * sin(loop * 2 * PI.toFloat()).let { (it + 1f) / 2f } else 1f
            drawMacBook(g, pal, notchGlow = glow * breath, island = island, dim = dim, success = c.success)
            drawPhone(g, pal, dim = dim, lit = glow, glyph = glyph)
            drawThread(g, state, thread, threadColor, fill, progress, loop, c.textTertiary)
        }
        // The phone's screen: the expressive loading indicator while searching, a check when something arrived.
        val phone = HeroGeometry(1f).phone
        AnimatedVisibility(
            visible = state == LinkState.Searching,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier.offset(
                x = wDp * phone.center.x - wDp * 0.05f,
                y = wDp * phone.center.y - wDp * 0.05f,
            ),
        ) {
            TdLoadingIndicator(Modifier.size(wDp * 0.1f), color = Brand.Light.copy(alpha = 0.95f).let { lighten(it, 0.25f) })
        }
        CheckBurst(
            visible = state == LinkState.Received,
            size = wDp * 0.07f,
            modifier = Modifier.offset(
                x = wDp * (phone.center.x) - wDp * 0.07f * 0.9f,
                y = wDp * (phone.center.y) - wDp * 0.07f * 0.9f,
            ),
        )
    }
}

/** Geometry of [DeviceHero] for a canvas [w] wide (height = w / 1.6). */
private class HeroGeometry(w: Float) {
    val lidW = 0.60f * w
    val lidH = lidW / 1.52f
    val lid = Rect(Offset(0.085f * w, 0.165f * w), Size(lidW, lidH))
    val bezel = 0.026f * lidW
    val chin = 0.045f * lidW
    val display = Rect(lid.left + bezel, lid.top + bezel, lid.right - bezel, lid.bottom - chin)
    val notchW = 0.15f * display.width
    val notchH = 0.052f * display.width
    val notch = Rect(display.center.x - notchW / 2, display.top, display.center.x + notchW / 2, display.top + notchH)
    val baseH = 0.026f * w
    val base = Rect(lid.left - 0.045f * lidW, lid.bottom, lid.right + 0.045f * lidW, lid.bottom + baseH)
    val phoneW = 0.128f * w
    val phoneH = phoneW * 2.08f
    val phone = Rect(Offset(lid.right + 0.06f * w, base.bottom + 0.014f * w - phoneH), Size(phoneW, phoneH))
    val threadStart = Offset(phone.center.x, phone.top - 0.016f * w)
    val threadEnd = Offset(notch.center.x, lid.top - 0.004f * w)
    val threadPath = Path().apply {
        moveTo(threadStart.x, threadStart.y)
        cubicTo(
            threadStart.x, threadStart.y - 0.24f * w,
            threadEnd.x, threadEnd.y - 0.20f * w,
            threadEnd.x, threadEnd.y,
        )
    }
}

private fun DrawScope.drawTableShadow(g: HeroGeometry, pal: MacPalette) {
    val cy = g.base.bottom + size.width * 0.006f
    val r = g.base.width * 0.62f
    drawOval(
        Brush.radialGradient(listOf(pal.shadow, Color.Transparent), center = Offset(g.base.center.x, cy), radius = r),
        topLeft = Offset(g.base.center.x - r, cy - r * 0.08f),
        size = Size(r * 2, r * 0.16f),
    )
    val pr = g.phone.width * 0.9f
    drawOval(
        Brush.radialGradient(listOf(pal.shadow, Color.Transparent), center = Offset(g.phone.center.x, g.phone.bottom), radius = pr),
        topLeft = Offset(g.phone.center.x - pr, g.phone.bottom - pr * 0.1f),
        size = Size(pr * 2, pr * 0.2f),
    )
}

private fun DrawScope.drawMacBook(g: HeroGeometry, pal: MacPalette, notchGlow: Float, island: Float, dim: Float, success: Color) {
    val lidR = g.lidW * 0.035f
    // lid
    val lidPath = Path().apply {
        addRoundRect(
            RoundRect(
                g.lid,
                topLeft = CornerRadius(lidR), topRight = CornerRadius(lidR),
                bottomLeft = CornerRadius(lidR * 0.35f), bottomRight = CornerRadius(lidR * 0.35f),
            )
        )
    }
    drawPath(lidPath, Brush.verticalGradient(listOf(pal.lidTop, pal.lidBottom), g.lid.top, g.lid.bottom))
    drawPath(lidPath, pal.lidEdge.copy(alpha = pal.lidEdge.alpha * 0.6f), style = Stroke(1.dp.toPx()))
    // bezel
    val bezelRect = Rect(g.lid.left + g.bezel * 0.35f, g.lid.top + g.bezel * 0.35f, g.lid.right - g.bezel * 0.35f, g.lid.bottom - g.chin * 0.55f)
    drawRoundRect(Color(0xFF050507), bezelRect.topLeft, bezelRect.size, CornerRadius(lidR * 0.8f))
    // display
    val dispR = g.lidW * 0.022f
    val displayPath = Path().apply {
        addRoundRect(
            RoundRect(
                g.display,
                topLeft = CornerRadius(dispR), topRight = CornerRadius(dispR),
                bottomLeft = CornerRadius(dispR * 0.3f), bottomRight = CornerRadius(dispR * 0.3f),
            )
        )
    }
    clipPath(displayPath) {
        drawWallpaper(g.display, pal, dim)
        // the notch's light: a wide violet bloom with a bright core right under the notch
        if (notchGlow > 0.01f) {
            val gc = Offset(g.notch.center.x, g.notch.bottom)
            val gr = g.display.width * 0.55f
            drawCircle(
                Brush.radialGradient(
                    0f to Brand.Light.copy(alpha = 0.78f * notchGlow),
                    0.3f to Brand.Light.copy(alpha = 0.34f * notchGlow),
                    0.65f to Brand.Light.copy(alpha = 0.10f * notchGlow),
                    1f to Color.Transparent,
                    center = gc, radius = gr,
                ),
                radius = gr, center = gc,
            )
            val cr = g.notchW * 0.75f
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.55f * notchGlow),
                    0.4f to Color(0xFFEEF1FF).copy(alpha = 0.25f * notchGlow),
                    1f to Color.Transparent,
                    center = gc, radius = cr,
                ),
                radius = cr, center = gc,
            )
        }
        // glass sheen
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.06f), 0.4f to Color.Transparent,
                start = g.display.topLeft, end = Offset(g.display.left + g.display.width * 0.55f, g.display.bottom),
            ),
            g.display.topLeft, g.display.size,
        )
        // notch + island
        val iw = g.notchW + (g.notchW * 1.45f) * island
        val ih = g.notchH + g.notchH * 0.55f * island
        val nr = ih * 0.46f
        val notchRect = Rect(g.notch.center.x - iw / 2, g.display.top - 1f, g.notch.center.x + iw / 2, g.display.top + ih)
        val notchPath = Path().apply {
            addRoundRect(RoundRect(notchRect, topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero, bottomLeft = CornerRadius(nr), bottomRight = CornerRadius(nr)))
        }
        drawPath(notchPath, Color.Black)
        if (notchGlow > 0.01f) {
            // a hairline of light along the notch's lower edge
            drawLine(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.55f * notchGlow), Color.Transparent),
                    notchRect.left, notchRect.right,
                ),
                Offset(notchRect.left + nr, notchRect.bottom + 0.6f), Offset(notchRect.right - nr, notchRect.bottom + 0.6f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (island > 0.3f) {
            val a = ((island - 0.3f) / 0.7f).coerceIn(0f, 1f)
            val dotR = ih * 0.26f
            val dc = Offset(notchRect.left + nr + dotR * 0.6f, notchRect.top + ih * 0.52f)
            drawCircle(success.copy(alpha = a), dotR, dc)
            val ck = Path().apply {
                moveTo(dc.x - dotR * 0.45f, dc.y + dotR * 0.02f)
                lineTo(dc.x - dotR * 0.1f, dc.y + dotR * 0.36f)
                lineTo(dc.x + dotR * 0.48f, dc.y - dotR * 0.32f)
            }
            drawPath(ck, Color.White.copy(alpha = a), style = Stroke(dotR * 0.32f, cap = StrokeCap.Round))
            val barX = dc.x + dotR * 1.8f
            val barW = (notchRect.right - nr - barX).coerceAtLeast(0f)
            drawRoundRect(Color.White.copy(alpha = 0.85f * a), Offset(barX, dc.y - ih * 0.17f), Size(barW * 0.85f, ih * 0.13f), CornerRadius(ih))
            drawRoundRect(Color.White.copy(alpha = 0.4f * a), Offset(barX, dc.y + ih * 0.06f), Size(barW * 0.55f, ih * 0.11f), CornerRadius(ih))
        }
        // camera
        drawCircle(Color(0xFF1B2333), g.notchH * 0.16f, Offset(g.notch.center.x, g.display.top + g.notchH * 0.45f))
    }
    // base
    val b = g.base
    val basePath = Path().apply {
        addRoundRect(
            RoundRect(
                b,
                topLeft = CornerRadius(b.height * 0.15f), topRight = CornerRadius(b.height * 0.15f),
                bottomLeft = CornerRadius(b.height * 0.9f, b.height * 0.9f), bottomRight = CornerRadius(b.height * 0.9f, b.height * 0.9f),
            )
        )
    }
    drawPath(basePath, Brush.verticalGradient(listOf(pal.baseTop, pal.baseBottom), b.top, b.bottom))
    drawLine(pal.lidEdge, Offset(b.left + b.height, b.top + 0.5f), Offset(b.right - b.height, b.top + 0.5f), strokeWidth = 1f)
    val sw = g.lidW * 0.16f
    drawRoundRect(
        pal.scoop, Offset(b.center.x - sw / 2, b.top), Size(sw, b.height * 0.4f),
        CornerRadius(b.height * 0.4f),
    )
}

private fun DrawScope.drawWallpaper(r: Rect, pal: MacPalette, dim: Float) {
    drawRect(Brush.verticalGradient(listOf(pal.wallTop, pal.wallBottom), r.top, r.bottom), r.topLeft, r.size)
    val k = 1f - dim * 0.75f
    fun glow(color: Color, fx: Float, fy: Float, fr: Float, a: Float) {
        val c = Offset(r.left + r.width * fx, r.top + r.height * fy)
        val rad = r.width * fr
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = a * k), Color.Transparent), center = c, radius = rad),
            radius = rad, center = c,
        )
    }
    // Light pools low on the screen, so the top (and the notch's own light) stays dark and clear.
    glow(Brand.Light, 0.16f, 1.0f, 0.62f, 0.50f)
    glow(Brand.Light, 0.64f, 0.92f, 0.66f, 0.52f)
    glow(Brand.Light, 0.98f, 0.55f, 0.42f, 0.26f)
    if (dim > 0f) drawRect(Color(0xFF0E0E12).copy(alpha = 0.55f * dim), r.topLeft, r.size)
}

private fun DrawScope.drawPhone(g: HeroGeometry, pal: MacPalette, dim: Float, lit: Float, glyph: Float) {
    val p = g.phone
    val r = p.width * 0.19f
    drawRoundRect(pal.phoneBody, p.topLeft, p.size, CornerRadius(r))
    drawRoundRect(pal.phoneEdge, p.topLeft, p.size, CornerRadius(r), style = Stroke(1.dp.toPx()))
    val inset = p.width * 0.055f
    val s = Rect(p.left + inset, p.top + inset, p.right - inset, p.bottom - inset)
    val sr = r - inset * 0.8f
    val screen = Path().apply { addRoundRect(RoundRect(s, CornerRadius(sr))) }
    clipPath(screen) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0E0E11), Color(0xFF17171B)), s.top, s.bottom), s.topLeft, s.size)
        val gc = s.center
        drawCircle(
            Brush.radialGradient(listOf(Brand.Light.copy(alpha = 0.45f * (0.5f + 0.5f * lit) * (1f - dim * 0.7f)), Color.Transparent), center = gc, radius = s.width * 0.9f),
            radius = s.width * 0.9f, center = gc,
        )
        if (glyph > 0.01f) drawBrandGlyph(gc, s.width * 0.42f, Color.White.copy(alpha = (0.92f - dim * 0.5f) * glyph))
        // a home bar and a status-bar hint keep it reading as a phone
        drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(s.center.x - s.width * 0.18f, s.bottom - s.height * 0.035f), Size(s.width * 0.36f, s.height * 0.012f), CornerRadius(s.width))
        if (dim > 0f) drawRect(Color(0xFF0E0E12).copy(alpha = 0.5f * dim), s.topLeft, s.size)
    }
    drawCircle(Color.Black, p.width * 0.045f, Offset(p.center.x, s.top + s.height * 0.045f))
}

private fun DrawScope.drawThread(
    g: HeroGeometry,
    state: LinkState,
    alpha: Float,
    color: Color,
    fill: Float,
    progress: Float?,
    loop: Float,
    muted: Color,
) {
    val path = g.threadPath
    val w = size.width
    val core = (0.0062f * w).coerceAtLeast(1.5f)
    if (state == LinkState.Offline) {
        // a broken thread: two dashed ends that don't meet
        val pm = PathMeasure().apply { setPath(path, false) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(core * 2.4f, core * 2.6f))
        listOf(0f to 0.36f, 0.64f to 1f).forEach { (a, b) ->
            val seg = Path()
            pm.getSegment(pm.length * a, pm.length * b, seg, true)
            drawPath(seg, muted.copy(alpha = 0.55f), style = Stroke(core * 0.9f, cap = StrokeCap.Round, pathEffect = dash))
        }
        return
    }
    if (alpha <= 0.01f) return
    val gradient = Brush.linearGradient(listOf(Brand.Light, color), start = g.threadStart, end = g.threadEnd)
    when (state) {
        LinkState.Searching -> {
            val dash = PathEffect.dashPathEffect(floatArrayOf(core * 2.2f, core * 2.4f), phase = -loop * core * 4.6f * 6f)
            drawPath(path, color.copy(alpha = 0.55f * alpha), style = Stroke(core, cap = StrokeCap.Round, pathEffect = dash))
        }
        LinkState.Sending, LinkState.Receiving, LinkState.Sent, LinkState.Received -> {
            drawPath(path, color.copy(alpha = 0.25f), style = Stroke(core, cap = StrokeCap.Round))
            val pm = PathMeasure().apply { setPath(path, false) }
            val len = pm.length
            val outgoing = state == LinkState.Sending || state == LinkState.Sent
            val (from, to) = if (progress == null && (state == LinkState.Sending || state == LinkState.Receiving)) {
                val head = loop * 1.3f
                (head - 0.28f).coerceIn(0f, 1f) to head.coerceIn(0f, 1f)
            } else 0f to fill
            val a = if (outgoing) from else 1f - to
            val b = if (outgoing) to else 1f - from
            if (b > a) {
                val seg = Path()
                pm.getSegment(len * a, len * b, seg, true)
                glowPath(seg, Brand.Light.copy(alpha = 0.75f), core * 2.2f, core * 3.2f)
                drawPath(seg, gradient, style = Stroke(core * 1.3f, cap = StrokeCap.Round))
                val headPos = pm.getPosition(len * (if (outgoing) b else a))
                drawCircle(
                    Brush.radialGradient(listOf(Color.White, Brand.Light.copy(alpha = 0.6f), Color.Transparent), center = headPos, radius = core * 5f),
                    radius = core * 5f, center = headPos,
                )
            }
        }
        else -> {
            // steady thread with a soft halo; when live, a bead of light travels to the notch
            glowPath(path, color.copy(alpha = (if (state == LinkState.Live) 0.65f else 0.35f) * alpha), core * 2f, core * 3f)
            drawPath(path, gradient, alpha = alpha, style = Stroke(core, cap = StrokeCap.Round))
            if (state == LinkState.Live) {
                val pm = PathMeasure().apply { setPath(path, false) }
                val t = FastOutSlowInEasing.transform((loop * 1.6f).coerceIn(0f, 1f))
                if (t in 0.001f..0.999f) {
                    val pos = pm.getPosition(pm.length * t)
                    val fade = sin(t * PI.toFloat())
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = fade), Brand.Light.copy(alpha = 0.5f * fade), Color.Transparent), center = pos, radius = core * 4.5f),
                        radius = core * 4.5f, center = pos,
                    )
                }
            }
        }
    }
}

/** A soft, really blurred stroke along [path] (light, not a tube). */
private fun DrawScope.glowPath(path: Path, color: Color, width: Float, blur: Float) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.color = color.toArgb()
            maskFilter = android.graphics.BlurMaskFilter(blur.coerceAtLeast(1f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
}

// MARK: - how to tap

/**
 * "Touch the top of your phone's screen to the notch", as a close-up from your seat: the phone (seen from the
 * back, its screen facing the Mac) rises to the notch, its screen lights up and the light spills around it onto
 * the display, then it goes dark, the phone buzzes and the notch answers with an island and a check (the light
 * key: the notch has to see the screen light up and go dark right against it).
 * Plays [loops] times, then rests on the lit moment of contact. Static under reduced motion. Aspect 1.9 : 1.
 *
 * @param startAtRest begin on the resting frame and start playing after a beat; use it where the
 *   illustration arrives as a shared element from another one that is already resting.
 */
@Composable
fun HowToTapIllustration(
    modifier: Modifier = Modifier,
    loops: Int = 2,
    startAtRest: Boolean = false,
    contentDescription: String = "The top of the phone's screen against the notch, its light spilling around it",
) {
    val c = Td.colors
    val pal = macPalette()
    val reduced = Td.reducedMotion
    val rest = TAP_REST
    val t = remember { Animatable(if (reduced || startAtRest) rest else 0f) }
    LaunchedEffect(loops, reduced) {
        if (reduced) {
            t.snapTo(rest)
            return@LaunchedEffect
        }
        if (startAtRest) {
            delay(900)
            t.animateTo(1f, tween((TAP_LOOP_MS * (1f - rest)).toInt(), easing = LinearEasing))
        }
        repeat(loops) {
            t.snapTo(0f)
            t.animateTo(1f, tween(TAP_LOOP_MS, easing = LinearEasing))
        }
        t.snapTo(0f)
        t.animateTo(rest, tween((TAP_LOOP_MS * rest).toInt(), easing = LinearEasing))
    }
    val backColor = if (c.isDark) Color(0xFF1F2026) else Color(0xFFE8E6E1)
    val barColor = if (c.isDark) Color(0xFF0E0E11) else Color(0xFFC4C5CB)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.9f)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val k = TapKeyFrame.at(t.value)
        drawTapCloseup(k, pal, backColor, barColor, c.success)
    }
}

/** One loop of the tap, in ms, and where it rests: on the notch, the screen lit. */
private const val TAP_LOOP_MS = 4400
private const val TAP_REST = 0.33f

/**
 * Where the tap is at [p] (0…1 of a loop): the phone rises to the notch, its screen lights up, holds, goes dark
 * (the echo the notch has to see), buzzes while the notch shows its check, then lowers.
 */
private class TapKeyFrame(val gap: Float, val glow: Float, val island: Float, val buzz: Float, val shake: Float) {
    companion object {
        fun at(p: Float): TapKeyFrame {
            val rise = FastOutSlowInEasing.transform(phase(p, 0.02f, 0.22f))
            val fall = FastOutSlowInEasing.transform(phase(p, 0.82f, 0.96f))
            val glow = phase(p, 0.21f, 0.27f) * (1f - phase(p, 0.44f, 0.47f))
            val island = TdEase.overshoot(phase(p, 0.48f, 0.62f)) * (1f - phase(p, 0.84f, 0.93f))
            // The buzz: a quick, fading shiver as the key passes.
            val b = phase(p, 0.48f, 0.60f)
            val buzz = if (b <= 0f || b >= 1f) 0f else kotlin.math.sin(b * Math.PI.toFloat()).let { it * it }
            val shake = buzz * kotlin.math.sin(b * 2f * Math.PI.toFloat() * 7f)
            return TapKeyFrame((1f - rise) + fall * 0.75f, glow, island, buzz, shake)
        }
    }
}

private fun phase(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

private object TdEase {
    fun overshoot(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val s = 1.4f
        val y = x - 1f
        return y * y * ((s + 1f) * y + s) + 1f
    }
}

private fun DrawScope.drawTapCloseup(k: TapKeyFrame, pal: MacPalette, back: Color, bar: Color, success: Color) {
    val gap = k.gap
    val glow = k.glow
    val island = k.island
    val w = size.width
    val h = size.height
    val cx = w / 2
    // the room's light behind the Mac, brightest where the notch is
    val bloom = 0.62f * w
    drawCircle(
        Brush.radialGradient(
            0f to Brand.Light.copy(alpha = 0.30f), 0.5f to Brand.Light.copy(alpha = 0.08f), 1f to Color.Transparent,
            center = Offset(cx, 0.1f * h), radius = bloom,
        ),
        radius = bloom, center = Offset(cx, 0.1f * h),
    )
    // the top of the lid, with its rounded corners in frame; the rest runs out of the bottom
    val lid = Rect(0.07f * w, 0.11f * h, 0.93f * w, h * 1.35f)
    val lidR = 0.05f * w
    val lidPath = Path().apply { addRoundRect(RoundRect(lid, CornerRadius(lidR))) }
    drawPath(lidPath, Brush.verticalGradient(listOf(pal.lidTop, pal.lidBottom), lid.top, h))
    drawPath(lidPath, pal.lidEdge.copy(alpha = pal.lidEdge.alpha * 0.8f), style = Stroke(1.dp.toPx()))
    val bezel = 0.02f * w
    val glass = Rect(lid.left + bezel * 0.35f, lid.top + bezel * 0.35f, lid.right - bezel * 0.35f, lid.bottom)
    drawRoundRect(Color(0xFF050507), glass.topLeft, glass.size, CornerRadius(lidR * 0.86f))
    val display = Rect(lid.left + bezel, lid.top + bezel, lid.right - bezel, lid.bottom)
    val displayPath = Path().apply { addRoundRect(RoundRect(display, CornerRadius(lidR * 0.62f))) }
    val notchW = 0.17f * w
    val notchH = 0.058f * w
    val notchBottom = display.top + notchH
    clipPath(displayPath) {
        drawWallpaper(display, pal, 0f)
        if (glow > 0.01f) {
            // the phone's screen light washing over the display around it
            val gc = Offset(cx, notchBottom + 0.05f * w)
            val gr = 0.44f * w
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.92f * glow),
                    0.22f to Color(0xFFE9E2FF).copy(alpha = 0.62f * glow),
                    0.55f to Brand.Light.copy(alpha = 0.30f * glow),
                    1f to Color.Transparent,
                    center = gc, radius = gr,
                ),
                radius = gr, center = gc,
            )
        }
        // glass sheen
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.07f), 0.35f to Color.Transparent,
                start = display.topLeft, end = Offset(display.left + display.width * 0.6f, display.bottom),
            ),
            display.topLeft, display.size,
        )
        // notch, growing into an island
        val iw = notchW * (1f + 1.35f * island)
        val ih = notchH * (1f + 0.45f * island)
        val nr = ih * 0.44f
        val nRect = Rect(cx - iw / 2, display.top - 1f, cx + iw / 2, display.top + ih)
        drawPath(
            Path().apply { addRoundRect(RoundRect(nRect, CornerRadius.Zero, CornerRadius.Zero, CornerRadius(nr), CornerRadius(nr))) },
            Color.Black,
        )
        drawCircle(Color(0xFF1B2333), notchH * 0.13f, Offset(cx, display.top + notchH * 0.42f))
        if (island > 0.35f) {
            val a = ((island - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val dotR = ih * 0.2f
            val dc = Offset(nRect.left + nr + dotR * 0.2f, nRect.top + ih * 0.56f)
            drawCircle(success.copy(alpha = a), dotR, dc)
            val ck = Path().apply {
                moveTo(dc.x - dotR * 0.45f, dc.y + dotR * 0.02f)
                lineTo(dc.x - dotR * 0.1f, dc.y + dotR * 0.36f)
                lineTo(dc.x + dotR * 0.48f, dc.y - dotR * 0.32f)
            }
            drawPath(ck, Color.White.copy(alpha = a), style = Stroke(dotR * 0.3f, cap = StrokeCap.Round))
            val barX = dc.x + dotR * 2f
            val barW = (nRect.right - nr * 0.8f - barX).coerceAtLeast(0f)
            drawRoundRect(Color.White.copy(alpha = 0.9f * a), Offset(barX, dc.y - ih * 0.15f), Size(barW * 0.82f, ih * 0.1f), CornerRadius(ih))
            drawRoundRect(Color.White.copy(alpha = 0.42f * a), Offset(barX, dc.y + ih * 0.05f), Size(barW * 0.52f, ih * 0.09f), CornerRadius(ih))
        }
    }
    // the phone, seen from the back, the top of its screen meeting the notch (shivering as it buzzes)
    val pw = 0.22f * w
    val ph = 0.47f * w
    val top = notchBottom - 0.004f * w + gap * 0.52f * h
    val px = cx + k.shake * 0.006f * w
    val pRect = Rect(px - pw / 2, top, px + pw / 2, top + ph)
    val pr = pw * 0.2f
    if (glow > 0.01f) {
        // light escaping around the phone's edges where it meets the display
        glowRect(pRect, pr, Color.White.copy(alpha = 0.55f * glow), 2.dp.toPx(), 5.dp.toPx())
    }
    if (k.buzz > 0.01f) {
        // the buzz: short arcs either side of the phone
        val a = k.buzz
        val mid = pRect.top + ph * 0.27f
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        for (side in listOf(-1f, 1f)) {
            for (i in 0..1) {
                val r = pw * (0.16f + 0.13f * i)
                val out = pw * (0.07f + 0.09f * i)
                val edge = if (side < 0) pRect.left - out else pRect.right + out
                val center = Offset(edge - side * r, mid)
                drawArc(
                    Color.White.copy(alpha = (0.85f - 0.3f * i) * a),
                    startAngle = if (side < 0) 152f else -28f, sweepAngle = 56f, useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = stroke,
                )
            }
        }
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(lerp(back, Color.White, 0.06f), back, lerp(back, Color.Black, 0.12f)), pRect.top, pRect.top + ph * 0.7f),
        pRect.topLeft, pRect.size, CornerRadius(pr),
    )
    // a soft vertical sheen so the back reads as glass, not a flat slab
    drawRoundRect(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.10f), 0.18f to Color.White.copy(alpha = 0.03f),
            0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.14f),
            startX = pRect.left, endX = pRect.right,
        ),
        pRect.topLeft, pRect.size, CornerRadius(pr),
    )
    drawRoundRect(Color.White.copy(alpha = 0.16f), pRect.topLeft, pRect.size, CornerRadius(pr), style = Stroke(1.dp.toPx()))
    // Pixel-style camera bar
    val barTop = pRect.top + pw * 0.15f
    val barH = pw * 0.19f
    drawRect(bar, Offset(pRect.left + 0.5f, barTop), Size(pw - 1f, barH))
    drawLine(Color.White.copy(alpha = 0.10f), Offset(pRect.left, barTop), Offset(pRect.right, barTop), strokeWidth = 1f)
    val lensR = barH * 0.25f
    listOf(0.24f, 0.42f).forEach { fx ->
        val lc = Offset(pRect.left + pw * fx, barTop + barH / 2)
        drawCircle(Color(0xFF050608), lensR * 1.25f, lc)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF34343A), Color(0xFF07080B)), center = lc, radius = lensR), lensR, lc)
        drawCircle(Color.White.copy(alpha = 0.4f), lensR * 0.2f, Offset(lc.x - lensR * 0.3f, lc.y - lensR * 0.3f))
    }
    drawCircle(Color(0xFFEEF1FF).copy(alpha = 0.75f), lensR * 0.32f, Offset(pRect.left + pw * 0.6f, barTop + barH / 2))
}

/** A blurred rounded-rect outline (rim light). */
private fun DrawScope.glowRect(r: Rect, radius: Float, color: Color, width: Float, blur: Float) {
    val path = Path().apply { addRoundRect(RoundRect(r, CornerRadius(radius))) }
    glowPath(path, color, width, blur)
}
