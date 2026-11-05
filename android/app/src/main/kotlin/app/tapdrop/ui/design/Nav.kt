package app.tapdrop.ui.design

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
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
        gesture = true
        try {
            events.collect { e -> seek.seekTo(e.progress, prev) }
            // Released: finish the scrubbed transition, then commit.
            seek.animateTo(prev)
            stack.pop()
        } catch (e: CancellationException) {
            seek.animateTo(stack.current)
        } finally {
            gesture = false
        }
    }

    val transition = rememberTransition(seek, label = "nav")
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavBackGesture provides gesture) {
            transition.AnimatedContent(
                transitionSpec = { navTransform(back = stack.popping || gesture, reduced = reduced) },
                contentKey = { it },
            ) { dest ->
                CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(dest) }
            }
        }
    }
}

private fun <T> AnimatedContentTransitionScope<T>.navTransform(back: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return (fadeIn(tween(140)) togetherWith fadeOut(tween(140))).using(SizeTransform(clip = false))
    val ease = FastOutSlowInEasing
    return if (!back) {
        // Material shared axis X: the new page slides in a little and fades through.
        (slideInHorizontally(tween(340, easing = ease)) { it / 8 } + fadeIn(tween(220, 90))) togetherWith
            (slideOutHorizontally(tween(340, easing = ease)) { -it / 12 } + fadeOut(tween(110)))
    } else {
        // Predictive back: the top page stays solid and shrinks toward your thumb, the page below brightens
        // from under it; the top page only fades in the last moment, once you've let go.
        (fadeIn(tween(300), initialAlpha = 0.45f) togetherWith
            (slideOutHorizontally(tween(300, easing = ease)) { it / 6 } +
                scaleOut(tween(300, easing = ease), targetScale = 0.88f) +
                fadeOut(tween(70, delayMillis = 230))))
            .apply { targetContentZIndex = -1f }
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
