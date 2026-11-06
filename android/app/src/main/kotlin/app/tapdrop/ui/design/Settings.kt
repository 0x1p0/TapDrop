package app.tapdrop.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.rememberHaptics

/**
 * The shape of item [index] of [count] in an Android 16 segmented group: 24 dp outer corners,
 * 6 dp inner corners (pair it with 2 dp gaps).
 */
fun segmentShape(index: Int, count: Int, outer: Dp = TdRadius.group, inner: Dp = TdRadius.segment): Shape = when {
    count <= 1 -> RoundedCornerShape(outer)
    index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    else -> RoundedCornerShape(inner)
}

/** Builder for [SettingsGroup]. Each item receives the segment shape it must use. */
class SettingsGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable (Shape) -> Unit>()

    /** Any custom segment; draw it with the given shape (or use [SettingsRow] with `shape = it`). */
    fun item(content: @Composable (shape: Shape) -> Unit) {
        items += content
    }

    /** A standard [SettingsRow] segment. */
    fun row(
        title: String,
        subtitle: String? = null,
        icon: ImageVector? = null,
        accent: Color? = null,
        onClick: (() -> Unit)? = null,
        enabled: Boolean = true,
        trailing: (@Composable RowScope.() -> Unit)? = null,
    ) {
        items += { shape ->
            SettingsRow(
                title = title, subtitle = subtitle, icon = icon, accent = accent ?: Td.colors.accent,
                shape = shape, onClick = onClick, enabled = enabled, trailing = trailing,
            )
        }
    }
}

/**
 * A group of rows in the Android 16 segmented style: each row is its own tonal tile, 2 dp apart,
 * with large outer and small inner corners. Optional [title] above and [footer] below.
 *
 * ```
 * SettingsGroup(title = "Tap to send") {
 *     row("Pick before sending", "A second to choose part of the screen", Icons.Rounded.TouchApp,
 *         trailing = { TdSwitch(pick, onCheckedChange = ::setPick) })
 *     item { shape -> MyCustomRow(shape) }
 * }
 * ```
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionHeader(title)
        Column(verticalArrangement = Arrangement.spacedBy(TdRadius.segmentGap)) {
            scope.items.forEachIndexed { i, item -> item(segmentShape(i, scope.items.size)) }
        }
        if (footer != null) {
            Text(
                footer,
                style = TdType.bodySmall,
                color = Td.colors.textSecondary,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp),
            )
        }
    }
}

/**
 * One settings row: icon chip, title, optional subtitle, and a trailing control (switch, chevron, value,
 * [SmallButton]). Tappable when [onClick] is set. Minimum height 64 dp; text never clips at large font sizes.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TdRadius.group),
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = Td.colors.accent,
    iconShape: RoundedPolygon? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Td.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                if (onClick != null) Modifier.pressable(shape, enabled = enabled, pressedScale = 0.985f, onClick = onClick)
                else Modifier
            )
            .background(c.surface, shape)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> IconChip(icon, accent, size = 36.dp, shape = iconShape, iconSize = 20.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = if (leading != null || icon != null) 14.dp else 0.dp, end = 8.dp),
        ) {
            Text(
                title,
                style = TdType.body.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) c.text else c.textTertiary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 1.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** Trailing chevron for rows that open a page. */
@Composable
fun RowChevron() = Icon(
    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
    tint = Td.colors.textTertiary, modifier = Modifier.size(22.dp),
)

/** Trailing value text for rows ("Smart", "3 apps"). */
@Composable
fun RowValue(text: String) = Text(text, style = TdType.bodySmall, color = Td.colors.textSecondary, maxLines = 1)

