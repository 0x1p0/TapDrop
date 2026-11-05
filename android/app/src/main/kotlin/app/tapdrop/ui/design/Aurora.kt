package app.tapdrop.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import kotlin.math.cos
import kotlin.math.sin

/** The mood of an [AuroraBackdrop]. */
enum class AuroraMood {
    /** Blue, violet and a touch of orchid: the brand. */
    Brand,
    /** Brand with a green lift: everything is live and ready. */
    Live,
    /** Cooled down to near-gray: offline, nothing to celebrate. */
    Quiet,
}

/**
 * A slow, low-contrast aurora of drifting radial light, for hero areas only (top of Home, Welcome, setup).
 * It "breathes" for a few seconds when it appears or when [mood] changes, then rests: a screen left open
 * never keeps redrawing. Fades into the background at the bottom. Static under reduced motion.
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    mood: AuroraMood = AuroraMood.Brand,
    intensity: Float = 1f,
) {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val drift = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(mood) {
        if (reduced) return@LaunchedEffect
        drift.snapTo(0f)
        drift.animateTo(1f, tween(9000, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)))
    }
    val k by animateFloatAsState(
        (if (c.isDark) 0.40f else 0.22f) * intensity * (if (mood == AuroraMood.Quiet) 0.55f else 1f),
        TdMotion.gentle(),
        label = "auroraK",
    )
    val first by animateColorAsState(if (mood == AuroraMood.Quiet) Color(0xFF6E7387) else Brand.Blue, TdMotion.gentle(), label = "a1")
    val second by animateColorAsState(if (mood == AuroraMood.Quiet) Color(0xFF5B5F73) else Brand.Violet, TdMotion.gentle(), label = "a2")
    val third by animateColorAsState(
        when (mood) {
            AuroraMood.Live -> Brand.Success
            AuroraMood.Quiet -> Color(0xFF4A4D5E)
            AuroraMood.Brand -> Brand.Orchid
        },
        TdMotion.gentle(), label = "a3",
    )
    Canvas(
        modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val t = drift.value
        val w = size.width
        val h = size.height
        // Each blob drifts along a short arc as the aurora settles.
        fun p(x0: Float, y0: Float, r: Float, a0: Float, sweep: Float): Offset {
            val a = a0 + sweep * t
            return Offset(w * x0 + cos(a) * w * r, h * y0 + sin(a) * h * r * 0.6f)
        }
        blob(first, p(0.18f, 0.30f, 0.10f, 3.6f, 1.1f), w * 0.70f, k * 0.95f)
        blob(second, p(0.78f, 0.26f, 0.09f, 0.4f, -1.0f), w * 0.72f, k)
        blob(third, p(0.62f, 0.62f, 0.12f, 1.9f, 0.8f), w * 0.55f, k * (if (mood == AuroraMood.Live) 0.55f else 0.50f))
        blob(second, p(0.30f, 0.70f, 0.08f, 5.0f, -0.7f), w * 0.50f, k * 0.45f)
        // Melt into the background at the bottom.
        drawRect(
            Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black.copy(alpha = 0.85f), 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

private fun DrawScope.blob(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.45f to color.copy(alpha = alpha * 0.55f),
            0.75f to color.copy(alpha = alpha * 0.18f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
