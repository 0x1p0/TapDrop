package app.tapdrop.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Transfer progress on a ring, filling with the brand gradient. `progress = null` spins an indeterminate
 * arc. Progress moves on a soft spring so bursty updates still glide.
 */
@Composable
fun ProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    stroke: Dp = 4.dp,
    track: Color = Td.colors.text.copy(alpha = if (Td.colors.isDark) 0.10f else 0.07f),
    brush: Brush = SolidColor(Td.colors.accent),
) {
    val animated by animateFloatAsState(
        (progress ?: 0f).coerceIn(0f, 1f),
        spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "ring",
    )
    val rot = if (progress == null && !Td.reducedMotion) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rot").value
    } else 0f
    Canvas(
        modifier.semantics {
            progressBarRangeInfo = if (progress == null) ProgressBarRangeInfo.Indeterminate
            else ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
        }
    ) {
        val w = stroke.toPx()
        val tl = Offset(w / 2, w / 2)
        val s = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, tl, s, style = Stroke(w))
        rotate(if (progress == null) rot - 90f else -90f) {
            drawArc(brush, 0f, if (progress == null) 100f else 360f * animated, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/**
 * Success: a disc pops in (with a little overshoot), a check draws itself, and a short glow bursts out.
 * Shows while [visible]; replays each time it turns true. With [fromRing] the disc grows out of a closing
 * ring first, so a [ProgressRing] can hand over to it seamlessly.
 */
@Composable
fun CheckBurst(
    visible: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = Td.colors.success,
    fromRing: Boolean = false,
    /** The check: the accent's ink on the accent (bone in dark mode, ink in light), white on anything else. */
    mark: Color = if (color == Td.colors.success || color == Td.colors.accent) Td.colors.onAccent else Color.White,
) {
    val reduced = Td.reducedMotion
    val disc = remember { Animatable(if (visible) 1f else 0f) }
    val check = remember { Animatable(if (visible) 1f else 0f) }
    val burst = remember { Animatable(1f) }
    val ring = remember { Animatable(if (fromRing) 0f else 1f) }
    LaunchedEffect(visible) {
        if (!visible) {
            disc.animateTo(0f, tween(160))
            check.snapTo(0f)
            ring.snapTo(if (fromRing) 0f else 1f)
            return@LaunchedEffect
        }
        if (reduced) {
            ring.snapTo(1f); disc.snapTo(1f); check.snapTo(1f)
            return@LaunchedEffect
        }
        coroutineScope {
            if (fromRing) ring.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            launch { disc.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f)) }
            delay(90)
            check.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
    }
    Box(modifier.size(size * 1.8f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 1.8f)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val r = size.toPx() / 2
            // glow burst
            if (burst.value < 1f && visible) {
                val b = burst.value
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.5f * (1f - b)), color.copy(alpha = 0f)),
                        center = center, radius = r * (1f + 0.8f * b) + 1f,
                    ),
                    radius = r * (1f + 0.8f * b) + 1f, center = center,
                )
                drawCircle(color.copy(alpha = 0.6f * (1f - b)), r * (1f + 0.6f * b), center, style = Stroke(2.dp.toPx() * (1f - b) + 0.5f))
            }
            // closing ring (hand-over from a ProgressRing)
            if (fromRing && ring.value < 1f && visible) {
                val w = 4.dp.toPx()
                drawArc(
                    color, -90f, 360f * ring.value, false,
                    Offset(center.x - r + w / 2, center.y - r + w / 2), Size(r * 2 - w, r * 2 - w),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            // disc
            val d = disc.value
            if (d > 0f) drawCircle(color, r * d, center)
            // check that draws itself
            if (check.value > 0f) {
                val path = Path().apply {
                    moveTo(center.x - r * 0.42f, center.y + r * 0.02f)
                    lineTo(center.x - r * 0.12f, center.y + r * 0.32f)
                    lineTo(center.x + r * 0.44f, center.y - r * 0.30f)
                }
                val pm = PathMeasure().apply { setPath(path, false) }
                val seg = Path()
                pm.getSegment(0f, pm.length * check.value, seg, true)
                drawPath(seg, mark, style = Stroke(r * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/**
 * The Material 3 Expressive loading indicator in brand colors (a shape that morphs as it spins). Use it
 * while searching or connecting. [contained] puts it on a tonal disc for use over busy backgrounds.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TdLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Td.colors.accent,
    contained: Boolean = false,
) {
    val polygons = remember {
        listOf(
            MaterialShapes.SoftBurst,
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Pentagon,
            MaterialShapes.Pill,
            MaterialShapes.Sunny,
            MaterialShapes.Cookie4Sided,
            MaterialShapes.Oval,
        )
    }
    if (contained) {
        ContainedLoadingIndicator(
            modifier = modifier,
            containerColor = Td.colors.accentSoft,
            indicatorColor = color,
            polygons = polygons,
        )
    } else {
        LoadingIndicator(modifier = modifier, color = color, polygons = polygons)
    }
}
