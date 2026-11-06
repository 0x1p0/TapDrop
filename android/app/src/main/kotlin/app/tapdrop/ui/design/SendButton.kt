package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdType

/** What a [SendButton] is showing. */
@Immutable
sealed interface SendButtonState {
    /** Ready: the brand-gradient call to action. */
    data object Ready : SendButtonState

    /** Working: light fills the pill as [progress] goes 0 → 1. Null while connecting: a comet sweeps across. */
    data class Working(val label: String, val progress: Float? = null) : SendButtonState

    /** Finished well: the pill turns green and a check draws itself. */
    data class Done(val label: String) : SendButtonState

    /** Finished with nothing to report ("Nothing waiting on your Mac"): a quiet neutral pill. */
    data class Notice(val label: String) : SendButtonState

    /** Failed: a calm red pill; tapping it runs [SendButton]'s onClick again. */
    data class Failed(val label: String = "Try again") : SendButtonState
}

/**
 * The share sheet's one big action, and the shape every transfer takes: a 56 dp gradient pill that
 * morphs into its own progress bar (the label stays legible as the light passes under it), then into a
 * green check, or a calm red "Try again". Meant to be full width. It is the view's one gradient element.
 *
 * Taps only count while it is [SendButtonState.Ready] (and [enabled]) or [SendButtonState.Failed].
 */
@Composable
fun SendButton(
    state: SendButtonState,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Rounded.ArrowUpward,
    enabled: Boolean = true,
) {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val kind = when (state) {
        SendButtonState.Ready -> 0
        is SendButtonState.Working -> 1
        is SendButtonState.Done -> 2
        is SendButtonState.Notice -> 3
        is SendButtonState.Failed -> 4
    }
    val layerSpec = if (reduced) TdMotion.quick<Float>() else tween<Float>(320, easing = FastOutSlowInEasing)
    val ready by animateFloatAsState(if (kind == 0) 1f else 0f, layerSpec, label = "sendReady")
    val work by animateFloatAsState(if (kind == 1) 1f else 0f, layerSpec, label = "sendWork")
    val done by animateFloatAsState(if (kind == 2) 1f else 0f, layerSpec, label = "sendDone")
    val notice by animateFloatAsState(if (kind == 3) 1f else 0f, layerSpec, label = "sendNotice")
    val fail by animateFloatAsState(if (kind == 4) 1f else 0f, layerSpec, label = "sendFail")

    // Remember the last working label/progress so the bar doesn't blank out while it hands over.
    var lastWork by remember { mutableStateOf(SendButtonState.Working("")) }
    if (state is SendButtonState.Working) lastWork = state
    val progress = lastWork.progress
    val fill by animateFloatAsState(
        when {
            kind == 2 -> 1f
            else -> (progress ?: 0f).coerceIn(0f, 1f)
        },
        if (reduced) TdMotion.quick() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow),
        label = "sendFill",
    )
    val indeterminate = kind == 1 && progress == null
    val sweep = if (indeterminate && !reduced) {
        val t = rememberInfiniteTransition(label = "sendSweep")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1250, easing = LinearEasing)), label = "sweep").value
    } else 0.5f

    // A small "pop" when it lands.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(kind) {
        if (kind == 2 && !reduced) {
            pop.snapTo(0.96f)
            pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
        }
    }

    val tappable = enabled && (kind == 0 || kind == 4)
    val track = c.accentSoft
    val neutral = if (c.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
    val danger = c.danger.copy(alpha = if (c.isDark) 0.16f else 0.11f)

    Box(
        modifier
            .heightIn(min = 56.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .pressable(CircleShape, enabled = tappable, onClick = onClick)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                when (state) {
                    is SendButtonState.Working -> {
                        stateDescription = state.label
                        progressBarRangeInfo = state.progress?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) }
                            ?: ProgressBarRangeInfo.Indeterminate
                    }
                    is SendButtonState.Done -> stateDescription = state.label
                    is SendButtonState.Notice -> stateDescription = state.label
                    is SendButtonState.Failed -> stateDescription = "Didn't work"
                    SendButtonState.Ready -> Unit
                }
            }
            .drawBehind {
                val w = size.width
                val h = size.height
                if (ready > 0f) drawRect(if (enabled) c.accent else neutral, alpha = ready)
                if (work > 0f) {
                    drawRect(track, alpha = work)
                    if (indeterminate) {
                        val band = w * 0.45f
                        val x = -band + (w + band) * sweep
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent, 0.45f to c.accent.copy(alpha = 0.30f),
                                0.75f to c.accent.copy(alpha = 0.55f), 1f to Color.Transparent,
                                startX = x, endX = x + band,
                            ),
                            alpha = work,
                        )
                    } else if (fill > 0f) {
                        drawRect(c.accent, size = Size(w * fill, h), alpha = work)
                    }
                }
                if (done > 0f) drawRect(c.accent, alpha = done)
                if (notice > 0f) drawRect(neutral, alpha = notice)
                if (fail > 0f) drawRect(danger, alpha = fail)
            }
            .then(if (fail > 0.01f) Modifier.border(1.dp, c.danger.copy(alpha = 0.32f * fail), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = kind,
            transitionSpec = {
                if (reduced) fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                else (fadeIn(tween(220, 60)) + scaleIn(tween(260, 40, FastOutSlowInEasing), initialScale = 0.92f)) togetherWith
                    (fadeOut(tween(110)) + scaleOut(tween(160), targetScale = 0.96f))
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
            label = "sendLabel",
        ) { k ->
            when (k) {
                0 -> PillLabel(text, icon, if (enabled) c.onAccent else c.textTertiary)
                1 -> WorkingLabel(lastWork.label, fill, partial = !indeterminate)
                2 -> PillLabel((state as? SendButtonState.Done)?.label ?: "Done", null, c.onAccent, check = true)
                3 -> PillLabel((state as? SendButtonState.Notice)?.label.orEmpty(), null, c.textSecondary)
                else -> PillLabel((state as? SendButtonState.Failed)?.label ?: "Try again", Icons.Rounded.Refresh, c.dangerText)
            }
        }
    }
}

private val pillText: TextStyle
    get() = TdType.subtitle.copy(fontSize = TdType.body.fontSize, fontFeatureSettings = "tnum")

@Composable
private fun PillLabel(text: String, icon: ImageVector?, color: Color, check: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (check) {
            DrawnCheck(color, Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = pillText, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * The label while working: in the text colour over the track, inverted where the fill has already reached,
 * split exactly at the fill edge.
 */
@Composable
private fun WorkingLabel(text: String, fill: Float, partial: Boolean) {
    val c = Td.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text, style = pillText, color = c.accentText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
        )
        if (partial && fill > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent { clipRect(right = size.width * fill) { this@drawWithContent.drawContent() } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text, style = pillText, color = c.onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** A check mark that draws itself once, left to right. Static under reduced motion. */
@Composable
fun DrawnCheck(color: Color, modifier: Modifier = Modifier, stroke: Dp = 2.6.dp) {
    val reduced = Td.reducedMotion
    val t = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) t.animateTo(1f, tween(340, 60, FastOutSlowInEasing)) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.53f)
            lineTo(w * 0.40f, h * 0.76f)
            lineTo(w * 0.86f, h * 0.26f)
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * t.value, seg, true)
        drawPath(seg, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
