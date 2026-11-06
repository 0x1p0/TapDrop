package app.tapdrop.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion

/**
 * A small drawn MacBook seen from the front: the lid with its notch and wallpaper, and the base. It is the
 * Mac from [DeviceHero] at icon size, for rows, cards and sheet headers ("which Mac").
 *
 * - [lit]: the notch glows violet, as in DeviceHero when the link is up (online, ready to pair).
 * - [dim]: grays the display out (not reachable).
 *
 * Height is 0.64 × [width]. Both changes animate gently.
 */
@Composable
fun MacGlyph(
    modifier: Modifier = Modifier,
    width: Dp = 52.dp,
    lit: Boolean = true,
    dim: Boolean = false,
    contentDescription: String? = null,
) {
    val dark = Td.colors.isDark
    val glow by animateFloatAsState(if (lit && !dim) 1f else 0f, TdMotion.gentle(), label = "macGlyphGlow")
    val shade by animateFloatAsState(if (dim) 1f else 0f, TdMotion.gentle(), label = "macGlyphDim")
    Canvas(
        modifier
            .size(width, width * GLYPH_ASPECT)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        drawMacGlyph(dark, glow, shade)
    }
}

private const val GLYPH_ASPECT = 0.64f

private fun DrawScope.drawMacGlyph(dark: Boolean, glow: Float, dim: Float) {
    val w = size.width
    val lidW = w * 0.84f
    val lidH = lidW / 1.5f
    val lid = Rect(Offset((w - lidW) / 2f, w * 0.004f), Size(lidW, lidH))
    val baseH = w * 0.052f
    val base = Rect(0f, lid.bottom - baseH * 0.12f, w, lid.bottom + baseH * 0.88f)

    val lidTop = if (dark) Color(0xFF3A3C46) else Color(0xFFE6E7EC)
    val lidBottom = if (dark) Color(0xFF24252C) else Color(0xFFC9CBD3)
    val edge = if (dark) Color.White.copy(alpha = 0.18f) else Color.White
    val baseTop = if (dark) Color(0xFF4E505B) else Color(0xFFF2F3F6)
    val baseBottom = if (dark) Color(0xFF1C1D23) else Color(0xFFB4B7C1)

    // a soft contact shadow, so it sits on light cards instead of floating
    val sr = w * 0.5f
    drawOval(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = if (dark) 0.45f else 0.16f), Color.Transparent),
            center = Offset(w / 2f, base.bottom), radius = sr,
        ),
        topLeft = Offset(w / 2f - sr, base.bottom - sr * 0.09f),
        size = Size(sr * 2f, sr * 0.18f),
    )

    // lid
    val lidR = lidW * 0.07f
    val lidPath = Path().apply {
        addRoundRect(
            RoundRect(
                lid,
                topLeft = CornerRadius(lidR), topRight = CornerRadius(lidR),
                bottomLeft = CornerRadius(lidR * 0.3f), bottomRight = CornerRadius(lidR * 0.3f),
            )
        )
    }
    drawPath(lidPath, Brush.verticalGradient(listOf(lidTop, lidBottom), lid.top, lid.bottom))
    drawPath(lidPath, edge.copy(alpha = edge.alpha * 0.7f), style = Stroke((w * 0.012f).coerceAtLeast(1f)))

    // bezel and display
    val bezel = lidW * 0.035f
    val chin = lidW * 0.062f
    drawRoundRect(
        Color(0xFF050507),
        Offset(lid.left + bezel * 0.4f, lid.top + bezel * 0.4f),
        Size(lid.width - bezel * 0.8f, lid.height - chin * 0.6f),
        CornerRadius(lidR * 0.8f),
    )
    val display = Rect(lid.left + bezel, lid.top + bezel, lid.right - bezel, lid.bottom - chin)
    val dispR = lidW * 0.045f
    val displayPath = Path().apply {
        addRoundRect(
            RoundRect(
                display,
                topLeft = CornerRadius(dispR), topRight = CornerRadius(dispR),
                bottomLeft = CornerRadius(dispR * 0.35f), bottomRight = CornerRadius(dispR * 0.35f),
            )
        )
    }
    clipPath(displayPath) {
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF0B0B0E), Color(0xFF17171B)), display.top, display.bottom),
            display.topLeft, display.size,
        )
        val k = 1f - dim * 0.8f
        fun pool(color: Color, fx: Float, fy: Float, fr: Float, a: Float) {
            val c = Offset(display.left + display.width * fx, display.top + display.height * fy)
            val r = display.width * fr
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a * k), Color.Transparent), c, r), r, c)
        }
        pool(Brand.Light, 0.5f, 0.0f, 0.75f, 0.22f)

        val notchW = display.width * 0.19f
        val notchH = display.width * 0.075f
        val nc = Offset(display.center.x, display.top + notchH)
        if (glow > 0.01f) {
            val gr = display.width * 0.6f
            drawCircle(
                Brush.radialGradient(
                    0f to Brand.Light.copy(alpha = 0.75f * glow),
                    0.35f to Brand.Light.copy(alpha = 0.25f * glow),
                    1f to Color.Transparent,
                    center = nc, radius = gr,
                ),
                gr, nc,
            )
            val cr = notchW * 0.9f
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.7f * glow),
                    0.5f to Color(0xFFD9CCFF).copy(alpha = 0.25f * glow),
                    1f to Color.Transparent,
                    center = nc, radius = cr,
                ),
                cr, nc,
            )
        }
        if (dim > 0f) drawRect(Color(0xFF0E0E12).copy(alpha = 0.6f * dim), display.topLeft, display.size)
        // the notch: square top, rounded bottom corners
        val nr = notchH * 0.5f
        drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(display.center.x - notchW / 2f, display.top - 1f, display.center.x + notchW / 2f, display.top + notchH),
                        CornerRadius.Zero, CornerRadius.Zero, CornerRadius(nr), CornerRadius(nr),
                    )
                )
            },
            Color.Black,
        )
        // a sheen across the glass
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.08f), 0.45f to Color.Transparent,
                start = display.topLeft, end = Offset(display.left + display.width * 0.6f, display.bottom),
            ),
            display.topLeft, display.size,
        )
    }

    // base, with the opening scoop
    val basePath = Path().apply {
        addRoundRect(
            RoundRect(
                base,
                topLeft = CornerRadius(baseH * 0.2f), topRight = CornerRadius(baseH * 0.2f),
                bottomLeft = CornerRadius(baseH * 1.2f, baseH), bottomRight = CornerRadius(baseH * 1.2f, baseH),
            )
        )
    }
    drawPath(basePath, Brush.verticalGradient(listOf(baseTop, baseBottom), base.top, base.bottom))
    val scoopW = lidW * 0.18f
    drawRoundRect(
        if (dark) Color(0xFF15161B) else Color(0xFFA3A6B0),
        Offset(base.center.x - scoopW / 2f, base.top),
        Size(scoopW, base.height * 0.42f),
        CornerRadius(base.height * 0.4f),
    )
}
