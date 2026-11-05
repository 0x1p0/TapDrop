package app.tapdrop.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.sectionLabel

/**
 * A section header above a group or grid ("Send", "Recent"). Optional trailing [action] (e.g. a text button).
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 2.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = sectionLabel, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/**
 * A quick action: an expressive icon chip, a title and a subtitle on a tonal card. Presses scale to 0.97,
 * morph the chip toward a circle, and tick the haptics. Lay them out two per row with `Modifier.weight(1f)`.
 *
 * @param accent the chip hue (Brand.Blue / Violet / Orchid / Teal…); the card itself stays neutral.
 * @param shape the chip's expressive shape (see [TdShapes]).
 */
@Composable
fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedPolygon = TdShapes.Cookie9,
    enabled: Boolean = true,
) {
    val c = Td.colors
    val cardShape = RoundedCornerShape(TdRadius.card)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Column(
        modifier
            .then(
                if (c.isDark) Modifier
                else Modifier.shadow(12.dp, cardShape, ambientColor = c.shadow.copy(alpha = 0.08f), spotColor = c.shadow.copy(alpha = 0.08f))
            )
            .pressable(cardShape, enabled = enabled, interactionSource = src, onClick = onClick)
            .background(c.surface)
            .then(if (c.isDark) Modifier.border(1.dp, c.hairline, cardShape) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(start = 16.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            IconChip(icon, accent, size = 46.dp, shape = shape, morphTo = TdShapes.Circle, pressed = pressed, iconSize = 22.dp)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Rounded.ArrowOutward, null,
                tint = c.textTertiary.copy(alpha = if (pressed) 1f else 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        // Two lines before an ellipsis, so larger text sizes wrap instead of cutting words off (tiles in a row
        // share the tallest one's height).
        Text(title, style = TdType.subtitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(
                subtitle, style = TdType.bodySmall, color = c.textSecondary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A thin hairline divider, inset from the start. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Td.colors.hairline))
}
