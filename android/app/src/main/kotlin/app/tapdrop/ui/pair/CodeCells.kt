package app.tapdrop.ui.pair

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The 6-digit pairing code in large cells, grouped 3 + 3 exactly like the Mac's pair prompt, so the two
 * screens read the same at a glance.
 *
 * - `code == null`: empty cells with a slow wave of light (the Mac is on its way).
 * - A code: digits pop in one after another.
 * - [confirmed]: each cell gets the brand-gradient ring, as the Mac's cells do once you click Pair.
 */
@Composable
fun CodeCells(code: String?, confirmed: Boolean, modifier: Modifier = Modifier) {
    val reduced = Td.reducedMotion
    val spoken = code?.let { "Code ${it.take(3).toList().joinToString(" ")}, ${it.drop(3).toList().joinToString(" ")}" }
        ?: "Waiting for the code"
    val wave = if (code == null && !reduced) {
        val t = rememberInfiniteTransition(label = "codeWave")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "wave").value
    } else -1f
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        val gap = 8.dp
        val groupGap = 18.dp
        val cellW = ((maxWidth - gap * 4 - groupGap) / 6).coerceIn(36.dp, 52.dp)
        val cellH = cellW * 1.3f
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 6) {
                if (i == 3) Spacer(Modifier.width(groupGap)) else if (i > 0) Spacer(Modifier.width(gap))
                CodeCell(code?.getOrNull(i), i, code, confirmed, wave, cellW, cellH)
            }
        }
    }
}

@Composable
private fun CodeCell(digit: Char?, index: Int, code: String?, confirmed: Boolean, wave: Float, w: Dp, h: Dp) {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val shape = RoundedCornerShape(w * 0.3f)
    val pop = remember(code) { Animatable(if (digit != null && reduced) 1f else 0f) }
    LaunchedEffect(code) {
        if (digit != null && !reduced) {
            delay(70L + 55L * index)
            pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 360f))
        }
    }
    val ring by animateFloatAsState(
        if (confirmed) 1f else 0f,
        if (reduced) tween(140) else tween(420, delayMillis = 45 * index, easing = FastOutSlowInEasing),
        label = "cellRing",
    )
    // The wave lights each cell in turn while the code is on its way.
    val lift = if (wave < 0f) 0f else (1f - abs(wave * 1.5f - 0.25f - index * 0.16f) * 3.2f).coerceIn(0f, 1f)
    val fill = if (c.isDark) Color.White.copy(alpha = 0.06f + 0.05f * lift) else lerpWhite(c.surfaceSunken, lift)
    val edge = if (c.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    Box(
        Modifier
            .size(w, h)
            .clip(shape)
            .background(fill)
            .drawWithContent {
                drawContent()
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, edge, style = Stroke(1.dp.toPx()))
                if (ring > 0f) drawOutline(outline, c.accent, alpha = ring, style = Stroke(2.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (digit != null) {
            Text(
                digit.toString(),
                style = TdType.mono.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val p = pop.value
                    alpha = p.coerceIn(0f, 1f)
                    val s = 0.55f + 0.45f * p
                    scaleX = s
                    scaleY = s
                    translationY = (1f - p) * 10.dp.toPx()
                },
            )
        }
    }
}

private fun lerpWhite(base: Color, t: Float) = Color(
    red = base.red + (1f - base.red) * t * 0.6f,
    green = base.green + (1f - base.green) * t * 0.6f,
    blue = base.blue + (1f - base.blue) * t * 0.6f,
    alpha = base.alpha,
)
