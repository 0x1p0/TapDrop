package app.tapdrop.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType

/**
 * A calm empty (or error) state: an expressive icon chip, a title, a line of explanation and an optional
 * action. Centered, width-capped for readable lines.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    accent: Color = Td.colors.accent,
    shape: RoundedPolygon = TdShapes.Cookie12,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Td.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconChip(icon, accent, size = 64.dp, shape = shape, iconSize = 28.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = TdType.subtitle, color = c.text, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = TdType.bodySmall,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp).padding(top = 6.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
