package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.tapdrop.R
import app.tapdrop.TapDropApp
import app.tapdrop.core.LightKeyRules
import app.tapdrop.core.Otp
import app.tapdrop.core.OutItem
import app.tapdrop.core.UnreachableException
import app.tapdrop.core.WatchSession
import app.tapdrop.data.AppRules
import app.tapdrop.data.SavedMac
import app.tapdrop.data.SendMode
import app.tapdrop.net.TState
import app.tapdrop.ui.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.math.exp

/** How the latest grab ended, for the "Test your tap" screen ([NotchTapService.lastTap]). */
data class TapTrace(
    /** Wall-clock time (ms). */
    val at: Long,
    /** The light the phone used ("screen"); null when it declined before lighting up. */
    val src: String?,
    /** The notch saw the light come and go: the tap counted and the phone went on to send. */
    val ok: Boolean,
    /** The brightest notch reading while the light was on (lux, from the Mac); null without an answer. */
    val peak: Float?,
    /** The level the light had to reach (lux, from the Mac); null without an answer. */
    val need: Float?,
    /**
     * Why it didn't count (null when [ok]). The phone's own declines: "screen-off", "locked", "in-call", "still",
     * "cooldown", "busy", "facing" (its back was to the notch), "update-mac", "no-light" (the screen couldn't light
     * up). The Mac's key-fail reasons: "not-seen", "no-light-sent", "stayed-lit" (the notch didn't go dark with the
     * phone), "no-key-off", "screen-only", "other-phone", "late", and "calibrating" (not a failure: one of the Mac's
     * calibration holds, measured and not sent). Or the phone's: "timeout" (no answer in time), "gone" (the link
     * dropped), "early-ok" (a key-ok before the phone went dark, which never counts).
     */
    val why: String?,
)

/** Motion readings may wait this long in the sensor hub's queue (µs). */
private const val MOTION_BATCH_US = 100_000

/**
 * "Tap to send" without NFC, plus Smart codes.
 *
 * While the screen is on, keeps a live encrypted link to the Mac. A tap only sends with a *light key*: when the
 * Mac's notch sensor sees something (or this phone knocks: its proximity sensor touched something while it was
 * being moved, and the notch then sees it land), the Mac sends "grab"; the phone captures what's on screen, turns its screen white at full
 * brightness, and the Mac's notch must read a level only a screen on the notch gives ("key-seen"); the phone then
 * turns the screen black and the notch must go dark with it before the Mac answers "key-ok". Only then does the
 * phone send, according to the foreground app's mode (Smart by default): the page URL in browsers, the original
 * photo/video in gallery apps, the newest shot in camera apps, otherwise a screenshot — or its text.
 * Also relays one-time codes from notifications and receives anything queued on the Mac instantly.
 */
