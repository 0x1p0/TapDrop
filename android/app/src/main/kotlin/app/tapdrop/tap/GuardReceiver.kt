package app.tapdrop.tap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "Resume" in the paused notification. Runs in the watcher's process (":guard"), which is up while TapDrop is paused. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Guard.ACTION_RESUME) return
        ctx.stopService(Intent(ctx, GuardService::class.java))
        if (!Guard.resume(ctx)) runCatching { ctx.startActivity(Guard.openSettingsIntent(ctx)) }
    }
}

/**
 * Starts TapDrop's main process after a pause; nothing else. Android connects the parked accessibility service as the
 * process starts.
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {}
}
