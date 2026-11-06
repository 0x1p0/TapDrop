package app.tapdrop.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import app.tapdrop.core.Endpoint
import app.tapdrop.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.util.concurrent.Executor

data class FoundMac(val name: String, val id: String?, val host: String, val port: Int) {
    val endpoint get() = Endpoint(host, port)
}

/** Finds Macs running TapDrop on the local network via Bonjour (DNS-SD). */
class MacFinder(private val ctx: Context, private val prefs: Prefs) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }

    private val _found = MutableStateFlow<Map<String, FoundMac>>(emptyMap())
    val found: StateFlow<Map<String, FoundMac>> = _found.asStateFlow()

    private var users = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private val legacyQueue = ArrayDeque<NsdServiceInfo>()
    private var legacyBusy = false
    /** Macs Bonjour just called lost, by service name: they leave [found] only if they don't come back first. */
    private val leaving = mutableMapOf<String, Runnable>()

    @MainThread
    fun start() {
        users++
        // A capture build with a stand-in lists only the stand-in: no real Mac (or its name) ever shows up in a capture.
        if (captureStandIn()) return
        if (listener == null) discover()
    }

    /** Capture builds only (see app/build.gradle.kts): a stand-in Mac from files/capture-mac.txt. */
    private fun captureStandIn(): Boolean {
        if (app.tapdrop.BuildConfig.BUILD_TYPE != "capture") return false
        val line = runCatching { java.io.File(ctx.getExternalFilesDir(null), "capture-mac.txt").readText().trim() }.getOrNull() ?: return false
        val p = line.split("|")
        if (p.size != 4) return false
        val m = FoundMac(p[0], p[1], p[2], p[3].toIntOrNull() ?: return false)
        _found.value = mapOf(("capture:" + m.id) to m)
        return true
    }

    @MainThread
    fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) halt()
    }

    fun endpointFor(id: String): Endpoint? = _found.value.values.firstOrNull { it.id == id }?.endpoint

    /** Waits for Bonjour to (re)discover a Mac, e.g. after its IP address changed. */
    suspend fun awaitEndpoint(id: String, timeoutMs: Long): Endpoint? {
        withContext(Dispatchers.Main) { start() }
        try {
            return withTimeoutOrNull(timeoutMs) {
                found.first { m -> m.values.any { it.id == id } }.values.first { it.id == id }.endpoint
            }
        } finally {
            withContext(Dispatchers.Main) { stop() }
        }
    }

    private fun discover() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (listener === this) listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { watch(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    unwatch(info.serviceName)
                    lost(info.serviceName)
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null }
    }

    private fun halt() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        callbacks.keys.toList().forEach(::unwatch)
        legacyQueue.clear()
    }

    private fun watch(info: NsdServiceInfo) {
        val name = info.serviceName
        if (Build.VERSION.SDK_INT >= 34) {
            if (callbacks.containsKey(name)) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    main.post { callbacks.remove(name) }
                }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    main.post { publish(serviceInfo) }
                }
                override fun onServiceLost() {
                    main.post { lost(name) }
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            callbacks[name] = cb
            runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }.onFailure { callbacks.remove(name) }
        } else {
            legacyQueue.addLast(info)
            pumpLegacy()
        }
    }

    private fun unwatch(name: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            callbacks.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    /**
     * Bonjour on phones (and Wi‑Fi that drops multicast now and then) often reports a Mac lost and finds it again a
     * moment later; the list of nearby Macs mustn't blink empty each time. A lost Mac leaves after a short grace
     * unless it's heard from again ([publish] keeps it).
     */
    private fun lost(name: String) {
        if (name !in _found.value || name in leaving) return
        val gone = Runnable {
            leaving.remove(name)
            _found.update { it - name }
        }
        leaving[name] = gone
        main.postDelayed(gone, LOST_GRACE_MS)
    }

    @Suppress("DEPRECATION")
    private fun pumpLegacy() {
        if (legacyBusy) return
        val next = legacyQueue.removeFirstOrNull() ?: return
        legacyBusy = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { legacyBusy = false; pumpLegacy() }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post { publish(serviceInfo); legacyBusy = false; pumpLegacy() }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun publish(si: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
        val addr = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return
        val host = addr.hostAddress ?: return
        // An update can come without the TXT record (when Bonjour finds a Mac again it may send the address first);
        // the Mac it's about keeps the id it had, so it doesn't drop out of the list for a moment.
        val id = si.attributes["id"]?.let { String(it) } ?: _found.value[si.serviceName]?.id
        val mac = FoundMac(decodeName(si.serviceName), id, host, si.port)
        leaving.remove(si.serviceName)?.let(main::removeCallbacks)
        _found.update { it + (si.serviceName to mac) }
        if (id != null) prefs.updateEndpoint(id, host, si.port)
    }

    companion object {
        const val SERVICE_TYPE = "_tapdrop._tcp"
        /** How long a Mac Bonjour lost stays listed, in case it's found again (ms). */
        private const val LOST_GRACE_MS = 8_000L

        /** DNS-SD escapes non-ASCII bytes as \DDD; turn "Studio\226\128\153s" back into "Studio’s". */
        fun decodeName(raw: String): String {
            if (!raw.contains('\\')) return raw
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\') {
                    val digits = raw.substring(i + 1, minOf(i + 4, raw.length))
                    if (digits.length == 3 && digits.all(Char::isDigit)) {
                        out.write(digits.toInt())
                        i += 4
                        continue
                    }
                    if (i + 1 < raw.length) {
                        out.write(raw[i + 1].toString().toByteArray())
                        i += 2
                        continue
                    }
                }
                out.write(c.toString().toByteArray())
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
