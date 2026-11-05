package app.tapdrop.ui.pair

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.tapdrop.ui.DeviceSupport

/**
 * Whether this phone is on Wi‑Fi (or Ethernet) right now, kept live with a network callback, so the
 * screen changes the moment Wi‑Fi comes or goes.
 */
@Composable
fun rememberOnWifi(): Boolean {
    val ctx = LocalContext.current
    var onWifi by remember { mutableStateOf(DeviceSupport.onWifi(ctx)) }
    DisposableEffect(ctx) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onWifi = DeviceSupport.onWifi(ctx) }
            override fun onLost(network: Network) { onWifi = DeviceSupport.onWifi(ctx) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onWifi = DeviceSupport.onWifi(ctx) }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess
        onWifi = DeviceSupport.onWifi(ctx)
        onDispose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return onWifi
}

/** Opens the system Wi‑Fi panel over the app (falls back to Wi‑Fi settings). */
fun openWifiSettings(ctx: Context) {
    val panel = Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(panel) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