/** The brand switch: violet track, white thumb, haptic on change. */
@Composable
fun TdSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Td.colors
    val haptics = rememberHaptics()
    Switch(
        checked = checked,
        onCheckedChange = {
            haptics.toggle(it)
            onCheckedChange(it)
        },
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = c.accent,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = c.accent,
            uncheckedThumbColor = if (c.isDark) c.textSecondary else Color.White,
            uncheckedTrackColor = c.surfaceSunken,
            uncheckedBorderColor = if (c.isDark) c.hairline else Color.Black.copy(alpha = 0.10f),
        ),
    )
}

// MARK: - setup checklists

/** Where a setup step stands. */
enum class StepState {
    /** Finished: green check, body collapses. */
    Done,
    /** The step to do now: violet, with the prominent action. */
    Current,
    /** Still to do, after the current one. */
    Pending,
    /** Only needed on some phones ("if you see …"): dashed badge. */
    Optional,
}

/**
 * One step of a setup checklist: a numbered badge that turns into a check, a title, an explanation that
 * collapses once done, and an action button. Use inside [SettingsGroup] (`item { shape -> StepRow(…, shape = shape) }`).
 */
@Composable
fun StepRow(
    number: Int,
    title: String,
    state: StepState,
    modifier: Modifier = Modifier,
    body: String? = null,
    shape: Shape = RoundedCornerShape(TdRadius.group),
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Td.colors
    val done = state == StepState.Done
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (state == StepState.Current) c.surfaceRaised else c.surface, shape)
            .then(if (state == StepState.Current) Modifier.border(1.dp, c.accent.copy(alpha = if (c.isDark) 0.35f else 0.25f), shape) else Modifier)
            .semantics(mergeDescendants = false) {
                stateDescription = when (state) {
                    StepState.Done -> "Done"
                    StepState.Current -> "To do next"
                    StepState.Pending -> "Not done yet"
                    StepState.Optional -> "Only if needed"
                }
            }
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepBadge(number, state)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                title,
                style = TdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (done) c.textSecondary else c.text,
            )
            AnimatedVisibility(
                visible = body != null && !done,
                enter = fadeIn() + expandVertically(TdMotion.standard()),
                exit = fadeOut() + shrinkVertically(TdMotion.standard()),
            ) {
                Text(body.orEmpty(), style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        AnimatedContent(
            targetState = done,
            transitionSpec = { (fadeIn(tween(200)) + scaleIn(initialScale = 0.85f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.85f)) },
            label = "stepTrailing",
        ) { isDone ->
            if (isDone) {
                Text("Done", style = TdType.label, color = c.successText, modifier = Modifier.padding(horizontal = 8.dp))
            } else if (action != null && onAction != null) {
                SmallButton(action, onAction, prominent = state == StepState.Current)
            }
        }
    }
}

/** The numbered badge of a [StepRow]. */
@Composable
fun StepBadge(number: Int, state: StepState, modifier: Modifier = Modifier) {
    val c = Td.colors
    val fill by animateColorAsState(
        when (state) {
            StepState.Done -> c.success
            StepState.Current -> c.accent
            else -> Color.Transparent
        },
        TdMotion.standard(), label = "badge",
    )
    Box(
        modifier
            .size(30.dp)
            .background(fill, CircleShape)
            .then(
                when (state) {
                    StepState.Pending -> Modifier.border(1.5.dp, c.textTertiary.copy(alpha = 0.6f), CircleShape)
                    StepState.Optional -> Modifier.dashedCircle(c.textTertiary.copy(alpha = 0.7f))
                    else -> Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state == StepState.Done,
            transitionSpec = { (scaleIn(TdMotion.emphasized(), initialScale = 0.4f) + fadeIn()) togetherWith fadeOut(tween(90)) },
            label = "badgeContent",
        ) { isDone ->
            if (isDone) {
                Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    "$number",
                    style = TdType.label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (state == StepState.Current) c.onAccent else c.textSecondary,
                )
            }
        }
    }
}

private fun Modifier.dashedCircle(color: Color) = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawCircle(
        color, size.minDimension / 2 - stroke / 2,
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 2.2f, stroke * 1.8f))),
    )
}
