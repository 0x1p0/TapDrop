package app.tapdrop.tap

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import app.tapdrop.R
import app.tapdrop.net.Received
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Debug builds only: drives every overlay state from adb so each one can be photographed.
 *
 *     adb shell am broadcast -a app.tapdrop.debug.OVERLAY --es do <command> [--es key value ...]
 *
 * - `island --es state <s>`: sending, progress, compact, sent, failed, received, received-link, received-text,
 *   received-many, code, code-done, code-failed, notice, notice-error, notice-long, collapse.
 * - `fly --es kind <k>`: screenshot, region, words, link, text, file, card; `--es then sent|failed|progress|none`,
 *   `--ei after <ms>` before the result.
 * - `pick --es plan <p>`: link, text, file, photo, video, screen, ocr, finding; `--ei window <ms>` (countdown),
 *   `--ei release <ms>` (when the phone "leaves"; -1 never), `--es then fly|none`. Touch it with
 *   `adb shell input motionevent DOWN|MOVE|UP x y`.
 * - `clock --ef scale <f>` (0 stops every overlay animation, 1 is normal); `--ei after <ms>` applies it later.
 * - `hole --ez on true|false`: draw the front camera (screenshots never show the real lens). On by default.
 * - `clear`: everything off the screen at once.
 *
 * Registered with the DUMP permission, so only the shell (adb) can send it.
 */
internal object OverlayLab {
    private const val ACTION = "app.tapdrop.debug.OVERLAY"
    private var installed = false
    private val scope = MainScope()
    private var overlay: SendOverlay? = null
    private var picker: PickOverlay? = null
    private var pill: SendOverlay.Pill? = null
    private var job: Job? = null
    /** When something was last taken off the screen (a new picker waits until it has gone). */
    private var clearedAt = 0L

