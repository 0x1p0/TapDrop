package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdColors
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.rememberHaptics

// Extensions of the settings components in Settings.kt for pages with switches, choices and search
// (Settings, App rules, Test your tap). Same tokens and anatomy as SettingsRow; nothing here restyles it.

/**
 * The Android 16 expressive switch: [TdSwitch]'s colors, with a check in the thumb while it's on (the thumb
 * grows into it). Pass `onCheckedChange = null` when a whole row toggles it ([SwitchRow]).
 */
@Composable
fun TdIconSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = Td.colors
    val haptics = rememberHaptics()
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let { cb ->
            { on: Boolean ->
                haptics.toggle(on)
                cb(on)
            }
        },
        modifier = modifier,
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
        } else null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.accent,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = c.accent,
            uncheckedThumbColor = if (c.isDark) c.textSecondary else Color.White,
            uncheckedTrackColor = c.surfaceSunken,
            uncheckedBorderColor = if (c.isDark) c.hairline else Color.Black.copy(alpha = 0.10f),
            disabledCheckedThumbColor = Color.White.copy(alpha = 0.9f),
            disabledCheckedTrackColor = c.accent.copy(alpha = 0.35f),
            disabledCheckedIconColor = c.accent.copy(alpha = 0.5f),
            disabledUncheckedThumbColor = c.textTertiary.copy(alpha = 0.5f),
            disabledUncheckedTrackColor = c.surfaceSunken.copy(alpha = 0.6f),
            disabledUncheckedBorderColor = c.hairline,
        ),
    )
}

/**
 * A [SettingsRow] that is one big switch: tapping anywhere toggles it, and screen readers hear a single
 * "switch, on/off" with the row's text as its label.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TdRadius.group),
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = Td.colors.accent,
    iconShape: RoundedPolygon? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        iconShape = iconShape,
        enabled = enabled,
        shape = shape,
        modifier = modifier
            .pressable(shape, enabled = enabled, pressedScale = 0.985f, haptic = false, role = Role.Switch) {
                haptics.toggle(!checked)
                onCheckedChange(!checked)
            }
            .semantics { toggleableState = ToggleableState(checked) },
        trailing = { TdIconSwitch(checked, null, enabled = enabled) },
    )
}

/**
 * The Android 16 "main switch": the first segment of a feature's group, tinted with the accent while the
 * feature is on, its chip filling in. [onClick] opens the feature's page; the switch toggles it.
 */
@Composable
fun MainSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TdRadius.group),
    subtitle: String? = null,
    iconShape: RoundedPolygon? = TdShapes.Cookie9,
    onClick: (() -> Unit)? = null,
) {
    val c = Td.colors
    val tint by animateColorAsState(
        if (checked) c.accentSoft.compositeOver(c.surface) else c.surface,
        TdMotion.standard(), label = "mainSwitchTint",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .then(if (onClick != null) Modifier.pressable(shape, pressedScale = 0.985f, onClick = onClick) else Modifier)
            .background(tint, shape)
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(icon, c.accent, size = 40.dp, shape = iconShape, filled = checked, iconSize = 22.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
            Text(title, style = TdType.subtitle, color = c.text)
            if (subtitle != null) {
                Text(subtitle, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        TdIconSwitch(checked, onCheckedChange)
    }
}

/**
 * Segments inside a [GlassSheet] have to stand off the sheet itself, which is raised (dark) or white (light):
 * a step darker than it in dark mode, the page's own gray in light mode.
 */
val TdColors.sheetSegment: Color get() = if (isDark) surface else bg

/**
 * One option of a single choice, as a segment: icon chip, title, a line of detail, and a radio mark.
 * The chosen one is tinted and outlined in the accent. Use it inside a [SettingsGroup] (in a sheet, pass
 * `color = Td.colors.sheetSegment`).
 */
@Composable
fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TdRadius.group),
    detail: String? = null,
    icon: ImageVector? = null,
    accent: Color = Td.colors.accent,
    iconShape: RoundedPolygon? = null,
    color: Color = Td.colors.surface,
) {
    val c = Td.colors
    val haptics = rememberHaptics()
    val fill by animateColorAsState(
        if (selected) c.accentSoft.compositeOver(color) else color,
        TdMotion.standard(), label = "choiceFill",
    )
    val edge by animateColorAsState(
        if (selected) c.accent.copy(alpha = if (c.isDark) 0.55f else 0.45f) else Color.Transparent,
        TdMotion.standard(), label = "choiceEdge",
    )
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(shape, pressedScale = 0.985f, haptic = false, role = Role.RadioButton) {
                haptics.tick()
                onClick()
            }
            .semantics { this.selected = selected }
            .background(fill, shape)
            .border(1.5.dp, edge, shape)
            .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) IconChip(icon, accent, size = 36.dp, shape = iconShape, iconSize = 20.dp)
        Column(Modifier.weight(1f).padding(start = if (icon != null) 14.dp else 2.dp, end = 10.dp)) {
            Text(title, style = TdType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
            if (detail != null) {
                Text(detail, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 1.dp))
            }
        }
        RadioMark(selected)
    }
}