class NotchTapService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = TapDropApp.instance
    private lateinit var overlay: SendOverlay
    private lateinit var matcher: MediaMatcher
    private lateinit var files: FileFinder
    /** The last window title an app announced, with its package. */
    @Volatile private var lastTitle: Pair<String, String>? = null
    private lateinit var light: LightKey

    private var watchJob: Job? = null
    @Volatile private var session: WatchSession? = null

    // Motion and proximity. Their listeners run on their own thread (not the main one, which draws the light key),
    // and motion readings come batched, a few wake-ups a second instead of fifty.
    private val sensorThread = HandlerThread("TapDrop sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    @Volatile private var sensorAlive = false
    @Volatile private var lastMotion = LightKeyRules.NEVER
    private val gravity = FloatArray(3)
    @Volatile private var gz = 0f
    private var lastSampleNs = 0L
    @Volatile private var near = false
    @Volatile private var lastNearAt = LightKeyRules.NEVER
    @Volatile private var lastKnock = LightKeyRules.NEVER
    /** Last time the user touched something on screen (clicks, long presses, typing, as apps report them). */
    @Volatile private var lastTouch = LightKeyRules.NEVER

    /** A light-key round is running on this phone (one at a time). */
    @Volatile private var keying = false
    /** When a tap's key last passed (it went on to send): the 2.5 s cooldown runs from here. */
    @Volatile private var lastSent = LightKeyRules.NEVER
    @Volatile private var picking = false
    @Volatile private var releaseSignal: CompletableDeferred<Unit>? = null
    /** The round the Mac last released (the phone left the notch), -1 for a release without an id. */
    @Volatile private var releasedRound = -1
    /**
     * The Mac's answers ("key-seen", "key-ok", "key-fail") for running rounds, by round id, delivered by the reader
     * thread. Each belongs to the session its grab came on: an answer on any other link never counts.
     */
    private val rounds = ConcurrentHashMap<Int, Inbox>()
    private class Inbox(val session: WatchSession, val id: Int) {
        val box = Channel<JSONObject>(Channel.UNLIMITED)
        /** The screen lit up for this round (key-on went out), and when its white frame was committed. */
        @Volatile var lit = false
        @Volatile var litAt = 0L
        /** The echo has started (once per round, from whichever thread sees key-seen first). */
        val darkening = AtomicBoolean(false)
        /** Completed once the screen is black and key-off went out (true), or the light was already gone (false). */
        val dark = CompletableDeferred<Boolean>()
        @Volatile var offAt = 0L
    }
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /**
     * The main thread, ahead of whatever is queued there (TapDrop's own screens drawing, say): a light-key round's
     * steps run as soon as the current message is done, so a busy screen doesn't eat the Mac's deadlines.
     */
    private val roundMain = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            overlayHandler.postAtFrontOfQueue(block)
        }
    }
    private var lastPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> stopWatching()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> startWatching()
            }
        }
    }

    private val motion = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            sensorAlive = true
            // Gravity follows over ~0.35 s whatever the sampling rate; what's left is the phone moving.
            val dt = if (lastSampleNs == 0L) 0f else ((e.timestamp - lastSampleNs) / 1e9f).coerceIn(0f, 0.5f)
            lastSampleNs = e.timestamp
            val a = if (dt == 0f) 1f else 1f - exp(-dt / LightKeyRules.GRAVITY_TAU)
            for (k in 0..2) gravity[k] += a * (e.values[k] - gravity[k])
            gz = gravity[2]
            val x = e.values[0] - gravity[0]
            val y = e.values[1] - gravity[1]
            val z = e.values[2] - gravity[2]
            if (x * x + y * y + z * z > LightKeyRules.MOTION * LightKeyRules.MOTION) {
                lastMotion = maxOf(lastMotion, uptimeOf(e.timestamp))
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** An event's time on the uptime clock (events carry elapsed-realtime nanoseconds, and may arrive batched). */
    private fun uptimeOf(eventNs: Long): Long =
        SystemClock.uptimeMillis() - ((SystemClock.elapsedRealtimeNanos() - eventNs) / 1_000_000L).coerceIn(0L, 5_000L)

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.uptimeMillis()
            val isNear = e.values[0] < minOf(LightKeyRules.NEAR_CM, e.sensor.maximumRange)
            val was = near
            val farFor = now - lastNearAt  // how long it had read far, if it's near now
            if (isNear || was) lastNearAt = now
            near = isNear
            if (isNear && !was) maybeKnock(now, farFor)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onServiceConnected() {
        instance = this
        overlay = SendOverlay(this)
        matcher = MediaMatcher(
            this,
            onIndex = { done, total ->
                _videoIndex.value = done to total
                sendIndex(done, total)
            },
            busy = { keying || picking || warm() },
            onPhotos = { done, total -> _photoIndex.value = done to total },
            onPaused = { paused -> _indexPaused.value = if (paused) (if (warm()) "warm" else "tap") else null },
        )
        files = FileFinder(this)
        light = LightKey(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        _running.value = true
        if (interactive()) startWatching()
        scope.launch {
            app.prefs.macs.first { it.isNotEmpty() }
            // Only with the screen on: connecting while it's off (the service restarting in a pocket) would keep the
            // sensors and the link running until the next screen-off.
            if (interactive()) startWatching()
        }
    }

    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            // The user touching the screen: a phone being used in the hand, not one on the notch.
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (pkg != packageName) lastTouch = SystemClock.uptimeMillis()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
                lastPackage = pkg
                // Viewers announce what they show ("Boarding pass.pdf", a video's name) as their window's title.
                event.text.joinToString(" ").trim().takeIf { it.isNotEmpty() }?.let { lastTitle = pkg to it }
                // Opening a gallery: pre-compute photo fingerprints so a tap matches instantly.
                if ((AppRules.isGallery(pkg) || AppRules.isChat(pkg)) && app.prefs.effectiveMode(pkg) == SendMode.MEDIA) {
                    scope.launch(Dispatchers.IO) { runCatching { matcher.warm() } }
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg == packageName) return
                val n = event.parcelableData as? Notification ?: return
                if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
                // A group's summary only repeats its messages ("3 new messages").
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
                // An old notification posted again (a later update to it) shouldn't bring back a code long used.
                if (n.`when` > 0 && System.currentTimeMillis() - n.`when` > 10 * 60_000L) return
                val ex = n.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val chat = Otp.isChatApp(pkg)
                // Only the message itself. The title is a sender, a person or a group ("+91 98765 43210",
                // "Flat 302"): it names where a code came from but never holds one.
                val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                val short = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val body = when {
                    big.isBlank() -> short
                    short.isBlank() || short in big -> big
                    else -> "$short\n$big"
                }.ifBlank {
                    // Some apps only set a ticker, often "Sender: message". Chat tickers are always someone's words.
                    if (chat) "" else event.text.joinToString(" ").let { t -> title?.let { t.removePrefix(it).trimStart(':', ' ') } ?: t }
                }
                if (body.isNotBlank()) {
                    val source = title?.takeIf { it.length in 1..32 }?.let(OtpRelay::cleanSender) ?: appName(pkg)
                    OtpRelay.handle(applicationContext, body, source, if (chat) Otp.From.CHAT else Otp.From.NOTIFICATION, title)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        _running.value = false
        runCatching { unregisterReceiver(screenReceiver) }
        stopWatching()
        scope.cancel()
        sensorThread.quitSafely()
        if (::light.isInitialized) light.quit()
        super.onDestroy()
    }

    /** Pill for a code that just arrived (called by [OtpRelay]). */
    fun overlayCode(code: String, source: String): SendOverlay.Pill? {
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        return overlay.code(code, source)
    }

    // MARK: live link

    @Volatile private var indexSentAt = 0L

    /** The video index's progress, to the Mac's Settings (at most once a second, and always when it completes). */
    private fun sendIndex(done: Int, total: Int) {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - indexSentAt < 1000) return
        indexSentAt = now
        scope.launch(Dispatchers.IO) { runCatching { s.send("t" to "index", "done" to done, "total" to total) } }
    }

    private fun startWatching() {
        startSensors()
        scope.launch(Dispatchers.IO) { runCatching { matcher.warm() } }
        if (watchJob?.isActive == true) return
        watchJob = scope.launch(Dispatchers.IO) { watchLoop() }
    }

    private fun stopWatching() {
        stopSensors()
        if (::light.isInitialized) light.off()
        watchJob?.cancel()
        watchJob = null
        session?.let { s -> Thread { runCatching { s.close() } }.start() }
        session = null
        _live.value = false
        _roomDark.value = null
        _lux.value = null
    }

    private suspend fun watchLoop() {
        var backoff = 1500L
        var churn = 0L
        while (currentCoroutineContext().isActive) {
            val mac = app.prefs.macs.value.firstOrNull()
            if (mac == null) {
                delay(5000)
                continue
            }
            val s = try {
                open(mac)  // announces the light key ("caps") as it opens
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (s == null) {
                _live.value = false
                delay(backoff)
                // Away from the Mac's network: settle into a slow, cheap retry.
                backoff = (backoff * 2).coerceAtMost(if (onLocalNetwork()) 20_000 else 60_000)
                continue
            }
            backoff = 1500
            val openedAt = SystemClock.elapsedRealtime()
            session = s
            _live.value = true
            _videoIndex.value?.let { (done, total) -> runCatching { s.send("t" to "index", "done" to done, "total" to total) } }
            if (_diag.value) runCatching { s.diag(true) }
            if (s.pendingAtStart > 0) pullInbox(mac)
            val keepAlive = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(15_000)
                    runCatching { s.send("t" to "ka") }
                }
            }
            try {
                while (currentCoroutineContext().isActive) {
                    val m = s.next()
                    when (m.optString("t")) {
                        "grab" -> scope.launch(roundMain) { onGrab(s, mac, m) }
                        // Only to the round that asked on this very link: an answer meant for another link (an old
                        // session, a restarted Mac reusing round numbers) never reaches it.
                        "key-seen", "key-ok", "key-fail" -> rounds[m.optInt("id", -1)]?.takeIf { it.session === s }?.let { inbox ->
                            // The echo starts right here on the reader thread: black, and key-off the moment that
                            // frame is committed, without waiting for the main thread.
                            if (m.optString("t") == "key-seen") goDark(inbox)
                            inbox.box.trySend(m)
                        }
                        "release" -> {
                            releasedRound = m.optInt("id", -1)
                            releaseSignal?.complete(Unit)
                        }
                        "room" -> _roomDark.value = m.optBoolean("dark")
                        "lux" -> if (_diag.value) _lux.value = m.optDouble("v").toFloat() to m.optDouble("b").toFloat()
                        "inbox" -> pullInbox(mac)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                keepAlive.cancel()
                runCatching { s.close() }
                if (session === s) session = null
                _live.value = false
                _roomDark.value = null
                _lux.value = null
                // Rounds on this link can't get an answer any more: their light goes off now, not after a timeout.
                rounds.values.filter { it.session === s }.forEach { it.box.close() }
            }
            // A link that dies right after opening (the Mac turning it away, say) mustn't turn into a busy loop.
            if (SystemClock.elapsedRealtime() - openedAt < 5_000) {
                churn = if (churn == 0L) 1_000 else (churn * 2).coerceAtMost(30_000)
                delay(churn)
            } else {
                churn = 0
            }
        }
    }

    private fun onLocalNetwork(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun open(mac: SavedMac): WatchSession {
        val client = app.transfers.client
        val first = app.finder.endpointFor(mac.id) ?: mac.endpoint
        return try {
            client.watch(first, mac.macKey)
        } catch (e: UnreachableException) {
            val fresh = app.finder.awaitEndpoint(mac.id, 5000) ?: throw e
            client.watch(fresh, mac.macKey)
        }
    }

    private fun sendDiag(on: Boolean) {
        val s = session ?: return
        scope.launch(Dispatchers.IO) { runCatching { s.diag(on) } }
    }

    private fun pullInbox(mac: SavedMac) {
        scope.launch {
            if (app.transfers.busy) return@launch
            app.transfers.receiveInbox(mac).join()
            val st = app.transfers.state.value
            if (st is TState.Done && !st.outgoing && !st.empty) {
                val thumb = st.received.firstOrNull { it.uri != null && (it.isImage || it.isVideo) }?.uri?.let { u ->
                    withContext(Dispatchers.IO) {
                        // 192 px: sharp in the island's 48 dp thumbnail at up to 4× density.
                        runCatching { contentResolver.loadThumbnail(u, android.util.Size(192, 192), null) }.getOrNull()
                    }
                }
                overlay.received(st.title, st.subtitle, thumb, st.received)
            }
        }
    }

    // MARK: knock (the phone starts a round itself)

    /** On the sensor thread: the proximity sensor just went near after reading far for [farFor] ms. */
    private fun maybeKnock(now: Long, farFor: Long) {
        val s = session ?: return
        if (!LightKeyRules.knock(state(now), live = _live.value, lastKnock = lastKnock, farFor = farFor)) return
        lastKnock = now
        Log.i("TapDrop", "knock (gz=%.1f, far for %s)".format(gz, if (farFor > 60_000) "over a minute" else "$farFor ms"))
        // Nothing lights up or brightens here: a knock is only the phone's guess. The Mac answers with a grab once its
        // notch sees the phone on it, and never if it doesn't (a thumb or a pillow on the proximity sensor).
        scope.launch(Dispatchers.IO) { runCatching { s.knock() } }
    }

    private fun state(now: Long = SystemClock.uptimeMillis()) = LightKeyRules.State(
        now = now,
        screenOn = interactive(),
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        inCall = inCall(),
        motionSensor = sensorAlive,
        lastMotion = lastMotion,
        near = near,
        lastNear = lastNearAt,
        picking = picking,
        keying = keying,
        lastSent = lastSent,
        busy = app.transfers.busy,
        gz = gz,
        lastTouch = lastTouch,
    )

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    // MARK: the tap

    /** Answers the Mac's "grab". When the tap is declined, [why] tells the Mac (and its tap history) why. */
    private suspend fun ack(s: WatchSession, id: Int, ok: Boolean, kind: String, why: String? = null) =
        withContext(Dispatchers.IO) { runCatching { s.grabAck(id, ok, kind, why) } }

    private fun trace(src: String?, ok: Boolean, peak: Float? = null, need: Float? = null, why: String? = null) {
        _lastTap.value = TapTrace(System.currentTimeMillis(), src, ok, peak, need, why)
    }

    /** Everything a tap may send, as it was *before* any screen light: the Mac must never receive a white frame. */
    private class Capture(
        val pkg: String,
        val self: Boolean,
        val appName: String,
        val mode: SendMode,
        val url: String?,
        val screenText: String,
        val labels: List<String>,
        val boxes: List<ScreenText.Box>,
        val shot: Shot,
        /** The window's own title and the title the app last announced: viewers put the document's name there. */
        val titles: List<String?> = emptyList(),
    )

    /** The part of the capture that has to come before the light: whose screen it is, and its screenshot. */
    private class Glance(
        val root: AccessibilityNodeInfo?, val pkg: String, val self: Boolean, val appName: String, val mode: SendMode, val shot: Shot,
        val titles: List<String?> = emptyList(),
    )

    private suspend fun glance(): Glance {
        val root: AccessibilityNodeInfo? = appRoot()
        val pkg = root?.packageName?.toString() ?: lastPackage ?: ""
        if (pkg == packageName) return Glance(null, pkg, true, "TapDrop", SendMode.SMART, Shot.Failed)
        val name = appName(pkg)
        val mode = app.prefs.effectiveMode(pkg)
        if (mode == SendMode.OFF) return Glance(null, pkg, false, name, mode, Shot.Failed)
        val titles = listOf(appWindowTitle(), lastTitle?.takeIf { it.first == pkg }?.second)
        return Glance(root, pkg, false, name, mode, screenshot(), titles)
    }

    /**
     * The rest of the capture — page address, text, element boxes — read from the app's own accessibility tree, which
     * the light key's window doesn't touch. Runs off the main thread while the Mac looks for the light.
     */
    private fun capture(g: Glance): Capture {
        val root = g.root
        if (g.self || g.mode == SendMode.OFF || root == null) {
            return Capture(g.pkg, g.self, g.appName, g.mode, null, "", emptyList(), emptyList(), g.shot)
        }
        val url = if (g.mode == SendMode.LINK) findUrl(root, g.pkg) else null
        val screenText = if (g.mode == SendMode.TEXT) ScreenText.collect(root) else ""
        return Capture(g.pkg, false, g.appName, g.mode, url, screenText, ScreenText.labels(root), ScreenText.boxes(root), g.shot, g.titles)
    }

    /**
     * A light-key round: decline, or capture → white screen → `key-on` → the Mac sees it (`key-seen`) → black screen →
     * `key-off` → the Mac sees the notch go dark with it (`key-ok`) → send. Anything else — a `key-fail`, a `key-ok`
     * before the phone went dark, no answer in time, the link dropping — drops the capture and shows nothing.
     */
    private suspend fun onGrab(s: WatchSession, mac: SavedMac, m: JSONObject) {
        val id = m.optInt("id", -1)
        // A Mac whose light key is older than this phone's (or that asks without one) never gets a send.
        if (!LightKeyRules.grabCurrent(m)) {
            Log.i("TapDrop", "grab $id without the current light key: TapDrop on the Mac needs the update")
            ack(s, id, false, "none", "update-mac")
            trace(null, false, why = "update-mac")
            return
        }
        val st = state()
        val declined = LightKeyRules.decline(st) ?: (LightKeyRules.choose(st) as? LightKeyRules.Light.Decline)?.why
        if (declined != null) {
            Log.i("TapDrop", "grab $id declined: $declined (near=$near gz=%.1f)".format(gz))
            ack(s, id, false, "none", declined)
            trace(null, false, why = declined)
            return
        }
        val src = LightKeyRules.Src.SCREEN
        light.brighten(BRIGHTEN_AHEAD_MS)  // while the screen is captured: it's at full when the white goes up
        keying = true
        val inbox = Inbox(s, id)
        rounds[id] = inbox
        val round = LightKeyRules.Round(id)
        val cap = try {
            key(s, id, st.now, src, round, inbox)
        } finally {
            light.off()
            rounds.remove(id, inbox)
            keying = false
        }
        if (cap == null) {
            if (round.calibrating) {
                // One of the Mac's calibration holds: measured, nothing to send, nothing to explain.
                Log.i("TapDrop", "light key $id: calibration hold (peak=${round.peak} need=${round.need})")
            } else {
                Log.i("TapDrop", "light key $id ✗ ${round.why} (peak=${round.peak} need=${round.need})")
                // Anything on the phone that dims its screen, for the Mac's tap log.
                screenDimmers()?.let { pickNote(s, "light key didn't count · $it") }
            }
            ack(s, id, false, "none", "no-light")
            trace(if (inbox.lit) src.wire else null, false, round.peak, round.need, round.why)
            return
        }
        Log.i("TapDrop", "light key $id ✓ (peak=${round.peak} need=${round.need}) in ${SystemClock.uptimeMillis() - st.now} ms")
        trace(src.wire, true, round.peak, round.need, null)
        sendTap(s, mac, id, cap)
    }

    /** The light key itself. Returns what was on screen once the key passed, null otherwise ([round] says why). */
    private suspend fun key(
        s: WatchSession,
        id: Int,
        t0: Long,
        src: LightKeyRules.Src,
        round: LightKeyRules.Round,
        inbox: Inbox,
    ): Capture? {
        // What's on screen is captured before the screen lights up.
        val g = glance()
        Log.i("TapDrop", "grab $id: ${g.pkg} (shot=${g.shot is Shot.Ok}) in ${SystemClock.uptimeMillis() - t0} ms")
        // The Mac may have given up meanwhile (a slow screenshot), the link gone, or the screen gone off: no light.
        while (true) round.onMac(inbox.box.tryReceive().getOrNull() ?: break)
        if (round.done) return null
        if (session !== s || !interactive()) {
            round.giveUp("gone")
            return null
        }
        val shown = light.screen {
            // On the light's own thread, the moment the white frame is committed: key-on goes out now.
            inbox.litAt = SystemClock.uptimeMillis()
            inbox.lit = true
            runCatching { s.keyOn(id, src) }
        }
        if (!shown) {
            round.giveUp("no-light")
            return null
        }
        val litAt = inbox.litAt
        Log.i("TapDrop", "key-on $id (+${litAt - t0} ms)")
        // The rest of what's on screen is read while the Mac looks for the light.
        val rest = scope.async(Dispatchers.Default) {
            runCatching { capture(g) }.getOrElse { Capture(g.pkg, g.self, g.appName, g.mode, null, "", emptyList(), emptyList(), g.shot) }
        }
        var offSent = false
        var deadline = litAt + LightKeyRules.WHITE_MAX_MS
        while (!round.done) {
            val left = deadline - SystemClock.uptimeMillis()
            val next = if (left > 0) withTimeoutOrNull(left) { inbox.box.receiveCatching() } else null
            if (next == null) {
                round.giveUp("timeout")
                break
            }
            val msg = next.getOrNull()
            if (msg == null) {
                round.giveUp("gone")  // the link closed
                break
            }
            when (round.onMac(msg)) {
                LightKeyRules.Round.Act.GO_DARK -> {
                    // The notch sees the white: now it has to see the dark, right as the phone makes it. The reader
                    // thread has normally started it already (and key-off has gone out); this only waits for it.
                    goDark(inbox)
                    val black = withTimeoutOrNull(DARK_WAIT_MS) { inbox.dark.await() }
                    offSent = black != null  // the light thread sent key-off with it
                    if (black != true) {
                        round.giveUp("no-light")
                        break
                    }
                    round.wentDark()
                    Log.i("TapDrop", "key-seen $id: key-off after ${inbox.offAt - litAt} ms lit")
                    deadline = inbox.offAt + LightKeyRules.DARK_MAX_MS
                }
                // The buzz is the "you can lift it now": it comes with key-ok itself, not once what's on screen
                // has been read (that can take a few hundred ms more).
                LightKeyRules.Round.Act.PASS -> {
                    Log.i("TapDrop", "key-ok $id after ${SystemClock.uptimeMillis() - litAt} ms")
                    Haptics.tap(this)
                }
                // A calibration hold: nothing is sent, nothing failed. A short tick says it was measured.
                LightKeyRules.Round.Act.FAIL -> if (round.calibrating) Haptics.tick(this)
                LightKeyRules.Round.Act.IGNORE -> Unit
            }
        }
        light.off()
        if (!offSent) withContext(Dispatchers.IO) { runCatching { s.keyOff(id) } }
        if (round.step != LightKeyRules.Round.Step.PASSED) {
            rest.cancel()
            return null
        }
        lastSent = SystemClock.uptimeMillis()
        return rest.await()
    }

    /**
     * The echo for one round, from whichever thread sees key-seen first (normally the link's reader): the screen turns
     * black on the light's own thread and key-off goes out the moment that frame is committed. Once per round.
     */
    private fun goDark(inbox: Inbox) {
        if (!inbox.lit || !inbox.darkening.compareAndSet(false, true)) return
        light.dark { black ->
            // On the light thread. Key-off either way: the screen is black, or its light is already gone.
            runCatching { inbox.session.keyOff(inbox.id) }
            inbox.offAt = SystemClock.uptimeMillis()
            inbox.dark.complete(black)
        }
    }

    /** What goes to the Mac. Resolved in the background while the picker is up. */
    private sealed interface Plan {
        data class Link(val url: String) : Plan
        data class Media(val media: MediaMatcher.Media) : Plan
        data class File(val file: FileFinder.Hit) : Plan
        data class Text(val text: String) : Plan
        data class Shot(val ocr: Boolean, val why: String?) : Plan
    }

    /** The thing under a finger in the picker. */
    private data class Target(val rect: android.graphics.Rect, val text: String?, val file: FileFinder.Row?)

    private fun targetAt(boxes: List<ScreenText.Box>, x: Int, y: Int): Target? {
        val dm = resources.displayMetrics
        val area = dm.widthPixels.toLong() * dm.heightPixels
        FileFinder.fileAt(boxes, x, y, area)?.let { return Target(it.rect, null, it) }
        val under = boxes.filter { it.rect.contains(x, y) }
        val hits = under.filter { it.area < area * 0.85 }
        val text = hits.filter { !it.text.isNullOrBlank() }.minByOrNull { it.area }
        // A picture: the whole picture, never a layer inside it (chat apps stack overlays, gradients and buttons on a
        // photo, and the smallest of those is a sliver of it). The largest image element under the finger that isn't
        // the whole screen (a chat's wallpaper is); a full-screen picture (a photo viewer) only if there's nothing else.
        val side = (72 * dm.density).toInt()
        val pictures = under.filter { b ->
            b.text.isNullOrBlank() && b.rect.width() >= side && b.rect.height() >= side && (
                b.cls.endsWith("ImageView") || b.cls.contains("Image", ignoreCase = true) || b.cls.contains("Photo", ignoreCase = true) ||
                    b.cls.endsWith("TextureView") || b.cls.endsWith("SurfaceView") ||
                    b.desc?.let { d -> d.contains("photo", true) || d.contains("image", true) || d.contains("picture", true) || d.contains("video", true) } == true
                )
        }
        val picture = pictures.filter { it.area < area * 0.85 }.maxByOrNull { it.area }
            ?: pictures.maxByOrNull { it.area }
            // The screen's own photo or video, as the media detector sees it, if the finger is on it.
            ?: MediaDetector.detect(boxes, dm.widthPixels, dm.heightPixels)?.rect?.takeIf { it.contains(x, y) }
                ?.let { ScreenText.Box(it, null, "media") }
        // Text wins only when it's what the finger is really on: a caption, a message, not a time stamped on a photo.
        if (text != null && (picture == null || text.area * 4 > picture.area)) return Target(text.rect, text.text, null)
        picture?.let { return Target(it.rect, null, null) }
        text?.let { return Target(it.rect, it.text, null) }
        val minSide = (48 * dm.density).toInt()
        return hits.filter { it.rect.width() >= minSide && it.rect.height() >= minSide }.minByOrNull { it.area }?.let { Target(it.rect, null, null) }
    }

    /** The light key passed: send what was on screen (as before the light), the way the app's mode says. */
    private suspend fun sendTap(s: WatchSession, mac: SavedMac, id: Int, cap: Capture) {
        if (cap.self) {
            // TapDrop itself is open: collect anything waiting on the Mac, or say what a tap does.
            val pending = withContext(Dispatchers.IO) { app.transfers.ping(mac)?.pending ?: 0 }
            if (pending > 0) {
                ack(s, id, true, "inbox")
                pullInbox(mac)
            } else {
                ack(s, id, false, "none", "self")
                // The "Test your tap" screen (diag on) shows the result itself.
                if (!_diag.value) overlay.notice(R.drawable.ic_close, "Open what you want to send, then tap")
            }
            return
        }
        val pkg = cap.pkg
        val appName = cap.appName
        val mode = cap.mode
        if (mode == SendMode.OFF) {
            ack(s, id, false, "none", "off")
            overlay.notice(R.drawable.ic_close, "Sending is off for $appName")
            return
        }

        val url = cap.url
        val screenText = cap.screenText
        val labels = cap.labels
        val boxes = cap.boxes
        val shotResult = cap.shot
        val shot = (shotResult as? Shot.Ok)?.bitmap
        val hit = shot?.let { MediaDetector.detect(boxes, it.width, it.height) }
        val docName = if (mode == SendMode.MEDIA && shot != null) FileFinder.fileOnScreen(boxes, shot.height) else null
        val what = if (hit?.kind == MediaDetector.Kind.VIDEO) "video" else "photo"
        // A document viewer or a video player: the file itself, found by the name the app shows for it.
        val docViewer = AppRules.isDocViewer(pkg)
        val videoShown = hit?.kind == MediaDetector.Kind.VIDEO || AppRules.isVideoPlayer(pkg)
        val titles = if (mode == SendMode.MEDIA && shot != null && (docViewer || videoShown))
            FileFinder.titlesOnScreen(boxes, shot.height, cap.titles) else emptyList()
        Log.i("TapDrop", "tap $id: pkg=$pkg mode=$mode shot=${shot != null} media=${hit?.kind} doc=${docName != null} titles=${titles.size}")

        var matchNote = ""
        val planJob: Deferred<Plan> = scope.async(Dispatchers.Default) {
            runCatching {
                when {
                    url != null -> Plan.Link(url)
                    mode == SendMode.TEXT && screenText.length >= 40 && !AppRules.isGallery(pkg) -> Plan.Text(screenText)
                    mode == SendMode.TEXT -> Plan.Shot(ocr = true, why = null)
                    mode == SendMode.LATEST_MEDIA -> matcher.latest()?.let { Plan.Media(it) } ?: Plan.Shot(false, "No photos found")
                    mode == SendMode.MEDIA -> {
                        val doc = docName?.let { n -> (files.find(n, null) as? FileFinder.Outcome.Found)?.file }
                            ?: titles.firstNotNullOfOrNull { t -> files.findTitled(t, docs = docViewer, videos = videoShown) }
                        when {
                            doc != null -> Plan.File(doc)
                            shot == null -> Plan.Shot(false, null)
                            // A viewer showing a document TapDrop isn't allowed to see yet: say how to send the file itself.
                            docViewer && hit == null && !files.hasAllFiles() ->
                                Plan.Shot(false, "Turn on File access in TapDrop to send the file itself")
                            // The library's videos only where they can be on screen: a gallery, a chat, a video
                            // player, or a video playing. Never a social feed or a browser.
                            else -> when (val o = matcher.find(
                                shot, labels, hit,
                                allowVideos = AppRules.isGallery(pkg) || AppRules.isChat(pkg) || AppRules.isVideoPlayer(pkg) ||
                                    hit?.kind == MediaDetector.Kind.VIDEO,
                                gallery = AppRules.isGallery(pkg),
                            ).also { matchNote = matcher.lastNote }) {
                                is MediaMatcher.Outcome.Found -> Plan.Media(o.media)
                                MediaMatcher.Outcome.NoAccess -> Plan.Shot(false, if (hit != null) "Allow photo access in TapDrop to send the $what itself" else null)
                                MediaMatcher.Outcome.NotFound -> Plan.Shot(false, if (hit != null) "Couldn't find this $what in your gallery" else null)
                            }
                        }
                    }
                    else -> Plan.Shot(false, null)
                }
            }.getOrElse { Plan.Shot(false, null) }
        }

        // Freeze-frame pick: a moment after the phone leaves the notch to tap / drag something specific.
        var pick: PickOverlay.Result = PickOverlay.Result.Timeout
        var frozen: PickOverlay? = null
        if (shot != null && app.prefs.pickFirst.value) {
            ack(s, id, true, "pick")
            val released = CompletableDeferred<Unit>()
            releaseSignal = released
            if (releasedRound == id) released.complete(Unit)  // the phone already left the notch
            picking = true
            val f = PickOverlay(this)
            frozen = f
            // Live preview of what goes if nothing is picked (runs once the picker is on screen).
            val previewJob = scope.launch(Dispatchers.Main) {
                val docHint = docName ?: titles.firstOrNull()?.takeIf { docViewer }
                if (hit != null || docHint != null) {
                    val kind = when {
                        docHint != null && hit == null -> PickOverlay.Kind.FILE
                        hit?.kind == MediaDetector.Kind.VIDEO -> PickOverlay.Kind.VIDEO
                        else -> PickOverlay.Kind.PHOTO
                    }
                    f.preview(PickOverlay.Plan(kind, docHint, finding = true))
                    f.hold(true)
                }
                val plan = withTimeoutOrNull(4000) { planJob.await() } ?: Plan.Shot(false, null)
                showPlan(f, plan)
                f.hold(false)
            }
            pick = try {
                f.run(shot, released, highlight = { x, y -> targetAt(boxes, x, y)?.let { PickOverlay.Target(it.rect, it.text, it.file?.name) } })
            } finally {
                previewJob.cancel()
                picking = false
                releaseSignal = null
            }
        }
        Log.i("TapDrop", "pick result: $pick")
        val removeFrozen: () -> Unit = { frozen?.let { f -> overlayHandler.postDelayed({ f.remove() }, 60) } }

        when (val p = pick) {
            PickOverlay.Result.Cancel -> {
                frozen?.remove()
                withContext(Dispatchers.IO) { runCatching { s.send("t" to "grab-cancel", "id" to id) } }
                return
            }
            is PickOverlay.Result.Tap -> {
                val t = targetAt(boxes, p.x, p.y)
                val dm = resources.displayMetrics
                // A file row (Files, Drive, a document in a chat) → that file.
                t?.file?.let { row ->
                    when (val o = withContext(Dispatchers.IO) { files.find(row.name, row.size) }) {
                        is FileFinder.Outcome.Found -> {
                            val pill = overlay.flyRegion(crop(shot!!, row.rect), clip(row.rect, shot), "Sending ${o.file.name}")
                            removeFrozen()
                            sendFile(mac, o.file, pill)
                            return
                        }
                        FileFinder.Outcome.NeedsAccess -> {
                            frozen?.remove()
                            withContext(Dispatchers.IO) { runCatching { s.send("t" to "grab-cancel", "id" to id) } }
                            Haptics.error(this)
                            overlay.notice(R.drawable.ic_close, "To send files, turn on File access in TapDrop", error = true)
                            return
                        }
                        FileFinder.Outcome.NotFound -> Unit
                    }
                }
                // A picture (e.g. a photo in a chat) → its original, if it's in the gallery.
                if (t != null && t.text == null && t.rect.width() > dm.widthPixels / 4 && t.rect.height() > dm.widthPixels / 6) {
                    val original = matcher.findIn(shot!!, t.rect)
                    pickNote(s, "picked a picture in $appName → ${original?.name ?: "sent that picture"} · ${matcher.lastNote}")
                    if (original != null) {
                        val pill = overlay.flyRegion(crop(shot, t.rect), clip(t.rect, shot), "Sending ${if (original.isVideo) "video" else "photo"}")
                        removeFrozen()
                        sendMedia(mac, original, pill)
                        return
                    }
                } else {
                    pickNote(s, "picked ${if (t?.text != null) "text" else "an area"} in $appName")
                }
                val rect = t?.rect ?: android.graphics.Rect(p.x - dm.widthPixels / 4, p.y - dm.widthPixels / 4, p.x + dm.widthPixels / 4, p.y + dm.widthPixels / 4)
                sendPicked(mac, shot!!, rect, t?.text, ocr = false, appName = appName, then = removeFrozen)
                return
            }
            is PickOverlay.Result.Region -> {
                val text = ScreenText.textIn(boxes, p.rect).takeIf { it.length >= 2 }
                sendPicked(mac, shot!!, p.rect, text, ocr = text == null, appName = appName, then = removeFrozen)
                return
            }
            PickOverlay.Result.Timeout -> Unit
        }

        // Nothing picked → the plan.
        val plan = withTimeoutOrNull(4000) { planJob.await() } ?: Plan.Shot(false, "took too long to find")
        // What the phone decided and why, for the Mac's tap log (Settings → Tap to Send → Tap Log).
        val decided = when (plan) {
            is Plan.Link -> "the page link"
            is Plan.Text -> "the text on screen"
            is Plan.Media -> "the original ${plan.media.name}"
            is Plan.File -> plan.file.name
            is Plan.Shot -> "a screenshot" + (plan.why?.let { " ($it)" } ?: "")
        }
        val note = "sent $decided from $appName" + (matchNote.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        scope.launch(Dispatchers.IO) { runCatching { s.send("t" to "tap-note", "text" to note.take(400)) } }
        if (frozen == null) {
            if (shot == null && plan is Plan.Shot) {
                if (shotResult == Shot.Secure) {
                    ack(s, id, false, "none", "secure")
                    Haptics.error(this)
                    overlay.notice(R.drawable.ic_close, "$appName blocks screenshots — try Text in TapDrop", error = true)
                } else {
                    ack(s, id, false, "none", "no-screenshot")
                }
                return
            }
            ack(s, id, true, if (plan is Plan.Link) "link" else "screenshot")
        }
        when (plan) {
            is Plan.Link -> {
                val host = Uri.parse(plan.url).host?.removePrefix("www.") ?: plan.url
                val pill = overlay.flyChip(SendOverlay.Chip.LINK, host, "Opening on your Mac")
                removeFrozen()
                finish(pill, send(mac, listOf(OutItem.Text(plan.url)), host, mime = "text/x-link"), "Opened on your Mac")
            }
            is Plan.Text -> {
                val words = plan.text.split(Regex("\\s+")).size
                val pill = overlay.flyChip(SendOverlay.Chip.TEXT, "$words words", "Copying text to your Mac")
                removeFrozen()
                finish(pill, send(mac, listOf(OutItem.Text(plan.text, clip = true)), "Text · $appName"), "Text copied on your Mac")
            }
            is Plan.Media -> {
                val what2 = if (plan.media.isVideo) "video" else "photo"
                val pill = if (shot != null) overlay.flyScreenshot(shot, "Sending $what2")
                else withContext(Dispatchers.IO) { matcher.thumbnail(plan.media, 720) }?.let { overlay.flyCard(it, "Sending $what2") }
                    ?: overlay.sending("Sending $what2")
                removeFrozen()
                sendMedia(mac, plan.media, pill)
            }
            is Plan.File -> {
                val pill = overlay.flyChip(SendOverlay.Chip.FILE, plan.file.name, "Sending ${plan.file.name}")
                removeFrozen()
                sendFile(mac, plan.file, pill)
            }
            is Plan.Shot -> {
                if (shot == null) {
                    frozen?.remove()
                    return
                }
                if (plan.ocr) {
                    sendPicked(mac, shot, ScreenText.contentBand(boxes, shot.width, shot.height), null, ocr = true, appName = appName, then = removeFrozen)
                    return
                }
                val pill = overlay.flyScreenshot(shot, "Sending a screenshot")
                removeFrozen()
                val png = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.getDefault()).format(Date())
                val item = OutItem.File("$appName $stamp.png", png.size.toLong(), "image/png", clip = true) { ByteArrayInputStream(png) }
                finish(pill, send(mac, listOf(item), "Screenshot · $appName", previewOf(shot), "image/png"), "Screenshot sent")
            }
        }
    }

    private fun showPlan(f: PickOverlay, plan: Plan) {
        when (plan) {
            is Plan.Link -> f.preview(PickOverlay.Plan(PickOverlay.Kind.LINK, Uri.parse(plan.url).host?.removePrefix("www.") ?: "Web page"))
            is Plan.Text -> f.preview(PickOverlay.Plan(PickOverlay.Kind.TEXT, "${plan.text.split(Regex("\\s+")).size} words, to your Mac's clipboard"))
            is Plan.File -> f.preview(PickOverlay.Plan(PickOverlay.Kind.FILE, "${plan.file.name} · ${fmtSize(plan.file.size)}"))
            is Plan.Media -> {
                val t = matcher.thumbnail(plan.media, 160)
                f.preview(PickOverlay.Plan(if (plan.media.isVideo) PickOverlay.Kind.VIDEO else PickOverlay.Kind.PHOTO, plan.media.name, t))
            }
            is Plan.Shot -> f.preview(PickOverlay.Plan(if (plan.ocr) PickOverlay.Kind.SCREEN_TEXT else PickOverlay.Kind.SCREEN, note = plan.why))
        }
    }

    private fun fmtSize(b: Long): String = when {
        b <= 0 -> "on your phone"
        b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    private fun clip(r: android.graphics.Rect, shot: Bitmap): android.graphics.Rect {
        val c = android.graphics.Rect(r)
        if (!c.intersect(0, 0, shot.width, shot.height) || c.width() < 4 || c.height() < 4) c.set(0, 0, shot.width, shot.height)
        return c
    }

    private fun crop(shot: Bitmap, r: android.graphics.Rect): Bitmap {
        val c = clip(r, shot)
        return Bitmap.createBitmap(shot, c.left, c.top, c.width(), c.height())
    }

    /** A small saved copy of a sent screenshot, so Recent can show it. */
    private suspend fun previewOf(shot: Bitmap): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(cacheDir, "sent").apply { mkdirs() }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
            val f = java.io.File(dir, "s${System.currentTimeMillis()}.jpg")
            val scale = 360f / shot.width
            val small = Bitmap.createScaledBitmap(shot, 360, (shot.height * scale).toInt(), true)
            f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            Uri.fromFile(f)
        }.getOrNull()
    }

    /** Sends the picked element/region: its text if it has any, otherwise the cropped image. */
    /** The phone is warm enough that Android may cap the screen's brightness. */
    /**
     * Warm enough that Android starts holding the phone back (screen brightness included, which taps need): background
     * fingerprinting waits. "Light" isn't it (a charging phone sits there for hours, and nothing is limited yet).
     */
    private fun warm(): Boolean =
        getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

    /** Whatever is dimming the screen right now ("phone warm (moderate) · Battery Saver on"), or null. */
    private fun screenDimmers(): String? {
        val pm = getSystemService(PowerManager::class.java)
        val heat = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> null
            PowerManager.THERMAL_STATUS_LIGHT -> "phone warm (light)"
            PowerManager.THERMAL_STATUS_MODERATE -> "phone warm (moderate)"
            PowerManager.THERMAL_STATUS_SEVERE -> "phone hot (severe)"
            else -> "phone very hot"
        }
        val saver = "Battery Saver on".takeIf { pm.isPowerSaveMode }
        val dim = "Extra dim on".takeIf {
            runCatching { android.provider.Settings.Secure.getInt(contentResolver, "reduce_bright_colors_activated", 0) == 1 }
                .getOrDefault(false)
        }
        return listOfNotNull(heat, saver, dim).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** What the pick step chose, for the Mac's tap log. */
    private fun pickNote(s: WatchSession, note: String) {
        scope.launch(Dispatchers.IO) { runCatching { s.send("t" to "tap-note", "text" to note.take(400)) } }
    }

    private suspend fun sendPicked(
        mac: SavedMac,
        shot: Bitmap,
        rect: android.graphics.Rect,
        text: String?,
        ocr: Boolean,
        appName: String,
        then: () -> Unit,
    ) {
        val r = clip(rect, shot)
        val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
        if (text != null) {
            val words = text.split(Regex("\\s+")).size
            val pill = overlay.flyRegion(crop, r, if (words == 1) "Copying to your Mac" else "Copying $words words to your Mac", text)
            then()
            finish(pill, send(mac, listOf(OutItem.Text(text, clip = true)), "Text · $appName"), "Copied on your Mac")
            return
        }
        val pill = overlay.flyRegion(crop, r, if (ocr) "Reading it on your Mac" else "Sending to your Mac")
        then()
        val png = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.getDefault()).format(Date())
        val item = OutItem.File("$appName $stamp.png", png.size.toLong(), "image/png", clip = true, ocr = ocr) { ByteArrayInputStream(png) }
        finish(
            pill,
            send(mac, listOf(item), if (ocr) "Text · $appName" else "Clip · $appName", previewOf(crop), "image/png"),
            if (ocr) "Done — on your Mac" else "Sent to your Mac",
        )
    }

    private suspend fun sendFile(mac: SavedMac, h: FileFinder.Hit, pill: SendOverlay.Pill) {
        val item = OutItem.File(h.name, h.size, h.mime) { files.open(h) }
        val watcher = scope.launch {
            app.transfers.state.collect { st ->
                if (st is TState.Working && st.total > 3_000_000) st.progress?.let { pill.progress(it) }
            }
        }
        val isMedia = h.mime.startsWith("image/") || h.mime.startsWith("video/")
        val err = send(mac, listOf(item), h.name, if (isMedia) h.uri else null, h.mime)
        watcher.cancel()
        finish(pill, err, "${h.name} sent")
    }

    private suspend fun sendMedia(mac: SavedMac, m: MediaMatcher.Media, pill: SendOverlay.Pill) {
        val item = OutItem.File(m.name, m.size, m.mime) {
            contentResolver.openInputStream(m.uri) ?: throw java.io.FileNotFoundException(m.name)
        }
        val what = if (m.isVideo) "video" else "photo"
        val watcher = scope.launch {
            app.transfers.state.collect { st ->
                if (st is TState.Working && st.total > 3_000_000) st.progress?.let { pill.progress(it) }
            }
        }
        val err = send(mac, listOf(item), m.name, m.uri, m.mime)
        watcher.cancel()
        finish(pill, err, "${what.replaceFirstChar { it.uppercase() }} sent")
    }

    private fun finish(pill: SendOverlay.Pill, error: String?, success: String) {
        if (error == null) pill.finish(true, success) else pill.finish(false, error)
    }

    /** Sends through the shared transfer queue; returns null on success or an error message. */
    private suspend fun send(mac: SavedMac, items: List<OutItem>, label: String, preview: Uri? = null, mime: String? = null): String? {
        Log.i("TapDrop", "sending $label (${items.size} item(s))")
        app.transfers.send(mac, items, label, preview, mime).join()
        Log.i("TapDrop", "send finished: ${app.transfers.state.value::class.simpleName}")
        return when (val st = app.transfers.state.value) {
            is TState.Failed -> st.message.substringBefore('.').ifBlank { "Couldn't send" }
            else -> null
        }
    }

    /**
     * The app the user is looking at. System pop-ups (heads-up notifications, the volume slider,
     * "Viewing full screen" tips) can hold focus, so take the top-most *application* window instead.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        val app = runCatching { windows }.getOrNull().orEmpty().firstOrNull { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString().let { it != null && it != packageName && it != "com.android.systemui" }
        }
        return app?.root ?: runCatching { rootInActiveWindow }.getOrNull()
    }

    /** The title of the window [appRoot] reads (activities often title themselves with the open document). */
    private fun appWindowTitle(): String? = runCatching {
        windows.firstOrNull { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString().let { it != null && it != packageName && it != "com.android.systemui" }
        }?.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun appName(pkg: String?): String = runCatching {
        pkg ?: return "Screenshot"
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("Screenshot")

    private sealed interface Shot {
        class Ok(val bitmap: Bitmap) : Shot
        data object Secure : Shot
        data object Failed : Shot
    }

    private suspend fun screenshot(): Shot = withTimeoutOrNull(2500) {
        suspendCancellableCoroutine<Shot> { cont ->
            // Answered off the main thread: copying the frame mustn't wait behind (or hold up) the UI.
            takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = runCatching {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    hb.close()
                    if (cont.isActive) cont.resume(if (bmp == null) Shot.Failed else if (allBlack(bmp)) Shot.Secure else Shot.Ok(bmp))
                }

                override fun onFailure(errorCode: Int) {
                    val secure = Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    if (cont.isActive) cont.resume(if (secure) Shot.Secure else Shot.Failed)
                }
            })
        }
    } ?: Shot.Failed

    /** Apps with FLAG_SECURE (banking, streaming) come back as a black frame on some versions. */
    private fun allBlack(b: Bitmap): Boolean {
        val step = 24
        var y = b.height / 10
        while (y < b.height * 9 / 10) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                if ((c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff) > 24) return false
                x += b.width / step
            }
            y += b.height / step
        }
        return true
    }

    // MARK: browsers → send the page itself

    private fun findUrl(root: AccessibilityNodeInfo?, pkg: String): String? {
        root ?: return null
        val ids = AppRules.URL_BARS[pkg] ?: return null
        for (id in ids) {
            val text = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }.getOrNull()
                ?.firstNotNullOfOrNull { it.text?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
                ?: continue
            if (text.contains(' ') || !text.contains('.')) continue  // a search being typed, not an address
            return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        }
        return null
    }

    // MARK: sensors (only while the screen is on)

    private fun startSensors() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sm.unregisterListener(motion)
        sm.unregisterListener(proximity)
        // ~50 readings a second: motion in the last second is what tells a knock or a tap from a phone at rest. They
        // may be batched up to 100 ms (each carries its own time), so the app wakes ~10 times a second, not 50.
        lastSampleNs = 0L
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(motion, it, SensorManager.SENSOR_DELAY_GAME, MOTION_BATCH_US, sensorHandler)
        }
        near = false
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sm.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
        }
    }

    private fun stopSensors() {
        val sm = getSystemService(SensorManager::class.java)
        sm?.unregisterListener(motion)
        sm?.unregisterListener(proximity)
        sensorAlive = false
        near = false
    }

    companion object {
        @Volatile var instance: NotchTapService? = null
            private set

        /** How long a round waits for its echo once it hears key-seen (the light thread gives up on a frame long before). */
        private const val DARK_WAIT_MS = 600L
        /** How long a grab's brightening lasts if its light never goes up. */
        private const val BRIGHTEN_AHEAD_MS = 2_500L

        private val _running = MutableStateFlow(false)
        /** The accessibility service is enabled and connected. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _live = MutableStateFlow(false)
        /** A live link to the Mac is open (screen on, Mac reachable). */
        val live: StateFlow<Boolean> = _live.asStateFlow()

        private val _roomDark = MutableStateFlow<Boolean?>(null)
        /**
         * The room at the Mac's notch: true = dark, false = lit, null = no live link or the Mac hasn't measured it
         * yet. Either way a tap is the top of the phone's screen on the notch; the room only changes how the Mac
         * notices the phone arriving.
         */
        val roomDark: StateFlow<Boolean?> = _roomDark.asStateFlow()

        private val _lux = MutableStateFlow<Pair<Float, Float>?>(null)
        /**
         * Live notch light while [setDiag] is on and the link is live: (reading, room baseline B) in lux,
         * up to 8 updates a second. Null otherwise.
         */
        val lux: StateFlow<Pair<Float, Float>?> = _lux.asStateFlow()

        private val _diag = MutableStateFlow(false)

        /** The "Test your tap" screen is open: stream the notch's light into [lux]. Survives reconnects. */
        fun setDiag(on: Boolean) {
            _diag.value = on
            if (!on) _lux.value = null
            instance?.sendDiag(on)
        }

        private val _videoIndex = MutableStateFlow<Pair<Int, Int>?>(null)
        /** (done, total): how many of the library's videos are fingerprinted, so a tap can send the original video. */
        val videoIndex: StateFlow<Pair<Int, Int>?> = _videoIndex.asStateFlow()

        private val _indexPaused = MutableStateFlow<String?>(null)
        /** Why fingerprinting is waiting right now ("warm": the phone is warm; "tap": a tap is happening), or null. */
        val indexPaused: StateFlow<String?> = _indexPaused.asStateFlow()

        private val _photoIndex = MutableStateFlow<Pair<Int, Int>?>(null)
        /** (done, total): how many of the library's photos are fingerprinted, so a tap can send the original photo. */
        val photoIndex: StateFlow<Pair<Int, Int>?> = _photoIndex.asStateFlow()

        private val _lastTap = MutableStateFlow<TapTrace?>(null)
        /** How the latest grab ended: declined, light key ✓ (and sending) or ✗. */
        val lastTap: StateFlow<TapTrace?> = _lastTap.asStateFlow()

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, NotchTapService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
