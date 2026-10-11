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
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.tapdrop.R
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.core.UnreachableException
import app.tapdrop.core.WatchSession
import app.tapdrop.data.AppRules
import app.tapdrop.data.SavedMac
import app.tapdrop.data.SendMode
import app.tapdrop.net.TState
import app.tapdrop.ui.Haptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * "Tap to send" without NFC, plus Smart codes.
 *
 * While the screen is on, keeps a live encrypted link to the Mac. When the Mac's notch light sensor
 * sees the phone cover it, the Mac sends "grab" and we answer according to the foreground app's
 * mode (Smart by default): the page URL in browsers, the original photo/video in gallery apps,
 * the newest shot in camera apps, otherwise a screenshot — or its text, or nothing.
 * Also relays one-time codes from notifications and receives anything queued on the Mac instantly.
 */
class NotchTapService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = TapDropApp.instance
    private lateinit var overlay: SendOverlay
    private lateinit var matcher: MediaMatcher
    private lateinit var files: FileFinder

    private var watchJob: Job? = null
    @Volatile private var session: WatchSession? = null
    @Volatile private var lastMotion = 0L
    /** The tap being answered started here: the phone touched its magnet spot on the Mac. */
    @Volatile private var grabBySpot = false
    /** Near the spot right now (re-arms once the phone has moved away). */
    private var atSpot = false
    @Volatile private var sensorAlive = false
    private val gravity = FloatArray(3)
    private var lastSampleNs = 0L
    private var lastGrab = 0L
    @Volatile private var picking = false
    @Volatile private var releaseSignal: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
            val a = if (dt == 0f) 1f else 1f - kotlin.math.exp(-dt / 0.35f)
            for (k in 0..2) gravity[k] += a * (e.values[k] - gravity[k])
            val x = e.values[0] - gravity[0]
            val y = e.values[1] - gravity[1]
            val z = e.values[2] - gravity[2]
            if (x * x + y * y + z * z > 1.4f * 1.4f) lastMotion = SystemClock.uptimeMillis()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onServiceConnected() {
        instance = this
        overlay = SendOverlay(this)
        matcher = MediaMatcher(this)
        files = FileFinder(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        _running.value = true
        if (getSystemService(PowerManager::class.java).isInteractive) startWatching()
        scope.launch {
            app.prefs.macs.first { it.isNotEmpty() }
            startWatching()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
                lastPackage = pkg
                // Opening a gallery: pre-compute photo fingerprints so a tap matches instantly.
                if (AppRules.isGallery(pkg) && app.prefs.effectiveMode(pkg) == SendMode.MEDIA) {
                    scope.launch(Dispatchers.IO) { runCatching { matcher.warm() } }
                }
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg == packageName) return
                val n = event.parcelableData as? Notification ?: return
                if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
                val ex = n.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val text = listOfNotNull(
                    title,
                    ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                    event.text.joinToString(" ").takeIf { it.isNotBlank() },
                ).joinToString("\n")
                if (text.isNotBlank()) {
                    val source = title?.takeIf { it.length in 1..32 }?.let(OtpRelay::cleanSender) ?: appName(pkg)
                    OtpRelay.handle(applicationContext, text, source)
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
        super.onDestroy()
    }

    /** Pill for a code that just arrived (called by [OtpRelay]). */
    fun overlayCode(code: String, source: String): SendOverlay.Pill? {
        if (!getSystemService(PowerManager::class.java).isInteractive) return null
        return overlay.code("Code ${OtpRelay.pretty(code)} from $source")
    }

    // MARK: live link

    private fun startWatching() {
        startMotion()
        scope.launch(Dispatchers.IO) { runCatching { matcher.warm() } }
        if (watchJob?.isActive == true) return
        watchJob = scope.launch(Dispatchers.IO) { watchLoop() }
    }

    private fun stopWatching() {
        stopMotion()
        watchJob?.cancel()
        watchJob = null
        session?.let { s -> Thread { runCatching { s.close() } }.start() }
        session = null
        _live.value = false
    }

    private suspend fun watchLoop() {
        var backoff = 1500L
        while (currentCoroutineContext().isActive) {
            val mac = app.prefs.macs.value.firstOrNull()
            if (mac == null) {
                delay(5000)
                continue
            }
            val s = try {
                open(mac)
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
            session = s
            _live.value = true
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
                        "grab" -> scope.launch { onGrab(s, mac) }
                        "release" -> releaseSignal?.complete(Unit)
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

    private fun pullInbox(mac: SavedMac) {
        scope.launch {
            if (app.transfers.busy) return@launch
            app.transfers.receiveInbox(mac).join()
            val st = app.transfers.state.value
            if (st is TState.Done && !st.outgoing && !st.empty) {
                val thumb = st.received.firstOrNull { it.uri != null && (it.isImage || it.isVideo) }?.uri?.let { u ->
                    withContext(Dispatchers.IO) {
                        runCatching { contentResolver.loadThumbnail(u, android.util.Size(120, 120), null) }.getOrNull()
                    }
                }
                overlay.received(if (st.subtitle.isNotEmpty() && st.received.size == 1) "${st.title} · ${st.subtitle}" else st.title, thumb)
            }
        }
    }

    // MARK: the tap

    /** Answers the Mac's "grab". When the tap is declined, [why] tells the Mac (and its tap history) why. */
    private suspend fun ack(s: WatchSession, ok: Boolean, kind: String, why: String? = null) = withContext(Dispatchers.IO) {
        runCatching {
            when {
                why != null -> s.send("t" to "grab-ack", "ok" to ok, "kind" to kind, "why" to why)
                grabBySpot -> s.send("t" to "grab-ack", "ok" to ok, "kind" to kind, "spot" to true)
                else -> s.send("t" to "grab-ack", "ok" to ok, "kind" to kind)
            }
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
        val hits = boxes.filter { it.rect.contains(x, y) && it.area < area * 0.85 }
        hits.filter { !it.text.isNullOrBlank() }.minByOrNull { it.area }?.let { return Target(it.rect, it.text, null) }
        val minSide = (48 * dm.density).toInt()
        return hits.filter { it.rect.width() >= minSide && it.rect.height() >= minSide }.minByOrNull { it.area }?.let { Target(it.rect, null, null) }
    }

    private suspend fun onGrab(s: WatchSession, mac: SavedMac, spot: Boolean = false) {
        grabBySpot = spot
        val now = SystemClock.uptimeMillis()
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        // The phone moved in the last few seconds (picked up, carried to the Mac). A phone lying
        // on the desk while a hand covers the notch hasn't. No waiting here: the answer is instant.
        val moved = spot || !sensorAlive || now - lastMotion < 4000
        val why = when {
            !pm.isInteractive -> "screen-off"
            km.isKeyguardLocked -> "locked"
            !moved -> "still"
            picking || now - lastGrab < 2500 -> "cooldown"
            app.transfers.busy -> "busy"
            else -> null
        }
        if (why != null) {
            android.util.Log.i("TapDrop", "grab ignored: $why")
            ack(s, false, "none", why)
            return
        }
        val root: AccessibilityNodeInfo? = appRoot()
        val pkg = root?.packageName?.toString() ?: lastPackage ?: ""
        if (pkg == packageName) {
            // TapDrop itself is open: collect anything waiting on the Mac, or say what a tap does.
            lastGrab = now
            val pending = withContext(Dispatchers.IO) { app.transfers.ping(mac)?.pending ?: 0 }
            if (pending > 0) {
                ack(s, true, "inbox")
                Haptics.tap(this)
                pullInbox(mac)
            } else {
                ack(s, false, "none", "self")
                Haptics.tick(this)
                overlay.notice(R.drawable.ic_close, "Open what you want to send, then tap")
            }
            return
        }
        lastGrab = now
        val appName = appName(pkg)
        val mode = app.prefs.effectiveMode(pkg)
        if (mode == SendMode.OFF) {
            ack(s, false, "none", "off")
            Haptics.tick(this)
            overlay.notice(R.drawable.ic_close, "Sending is off for $appName")
            return
        }

        // Read everything we need *before* the picker covers the app.
        val url = if (mode == SendMode.LINK) findUrl(root, pkg) else null
        val screenText = if (mode == SendMode.TEXT) ScreenText.collect(root) else ""
        val labels = ScreenText.labels(root)
        val boxes = ScreenText.boxes(root)
        val shotResult = screenshot()
        val shot = (shotResult as? Shot.Ok)?.bitmap
        val hit = shot?.let { MediaDetector.detect(boxes, it.width, it.height) }
        val docName = if (mode == SendMode.MEDIA && shot != null) FileFinder.fileOnScreen(boxes, shot.height) else null
        val what = if (hit?.kind == MediaDetector.Kind.VIDEO) "video" else "photo"
        android.util.Log.i("TapDrop", "grab: pkg=$pkg mode=$mode shot=${shot != null} media=${hit?.kind} doc=${docName != null}")

        val planJob: Deferred<Plan> = scope.async(Dispatchers.Default) {
            runCatching {
                when {
                    url != null -> Plan.Link(url)
                    mode == SendMode.TEXT && screenText.length >= 40 && !AppRules.isGallery(pkg) -> Plan.Text(screenText)
                    mode == SendMode.TEXT -> Plan.Shot(ocr = true, why = null)
                    mode == SendMode.LATEST_MEDIA -> matcher.latest()?.let { Plan.Media(it) } ?: Plan.Shot(false, "No photos found")
                    mode == SendMode.MEDIA -> {
                        val doc = docName?.let { n -> (files.find(n, null) as? FileFinder.Outcome.Found)?.file }
                        when {
                            doc != null -> Plan.File(doc)
                            shot == null -> Plan.Shot(false, null)
                            else -> when (val o = matcher.find(shot, labels, hit)) {
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
            ack(s, true, "pick")
            Haptics.tap(this)
            val released = kotlinx.coroutines.CompletableDeferred<Unit>()
            releaseSignal = released
            if (spot) released.complete(Unit)  // the phone stays on the spot: the countdown starts now
            picking = true
            val f = PickOverlay(this)
            frozen = f
            // Live preview of what goes if nothing is picked (runs once the picker is on screen).
            val previewJob = scope.launch(Dispatchers.Main) {
                if (hit != null || docName != null) {
                    f.preview(if (docName != null) "Finding $docName…" else "Finding the $what…", "Looking for the original", null, busy = true)
                    f.hold(true)
                }
                val plan = withTimeoutOrNull(4000) { planJob.await() } ?: Plan.Shot(false, null)
                showPlan(f, plan)
                f.hold(false)
            }
            pick = try {
                f.run(shot, released, highlight = { x, y -> targetAt(boxes, x, y)?.rect })
            } finally {
                previewJob.cancel()
                picking = false
                releaseSignal = null
            }
        }
        android.util.Log.i("TapDrop", "pick result: $pick")
        val removeFrozen: () -> Unit = { frozen?.let { f -> overlayHandler.postDelayed({ f.remove() }, 60) } }

        when (val p = pick) {
            PickOverlay.Result.Cancel -> {
                frozen?.remove()
                withContext(Dispatchers.IO) { runCatching { s.send("t" to "grab-cancel") } }
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
                            withContext(Dispatchers.IO) { runCatching { s.send("t" to "grab-cancel") } }
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
                    if (original != null) {
                        val pill = overlay.flyRegion(crop(shot, t.rect), clip(t.rect, shot), "Sending ${if (original.isVideo) "video" else "photo"}")
                        removeFrozen()
                        sendMedia(mac, original, pill)
                        return
                    }
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
        val plan = withTimeoutOrNull(4000) { planJob.await() } ?: Plan.Shot(false, null)
        if (frozen == null) {
            if (shot == null && plan is Plan.Shot) {
                if (shotResult == Shot.Secure) {
                    ack(s, false, "none", "secure")
                    Haptics.error(this)
                    overlay.notice(R.drawable.ic_close, "$appName blocks screenshots — try Text in TapDrop", error = true)
                } else {
                    ack(s, false, "none", "no-screenshot")
                }
                return
            }
            ack(s, true, if (plan is Plan.Link) "link" else "screenshot")
            Haptics.tap(this)
        }
        when (plan) {
            is Plan.Link -> {
                val host = Uri.parse(plan.url).host?.removePrefix("www.") ?: plan.url
                val pill = overlay.flyChip(R.drawable.ic_globe, host, "Opening on your Mac")
                removeFrozen()
                finish(pill, send(mac, listOf(OutItem.Text(plan.url)), host, mime = "text/x-link"), "Opened on your Mac")
            }
            is Plan.Text -> {
                val words = plan.text.split(Regex("\\s+")).size
                val pill = overlay.flyChip(R.drawable.ic_text, "$words words", "Copying text to your Mac")
                removeFrozen()
                finish(pill, send(mac, listOf(OutItem.Text(plan.text, clip = true)), "Text · $appName"), "Text copied on your Mac")
            }
            is Plan.Media -> {
                val what2 = if (plan.media.isVideo) "video" else "photo"
                val pill = if (shot != null) overlay.flyScreenshot(shot, "Sending $what2")
                else withContext(Dispatchers.IO) { matcher.thumbnail(plan.media, 720) }?.let { overlay.flyCard(it, "Sending $what2") }
                    ?: overlay.notice(R.drawable.ic_arrow_up, "Sending $what2")
                removeFrozen()
                sendMedia(mac, plan.media, pill)
            }
            is Plan.File -> {
                val pill = overlay.flyChip(R.drawable.ic_text, plan.file.name, "Sending ${plan.file.name}")
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
            is Plan.Link -> f.preview(Uri.parse(plan.url).host?.removePrefix("www.") ?: "Web page", "Opens on your Mac", null)
            is Plan.Text -> f.preview("Text on screen", "${plan.text.split(Regex("\\s+")).size} words → your Mac's clipboard", null)
            is Plan.File -> f.preview(plan.file.name, "File · ${fmtSize(plan.file.size)}", null)
            is Plan.Media -> {
                val t = matcher.thumbnail(plan.media, 160)
                f.preview(plan.media.name, if (plan.media.isVideo) "Video · the original file" else "Photo · the original file", t)
            }
            is Plan.Shot -> f.preview(
                if (plan.ocr) "Text from the screen" else "Screenshot",
                plan.why ?: if (plan.ocr) "Read by your Mac" else "The whole screen, as you see it",
                null,
            )
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
            val pill = overlay.flyRegion(crop, r, if (words == 1) "Copying to your Mac" else "Copying $words words to your Mac")
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
                if (st is TState.Working && st.total > 3_000_000) st.progress?.let { pill.update("Sending ${h.name} · ${(it * 100).toInt()}%") }
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
                if (st is TState.Working && st.total > 3_000_000) st.progress?.let { pill.update("Sending $what · ${(it * 100).toInt()}%") }
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
        android.util.Log.i("TapDrop", "sending $label (${items.size} item(s))")
        app.transfers.send(mac, items, label, preview, mime).join()
        android.util.Log.i("TapDrop", "send finished: ${app.transfers.state.value::class.simpleName}")
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
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
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

    // MARK: motion gate (a phone lying on the desk won't send when something else covers the notch)

    /**
     * The Mac's magnets (speakers, the trackpad's haptic motor, MagSafe) are strong and right under its
     * case: touching the spot the user found makes the field jump from ~45 µT (Earth) to hundreds.
     * Field strength doesn't depend on how the phone is turned, so this works in any light, any grip.
     */
    private val magnet = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val peak = app.prefs.spotPeak.value
            if (peak <= 0f) return
            val b = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            val threshold = spotThreshold(peak)
            if (atSpot) {
                if (b < threshold * 0.6f) atSpot = false  // moved away: ready for the next tap
                return
            }
            if (b < threshold) return
            atSpot = true
            val s = session ?: return
            val mac = app.prefs.macs.value.firstOrNull() ?: return
            val now = SystemClock.uptimeMillis()
            if (picking || app.transfers.busy || now - lastGrab < 2500) return
            // Brought to the Mac just now (not, say, left lying on the palm rest).
            if (sensorAlive && now - lastMotion > 4000) return
            android.util.Log.i("TapDrop", "tap spot: %.0f µT (threshold %.0f)".format(b, threshold))
            scope.launch { onGrab(s, mac, spot = true) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startMotion() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.unregisterListener(motion)
        // ~15 readings a second each, delivered as they happen (only while the screen is on).
        lastSampleNs = 0L
        sm.registerListener(motion, acc, SensorManager.SENSOR_DELAY_UI)
        atSpot = false
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(magnet, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun stopMotion() {
        getSystemService(SensorManager::class.java)?.unregisterListener(motion)
        getSystemService(SensorManager::class.java)?.unregisterListener(magnet)
        sensorAlive = false
    }

    companion object {
        /** A tap fires a little before the strongest point, so it's quick but still needs real contact. */
        fun spotThreshold(peak: Float): Float = maxOf(120f, peak * 0.5f)

        /** Weakest spot worth using: well clear of Earth's field (25–65 µT) and turning the phone in it. */
        const val MIN_SPOT = 150f

        @Volatile var instance: NotchTapService? = null
            private set

        private val _running = MutableStateFlow(false)
        /** The accessibility service is enabled and connected. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _live = MutableStateFlow(false)
        /** A live link to the Mac is open (screen on, Mac reachable). */
        val live: StateFlow<Boolean> = _live.asStateFlow()

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, NotchTapService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
