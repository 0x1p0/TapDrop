package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdColors
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdType

/** What a [StatusPill] (and other status UI) is saying. */
enum class StatusTone {
    /** Live and ready: green, with a soft pulse. */
    Live,
    /** Fine but not live (connected, done): green, still. */
    Good,
    /** Working on it (searching, connecting): amber, with an animated arc. */
    Searching,
    /** Not reachable or turned off: gray. */
    Offline,
    /** Something needs attention: red. */
    Error,
}

/** The fill color for a tone (dots, icons). */
fun TdColors.toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Live, StatusTone.Good -> success
    StatusTone.Searching -> warning
    StatusTone.Offline -> textTertiary
    StatusTone.Error -> danger
}

/** The text color for a tone (legible on surfaces in both appearances). */
fun TdColors.toneText(tone: StatusTone): Color = when (tone) {
    StatusTone.Live, StatusTone.Good -> successText
    StatusTone.Searching -> warningText
    StatusTone.Offline -> textSecondary
    StatusTone.Error -> dangerText
}

/**
 * A live dot plus a label, in a soft capsule. The label cross-fades and the capsule resizes when either
 * changes; screen readers hear the change (polite live region).
 */
@Composable
fun StatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val c = Td.colors
    val fill by animateColorAsState(c.toneColor(tone), TdMotion.standard(), label = "pillFill")
    val ink by animateColorAsState(c.toneText(tone), TdMotion.standard(), label = "pillInk")
    Row(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .heightIn(min = 32.dp)
            .background(fill.copy(alpha = if (c.isDark) 0.13f else 0.11f), CircleShape)
            .border(1.dp, fill.copy(alpha = if (c.isDark) 0.22f else 0.18f), CircleShape)
            .padding(start = 11.dp, end = 13.dp)
            .animateContentSize(TdMotion.standard()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIndicator(tone, fill, Modifier.size(14.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(tween(220, 60)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
            },
            label = "pillText",
        ) { t ->
            Text(t, style = TdType.label, color = ink, maxLines = 1, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

/**
 * The dot from [StatusPill] on its own: pulsing (live), an orbiting arc (searching) or still.
 * Use it in rows and toolbars where a full pill is too much.
 */
@Composable
fun StatusIndicator(tone: StatusTone, color: Color = Td.colors.toneColor(tone), modifier: Modifier = Modifier.size(14.dp)) {
    val reduced = Td.reducedMotion
    when {
        tone == StatusTone.Live && !reduced -> {
            val t = rememberInfiniteTransition(label = "live")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p")
            Canvas(modifier) {
                val r = size.minDimension / 2
                val dot = r * 0.55f
                val ring = dot + (r - dot) * p
                drawCircle(color.copy(alpha = 0.45f * (1f - p)), ring)
                drawCircle(color, dot)
            }
        }
        tone == StatusTone.Searching && !reduced -> {
            val t = rememberInfiniteTransition(label = "searching")
            val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "rot")
            val sweep by t.animateFloat(60f, 200f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "sweep")
            Canvas(modifier) {
                val w = size.minDimension * 0.16f
                val inset = w / 2 + size.minDimension * 0.08f
                drawCircle(color.copy(alpha = 0.22f), size.minDimension / 2 - inset, style = Stroke(w))
                drawArc(
                    color, rot, sweep, false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
    }
}
