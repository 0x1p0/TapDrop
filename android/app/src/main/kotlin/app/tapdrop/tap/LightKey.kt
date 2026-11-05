package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's light for a light key: its screen, white at full brightness, then black on cue. The Mac's notch light
 * sensor has to see it come right after `key-on` and go right after `key-off` for a tap to send.
 *
 * The window lives on its own looper thread, not the main one. A view hierarchy belongs to the thread that adds it:
 * its ViewRootImpl, Choreographer, traversals and frame-commit callbacks all run there. So TapDrop's own screens
 * drawing on the main thread can't hold up the white frame, the switch to black, or the moment either is reported
 * (key-on and key-off go out from the frame-commit callback itself). The one view is created up front and, from
 * then on, touched only on that thread (added, recoloured, removed). Callable from any thread.
 */
class LightKey(svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val thread = HandlerThread("TapDrop light", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    /** Created here (the service's main thread), then touched only on [thread]. */
    private val view = View(svc)
    /** On [thread]: the window is added. */
    private var added = false
    @Volatile private var showing = false
    /** Bumped by [off]: a white or black switch asked for before it never runs after it. */
    private val generation = AtomicInteger()

    /** Whether the light-key window (white or black) is showing. */
    val screenShowing: Boolean get() = showing

    /**
     * A white window over everything, with the window brightness override at full. [lit] runs on the light thread the
     * moment its first frame is committed to the display (or, should no commit come, after [FIRST_FRAME_MS]); this
     * returns right after. False if it can't be shown or was switched off meanwhile ([lit] doesn't run then).
     */
    suspend fun screen(lit: () -> Unit): Boolean {
        val done = CompletableDeferred<Boolean>()
        val gen = generation.get()
        handler.post {
            if (gen != generation.get()) {
                done.complete(false)
                return@post
            }
            if (added) {
                lit()
                done.complete(true)
                return@post
            }
            view.setBackgroundColor(Color.WHITE)
            try {
                wm.addView(view, params())
            } catch (e: Exception) {
                Log.w("TapDrop", "light key: can't show the screen light", e)
                done.complete(false)
                return@post
            }
            added = true
            showing = true
            val t0 = SystemClock.uptimeMillis()
            // Registered before the first traversal runs (it's posted), so it fires for the very first frame.
            committed(FIRST_FRAME_MS) { ok ->
                if (!added || gen != generation.get()) {
                    done.complete(false)
                    return@committed
                }
                if (ok) Log.i("TapDrop", "light key: screen on (first frame committed in ${SystemClock.uptimeMillis() - t0} ms)")
                else Log.i("TapDrop", "light key: no frame commit within $FIRST_FRAME_MS ms; reporting it on anyway")
                lit()
                done.complete(true)
            }
        }
        return done.await()
    }

    /**
     * The echo: the window turns black (the brightness override stays, so nothing dims or flickers in between).
     * [done] runs on the light thread once that frame is committed (or after [DARK_FRAME_MS]) with true, or at once
     * with false if there's no window to turn black. Doesn't wait: callable straight from the link's reader thread.
     */
    fun dark(done: (Boolean) -> Unit) {
        val gen = generation.get()
        handler.post {
            if (!added || gen != generation.get()) {
                done(false)
                return@post
            }
            view.setBackgroundColor(Color.BLACK)
            val t0 = SystemClock.uptimeMillis()
            committed(DARK_FRAME_MS) { ok ->
                if (ok) Log.i("TapDrop", "light key: dark (frame committed in ${SystemClock.uptimeMillis() - t0} ms)")
                else Log.i("TapDrop", "light key: no frame commit for the dark within $DARK_FRAME_MS ms")
                done(added && gen == generation.get())
            }
        }
    }

    /** Everything off, as soon as the light thread gets to it (it has nothing else to do). */
    fun off() {
        generation.incrementAndGet()
        showing = false
        handler.post { remove() }
    }

    /** For the service's end: off, then the thread goes. */
    fun quit() {
        off()
        thread.quitSafely()
    }

    private fun remove() {
        if (!added) return
        added = false
        runCatching { wm.removeViewImmediate(view) }
    }

    private fun params() = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        title = "TapDrop light key"
    }

    /**
     * On the light thread: [then] runs once, on the light thread, when the view's next frame is committed (true) or
     * after [ms] (false). Commit callbacks are posted to the thread that owns the view, so neither waits on the main
     * thread.
     */
    private fun committed(ms: Long, then: (Boolean) -> Unit) {
        var fired = false
        val timeout = Runnable {
            if (!fired) {
                fired = true
                then(false)
            }
        }
        view.viewTreeObserver.registerFrameCommitCallback {
            handler.post {
                if (!fired) {
                    fired = true
                    handler.removeCallbacks(timeout)
                    then(true)
                }
            }
        }
        view.invalidate()
        handler.postDelayed(timeout, ms)
    }

    private companion object {
        /** No commit for the white frame this long (the screen going off, say): say it's on anyway. */
        const val FIRST_FRAME_MS = 800L
        /** No commit for the black frame this long: say it's off anyway, before the Mac's key-off deadline. */
        const val DARK_FRAME_MS = 250L
    }
}
