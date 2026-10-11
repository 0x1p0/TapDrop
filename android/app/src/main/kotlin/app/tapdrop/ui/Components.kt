package app.tapdrop.ui

import android.app.Activity
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

// MARK: - interaction

/** Springy press feedback + subtle haptic, the basic "premium" touch for every tappable surface. */
@Composable
fun Modifier.tappable(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 650f), label = "press",
    )
    val view = LocalView.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .clickable(interactionSource = src, indication = ripple(), enabled = enabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onClick()
        }
}

/** Staggered rise-and-fade entrance. */
@Composable
fun Modifier.enter(index: Int, key: Any = Unit): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(40L + index * 55L)
        a.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 240f))
    }
    return this.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * 26.dp.toPx()
        val s = 0.97f + 0.03f * a.value
        scaleX = s
        scaleY = s
    }
}

// MARK: - motion graphics

/** AirDrop-style expanding radar rings. */
@Composable
fun Ripples(
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    rings: Int = 3,
    periodMs: Int = 2600,
    start: Float = 0.32f,
    stroke: Dp = 1.5.dp,
) {
    val t = rememberInfiniteTransition(label = "ripples")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        val maxR = size.minDimension / 2
        repeat(rings) { i ->
            val f = (p + i.toFloat() / rings) % 1f
            val eased = 1f - (1f - f) * (1f - f)
            val r = maxR * (start + (1f - start) * eased)
            val alpha = (1f - f) * 0.6f
            drawCircle(color.copy(alpha = alpha * 0.10f), r)
            drawCircle(color.copy(alpha = alpha), r, style = Stroke(stroke.toPx()))
        }
    }
}

