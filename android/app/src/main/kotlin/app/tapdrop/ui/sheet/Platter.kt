package app.tapdrop.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdColors

/**
 * The fill of a platter: the quiet card that groups content on a glass sheet (the Mac's "inset" style).
 * A lift of white in dark mode; in light mode a whiter card than the glass, so rows stay crisp whether the
 * sheet floats over a bright app or a dark wallpaper.
 */
val TdColors.inset: Color
    get() = if (isDark) Color.White.copy(alpha = 0.055f) else Color.White.copy(alpha = 0.62f)

/** Draws a platter behind this element: [TdColors.inset] plus, in light mode, a hairline edge. */
@Composable
fun Modifier.platter(shape: Shape): Modifier {
    val c = Td.colors
    return if (c.isDark) background(c.inset, shape)
    else background(c.inset, shape).border(1.dp, Color.Black.copy(alpha = 0.045f), shape)
}

/**
 * Thickens a glass sheet that floats over another app. Put it first on the sheet body's outermost element.
 *
 * The sheet's glass lets a fifth of the backdrop through, which is right over TapDrop's own blurred pages but
 * not over other apps: there the blur comes from the system, which some screens (and battery saver) skip, and a
 * light sheet over a dark app turns gray. This lays a soft layer of the sheet's own color under the content, out
 * to the sheet's edges (the sheet clips it), fading in below the drag handle so the handle stays untouched.
 */
@Composable
fun Modifier.sheetBacking(): Modifier {
    val c = Td.colors
    val color = if (c.isDark) c.surfaceRaised.copy(alpha = 0.75f) else c.surface.copy(alpha = 0.75f)
    return drawBehind {
        val fade = 28.dp.toPx()
        val reach = 600.dp.toPx()
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 1f to color, startY = 0f, endY = fade),
            topLeft = Offset(-reach, 0f),
            size = Size(size.width + reach * 2, fade),
        )
        drawRect(color, topLeft = Offset(-reach, fade), size = Size(size.width + reach * 2, size.height - fade + reach))
    }
}
