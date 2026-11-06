package app.tapdrop.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td

// Lumen's building blocks: the Notchline (the notch's outline traced in one line of light), its glow, and the app
// mark. The same path as the app icon and the website (a 100-unit square: from x 16 to 84, y 38 to 49.5).

/** The Notchline, [width] wide, its left end at [left], its top (the straight runs) at [top]. */
fun notchlinePath(left: Float, top: Float, width: Float): Path {
    val k = width / 68f
    fun x(v: Float) = left + (v - 16f) * k
    fun y(v: Float) = top + (v - 38f) * k
    return Path().apply {
        moveTo(x(16f), y(38f))
        lineTo(x(34f), y(38f))
        cubicTo(x(37f), y(38f), x(38.5f), y(39.5f), x(38.5f), y(42.5f))
        lineTo(x(38.5f), y(43.5f))
        cubicTo(x(38.5f), y(47f), x(41f), y(49.5f), x(44.5f), y(49.5f))
        lineTo(x(55.5f), y(49.5f))
        cubicTo(x(59f), y(49.5f), x(61.5f), y(47f), x(61.5f), y(43.5f))
        lineTo(x(61.5f), y(42.5f))
        cubicTo(x(61.5f), y(39.5f), x(63f), y(38f), x(66f), y(38f))
        lineTo(x(84f), y(38f))
    }
}

/** How tall the Notchline is for a given [width] (the dip below its straight runs). */
fun notchlineHeight(width: Float) = width * 11.5f / 68f

/** A soft, really blurred stroke along [path]: light, not a tube. */
fun DrawScope.glowStroke(path: Path, color: Color, width: Float, blur: Float) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            this.color = color.toArgb()
            maskFilter = android.graphics.BlurMaskFilter(blur.coerceAtLeast(1f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.nativeCanvas.drawPath(path.asAndroidPath(), paint)
    }
}

/**
 * The Notchline lit: a core line in [core] over a blurred halo in [halo] ([glow] 0…1 scales the halo, for the
 * moment a tap counts). [stroke] is the core's width in px.
 */
