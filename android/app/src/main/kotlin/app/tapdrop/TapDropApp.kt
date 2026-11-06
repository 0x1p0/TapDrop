package app.tapdrop

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import app.tapdrop.data.Prefs
import app.tapdrop.net.MacFinder
import app.tapdrop.net.Transfers
import app.tapdrop.ui.Shortcuts
import app.tapdrop.ui.TapActivity
import app.tapdrop.update.Updates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach

class TapDropApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var prefs: Prefs
        private set
    lateinit var finder: MacFinder
        private set
    lateinit var transfers: Transfers
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        finder = MacFinder(this, prefs)
        transfers = Transfers(this, prefs, finder, scope)
        // Keep the Mac(s) as Direct Share targets at the top of Android's share sheet.
        prefs.macs.onEach { Shortcuts.publish(this, it) }.launchIn(scope)
        // Macs this phone forgot while they were away: tell them now, and whenever one turns up on the network.
        scope.launch { transfers.tellForgotten() }
        finder.found
            .map { found -> prefs.pendingUnpairs().any { p -> found.values.any { it.id == p.id } } }
            .distinctUntilChanged()
            .onEach { if (it) transfers.tellForgotten() }
            .launchIn(scope)
        // New versions: the daily check, the check on coming to the front, and what was found.
        Updates.init(this)
    }

    companion object {
        lateinit var instance: TapDropApp
            private set
    }
}

/** Quick Settings tile: opens the TapDrop quick sheet from anywhere. */
class TapTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, TapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TapActivity.EXTRA_SOURCE, "tile")
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startActivityAndCollapse(pi)
    }
}
