package app.tapdrop.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.rememberHaptics
import app.tapdrop.ui.LocalReducedMotion
import kotlinx.coroutines.delay

/**
 * The standard press for anything tappable: springs down to [pressedScale], ripples inside [shape],
 * and ticks the haptics. Pass an [interactionSource] to drive other press effects (shape morphs) from the same press.
 */
@Composable
fun Modifier.pressable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    haptic: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier {
    val src = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduced) pressedScale else 1f,
        TdMotion.snappy(),
        label = "press",
    )
    val haptics = rememberHaptics()
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .clickable(
            interactionSource = src,
            indication = ripple(),
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
        ) {
            if (haptic) haptics.click()
            onClick()
        }
}

/**
 * Staggered rise-and-fade entrance for lists of cards. [index] staggers by 45 ms; change [key] to replay.
 * Pass `enabled = false` when the screen is coming back (e.g. after navigating back) so content is simply
 * there, and on pages reached through a [NavHost] transition (the transition is the entrance).
 * Under reduced motion it simply appears.
 */
@Composable
fun Modifier.entrance(index: Int, key: Any = Unit, enabled: Boolean = true): Modifier {
    val reduced = LocalReducedMotion.current
    val a = remember(key) { Animatable(if (reduced || !enabled) 1f else 0f) }
    LaunchedEffect(key) {
        if (reduced || a.value >= 1f) return@LaunchedEffect
        delay(30L + index * 45L)
        a.animateTo(1f, TdMotion.emphasized())
    }
    return this.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * 18.dp.toPx()
        val s = 0.98f + 0.02f * a.value
        scaleX = s
        scaleY = s
    }
}
