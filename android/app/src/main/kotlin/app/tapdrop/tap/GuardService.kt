package app.tapdrop.tap

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * While tap to send is paused for an app ([Guard.pause]), watches for that app to go (Usage access), then resumes tap to
 * send. It runs in a process of its own (":guard"), so it carries on while TapDrop's main process is down, and its
 * notification says what's going on the whole time.
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var pkg: String? = null
    private var learned = false
    private var since = 0L
    private var top: String? = null
    private var seen = false
    private var away = 0
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val down = Guard.downFor(this)
        if (down == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val fresh = down.pkg != pkg
        pkg = down.pkg
        learned = down.learned
        if (fresh) android.util.Log.i("TapDrop", "guard: paused for ${down.pkg} (resumes by itself: ${Guard.canWatch(this)})")
        val note = Guard.note(this, down.pkg, learned, watching = Guard.canWatch(this)).build()
        runCatching { startForeground(Guard.NOTE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure {
                getSystemService(NotificationManager::class.java)?.notify(Guard.NOTE, Guard.note(this, down.pkg, learned, watching = false).build())
                stopSelf()
                return START_NOT_STICKY
            }
        // Without Usage access there's nothing to watch: the notification's Resume button brings tap to send back.
        if (!Guard.canWatch(this)) {
            pkg = null
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }
        if (fresh) {
            startedAt = down.at
            since = down.at - 3_000
            top = down.pkg
            seen = false
            away = 0
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, POLL_MS)
        // Started again by Android after it was stopped for memory: the file says what's paused.
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = pkg ?: return stopSelf()
            // Resumed some other way (the notification, TapDrop opened, switched on by hand).
            if (Guard.downFor(this@GuardService)?.pkg != p) return finish()
            val now = System.currentTimeMillis()
            val came = Guard.cameForward(this@GuardService, since, now)
            if (p in came) seen = true
            came.lastOrNull()?.let { top = it }
            since = now - 500  // a little overlap: events land a moment late
            away = if (top != p) away + 1 else 0
            when {
                // Gone (or it never opened: a tap that missed, a link that went elsewhere): resume.
                away >= (if (learned) AWAY_TICKS_LEARNED else AWAY_TICKS) || !seen && now - startedAt > NEVER_OPENED_MS -> {
                    Guard.resume(this@GuardService)
                    finish()
                    return
                }
                now - startedAt > Guard.WATCH_LIMIT_MS -> {
                    Guard.resume(this@GuardService)
                    finish()
                    return
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun finish() {
        handler.removeCallbacks(tick)
        pkg = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    companion object {
        private const val POLL_MS = 800L
        /**
         * ~4 s away before it resumes, so closing an app to open it again (as a warning asks), or a quick look at
         * another app, stays covered.
         */
        private const val AWAY_TICKS = 5
        /** Just learned from its warning, which asks to close the app and open it again: ~20 s to do that. */
        private const val AWAY_TICKS_LEARNED = 25
        private const val NEVER_OPENED_MS = 10_000L
    }
}
