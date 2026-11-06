package app.tapdrop.ui

import android.app.Activity
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.tapdrop.ui.design.AuroraBackdrop
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.MediaThumb
import app.tapdrop.ui.design.ProgressRing
import app.tapdrop.ui.design.entrance
import app.tapdrop.ui.design.pressable
import kotlin.math.hypot

// Shared building blocks from before the design system, kept so every screen still compiles.
// They now render with the design system; new code should use app.tapdrop.ui.design directly:
//   tappable → pressable · enter → entrance · GlowBackdrop → AuroraBackdrop · SheetHost → GlassSheet
//   Thumb → MediaThumb · DeviceOrb → DeviceHero / CheckBurst / ProgressRing · TapHint → HowToTapIllustration

// MARK: - interaction

/** Legacy name for [pressable]: springy press, ripple and a haptic tick. */
@Composable
fun Modifier.tappable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = pressable(shape = shape, enabled = enabled, pressedScale = pressedScale, onClick = onClick)

/** Legacy name for [entrance]: staggered rise-and-fade. */
@Composable
fun Modifier.enter(index: Int, key: Any = Unit): Modifier = entrance(index, key)

// MARK: - motion graphics

/** AirDrop-style expanding radar rings (used while discovering Macs). */
@Composable
fun Ripples(
    modifier: Modifier,
    color: Color = Td.colors.accent,
    rings: Int = 3,
    periodMs: Int = 2600,
    start: Float = 0.32f,
    stroke: Dp = 1.5.dp,
) {
    if (Td.reducedMotion) return
    val t = rememberInfiniteTransition(label = "ripples")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        val maxR = size.minDimension / 2
        repeat(rings) { i ->
            val f = (p + i.toFloat() / rings) % 1f
            val eased = 1f - (1f - f) * (1f - f)
            val r = maxR * (start + (1f - start) * eased)
            val alpha = (1f - f) * 0.6f
            drawCircle(color.copy(alpha = alpha * 0.08f), r)
            drawCircle(color.copy(alpha = alpha), r, style = Stroke(stroke.toPx()))
        }
    }
}

enum class OrbState { Idle, Working, Done, Failed }

/**
 * Legacy device "orb": a glowing gradient disc with the device symbol, a progress ring while working,
 * a check (or ✕) when finished. New screens use [app.tapdrop.ui.design.DeviceHero].
 */
@Composable
fun DeviceOrb(
    icon: ImageVector,
    state: OrbState,
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    pulsing: Boolean = false,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptics = rememberHaptics()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.92f
            state == OrbState.Done -> 1.04f
            else -> 1f
        },
        TdMotion.snappy(), label = "orb",
    )
    val c1 by animateColorAsState(
        when (state) {
            OrbState.Done -> Color(0xFF3A3A40)
            OrbState.Failed -> Brand.Danger
            else -> Color(0xFF2A2A30)
        }, tween(450), label = "c1",
    )
    val c2 by animateColorAsState(
        when (state) {
            OrbState.Done -> Color(0xFF1E1E22)
            OrbState.Failed -> Color(0xFFD63A4A)
            else -> Color(0xFF151518)
        }, tween(450), label = "c2",
    )
    val glow by animateFloatAsState(if (state == OrbState.Working || pulsing) 1f else 0.5f, tween(600), label = "glow")

    Box(modifier.size(size * 1.9f), contentAlignment = Alignment.Center) {
        if (pulsing || state == OrbState.Working) {
            Ripples(Modifier.fillMaxSize(), color = c2, periodMs = if (state == OrbState.Working) 1400 else 2600, start = 0.5f)
        }
        // Glow: alpha lives in the colors (a layer alpha would clip the blur to a square).
        Box(
            Modifier
                .size(size * scale * 1.05f)
                .blur(26.dp, BlurredEdgeTreatment.Unbounded)
                .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.55f * glow), c2.copy(alpha = 0.55f * glow))), CircleShape)
        )
        AnimatedVisibility(
            visible = state == OrbState.Working,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 1.15f),
        ) {
            ProgressRing(progress, Modifier.size(size + 18.dp), stroke = 3.5.dp, track = c2.copy(alpha = 0.18f))
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2)))
                .then(
                    if (onClick != null) Modifier.clickable(src, ripple(color = Color.White)) {
                        haptics.click()
                        onClick()
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.30f), 0.55f to Color.Transparent))
            )
            AnimatedContent(
                targetState = state,
                contentKey = { if (it == OrbState.Done) 1 else if (it == OrbState.Failed) 2 else 0 },
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.5f, stiffness = 500f), initialScale = 0.3f) + fadeIn(tween(160))) togetherWith
                        (scaleOut(targetScale = 0.3f) + fadeOut(tween(120)))
                },
                label = "orbIcon",
            ) { s ->
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    when (s) {
                        OrbState.Done -> CheckBurst(true, size = size * 0.62f, color = Color.Transparent)
                        OrbState.Failed -> Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
                        else -> Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.42f))
                    }
                }
            }
        }
    }
}

/**
 * 0 → 1 over [periodMs], [loops] times, then rests at 0: ambient motion that says hello
 * and then stops, instead of redrawing for as long as the screen stays open.
 */
