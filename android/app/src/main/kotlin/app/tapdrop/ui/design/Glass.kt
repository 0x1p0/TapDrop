package app.tapdrop.ui.design

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.rememberHaptics
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The backdrop that glass chrome may blur, or null where there is none (translucent share/tap sheets sit
 * over other apps, which can't be blurred). Provided by [GlassLayers] to its `chrome` slot only.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * A screen split into the content (blurred) and the glass chrome drawn over it (top bars, toolbars, sheets).
 * Anything that should frost over the content goes in [chrome]; [content] never sees a haze state, so sheets
 * opened from inside it fall back to solid surfaces instead of trying to blur themselves.
 */
@Composable
fun GlassLayers(
    modifier: Modifier = Modifier,
    background: Color = Td.colors.bg,
    state: HazeState = rememberHazeState(),
    chrome: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // The background lives inside the source so the captured layer is opaque: glass never shows
        // the live content through itself, even for a frame.
        Box(Modifier.matchParentSize().hazeSource(state).background(background)) {
            CompositionLocalProvider(LocalHazeState provides null) { content() }
            BottomFade(background, Modifier.align(Alignment.BottomCenter))
        }
        CompositionLocalProvider(LocalHazeState provides state) { chrome() }
    }
}

/**
 * Content scrolls edge to edge, so it fades into the background behind the navigation bar: the gesture
 * handle (or the buttons) always sits on a calm surface, never across a line of text.
 */
@Composable
private fun BottomFade(background: Color, modifier: Modifier = Modifier) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (nav <= 0.dp) return
    Box(
        modifier
            .fillMaxWidth()
            .height(nav + 28.dp)
            .background(
                Brush.verticalGradient(
                    0f to background.copy(alpha = 0f),
                    0.45f to background.copy(alpha = 0.78f),
                    1f to background.copy(alpha = 0.94f),
                )
            ),
    )
}

/**
 * Blur style for glass chrome: a strong blur under a tint of the surface color, with a whisper of grain.
 *
 * @param strength 0…1, read lazily (e.g. from scroll): fades the tint and grain in. The blur itself stays
 *   on, so the backdrop is always captured and the glass never lags a frame behind when it appears.
 */
@Composable
fun rememberGlassStyle(
    tint: Color = Td.colors.glass,
    radius: Dp = 28.dp,
    strength: () -> Float = { 1f },
    progressive: HazeProgressive? = null,
): HazeBlurStyle {
    val strengthState = rememberUpdatedState(strength)
    return remember(tint, radius, progressive) {
        HazeBlurStyle {
            val s = strengthState.value().coerceIn(0f, 1f)
            // The blur grows with strength too (never quite to zero, so capture stays live): at rest a bar shows
            // nothing at all, instead of a haze of the large title just below it with a hard bottom edge.
            blurRadius(radius * s.coerceAtLeast(0.03f))
            noiseFactor(0.05f * s)
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = tint.alpha * s))))
            fallbackColorEffect(HazeColorEffect.tint(tint.copy(alpha = (tint.alpha + 0.2f).coerceAtMost(0.97f) * s)))
            progressive(progressive)
        }
    }
}

