package app.tapdrop.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion

/**
 * The expressive shapes TapDrop uses (Material 3 Expressive `MaterialShapes`, normalized to a unit square).
 * Keep to this short list so the app has a recognizable family rather than a shape zoo.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object TdShapes {
    val Circle: RoundedPolygon get() = MaterialShapes.Circle
    val Square: RoundedPolygon get() = MaterialShapes.Square
    val Cookie4: RoundedPolygon get() = MaterialShapes.Cookie4Sided
    val Cookie6: RoundedPolygon get() = MaterialShapes.Cookie6Sided
    val Cookie7: RoundedPolygon get() = MaterialShapes.Cookie7Sided
    val Cookie9: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val Cookie12: RoundedPolygon get() = MaterialShapes.Cookie12Sided
    val Clover4: RoundedPolygon get() = MaterialShapes.Clover4Leaf
    val Clover8: RoundedPolygon get() = MaterialShapes.Clover8Leaf
    val Sunny: RoundedPolygon get() = MaterialShapes.Sunny
    val VerySunny: RoundedPolygon get() = MaterialShapes.VerySunny
    val Pill: RoundedPolygon get() = MaterialShapes.Pill
    val Gem: RoundedPolygon get() = MaterialShapes.Gem
    val Pentagon: RoundedPolygon get() = MaterialShapes.Pentagon
    val SoftBurst: RoundedPolygon get() = MaterialShapes.SoftBurst
    val Puffy: RoundedPolygon get() = MaterialShapes.Puffy
    val Slanted: RoundedPolygon get() = MaterialShapes.Slanted
    val Arch: RoundedPolygon get() = MaterialShapes.Arch
}

/** Draws [polygon] (unit-square coordinates) scaled to this draw scope. */
fun DrawScope.drawPolygon(polygon: RoundedPolygon, brush: Brush, path: Path = Path()) {
    drawMorphPath(Morph(polygon, polygon), 0f, brush, path)
}

/** Draws [morph] at [progress] scaled to this draw scope. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun DrawScope.drawMorphPath(morph: Morph, progress: Float, brush: Brush, path: Path = Path()) {
    path.rewind()
    morph.toPath(progress, path)
    val m = Matrix()
    m.scale(size.width, size.height)
    path.transform(m)
    drawPath(path, brush)
}

/** A [Shape] from a unit-square polygon: use it with `clip`/`background`/`border` for static expressive shapes. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    private val morph = Morph(polygon, polygon)

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        morph.toPath(0f, path)
        val m = Matrix()
        m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/**
 * An icon in a tinted chip. The chip is a rounded square by default, or an expressive [shape];
 * with [morphTo] it morphs while [pressed] (wire `pressed` to the parent's press state).
 *
 * - `filled = false` (default): tonal chip (accent at ~16 %) holding an accent-tinted symbol.
 * - `filled = true`: solid accent chip with a white symbol, for the one hero element of a view.
 */
@Composable
fun IconChip(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: RoundedPolygon? = null,
    morphTo: RoundedPolygon? = null,
    pressed: Boolean = false,
    filled: Boolean = false,
    iconSize: Dp = size * 0.5f,
    contentDescription: String? = null,
) {
    // Lumen: a plain glyph in the text colour in a quiet round well; [filled] is the one solid (bone or ink).
    // [accent], [shape] and [morphTo] are kept for callers but no longer tint or shape it: no rainbow, no blobs.
    val c = Td.colors
    val fill = if (filled) c.accent else c.accentSoft
    val tint = if (filled) c.onAccent else c.text
    Box(
        modifier
            .size(size)
            .drawBehind { drawCircle(fill) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

internal fun lighten(c: Color, amount: Float) = Color(
    red = c.red + (1f - c.red) * amount,
    green = c.green + (1f - c.green) * amount,
    blue = c.blue + (1f - c.blue) * amount,
    alpha = c.alpha,
)

internal fun darken(c: Color, amount: Float) = Color(
    red = c.red * (1f - amount),
    green = c.green * (1f - amount),
    blue = c.blue * (1f - amount),
    alpha = c.alpha,
)