fun DrawScope.drawNotchline(left: Float, top: Float, width: Float, core: Color, halo: Color, stroke: Float, glow: Float = 1f) {
    val path = notchlinePath(left, top, width)
    if (glow > 0.01f) {
        glowStroke(path, halo.copy(alpha = halo.alpha * glow), stroke * 4.5f, stroke * 3.5f)
        glowStroke(path, halo.copy(alpha = halo.alpha * glow * 0.8f), stroke * 2f, stroke * 1.2f)
    }
    drawPath(path, core, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The TapDrop glyph (the Notchline alone) centred at [c], [s] wide, in [color]. */
fun DrawScope.drawBrandGlyph(c: Offset, s: Float, color: Color) {
    val h = notchlineHeight(s)
    drawNotchline(c.x - s / 2f, c.y - h / 2f, s, color, color.copy(alpha = color.alpha * 0.6f), s * 0.06f)
}

/** The app mark, as on the launcher icon: the lit Notchline on a night tile. The same in light and dark. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    androidx.compose.foundation.layout.Spacer(
        modifier.size(size).drawWithCache {
            val mark = offscreen(this) {
                val w = this.size.minDimension
                val r = CornerRadius(w * 0.23f)
                drawRoundRect(Color(0xFF111114), cornerRadius = r)
                drawRoundRect(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.45f to Brand.Light.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        center = Offset(w * 0.5f, w * 0.4f),
                        radius = w * 0.46f,
                    ),
                    cornerRadius = r,
                )
                drawNotchline(w * 0.16f, w * 0.38f, w * 0.68f, Color.White, Color.White.copy(alpha = 0.7f), w * 0.036f)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r, style = Stroke(1f))
            }
            onDrawBehind { drawImage(mark) }
        },
    )
}

/**
 * The home screen's horizon: the Notchline drawn wide, lit. In dark mode a white line with a white glow; in light mode
 * an ink line with a white halo behind it, as if lit. [glow] 0…1 (it blooms when a tap counts).
 */
@Composable
fun NotchlineHero(modifier: Modifier = Modifier, glow: Float = 1f) {
    val c = Td.colors
    // The halo is blurred once into a bitmap (and again only when [glow] or the size changes), so scrolling and
    // page transitions draw a picture instead of re-blurring every frame.
    androidx.compose.foundation.layout.Spacer(
        modifier.drawWithCache {
            val w = size.width * 0.9f
            val left = (size.width - w) / 2f
            val top = (size.height - notchlineHeight(w)) / 2f
            val stroke = (w / 320f) * 2.1f
            val halo = offscreen(this) {
                if (c.isDark) {
                    val path = notchlinePath(left, top, w)
                    glowStroke(path, Color.White.copy(alpha = (0.6f * glow).coerceAtMost(1f)), stroke * 4.5f, stroke * 3.5f)
                    glowStroke(path, Color.White.copy(alpha = (0.5f * glow).coerceAtMost(1f)), stroke * 2f, stroke * 1.2f)
                } else {
                    glowStroke(notchlinePath(left, top, w), Color.White.copy(alpha = glow.coerceAtMost(1f)), stroke * 9f, stroke * 6f)
                }
            }
            val core = notchlinePath(left, top, w)
            onDrawBehind {
                drawImage(halo)
                drawPath(core, c.text, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        },
    )
}

/** Draws [block] into an offscreen bitmap the size of this cache scope (for blurs worth reusing). */
private fun offscreen(scope: androidx.compose.ui.draw.CacheDrawScope, block: DrawScope.() -> Unit): androidx.compose.ui.graphics.ImageBitmap {
    val w = scope.size.width.toInt().coerceAtLeast(1)
    val h = scope.size.height.toInt().coerceAtLeast(1)
    val img = androidx.compose.ui.graphics.ImageBitmap(w, h)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(img), scope.size, block,
    )
    return img
}

/**
 * The light spilling from the top of a screen, as from the notch: a soft cool ellipse in dark mode, a white one on
 * bone in light mode. Static; it brightens with [strength] (0…1).
 */
@Composable
fun LightSpill(modifier: Modifier = Modifier, strength: Float = 1f) {
    val c = Td.colors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tint = if (c.isDark) Color(0xFFE8ECFF).copy(alpha = 0.15f * strength) else Color.White.copy(alpha = 0.95f * strength)
        drawRect(
            Brush.radialGradient(
                0f to tint,
                1f to Color.Transparent,
                center = Offset(w / 2f, 0f),
                radius = w * 0.95f,
            ),
            size = androidx.compose.ui.geometry.Size(w, h),
        )
    }
}

/** A live dot: bone or ink, with the light's halo around it in dark mode. */
@Composable
fun LiveDot(modifier: Modifier = Modifier, size: Dp = 7.dp, lit: Boolean = true) {
    val c = Td.colors
    Canvas(modifier.size(size * 2.6f)) {
        val r = size.toPx() / 2f
        if (lit && c.isDark) {
            drawCircle(Brush.radialGradient(listOf(Brand.Light.copy(alpha = 0.7f), Color.Transparent), center, r * 2.6f), r * 2.6f)
        }
        drawCircle(if (lit) c.text else c.textTertiary, r)
    }
}

/** "TapDrop": Tap in Geist, Drop in the serif's italic. The action sharp, the drop fluid. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 22.sp, color: Color = Td.colors.text) {
    androidx.compose.material3.Text(
        androidx.compose.ui.text.buildAnnotatedString {
            withStyle(androidx.compose.ui.text.SpanStyle(fontFamily = app.tapdrop.ui.GeistFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) {
                append("Tap")
            }
            withStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontFamily = app.tapdrop.ui.SerifFamily,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    letterSpacing = (-0.01).em,
                ),
            ) { append("Drop") }
        },
        fontSize = size,
        letterSpacing = (-0.035).em,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}