/**
 * Frosted glass behind this element: blurs the [GlassLayers] content under it (or draws a solid tinted
 * surface when there is no backdrop), clipped to [shape], with a hairline edge.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    tint: Color = Td.colors.glass,
    edge: Boolean = true,
    state: HazeState? = LocalHazeState.current,
): Modifier {
    val c = Td.colors
    val style = rememberGlassStyle(tint)
    val base = this.clip(shape)
    val surface = if (state != null) {
        base.hazeBlur(input = HazeInput.Backdrop(state), style = style)
    } else {
        base.background(if (c.isDark) c.surfaceRaised else c.surface)
    }
    return if (edge) surface.border(1.dp, glassEdgeBrush(c.glassEdge, c.hairline), shape) else surface
}

private fun glassEdgeBrush(edge: Color, hairline: Color) =
    Brush.verticalGradient(0f to edge, 0.45f to hairline, 1f to hairline.copy(alpha = hairline.alpha * 0.6f))

/**
 * The standard container. On Android it is a solid tonal card (crisp and fast in lists): `surface` with a
 * hairline in dark mode, white with a soft shadow in light mode. 28 dp corners.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(TdRadius.card),
    padding: PaddingValues = PaddingValues(Td.space.lg),
    color: Color = Td.colors.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Td.colors
    val base = modifier
        .then(
            if (c.isDark) Modifier
            else Modifier.shadow(14.dp, shape, ambientColor = c.shadow.copy(alpha = 0.10f), spotColor = c.shadow.copy(alpha = 0.10f))
        )
    val shaped = if (onClick != null) base.pressable(shape, pressedScale = 0.985f, onClick = onClick) else base.clip(shape)
    Column(
        shaped
            .background(color)
            .then(if (c.isDark) Modifier.border(1.dp, c.hairline, shape) else Modifier)
            .padding(padding),
        content = content,
    )
}

// MARK: - top bar

/**
 * Edge-to-edge top bar that turns to glass as content scrolls under it.
 *
 * @param scrolled 0 when the content is at the top (bar fully transparent), 1 when content is under the
 *   bar (glass + hairline). Read lazily so scrolling doesn't recompose the bar.
 * @param title shown in the bar; pass a lambda that fades itself in for large-title screens.
 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    scrolled: () -> Float = { 1f },
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit = {},
) {
    val c = Td.colors
    val state = LocalHazeState.current
    val style = rememberGlassStyle(
        tint = c.glass,
        radius = 32.dp,
        strength = scrolled,
    )
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (state != null) Modifier.hazeBlur(input = HazeInput.Backdrop(state), style = style)
                else Modifier.drawWithContent {
                    drawRect(c.bg.copy(alpha = 0.94f * scrolled().coerceIn(0f, 1f)))
                    drawContent()
                }
            )
            .drawWithContent {
                drawContent()
                val a = scrolled().coerceIn(0f, 1f)
                if (a > 0f) {
                    drawLine(
                        c.hairline.copy(alpha = c.hairline.alpha * a),
                        Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f),
                        strokeWidth = 1f,
                    )
                }
            }
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 8.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (navigation != null) navigation() else Box(Modifier.width(12.dp))
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) { title() }
            actions()
        }
    }
}

/** Round icon button for bars and floating chrome: 44 dp, tonal glass, with press + haptics. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Td.colors.text,
) {
    val c = Td.colors
    Box(
        modifier
            .size(48.dp)
            .padding(2.dp)
            .pressable(CircleShape, pressedScale = 0.9f, onClick = onClick)
            .background(if (c.isDark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.045f))
            .border(1.dp, c.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The back button used by [LargeTitleScaffold]. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick, modifier)

/**
 * A full screen with an Apple-style large title that collapses into a glass top bar as you scroll.
 * Edge-to-edge: content scrolls under the status bar and the bar blurs it.
 *
 * ```
 * LargeTitleScaffold(title = "Settings", onBack = onClose) {
 *     item { SettingsGroup(title = "Your Mac") { … } }
 * }
 * ```
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = Td.space.md),
    background: @Composable BoxScope.() -> Unit = {},
    chrome: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    var titleHeight by remember { mutableIntStateOf(1) }
    val collapse by remember {
        derivedStateOf {
            if (state.firstVisibleItemIndex > 0) 1f
            else (state.firstVisibleItemScrollOffset / titleHeight.toFloat()).coerceIn(0f, 1f)
        }
    }
    val bars = WindowInsets.systemBars.asPaddingValues()
    val top = bars.calculateTopPadding() + 60.dp
    val direction = LocalLayoutDirection.current
    GlassLayers(
        modifier.fillMaxSize(),
        chrome = {
            GlassTopBar(
                scrolled = { ((collapse - 0.15f) / 0.5f).coerceIn(0f, 1f) },
                navigation = onBack?.let { { BackButton(it) } },
                actions = actions,
                title = {
                    Text(
                        title,
                        style = TdType.subtitle,
                        color = Td.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val a = ((collapse - 0.55f) / 0.35f).coerceIn(0f, 1f)
                            alpha = a
                            translationY = (1f - a) * 6.dp.toPx()
                        },
                    )
                },
            )
            chrome()
        },
    ) {
        background()
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(direction),
                end = contentPadding.calculateEndPadding(direction),
                top = top,
                bottom = bars.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item("td-large-title") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { titleHeight = it.height.coerceAtLeast(1) }
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 20.dp)
                        .graphicsLayer {
                            // The large title lifts and fades as it hands over to the bar.
                            alpha = 1f - collapse
                            val s = 1f - 0.04f * collapse
                            scaleX = s; scaleY = s
                            transformOrigin = TransformOrigin(if (direction == LayoutDirection.Rtl) 1f else 0f, 0.5f)
                        },
                ) {
                    Text(title, style = TdType.display, color = Td.colors.text, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) {
                        Text(subtitle, style = TdType.bodySmall, color = Td.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            content()
        }
    }
}

// MARK: - sheet

/**
 * A bottom sheet that springs up over a scrim, can be flung down, and follows the predictive back
 * gesture (it shrinks and drops with your thumb, then closes on release).
 *
 * Inside a [GlassLayers] chrome slot it is real frosted glass over the screen; elsewhere it is a solid
 * raised surface. [onDismissed] fires once the exit animation has finished; call the `close` lambda
 * passed to [content] to dismiss from inside.
 */
