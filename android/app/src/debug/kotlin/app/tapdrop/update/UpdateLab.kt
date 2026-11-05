package app.tapdrop.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit

/**
 * Debug builds only: points the updater at a test server and drives it from adb.
 *
 *     adb shell am broadcast -n app.tapdrop/.update.UpdateLab --es feed http://10.0.2.2:8765/releases/latest
 *     adb shell am broadcast -n app.tapdrop/.update.UpdateLab --es do check     (an automatic check: notifies)
 *     adb shell am broadcast -n app.tapdrop/.update.UpdateLab --es do reset     (forget checks, skips, notifications)
 *     adb shell am broadcast -n app.tapdrop/.update.UpdateLab --es do unnotify  (forget only which version was notified)
 *
 * `--es feed ""` goes back to GitHub. Release builds have neither this receiver nor the cleartext exception for
 * 10.0.2.2, and ignore a saved feed. The receiver needs the DUMP permission, so only the shell (adb) can call it.
 */
class UpdateLab : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sp = Updates.store(context)
        intent.getStringExtra("feed")?.let { url ->
            sp.edit { if (url.isBlank()) remove(ReleaseFeed.KEY_DEBUG_FEED) else putString(ReleaseFeed.KEY_DEBUG_FEED, url) }
            Log.i("TapDrop", "update feed: ${ReleaseFeed.feedUrl(context)}")
        }
        when (intent.getStringExtra("do")) {
            "check" -> Updates.check(manual = false)
            // Forget which version was notified about (to test that a skipped one still isn't).
            "unnotify" -> sp.edit(commit = true) { remove("notified") }
            "reset" -> {
                val keep = listOf(ReleaseFeed.KEY_DEBUG_FEED, "lastRunVersion").associateWith { sp.getString(it, null) }
                sp.edit(commit = true) {
                    clear()
                    keep.forEach { (k, v) -> if (v != null) putString(k, v) }
                }
                Updates.load()
                UpdateInstaller.forget(context)
                Log.i("TapDrop", "update state reset")
            }
        }
    }
}
