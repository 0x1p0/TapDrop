package app.tapdrop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.Hairline
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SettingsRow
import app.tapdrop.ui.design.TdBadge
import app.tapdrop.ui.design.tdSharedBounds

/**
 * "Supported phones & Macs": what this phone has (and what each part is for), which Macs can be tapped, which
 * phones work, and what a setup needs. Opened from Settings → Help; its header chip arrives from that row.
 */
@Composable
fun CompatibilityScreen(onClose: () -> Unit) {
    LargeTitleScaffold(
        title = "Supported devices",
        subtitle = "Which phones and Macs TapDrop works with",
        onBack = onClose,
    ) {
        item("phone") { ThisPhoneCard() }
        item("macs") {
            SettingsGroup(title = "Macs", footer = Compatibility.macRequirement) {
                Compatibility.macs.forEach { m -> item { shape -> MacKindRow(m, shape) } }
            }
        }
        item("phones") {
            SettingsGroup(title = "Android phones", footer = Compatibility.androidVersions) {
                item { shape -> SettingsRow(title = "Google Pixel", subtitle = Compatibility.pixels.joinToString("\n"), shape = shape) }
                Compatibility.brands.forEach { g ->
                    item { shape -> SettingsRow(title = g.name, subtitle = g.models, shape = shape) }
                }
                item { shape ->
                    SettingsRow(
                        title = "Not supported",
                        subtitle = Compatibility.notSupported.joinToString("\n"),
                        shape = shape,
                        enabled = false,
                    )
                }
            }
        }
        item("needs") {
            SettingsGroup(title = "What you need") {
                Compatibility.needs.forEachIndexed { i, need ->
                    item { shape ->
                        SettingsRow(
                            title = need.title,
                            subtitle = need.detail,
                            shape = shape,
                            leading = { NumberBadge(i + 1) },
                        )
                    }
                }
            }
        }
        item("tested") {
            Text(
                Compatibility.tested,
                style = TdType.bodySmall,
                color = Td.colors.textTertiary,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 20.dp),
            )
        }
    }
}

/** This phone: model, Android version, and each part TapDrop uses, with what it's for. */
@Composable
private fun ThisPhoneCard() {
    val ctx = LocalContext.current
    val c = Td.colors
    val caps = remember {
        listOf(
            Cap("Android 13 or newer", "Required", DeviceSupport.supported, required = true),
            Cap("Motion sensor", "Tells a real tap from a hand on the notch", DeviceSupport.hasMotionSensor(ctx), required = true),
            Cap("Proximity sensor", "Lets your phone start a tap too", DeviceSupport.hasProximity(ctx)),
            Cap("NFC", "For the optional sticker", DeviceSupport.hasNfc(ctx)),
            Cap("On Wi‑Fi now", "Needed to reach your Mac", DeviceSupport.onWifi(ctx), required = true),
        )
    }
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(
                Icons.Rounded.Devices, Brand.Teal, size = 52.dp, iconSize = 26.dp,
                modifier = Modifier.tdSharedBounds(COMPAT_CHIP_KEY),
            )
            // The model name gets the whole width (some are one long word); the verdict sits under it.
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("This phone", style = TdType.caption, color = c.textTertiary)
                Text(DeviceSupport.modelName, style = TdType.subtitle, color = c.text)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (DeviceSupport.supported) {
                        TdBadge("Supported", c.success, icon = Icons.Rounded.CheckCircle, ink = c.successText)
                    } else {
                        TdBadge("Not supported", c.danger, icon = Icons.Rounded.ErrorOutline, ink = c.dangerText)
                    }
                    Text(
                        DeviceSupport.androidVersion, style = TdType.bodySmall, color = c.textSecondary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        Hairline(Modifier.padding(top = 16.dp, bottom = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            caps.forEach { CapLine(it) }
        }
    }
}

private class Cap(val name: String, val purpose: String, val present: Boolean, val required: Boolean = false)

@Composable
private fun CapLine(cap: Cap) {
    val c = Td.colors
    val (icon, tint, state) = when {
        cap.present -> Triple(Icons.Rounded.CheckCircle, c.success, "Yes")
        cap.required -> Triple(Icons.Rounded.ErrorOutline, c.warning, "Missing")
        else -> Triple(Icons.Rounded.RemoveCircleOutline, c.textTertiary, "Not on this phone")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${cap.name}: $state. ${cap.purpose}" },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(22.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                cap.name,
                style = TdType.body.copy(fontWeight = FontWeight.Medium),
                color = if (cap.present || cap.required) c.text else c.textSecondary,
            )
            Text(
                if (!cap.present && !cap.required) "${cap.purpose} · not on this phone" else cap.purpose,
                style = TdType.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}

/** A kind of Mac: which models, and what TapDrop does on them (a badge under the models). */
@Composable
private fun MacKindRow(m: Compatibility.MacGroup, shape: Shape) {
    val c = Td.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(c.surface, shape)
            .semantics(mergeDescendants = true) {}
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconChip(
            when {
                !m.runs -> Icons.Rounded.Computer
                m.tap -> Icons.Rounded.LaptopMac
                else -> Icons.Rounded.DesktopMac
            },
            when {
                !m.runs -> c.textTertiary
                m.tap -> Brand.Blue
                else -> Brand.Sky
            },
            size = 36.dp, iconSize = 20.dp,
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                m.name,
                style = TdType.body.copy(fontWeight = FontWeight.Medium),
                color = if (m.runs) c.text else c.textSecondary,
            )
            Text(m.models, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 1.dp))
            Box(Modifier.padding(top = 10.dp)) {
                when {
                    m.tap -> TdBadge(m.can, c.success, icon = Icons.Rounded.CheckCircle, ink = c.successText)
                    m.runs -> TdBadge(m.can, Brand.Sky, ink = c.textSecondary)
                    else -> TdBadge(m.can, c.textTertiary, icon = Icons.Rounded.RemoveCircleOutline, ink = c.textSecondary)
                }
            }
        }
    }
}

/** A small numbered disc for lists of steps or needs. */
@Composable
private fun NumberBadge(n: Int) {
    val c = Td.colors
    Box(Modifier.size(30.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", style = TdType.label.copy(fontWeight = FontWeight.SemiBold), color = c.accentText)
    }
}