/** The radio of a [ChoiceRow]: a ring that fills with a check when chosen. */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = Td.colors
    val ring by animateColorAsState(if (selected) c.accent else c.textTertiary.copy(alpha = 0.7f), TdMotion.standard(), label = "radioRing")
    Box(
        modifier
            .size(24.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(TdMotion.snappy(), initialScale = 0.3f) + fadeIn(tween(90)),
            exit = scaleOut(tween(120), targetScale = 0.3f) + fadeOut(tween(90)),
        ) {
            Box(Modifier.size(24.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Trailing mark for a permission or step that's already granted: a green check, read as "Allowed". */
@Composable
fun RowAllowed(label: String = "Allowed") {
    Icon(
        Icons.Rounded.CheckCircle, label,
        tint = Td.colors.success,
        modifier = Modifier.padding(horizontal = 6.dp).size(24.dp),
    )
}

/**
 * The search pill: a 52 dp capsule with a search glyph, placeholder, and a clear button once there's text.
 * The edge lights up in the accent while it has focus; the keyboard's search key just closes the keyboard.
 */
@Composable
fun TdSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Td.colors
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(
        if (focused) c.accent.copy(alpha = 0.6f) else c.hairline,
        TdMotion.standard(), label = "searchEdge",
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TdType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(c.surface, CircleShape)
                    .border(if (focused) 1.5.dp else 1.dp, edge, CircleShape)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = if (focused) c.accentText else c.textSecondary, modifier = Modifier.size(22.dp))
                Box(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = TdType.body, color = c.textTertiary, maxLines = 1)
                    field()
                }
                AnimatedVisibility(
                    visible = value.isNotEmpty(),
                    enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.6f),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .pressable(CircleShape, pressedScale = 0.9f, onClickLabel = "Clear search") { onValueChange("") },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(24.dp).background(c.textTertiary.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, "Clear search", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
    )
}

/** For the one destructive action of a view ("Forget this Mac"): a tonal red pill, the size of [SecondaryButton]. */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    val c = Td.colors
    val bg by animateColorAsState(
        if (filled) c.danger else c.danger.copy(alpha = if (c.isDark) 0.15f else 0.10f),
        TdMotion.standard(), label = "destructiveBg",
    )
    val ink by animateColorAsState(if (filled) Color.White else c.dangerText, TdMotion.standard(), label = "destructiveInk")
    Box(
        modifier
            .heightIn(min = 56.dp)
            .pressable(CircleShape, onClick = onClick)
            .background(bg)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = TdType.subtitle.copy(fontSize = TdType.body.fontSize), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A small capsule label with an optional glyph ("Lit room", "Link"). Tinted with [color]. A label too long for
 * its line wraps rather than clipping, and the capsule becomes a rounded block.
 */
@Composable
fun TdBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    ink: Color = color,
) {
    val c = Td.colors
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(color.copy(alpha = if (c.isDark) 0.15f else 0.12f), RoundedCornerShape(14.dp))
            .padding(start = if (icon != null) 8.dp else 11.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = TdType.caption.copy(fontSize = TdType.label.fontSize * 0.93f), color = ink)
    }
}

/**
 * A placeholder row while a list loads: a chip and two bars on a segment, with a soft sheen passing over
 * (still under reduced motion). Screen readers hear "Loading".
 */
@Composable
fun SkeletonRow(shape: Shape, modifier: Modifier = Modifier, chip: Dp = 40.dp, titleWidth: Float = 0.5f) {
    val c = Td.colors
    val reduced = Td.reducedMotion
    val sweep = if (reduced) -1f else {
        val t = rememberInfiniteTransition(label = "skeleton")
        t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep").value
    }
    val bar = if (c.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.06f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(c.surface, shape)
            .semantics { stateDescription = "Loading" }
            .drawWithContent {
                drawContent()
                if (sweep >= -0.4f) {
                    val x = size.width * sweep
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = if (c.isDark) 0.04f else 0.5f), Color.Transparent),
                            startX = x - size.width * 0.3f, endX = x + size.width * 0.3f,
                        ),
                        topLeft = Offset.Zero, size = size,
                    )
                }
            }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(chip).background(bar, CircleShape))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Box(Modifier.fillMaxWidth(titleWidth).height(14.dp).background(bar, RoundedCornerShape(7.dp)))
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(titleWidth * 0.62f).height(11.dp).background(bar, RoundedCornerShape(6.dp)))
        }
    }
}