@Composable
fun GlassSheet(
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val c = Td.colors
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reduced = Td.reducedMotion
    val slide = remember { Animatable(1f) }
    val drag = remember { Animatable(0f) }
    val back = remember { Animatable(0f) }
    var height by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    val latestOnDismissed by rememberUpdatedState(onDismissed)
    val hazeState = LocalHazeState.current

    LaunchedEffect(Unit) { slide.animateTo(0f, if (reduced) TdMotion.quick() else TdMotion.emphasized()) }

    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                slide.animateTo(1f, tween(if (reduced) 120 else 240, easing = FastOutLinearInEasing))
                latestOnDismissed()
            }
        }
    }

    PredictiveBackHandler(enabled = !closing) { events ->
        try {
            events.collect { e -> back.snapTo(e.progress) }
            close()
        } catch (e: CancellationException) {
            back.animateTo(0f, TdMotion.standard())
        }
    }

    val shape = RoundedCornerShape(topStart = TdRadius.sheet, topEnd = TdRadius.sheet)
    val sheetStyle = rememberGlassStyle(tint = if (c.isDark) c.surfaceRaised.copy(alpha = 0.80f) else c.surface.copy(alpha = 0.84f), radius = 36.dp)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val visible = (1f - slide.value).coerceIn(0f, 1f) * (1f - (drag.value / height).coerceIn(0f, 1f)) * (1f - back.value * 0.5f)
                    alpha = visible
                }
                .background(c.scrim)
                .pointerInput(dismissible) { detectTapGestures { if (dismissible) close() } }
        )
        overlay()
        Column(
            modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .graphicsLayer {
                    translationY = slide.value * height + drag.value + back.value * 0.06f * height
                    val s = 1f - back.value * 0.06f
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = dismissible,
                    state = rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtLeast(0f)) } },
                    onDragStarted = { haptics.dragStart() },
                    onDragStopped = { v ->
                        if (drag.value > height * 0.25f || v > 1600f) close()
                        else drag.animateTo(0f, TdMotion.snappy())
                    },
                )
                .clip(shape)
                .then(
                    if (hazeState != null) Modifier.hazeBlur(HazeInput.Backdrop(hazeState), sheetStyle)
                    else Modifier.background(if (c.isDark) c.surfaceRaised else c.surface)
                )
                .border(1.dp, glassEdgeBrush(c.glassEdge, c.hairline), shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .navigationBarsPadding()
                .padding(horizontal = Td.space.xl)
                .padding(bottom = Td.space.lg),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(c.textTertiary.copy(alpha = 0.45f))
                )
            }
            content(close)
        }
    }
}

/** Bottom padding that clears the navigation bar, for content that scrolls under it. */
@Composable
fun navigationBarPadding(extra: Dp = 0.dp): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + extra

/** Top padding that clears the status bar. */
@Composable
fun statusBarPadding(extra: Dp = 0.dp): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + extra
