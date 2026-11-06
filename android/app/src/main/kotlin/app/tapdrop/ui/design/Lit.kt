package app.tapdrop.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.TdColors

// Lumen's filled controls are never flat white. The accent is lit: bone with a soft top-to-bottom falloff, a rim and a
// faint glow of the notch's light in dark mode; ink with a sheen and a shadow in light mode. Neutral buttons are raised:
// a gentle gradient, a rim that catches the light, and (in light mode) a soft shadow.

/** The accent as a lit gradient (for fills drawn by hand). */
fun litBrush(c: TdColors): Brush = Brush.verticalGradient(
    if (c.isDark) listOf(Color(0xFFFFFFFF), Color(0xFFE2E0D9)) else listOf(Color(0xFF303036), Color(0xFF0B0B0D)),
)

/** What sits around a lit control: a glow of the notch's light (dark) or a soft shadow (light). Put it before the clip. */
fun Modifier.litHalo(shape: Shape, c: TdColors, strength: Float = 1f): Modifier = when {
    strength <= 0f -> this
    c.isDark -> drawBehind {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Brand.Light.copy(alpha = 0.34f * strength).toArgb()
                maskFilter = android.graphics.BlurMaskFilter(14.dp.toPx(), android.graphics.BlurMaskFilter.Blur.OUTER)
            }
            canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
        }
    }
    else -> shadow(6.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.32f))
}

/** The accent fill, lit (inside the control's clip). */
fun Modifier.litFill(shape: Shape, c: TdColors): Modifier = this
    .background(litBrush(c), shape)
    .border(
        1.dp,
        Brush.verticalGradient(
            if (c.isDark) listOf(Color.White, Color.White.copy(alpha = 0f), Color.Black.copy(alpha = 0.12f))
            else listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.04f), Color.Transparent),
        ),
        shape,
    )

/** A soft shadow under a raised neutral surface (light mode only). Put it before the clip. */
fun Modifier.raisedShadow(shape: Shape, c: TdColors): Modifier =
    if (c.isDark) this
    else shadow(3.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.16f))

/** A raised neutral surface: secondary buttons and round actions. */
fun Modifier.raisedFill(shape: Shape, c: TdColors): Modifier = this
    .background(
        Brush.verticalGradient(
            if (c.isDark) listOf(Color.White.copy(alpha = 0.105f), Color.White.copy(alpha = 0.045f))
            else listOf(Color.White, Color(0xFFF4F2ED)),
        ),
        shape,
    )
    .border(
        1.dp,
        Brush.verticalGradient(
            if (c.isDark) listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.05f))
            else listOf(Color.White, Color.Black.copy(alpha = 0.09f)),
        ),
        shape,
    )
