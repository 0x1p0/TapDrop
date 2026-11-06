package app.tapdrop.ui.design

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.tapdrop.ui.LocalReducedMotion
import kotlinx.coroutines.CancellationException

/** A tiny back stack of destinations. Hold it with [rememberNavStack]. */
@Stable
class NavStack<T : Any>(initial: T) {
    private val entries = mutableStateListOf(initial)

    /** The destination on top. */
    val current: T get() = entries.last()

    /** The destination under the top one, if any. */
    val previous: T? get() = entries.getOrNull(entries.size - 2)

    val size: Int get() = entries.size

    /** True while the last change was a pop (drives the transition direction). */
    internal var popping by mutableStateOf(false)

    /** Opens [dest] on top (no-op if it is already on top). */
    fun push(dest: T) {
        if (current == dest) return
        popping = false
        entries.add(dest)
    }

    /** Goes back one step. Returns false at the root. */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        popping = true
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** Pops everything above [dest] (if it's in the stack). */
    fun popTo(dest: T) {
        val i = entries.lastIndexOf(dest)
        if (i < 0 || i == entries.lastIndex) return
        popping = true
        while (entries.lastIndex > i) entries.removeAt(entries.lastIndex)
    }

    /** Replaces the whole stack with [dest]. */
    fun reset(dest: T) {
        popping = true
        entries.clear()
        entries.add(dest)
    }
}

@Composable
fun <T : Any> rememberNavStack(initial: T): NavStack<T> = remember { NavStack(initial) }

/** The shared-transition scope of the nearest [NavHost], for shared elements. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The animated scope of the destination being shown (the other half of a shared element). */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * True while the user is dragging a predictive back gesture. Shared elements sit out the scrub (the top page
 * shrinks as one solid card instead); they still fly on taps and on programmatic back.
 */
val LocalNavBackGesture = compositionLocalOf { false }

/**
 * Animated navigation between full screens with predictive back: during the back gesture the top screen
 * shrinks and slides with your thumb (seekable), the one below fades up, and releasing commits.
 * Screens can share elements across the transition with [tdSharedBounds] / [tdSharedElement].
 *
 * Screens shown here should not install their own `BackHandler` to close themselves; the host does it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun <T : Any> NavHost(
    stack: NavStack<T>,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val seek = remember { SeekableTransitionState(stack.current) }
    var gesture by remember { mutableStateOf(false) }

    LaunchedEffect(stack.current) {
        if (seek.targetState != stack.current || seek.currentState != stack.current) seek.animateTo(stack.current)
    }

    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        val prev = stack.previous
        if (prev == null) {
            events.collect { }
            return@PredictiveBackHandler
        }
        // A swipe scrubs the page with the thumb; the back key (or 3-button navigation) sends no progress, and gets
        // the ordinary slide instead of a card shrinking with no thumb to follow.
        var moved = false
        try {
            events.collect { e ->
                if (!moved && e.progress > 0f) {
                    moved = true
                    gesture = true
                }
                if (moved) seek.seekTo(e.progress, prev)
            }
            if (moved) {
                // Released: finish from wherever the thumb let go on a soft spring, then commit.
                seek.animateTo(prev, spring(dampingRatio = 1f, stiffness = 380f))
            }
            stack.pop()
        } catch (e: CancellationException) {
            if (moved) seek.animateTo(stack.current, spring(dampingRatio = 1f, stiffness = 500f))
        } finally {
            gesture = false
        }
    }

    val transition = rememberTransition(seek, label = "nav")
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavBackGesture provides gesture) {
            transition.AnimatedContent(
                transitionSpec = { navTransform(back = stack.popping || gesture, gesture = gesture, reduced = reduced) },
                contentKey = { it },
            ) { dest ->
                // Each page is a solid sheet with a soft shadow on its edges: it only shows while a page slides
                // over another (at rest the edges are off screen).
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .shadow(18.dp, androidx.compose.ui.graphics.RectangleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                        .background(app.tapdrop.ui.Td.colors.bg),
                ) {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this@AnimatedContent) { content(dest) }
                }
            }
        }
    }
}

private fun <T> AnimatedContentTransitionScope<T>.navTransform(back: Boolean, gesture: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return (fadeIn(tween(140)) togetherWith fadeOut(tween(140))).using(SizeTransform(clip = false))
    // Critically damped springs: they start fast and settle without a bump, and a page interrupted mid-flight
    // carries its momentum instead of restarting (no stiff curves).
    val glide = spring<IntOffset>(dampingRatio = 1f, stiffness = 480f)
    return when {
        // Swiping back: the page follows your thumb as one solid card, shrinking a little and moving right; the
        // page below rises from just behind it. Linear in the gesture (the thumb is the easing); it never pops.
        gesture -> (
            (fadeIn(tween(320, easing = LinearEasing), initialAlpha = 0.7f) +
                scaleIn(tween(320, easing = LinearEasing), initialScale = 0.97f)) togetherWith
                (scaleOut(tween(320, easing = LinearEasing), targetScale = 0.9f) +
                    slideOutHorizontally(tween(320, easing = LinearEasing)) { it / 10 } +
                    fadeOut(tween(90, delayMillis = 230, easing = LinearEasing)))
            ).apply { targetContentZIndex = -1f }
        // Back by a tap: the page slides off to the right as one solid sheet (no cross-fade, so two pages never
        // show through each other), and the page below glides in from a little to the left, out of a slight dim.
        back -> (
            (slideInHorizontally(glide) { -it / 4 } + fadeIn(tween(260, easing = LinearOutSlowInEasing), initialAlpha = 0.55f)) togetherWith
                slideOutHorizontally(glide) { it }
            ).apply { targetContentZIndex = -1f }
        // Forward: the new page slides in from the right edge over the one it leaves, which drifts left and dims.
        else ->
            slideInHorizontally(glide) { it } togetherWith
                (slideOutHorizontally(glide) { -it / 4 } + fadeOut(tween(260, easing = FastOutLinearInEasing), targetAlpha = 0.55f))
    }.using(SizeTransform(clip = false))
}

/**
 * Morphs this element's bounds into the element with the same [key] on the next screen (e.g. a card into a
 * page header), cross-fading the two. Use it when both sides show the same thing at different sizes.
 * No-op outside a [NavHost] and during a predictive back gesture.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.tdSharedBounds(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (LocalNavBackGesture.current) return this
    return with(shared) {
        this@tdSharedBounds.sharedBounds(rememberSharedContentState(key), anim)
    }
}

/**
 * Moves this exact element (identical content on both screens) to its counterpart with the same [key] on
 * the next screen. No-op outside a [NavHost] and during a predictive back gesture.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.tdSharedElement(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (LocalNavBackGesture.current) return this
    return with(shared) {
        this@tdSharedElement.sharedElement(rememberSharedContentState(key), anim)
    }
}