@Composable
fun loopPhase(periodMs: Int, loops: Int = 3): Float {
    val a = remember { Animatable(0f) }
    val reduced = Td.reducedMotion
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        repeat(loops) {
            a.snapTo(0f)
            a.animateTo(1f, tween(periodMs, easing = LinearEasing))
        }
        a.snapTo(0f)
    }
    return a.value
}

/** Rise to 1 by [rise], hold until [hold], back to 0 by [fall] (fractions of the loop), eased. */
fun bump(t: Float, rise: Float, hold: Float, fall: Float): Float = when {
    t < rise -> FastOutSlowInEasing.transform(t / rise)
    t < hold -> 1f
    t < fall -> 1f - FastOutSlowInEasing.transform((t - hold) / (fall - hold))
    else -> 0f
}

/** A phone bumping a laptop: a compact "tap your phone on the Mac" hint. */
@Composable
fun TapHint(modifier: Modifier = Modifier, tint: Color = Td.colors.accent) {
    val p = bump(loopPhase(2400), rise = 750 / 2400f, hold = 1250 / 2400f, fall = 2000 / 2400f)
    val touching = p > 0.97f
    val pulse by animateFloatAsState(if (touching) 1f else 0f, tween(if (touching) 500 else 0), label = "pulse")
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.LaptopMac, null,
            tint = Td.colors.textSecondary.copy(alpha = 0.8f),
            modifier = Modifier.size(46.dp).offset(y = 10.dp),
        )
        if (pulse > 0f) {
            Canvas(Modifier.size(72.dp)) {
                drawCircle(tint.copy(alpha = (1f - pulse) * 0.5f), radius = size.minDimension / 2 * (0.3f + 0.7f * pulse), center = Offset(size.width / 2, size.height * 0.55f), style = Stroke(2.dp.toPx()))
            }
        }
        Icon(
            Icons.Rounded.Smartphone, null, tint = tint,
            modifier = Modifier
                .size(30.dp)
                .offset(x = 12.dp, y = lerp((-18).dp, 2.dp, p))
                .graphicsLayer { rotationZ = -22f + 14f * p },
        )
    }
}

/** Full-screen shockwave from the top when a tap lands (NFC sticker, notch). */
@Composable
fun TapBurst(trigger: Int, modifier: Modifier = Modifier.fillMaxSize()) {
    val a = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            a.snapTo(0f)
            a.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        }
    }
    if (a.value < 1f) {
        Canvas(modifier) {
            val c = Offset(size.width / 2, size.height * 0.08f)
            val maxR = hypot(size.width, size.height)
            drawCircle(
                Brush.radialGradient(listOf(Brand.Light.copy(alpha = 0.30f * (1 - a.value)), Color.Transparent), c, maxR * a.value + 1f),
                radius = maxR * a.value + 1f, center = c,
            )
            for (i in 0..2) {
                val f = (a.value * 1.35f - i * 0.17f).coerceIn(0f, 1f)
                if (f in 0.001f..0.999f) {
                    drawCircle(
                        Brand.Light.copy(alpha = (1 - f) * 0.55f), radius = maxR * f, center = c,
                        style = Stroke((10.dp.toPx() * (1 - f)).coerceAtLeast(1f)),
                    )
                }
            }
        }
    }
}

/** Legacy name for [AuroraBackdrop]: soft brand light behind the top of a screen. */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier, intensity: Float = 1f) {
    AuroraBackdrop(modifier, intensity = intensity * 0.85f)
}

// MARK: - media

/** Legacy name for [MediaThumb] (Coil, crossfade, video frames). */
@Composable
fun Thumb(uri: Uri?, modifier: Modifier, fallback: ImageVector = Icons.AutoMirrored.Rounded.InsertDriveFile) {
    MediaThumb(uri, modifier, fallback)
}

// MARK: - bottom sheet

/**
 * Legacy name for [GlassSheet]: scrim + sheet that springs up, can be flung down, and follows predictive
 * back. [onClosed] fires once the exit animation has finished.
 */
@Composable
fun SheetHost(
    onClosed: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    GlassSheet(onDismissed = onClosed, overlay = overlay, content = content)
}

// MARK: - NFC

/** Foreground NFC reader mode while this screen is resumed (suppresses the system tag dispatch). */
@Composable
fun NfcReader(enabled: Boolean, onTag: (Tag) -> Unit) {
    val activity: Activity = LocalActivity.current ?: return
    val adapter = remember { NfcAdapter.getDefaultAdapter(activity) } ?: return
    val current by rememberUpdatedState(onTag)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(enabled, lifecycle) {
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val cb = NfcAdapter.ReaderCallback { tag -> activity.runOnUiThread { current(tag) } }
        fun on() = runCatching {
            adapter.enableReaderMode(activity, cb, flags, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 300) })
        }
        fun off() = runCatching { adapter.disableReaderMode(activity) }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (enabled) on()
                Lifecycle.Event.ON_PAUSE -> off()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) on()
        onDispose {
            lifecycle.removeObserver(obs)
            off()
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

/** Soft fade under the status bar so scrolling content never collides with the clock. */
@Composable
fun StatusBarScrim() {
    val bg = Td.colors.bg
    Box(
        Modifier
            .fillMaxWidth()
            .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp)
            .background(Brush.verticalGradient(0f to bg, 0.6f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0f)))
    )
}