    @JvmStatic
    fun install(svc: AccessibilityService) {
        if (installed) return
        installed = true
        OverlayLabFlags.showHole = true
        svc.applicationContext.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = handle(i)
            },
            IntentFilter(ACTION),
            "android.permission.DUMP",
            null,
            Context.RECEIVER_EXPORTED,
        )
        Log.i("TapDrop", "overlay lab ready ($ACTION)")
    }

    private fun svc(): AccessibilityService? = NotchTapService.instance

    private fun send(): SendOverlay? {
        val s = svc() ?: return null
        return overlay ?: SendOverlay(s).also { overlay = it }
    }

    private fun handle(i: Intent) {
        val cmd = i.getStringExtra("do") ?: return
        Log.i("TapDrop", "overlay lab: $cmd ${i.extras?.keySet()?.joinToString()}")
        when (cmd) {
            "hole" -> OverlayLabFlags.showHole = i.getBooleanExtra("on", true)
            "clock" -> {
                val f = i.getFloatExtra("scale", 1f)
                val after = i.getIntExtra("after", 0)
                scope.launch {
                    if (after > 0) delay(after.toLong())
                    OverlayClock.scale = f
                }
            }
            "island" -> island(i.getStringExtra("state") ?: "sending", i)
            "fly" -> fly(i.getStringExtra("kind") ?: "screenshot", i)
            "pick" -> pick(i)
            "dismiss" -> pill?.let { it.finish(true, "Done", 0) }
            "clear" -> {
                job?.cancel()
                OverlayClock.scale = 1f
                picker?.let {
                    it.remove()
                    clearedAt = android.os.SystemClock.uptimeMillis()
                }
                picker = null
                overlay?.clearNow()
            }
        }
    }

    // MARK: island states

    private fun island(state: String, i: Intent) {
        val o = send() ?: return
        job?.cancel()
        job = scope.launch {
            val shot = screenshot()
            when (state) {
                "sending" -> pill = o.sending("Sending a screenshot", shot)
                "progress" -> {
                    val p = o.sending("Sending video", photo())
                    pill = p
                    delay(500)
                    p.progress(i.getFloatExtra("p", 0.42f))
                }
                "compact" -> {
                    val p = o.sending("Sending video", photo())
                    pill = p
                    var v = 0.05f
                    repeat(28) {
                        delay(100)
                        v = (v + 0.013f).coerceAtMost(i.getFloatExtra("p", 0.42f))
                        p.progress(v)
                    }
                }
                "sent" -> {
                    val p = o.sending("Sending a screenshot", shot)
                    pill = p
                    delay(700)
                    p.finish(true, "Screenshot sent", 60_000)
                }
                "failed" -> {
                    val p = o.sending("Sending a screenshot", shot)
                    pill = p
                    delay(700)
                    p.finish(false, "Can't reach MacBook Air", 60_000)
                }
                // Titles and subtitles exactly as Transfers.describe() words them.
                "received" -> o.received("Received", "IMG_2041.jpg", photo(), listOf(Received(Uri.parse("content://media/external/images/media/1"), "IMG_2041.jpg", "image/jpeg")))
                "received-file" -> o.received("Received", "Quarterly report.pdf", null, listOf(Received(Uri.parse("content://media/external/downloads/1"), "Quarterly report.pdf", "application/pdf")))
                "received-link" -> o.received("Link copied", "https://developer.apple.com/design/human-interface-guidelines/", null, listOf(Received(null, "link", "text/plain", "https://developer.apple.com/design/human-interface-guidelines/")))
                "received-text" -> o.received("Text copied", "Meet at the north entrance at 7, I'll bring the tickets", null, listOf(Received(null, "text", "text/plain", "Meet at the north entrance at 7, I'll bring the tickets")))
                "received-many" -> o.received("Received 3 photos", "Saved to Photos", photo(), List(3) { Received(Uri.parse("content://x/$it"), "p$it.jpg", "image/jpeg") })
                "code" -> pill = o.code("482913", "HDFCBK")
                "code-done" -> {
                    val p = o.code("482913", "HDFCBK")
                    pill = p
                    delay(600)
                    p.finish(true, "Code 482 913 on your Mac", 60_000)
                }
                "code-failed" -> {
                    val p = o.code("482913", "HDFCBK")
                    pill = p
                    delay(600)
                    p.finish(false, "Couldn't reach your Mac", 60_000)
                }
                "notice" -> pill = o.notice(R.drawable.ic_close, "Open what you want to send, then tap")
                "notice-error" -> pill = o.notice(R.drawable.ic_close, "Chase blocks screenshots — try Text in TapDrop", error = true)
                "notice-long" -> pill = o.notice(R.drawable.ic_close, "To send files, turn on File access in TapDrop", error = true)
                "collapse" -> pill?.finish(true, "Sent", 0)
            }
        }
    }

    // MARK: flights

    private fun fly(kind: String, i: Intent) {
        val o = send() ?: return
        job?.cancel()
        job = scope.launch {
            val shot = screenshot() ?: return@launch
            val p = flyNow(o, kind, shot, null)
            pill = p
            result(p, i)
        }
    }

    private fun flyNow(o: SendOverlay, kind: String, shot: Bitmap, rect: Rect?): SendOverlay.Pill = when (kind) {
        "region" -> {
            val r = rect ?: Rect(shot.width / 8, shot.height * 3 / 10, shot.width * 7 / 8, shot.height * 4 / 10)
            o.flyRegion(Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height()), r, "Copying 12 words to your Mac")
        }
        "words" -> {
            val r = rect ?: Rect(shot.width / 6, shot.height * 3 / 10, shot.width * 2 / 3, shot.height * 33 / 100)
            o.flyRegion(Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height()), r, "Copying to your Mac", "Connected devices")
        }
        "link" -> o.flyChip(SendOverlay.Chip.LINK, "developer.android.com", "Opening on your Mac")
        "text" -> o.flyChip(SendOverlay.Chip.TEXT, "212 words", "Copying text to your Mac")
        "file" -> o.flyChip(SendOverlay.Chip.FILE, "Quarterly report.pdf", "Sending Quarterly report.pdf")
        "card" -> o.flyCard(photo(), "Sending photo")
        else -> o.flyScreenshot(shot, "Sending a screenshot")
    }

    private suspend fun result(p: SendOverlay.Pill, i: Intent) {
        val then = i.getStringExtra("then") ?: "sent"
        delay(i.getIntExtra("after", 900).toLong())
        when (then) {
            "sent" -> p.finish(true, "Screenshot sent", i.getIntExtra("hold", 1700).toLong())
            "failed" -> p.finish(false, "Can't reach MacBook Air", i.getIntExtra("hold", 3400).toLong())
            "progress" -> {
                var v = 0f
                repeat(30) {
                    delay(90)
                    v += 0.031f
                    p.progress(v.coerceAtMost(1f))
                }
                p.finish(true, "Video sent")
            }
        }
    }

    // MARK: the picker

    private fun pick(i: Intent) {
        val s = svc() ?: return
        val o = send() ?: return
        job?.cancel()
        val old = picker
        picker = null
        if (old != null) {
            old.remove()
            clearedAt = android.os.SystemClock.uptimeMillis()
        }
        o.clearNow()
        job = scope.launch {
            // Let an earlier picker leave the screen first, so the new one freezes the app and not the old picker.
            val wait = clearedAt + 1200 - android.os.SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            val shot = screenshot() ?: return@launch Unit.also { Log.w("TapDrop", "overlay lab: no screenshot") }
            val boxes = ScreenText.boxes(appRoot(s))
            val released = CompletableDeferred<Unit>()
            val f = PickOverlay(s)
            picker = f
            val plan = i.getStringExtra("plan") ?: "link"
            val thumb = photo()
            launch {
                f.preview(
                    when (plan) {
                        "text" -> PickOverlay.Plan(PickOverlay.Kind.TEXT, "212 words, to your Mac's clipboard")
                        "file" -> PickOverlay.Plan(PickOverlay.Kind.FILE, "Quarterly report.pdf · 2.4 MB")
                        "photo" -> PickOverlay.Plan(PickOverlay.Kind.PHOTO, "IMG_20261104_181203.jpg", thumb)
                        "video" -> PickOverlay.Plan(PickOverlay.Kind.VIDEO, "IMG_20261104_181203.mp4", thumb)
                        "screen" -> PickOverlay.Plan(PickOverlay.Kind.SCREEN)
                        "screen-note" -> PickOverlay.Plan(PickOverlay.Kind.SCREEN, note = "Couldn't find this photo in your gallery")
                        "ocr" -> PickOverlay.Plan(PickOverlay.Kind.SCREEN_TEXT)
                        "finding" -> PickOverlay.Plan(PickOverlay.Kind.PHOTO, finding = true)
                        else -> PickOverlay.Plan(PickOverlay.Kind.LINK, "developer.android.com")
                    },
                )
                if (plan == "finding") f.hold(true)
                val rel = i.getIntExtra("release", 400)
                if (rel >= 0) {
                    delay(rel.toLong())
                    released.complete(Unit)
                }
            }
            val r = f.run(
                shot, released,
                highlight = { x, y -> target(boxes, x, y, shot) },
                windowMs = i.getIntExtra("window", 1000).toLong(),
            )
            Log.i("TapDrop", "overlay lab: pick → $r")
            if (i.getStringExtra("then") == "none") return@launch
            val p = when (r) {
                PickOverlay.Result.Cancel -> {
                    f.remove()
                    return@launch
                }
                is PickOverlay.Result.Tap -> {
                    val tg = target(boxes, r.x, r.y, shot)
                    val t = tg?.rect?.let { Rect(it) } ?: Rect(r.x - shot.width / 4, r.y - shot.width / 4, r.x + shot.width / 4, r.y + shot.width / 4)
                    t.intersect(0, 0, shot.width, shot.height)
                    val words = tg?.text
                    if (words != null) {
                        o.flyRegion(Bitmap.createBitmap(shot, t.left, t.top, t.width(), t.height()), t, "Copying to your Mac", words)
                    } else {
                        flyNow(o, "region", shot, t)
                    }
                }
                is PickOverlay.Result.Region -> {
                    val t = Rect(r.rect)
                    t.intersect(0, 0, shot.width, shot.height)
                    flyNow(o, "region", shot, t)
                }
                PickOverlay.Result.Timeout -> flyNow(
                    o,
                    when (plan) {
                        "text" -> "text"
                        "file" -> "file"
                        "link" -> "link"
                        else -> "screenshot"
                    },
                    shot, null,
                )
            }
            pill = p
            delay(60)
            f.remove()
            result(p, i)
        }
    }

    /** Like the service's targetAt (files aside): the smallest box with words under the point, else a big one. */
    private fun target(boxes: List<ScreenText.Box>, x: Int, y: Int, shot: Bitmap): PickOverlay.Target? {
        val area = shot.width.toLong() * shot.height
        val hits = boxes.filter { it.rect.contains(x, y) && it.area < area * 0.85 }
        hits.filter { !it.text.isNullOrBlank() }.minByOrNull { it.area }?.let { return PickOverlay.Target(it.rect, it.text) }
        val min = shot.width / 9
        return hits.filter { it.rect.width() >= min && it.rect.height() >= min }.minByOrNull { it.area }?.let { PickOverlay.Target(it.rect) }
    }

    private fun appRoot(s: AccessibilityService) = runCatching { s.windows }.getOrNull().orEmpty().firstOrNull { w ->
        w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root?.packageName?.toString() != s.packageName
    }?.root ?: s.rootInActiveWindow

    // MARK: pictures

    private suspend fun screenshot(): Bitmap? {
        val s = svc() ?: return null.also { Log.w("TapDrop", "overlay lab: no service") }
        return suspendCancellableCoroutine { cont ->
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val b = runCatching { Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    hb.close()
                    if (cont.isActive) cont.resume(b)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w("TapDrop", "overlay lab: screenshot failed ($errorCode)")
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    /** A stand-in photo (a dusk sky over water) when the device has none. */
    private fun photo(): Bitmap {
        val w = 600
        val h = 800
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, intArrayOf(0xFF1B2A6B.toInt(), 0xFF6A4BC4.toInt(), 0xFFF08A6C.toInt(), 0xFFFFC98B.toInt()), floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
        p.shader = RadialGradient(w * 0.62f, h * 0.6f, w * 0.16f, 0xFFFFF1C9.toInt(), 0x00FFE0A0, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.62f, h * 0.6f, w * 0.16f, p)
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), intArrayOf(0xFF2C2A5C.toInt(), 0xFF0E1230.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0x55FFD7A0
        for (k in 0 until 14) {
            val y = h * 0.64f + k * 13f
            val half = (w * 0.12f) * (1f - k / 16f)
            c.drawRect(w * 0.62f - half, y, w * 0.62f + half, y + 3f, p)
        }
        return b
    }
}
