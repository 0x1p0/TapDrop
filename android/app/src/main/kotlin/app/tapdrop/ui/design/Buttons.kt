package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType

/**
 * The main call to action: a full pill filled with the brand gradient, 56 dp tall, white label.
 * Use at most one per view (it is the view's one gradient element). [loading] swaps the label for an
 * expressive loading indicator and ignores taps.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = Td.colors
    val active = enabled && !loading
    Box(
        modifier
            .heightIn(min = 56.dp)
            .pressable(CircleShape, enabled = active, onClick = onClick)
            .then(
                // Bone in dark mode, ink in light: the one solid fill on a screen.
                if (enabled) Modifier.background(c.accent)
                else Modifier.background(c.surfaceSunken)
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = { (fadeIn(tween(180)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(120)) },
            label = "primaryLoading",
        ) { busy ->
            if (busy) {
                TdLoadingIndicator(Modifier.size(32.dp), color = c.onAccent)
            } else {
                ButtonLabel(text, icon, if (enabled) c.onAccent else c.textTertiary)
            }
        }
    }
}

/**
 * The quieter button: a tonal pill (neutral, or violet with [accent]). Same size as [PrimaryButton].
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val c = Td.colors
    val bg = when {
        accent -> c.accentSoft
        c.isDark -> Color.White.copy(alpha = 0.07f)
        else -> Color.White
    }
    val ink = when {
        !enabled -> c.textTertiary
        accent -> c.accentText
        else -> c.text
    }
    Box(
        modifier
            .heightIn(min = 56.dp)
            .pressable(CircleShape, enabled = enabled, onClick = onClick)
            .background(bg)
            .border(1.dp, if (accent) Color.Transparent else c.hairline, CircleShape)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text, icon, ink)
    }
}

/** A text-only action in the accent color (for "Not now", "Details", inline links). 48 dp touch target. */
@Composable
fun TdTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Td.colors.accentText,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .pressable(CircleShape, enabled = enabled, pressedScale = 0.96f, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text, icon, if (enabled) color else Td.colors.textTertiary, TdType.label)
    }
}

/**
 * A compact pill for row trailing actions ("Allow", "Open", "Turn on"): 36 dp tall inside a 48 dp target.
 * [prominent] fills it with the gradient for the one step that matters now.
 */
@Composable
fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Td.colors
    Box(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(36.dp)
                .pressable(CircleShape, enabled = enabled, pressedScale = 0.94f, onClick = onClick)
                .then(if (prominent && enabled) Modifier.background(c.accent) else Modifier.background(c.accentSoft))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = TdType.label.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = when {
                    !enabled -> c.textTertiary
                    prominent -> c.onAccent
                    else -> c.accentText
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ButtonLabel(text: String, icon: ImageVector?, color: Color, style: androidx.compose.ui.text.TextStyle = TdType.subtitle.copy(fontSize = TdType.body.fontSize)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
