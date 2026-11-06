package app.tapdrop.ui.sheet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.MacGlyph
import app.tapdrop.ui.design.StatusIndicator
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.segmentShape
import app.tapdrop.ui.design.toneText

/** A paired Mac as a destination. [online] is null while TapDrop is still checking. */
@Immutable
data class MacChoice(val id: String, val name: String, val online: Boolean?)

/**
 * Where it's going: each paired Mac as a row with its glyph and whether it can be reached right now.
 * With several Macs the rows become a choice, the selected one checked.
 */
@Composable
fun MacTargets(
    macs: List<MacChoice>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val choosing = macs.size > 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        macs.forEachIndexed { i, m ->
            MacTargetRow(
                mac = m,
                shape = segmentShape(i, macs.size),
                selected = m.id == selected,
                choosing = choosing,
                enabled = enabled,
                onClick = { onSelect(m.id) },
            )
        }
    }
}

@Composable
private fun MacTargetRow(
    mac: MacChoice,
    shape: Shape,
    selected: Boolean,
    choosing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = Td.colors
    val dim by animateFloatAsState(if (choosing && !selected) 0.62f else 1f, TdMotion.standard(), label = "targetDim")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(
                if (choosing) Modifier.pressable(shape, enabled = enabled, pressedScale = 0.985f, role = Role.RadioButton, onClick = onClick)
                else Modifier
            )
            .semantics(mergeDescendants = true) { if (choosing) this.selected = selected }
            .platter(shape)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacGlyph(
            width = 50.dp,
            lit = mac.online == true,
            dim = mac.online == false,
            modifier = Modifier.graphicsLayer { alpha = dim },
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp)
                .graphicsLayer { alpha = dim },
        ) {
            Text(
                mac.name,
                style = TdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Presence(mac.online, Modifier.padding(top = 2.dp))
        }
        if (choosing) SelectionMark(selected)
    }
}

/** "Ready", "Checking…" or "Not reachable", with the matching live dot. */
@Composable
fun Presence(online: Boolean?, modifier: Modifier = Modifier) {
    val c = Td.colors
    val tone = when (online) {
        true -> StatusTone.Good
        false -> StatusTone.Offline
        null -> StatusTone.Searching
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusIndicator(tone, modifier = Modifier.size(12.dp))
        Text(
            when (online) {
                true -> "Ready"
                false -> "Not reachable"
                null -> "Checking…"
            },
            style = TdType.bodySmall,
            color = if (online == true) c.toneText(tone) else c.textSecondary,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val c = Td.colors
    val fill by animateColorAsState(if (selected) c.accent else Color.Transparent, TdMotion.standard(), label = "markFill")
    Box(
        Modifier
            .size(24.dp)
            .background(fill, CircleShape)
            .then(if (!selected) Modifier.border(1.5.dp, c.textTertiary.copy(alpha = 0.6f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
    }
}