@Composable
fun ProgressRing(progress: Float?, modifier: Modifier, stroke: Dp = 4.dp, track: Color = Color.White.copy(alpha = 0.14f)) {
    val animated by animateFloatAsState(progress ?: 0f, spring(stiffness = Spring.StiffnessLow), label = "ring")
    val t = rememberInfiniteTransition(label = "spin")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "rot")
    Canvas(modifier) {
        val w = stroke.toPx()
        val tl = Offset(w / 2, w / 2)
        val s = Size(size.width - w, size.height - w)
        drawArc(track, 0f, 360f, false, tl, s, style = Stroke(w))
        val brush = Brush.sweepGradient(listOf(Brand.A, Brand.B, Brand.C, Brand.A))
        rotate(if (progress == null) rot - 90f else -90f) {
            drawArc(brush, 0f, if (progress == null) 100f else 360f * animated, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun AnimatedCheck(modifier: Modifier, color: Color = Color.White, strokeWidth: Dp = 4.dp, delayMs: Long = 90) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        p.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.53f)
            lineTo(size.width * 0.42f, size.height * 0.74f)
            lineTo(size.width * 0.82f, size.height * 0.29f)
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        pm.getSegment(0f, pm.length * p.value, seg, true)
        drawPath(seg, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

enum class OrbState { Idle, Working, Done, Failed }

/**
 * The device "orb": glowing gradient circle with gloss, a progress ring while working,
 * morphing into an animated check (or ✕) when finished.
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
    val scale by animateFloatAsState(
        when {
            pressed -> 0.9f
            state == OrbState.Done -> 1.06f
            else -> 1f
        },
        spring(dampingRatio = 0.42f, stiffness = 420f), label = "orb",
    )
    val c1 by animateColorAsState(
        when (state) {
            OrbState.Done -> Brand.Green
            OrbState.Failed -> Brand.Red
            else -> Brand.A
        }, tween(450), label = "c1",
    )
    val c2 by animateColorAsState(
        when (state) {
            OrbState.Done -> Brand.GreenDeep
            OrbState.Failed -> Color(0xFFD63A4A)
            else -> Brand.B
        }, tween(450), label = "c2",
    )
    val glow by animateFloatAsState(if (state == OrbState.Working || pulsing) 1f else 0.55f, tween(600), label = "glow")
    val view = LocalView.current

    Box(modifier.size(size * 1.9f), contentAlignment = Alignment.Center) {
        if (pulsing || state == OrbState.Working) {
            Ripples(Modifier.fillMaxSize(), color = c2, periodMs = if (state == OrbState.Working) 1400 else 2600, start = 0.5f)
        }
        // Glow: alpha lives in the colors (a layer alpha would clip the blur to a square).
        Box(
            Modifier
                .size(size * scale * 1.05f)
                .blur(26.dp, BlurredEdgeTreatment.Unbounded)
                .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.6f * glow), c2.copy(alpha = 0.6f * glow))), CircleShape)
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
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onClick()
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 0.55f to Color.Transparent))
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
                        OrbState.Done -> AnimatedCheck(Modifier.size(size * 0.56f), strokeWidth = (size.value / 18).dp)
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
    LaunchedEffect(Unit) {
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

/** A phone bumping a laptop — "tap your phone on the Mac". */
@Composable
fun TapHint(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    val p = bump(loopPhase(2400), rise = 750 / 2400f, hold = 1250 / 2400f, fall = 2000 / 2400f)
    val touching = p > 0.97f
    val pulse by animateFloatAsState(if (touching) 1f else 0f, tween(if (touching) 500 else 0), label = "pulse")
    Box(modifier.size(72.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.LaptopMac, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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

/** Full-screen shockwave when an NFC sticker is detected. */
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
                Brush.radialGradient(listOf(Brand.B.copy(alpha = 0.35f * (1 - a.value)), Color.Transparent), c, maxR * a.value + 1f),
                radius = maxR * a.value + 1f, center = c,
            )
            for (i in 0..2) {
                val f = (a.value * 1.35f - i * 0.17f).coerceIn(0f, 1f)
                if (f in 0.001f..0.999f) {
                    drawCircle(
                        Brand.A.copy(alpha = (1 - f) * 0.7f), radius = maxR * f, center = c,
                        style = Stroke((10.dp.toPx() * (1 - f)).coerceAtLeast(1f)),
                    )
                }
            }
        }
    }
}

/**
 * Two soft brand-colored glows; gives screens depth.
 * Drawn as plain radial gradients, once — a blurred, drifting version re-rendered a 100 dp blur
 * every frame for as long as the screen was open, which is exactly what makes phones warm.
 */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier, intensity: Float = 1f) {
    val dark = isSystemInDarkTheme()
    val k = (if (dark) 0.42f else 0.22f) * intensity
    Box(
        modifier.drawBehind {
            fun glow(color: Color, center: Offset, radius: Float) = drawCircle(
                Brush.radialGradient(
                    0f to color.copy(alpha = k),
                    0.4f to color.copy(alpha = k * 0.75f),
                    0.7f to color.copy(alpha = k * 0.25f),
                    1f to Color.Transparent,
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            glow(Brand.A, Offset(80.dp.toPx(), 110.dp.toPx()), 270.dp.toPx())
            glow(Brand.B, Offset(size.width - 80.dp.toPx(), 180.dp.toPx()), 255.dp.toPx())
        }
    )
}

// MARK: - media

@Composable
fun Thumb(uri: Uri?, modifier: Modifier, fallback: ImageVector = Icons.Rounded.InsertDriveFile) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (it.scheme == "file") android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap()
                    else ctx.contentResolver.loadThumbnail(it, android.util.Size(360, 360), null).asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Crossfade(bmp, label = "thumb") { b ->
            if (b != null) {
                Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

// MARK: - bottom sheet for the translucent activities

/**
 * Scrim + bottom sheet that springs up, can be flung down to dismiss, and handles back.
 * [onClosed] fires once the exit animation has finished.
 */
@Composable
fun SheetHost(
    onClosed: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val slide = remember { Animatable(1f) }
    val drag = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    val latestOnClosed by rememberUpdatedState(onClosed)

    LaunchedEffect(Unit) { slide.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 360f)) }

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                slide.animateTo(1f, tween(240, easing = FastOutLinearInEasing))
                latestOnClosed()
            }
        }
    }
    BackHandler { close() }

    val visibleFraction = (1f - slide.value).coerceIn(0f, 1f) * (1f - (drag.value / height).coerceIn(0f, 1f))
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f * visibleFraction))
                .pointerInputTap { close() }
        )
        overlay()
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .graphicsLayer { translationY = slide.value * height + drag.value }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                    onDragStopped = { v ->
                        if (drag.value > height * 0.28f || v > 1800f) close()
                        else drag.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                    },
                )
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .pointerInputTap { }
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(38.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
            content(close)
        }
    }
}

private fun Modifier.pointerInputTap(onTap: () -> Unit) =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures { onTap() } })

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
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp)
            .background(Brush.verticalGradient(0f to bg, 0.6f to bg.copy(alpha = 0.85f), 1f to bg.copy(alpha = 0f)))
    )
}
