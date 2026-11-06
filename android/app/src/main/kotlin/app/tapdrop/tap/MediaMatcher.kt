package app.tapdrop.tap

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import app.tapdrop.core.TimeHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds the library original of the photo/video on screen, in any app.
 *
 * Signals, strongest first: the player's total length, the date/time the viewer shows, how recently the
 * file arrived, and a perceptual hash (dHash) of what's on screen against thumbnails and — for videos —
 * several frames, so a video can be recognised mid-playback. Heavy work is pre-computed in the background.
 */
class MediaMatcher(
    private val ctx: Context,
    /** How many of the library's videos are fingerprinted (done, total), as the index grows. */
    private val onIndex: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** Fingerprinting waits while this is true: a tap in progress, or a warm phone (which dims its screen). */
    private val busy: () -> Boolean = { false },
    /** (done, total) for the photo index, like [onIndex] for videos. */
    private val onPhotos: (done: Int, total: Int) -> Unit = { _, _ -> },
    /** Fingerprinting is waiting (the phone is warm, or a tap is happening), or going again. */
    private val onPaused: (Boolean) -> Unit = {},
) {
    /** Waits while [busy], saying so, so the progress doesn't look stuck. */
    private fun restWhileBusy() {
        if (!busy()) return
        onPaused(true)
        while (busy()) Thread.sleep(5_000)
        onPaused(false)
    }

    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "TapDrop index worker").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

    /**
     * Runs [block] for at most [ms]. A damaged file can make Android's decoder hang for good; then it's given up on
     * (null, recorded as unreadable) and the rest carry on.
     */
    private fun <T> limited(ms: Long, block: () -> T?): T? {
        val f = worker.submit<T?> { block() }
        return try {
            f.get(ms, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            f.cancel(true)
            null
        }
    }
    data class Media(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val takenMs: Long,
        val addedMs: Long,
        val isVideo: Boolean,
        val durationMs: Long,
    )

    sealed interface Outcome {
        data class Found(val media: Media) : Outcome
        /** No (or only "selected photos") access to the gallery. */
        data object NoAccess : Outcome
        data object NotFound : Outcome
    }

    /** A fingerprint: a 256-bit dHash (16 × 16 brightness steps) and the picture's shape. */
    private class Print(val bits: LongArray, val aspect: Float, val video: Boolean = false)

    private val thumbs = ConcurrentHashMap<Uri, Print>()
    private val frames = ConcurrentHashMap<Uri, List<Print>>()
    private val denseFrames = ConcurrentHashMap<Uri, List<Print>>()
    @Volatile private var recent: List<Media> = emptyList()
    @Volatile private var recentAt = 0L
    @Volatile private var videosList: List<Media> = emptyList()
    @Volatile private var videosAt = 0L
    @Volatile private var lastWarm = 0L

    /** What the last [find] looked at and why it chose what it did (for the Mac's tap log). */
    @Volatile var lastNote: String = ""
        private set

    fun hasAccess(): Boolean =
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Pre-computes fingerprints (recent photos + video frames) so a tap matches instantly. */
    suspend fun warm() = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        // (lastWarm 0 = never: right after a phone boots its clock is under 45 s too.)
        if (!hasAccess() || (lastWarm != 0L && now - lastWarm < 45_000)) return@withContext
        lastWarm = now
        val all = recentMedia(force = true)
        all.take(160).forEach { thumb(it) }
        // Every video is fingerprinted in the background (new ones too): nothing is decoded here, where it would
        // compete with a tap's own search for the phone's video decoder.
        index(recentVideos(force = true))
        gridVideos()
        indexPhotos()
    }

    suspend fun latest(): Media? = withContext(Dispatchers.IO) { if (hasAccess()) recentMedia(force = true).firstOrNull() else null }

    /**
     * [allowVideos]: the library's videos may be what's on screen (a gallery, a chat, a video player, or a video
     * playing). Elsewhere (a social feed, a browser) its videos aren't there, so only an unmistakable photo counts.
     */
    suspend fun find(
        screen: Bitmap, labels: List<String>, hit: MediaDetector.Hit?, allowVideos: Boolean = true, picked: Boolean = false,
        gallery: Boolean = false,
    ): Outcome =
        withContext(Dispatchers.Default) {
        if (!hasAccess()) return@withContext Outcome.NoAccess
        val all = recentMedia()
        if (all.isEmpty()) return@withContext Outcome.NotFound
        val now = System.currentTimeMillis()
        val video = hit?.kind == MediaDetector.Kind.VIDEO

        val time = TimeHints.parse(labels)
        val byTime = time?.let { h ->
            all.filter { abs(it.takenMs - h.timeMs) <= h.windowMs || abs(it.addedMs - h.timeMs) <= h.windowMs }
        }.orEmpty()
        val byLength = hit?.durationMs?.let { d -> all.filter { it.isVideo && it.durationMs > 0 && abs(it.durationMs - d) <= 1500 } }.orEmpty()
        val strong = (byLength + byTime).distinctBy { it.uri }.filter { !video || it.isVideo }

        // Length + time agree on exactly one video → that's it, no pixels needed.
        if (video && byLength.size == 1) return@withContext Outcome.Found(byLength[0])
        // (A time alone only counts with a picture on screen, or in a gallery: a chat or an app showing "9:55 PM" says
        // nothing about a photo taken that minute.)
        if (strong.size == 1 && (byLength.isNotEmpty() || (time?.windowMs == 61_000L && (hit != null || gallery)))) {
            return@withContext Outcome.Found(strong[0])
        }

        val small = Bitmap.createScaledBitmap(screen, max(9, screen.width / 4), max(8, screen.height / 4), true)
        // With a picture on screen, only the picture is compared, never the app around it: a chat or a feed looks just
        // like a screenshot of that app someone saved or forwarded, which must never stand in for the picture. (A
        // picture filling the screen is the screen, so then the whole screen counts too.)
        val hitShare = hit?.rect?.let { r -> r.width().toFloat() * r.height() / (screen.width.toFloat() * screen.height) } ?: 0f
        val onlyPicture = hit != null && hitShare < 0.85f
        val bands = buildList {
            hit?.rect?.let { r ->
                add(Rect(r.left / 4, r.top / 4, r.right / 4, r.bottom / 4))
                // …and a little inside it, in case its box takes in a border or a shadow.
                val ix = r.width() / 40
                val iy = r.height() / 40
                add(Rect((r.left + ix) / 4, (r.top + iy) / 4, (r.right - ix) / 4, (r.bottom - iy) / 4))
            }
            add(Rect(0, 0, small.width, small.height))
            add(Rect(0, (small.height * 0.08f).toInt(), small.width, (small.height * 0.88f).toInt()))
        }
        // Photos only against the picture's own box(es); videos against the whole screen too: players draw a video
        // centred on a full-screen surface whose box isn't always where the picture is, and a video file can't be a
        // saved screenshot of the app.
        val hitBands = if (hit != null) 2 else 0
        fun bandsFor(p: Print) = if (onlyPicture && !p.video) 0 until hitBands else bands.indices
        // A picture shaped like a phone screen (a screenshot, saved or forwarded) only counts against a region of that
        // shape: the picture on screen has to be one.
        val screenShape = screen.width.toFloat() / screen.height
        val hitShape = hit?.rect?.let { r -> r.width().toFloat() / max(1, r.height()) }
        fun screenShaped(a: Float) = abs(a - screenShape) / screenShape < 0.05f || abs(a - 1 / screenShape) * screenShape < 0.05f
        fun ruledOut(p: Print) = onlyPicture && screenShaped(p.aspect) && hitShape != null && abs(hitShape - p.aspect) / p.aspect > 0.12f
        // The screen's hash per band and media shape, computed once (the index compares thousands of frames).
        val memo = HashMap<Long, LongArray?>()
        fun score(p: Print) = if (ruledOut(p)) BITS else bandsFor(p).minOf { i ->
            val k = (i.toLong() shl 32) or (p.aspect * 100).toInt().toLong()
            val here = memo.getOrPut(k) { regionHash(small, bands[i], (k and 0xffffffffL).toFloat() / 100) }
            if (here == null) BITS else dist(p.bits, here)
        }
        // Everything below reads thumbnails and decodes video frames: it stops at the budget, best effort so far.
        val start = SystemClock.elapsedRealtime()
        fun inTime(ms: Long = BUDGET_MS) = SystemClock.elapsedRealtime() - start < ms
        val until = start + BUDGET_MS
        // Distances are of 256 bits: the same picture scores under ~30, unrelated ones ~100 or more.
        val good = 28

        // 0) the video index: about a frame a second of every indexed video, compared at once (no decoding).
        val indexed = if (allowVideos) indexBest(recentVideos()) { score(it) } else null
        // A video on screen that the index clearly knows: that's it, at once. (Nothing below is needed, and decoding
        // videos while the phone is busy can take longer than a tap may wait.)
        if (video && indexed != null && indexed.second <= 40 && indexed.third - indexed.second >= 16) {
            lastNote = "video on screen · closest indexed frame ${indexed.first.name} ${indexed.second} " +
                "(next video ${indexed.third}) · index ${indexEntries.size}/${recentVideos().size} videos → " +
                "${indexed.first.name} in ${SystemClock.elapsedRealtime() - start} ms"
            return@withContext Outcome.Found(indexed.first)
        }

        // 0b) the video grids: which video is on screen, and about where in it, at any moment; then the second
        // around that moment decoded, so only a frame that truly matches the screen sends it.
        val quarter by lazy { VideoGrids.quarter(screen) }
        fun screenGrid(band: Rect, aspect: Float, inset: Float): ByteArray? {
            val (lum, qw, qh) = quarter
            val b = Rect(band)
            if (!b.intersect(0, 0, qw, qh) || b.height() == 0) return null
            val (w, h) = if (b.width().toFloat() / b.height() > aspect) (b.height() * aspect).toInt() to b.height()
            else b.width() to (b.width() / aspect).toInt()
            val x = b.left + (b.width() - w) / 2
            val y = b.top + (b.height() - h) / 2
            return VideoGrids.gridOf(lum, qw, qh, Rect(x, y, x + w, y + h), inset)
        }
        var gridNote = ""
        fun byGrids(strict: Boolean): Media? {
            val videos = recentVideos()
            if (grids.size == 0) return null
            val gridBands = (if (hit != null) listOf(bands[0], bands[2]) else listOf(bands[0])).distinct()
            val best = HashMap<String, Pair<VideoGrids.Ranked, Rect>>()
            for (band in gridBands) {
                for (r in grids.rank(videos, ::key) { a -> screenGrid(band, a, VideoGrids.INSET_FULL) }) {
                    if ((best[r.uri]?.first?.score ?: -2f) < r.score) best[r.uri] = r to band
                }
            }
            val ranked = best.values.sortedByDescending { it.first.score }
            val byUri = videos.associateBy { it.uri.toString() }
            val (top, topBand) = ranked.firstOrNull() ?: return null
            val tm = byUri[top.uri] ?: return null
            // The next video that isn't a copy of it (WhatsApp keeps a received video and, once forwarded, a copy).
            val next = ranked.drop(1).firstOrNull { (r, _) ->
                byUri[r.uri]?.let { abs(it.durationMs - tm.durationMs) > 500 } ?: false
            }
            gridNote = " · closest video moment ${tm.name} at %.1f s %.2f (next %.2f)".format(
                top.atMs / 1000f, top.score, next?.first?.score ?: -1f,
            )
            if (top.score < GRID_TRY) return null
            val confirmBy = start + BUDGET_MS + 2_000
            fun confirm(r: VideoGrids.Ranked, band: Rect): Float? {
                val m = byUri[r.uri] ?: return null
                val full = screenGrid(band, r.aspect, VideoGrids.INSET_FULL) ?: return null
                return grids.confirm(m.uri, r.atMs, full, screenGrid(band, r.aspect, VideoGrids.INSET_MIDDLE), confirmBy)
            }
            val c1 = confirm(top, topBand)
            gridNote += c1?.let { " · its frames there %.2f".format(it) } ?: " · couldn't decode it in time"
            val need = if (strict) CONFIRM_STRICT else CONFIRM_MIN
            if (c1 != null && c1 >= need) {
                // A close second video gets the same look; the first has to stay clearly ahead.
                val c2 = next?.takeIf { it.first.score >= top.score - GRID_MARGIN }?.let { (r, band) -> confirm(r, band) }
                c2?.let { gridNote += " · next video's frames %.2f".format(it) }
                return tm.takeIf { c2 == null || c2 <= c1 - CONFIRM_MARGIN }
            }
            // Not decoded in time: only an unmistakable grid match.
            return tm.takeIf {
                c1 == null && !strict && top.score >= GRID_ALONE && top.score - (next?.first?.score ?: -1f) >= GRID_MARGIN
            }
        }
        if (video && allowVideos) byGrids(strict = false)?.let { m ->
            lastNote = "video on screen$gridNote · video grids ${grids.size}/${recentVideos().size} → ${m.name} in " +
                "${SystemClock.elapsedRealtime() - start} ms"
            return@withContext Outcome.Found(m)
        }

        // 1) thumbnails: cheap, mostly cached
        val pool = (strong + all.filter { if (video) it.isVideo else allowVideos || !it.isVideo }.take(if (video) 60 else 160))
            .distinctBy { it.uri }
        val scored = mutableListOf<Pair<Media, Int>>()
        // Every photo in the library, from the photo index (fingerprinted in the background): compared in memory.
        if (!video) {
            loadPhotos()
            val inPool = pool.mapTo(HashSet()) { it.uri }
            for (e in photoIndex.values) {
                val p = e.print ?: continue
                if (e.media.uri in inPool) continue
                scored += e.media to score(p)
            }
        }
        for (m in pool) {
            if (!inTime(BUDGET_MS / 3)) break
            thumb(m)?.let { scored += m to score(it) }
        }
        fun note(m: Media, d: Int) {
            val i = scored.indexOfFirst { it.first.uri == m.uri }
            if (i >= 0) scored[i] = m to minOf(scored[i].second, d) else scored += m to d
        }

        // A picture the user tapped in the picker: chats show photos cropped to their bubble, so also compare the
        // newest photos cropped the way the bubble crops them (centre crop to its shape).
        if (picked) hit?.rect?.let { r ->
            val band = Rect(r.left / 4, r.top / 4, r.right / 4, r.bottom / 4)
            band.intersect(0, 0, small.width, small.height)
            val here = if (band.width() >= 17 && band.height() >= 16) dHash(small, band.left, band.top, band.width(), band.height()) else null
            if (here != null) {
                val shape = band.width().toFloat() / band.height()
                for (m in all.filter { !it.isVideo }.take(80)) {
                    if (!inTime(BUDGET_MS / 2)) break
                    val t = thumbnail(m, 256) ?: continue
                    val (cw, ch) = if (t.width.toFloat() / t.height > shape) (t.height * shape).toInt() to t.height else t.width to (t.width / shape).toInt()
                    if (cw >= 17 && ch >= 16) {
                        // dHash looks at the middle 70 % of what it's given, the same as for the screen's band.
                        dHash(t, (t.width - cw) / 2, (t.height - ch) / 2, cw, ch)?.let { note(m, dist(it, here)) }
                    }
                    t.recycle()
                }
            }
        }

        // The player shows where it is: the exact frame there, for the videos of the right length.
        var atFrame: Pair<Media, Int>? = null
        hit?.positionMs?.takeIf { video && byLength.isNotEmpty() }?.let { pos ->
            for (m in byLength.filter { pos <= it.durationMs }.take(3)) {
                if (!inTime()) break
                val d = frameAt(m, pos)?.let { score(it) } ?: continue
                if (atFrame == null || d < atFrame!!.second) atFrame = m to d
                if (d <= good) break
            }
        }

        // 2) videos: a few frames each for the likeliest (strong signals, closest thumbnails, newest), then about one a
        // second for the closest two — stopping as soon as one is clearly it.
        val thumbBest = scored.minOfOrNull { it.second } ?: BITS
        if (video && (atFrame?.second ?: BITS) > good && thumbBest > good) {
            val focus = (strong + scored.sortedBy { it.second }.take(5).map { it.first } +
                all.filter { it.isVideo }.take(5)).distinctBy { it.uri }.filter { it.isVideo }
            val coarse = mutableListOf<Pair<Media, Int>>()
            for (m in focus) {
                if (!inTime()) break
                val f = videoFrames(m, dense = false, until)
                if (f.isEmpty()) continue
                val d = f.minOf { score(it) }
                coarse += m to d
                note(m, d)
                if (d <= good) break
            }
            if (coarse.none { it.second <= good }) {
                for ((m, _) in coarse.sortedBy { it.second }.take(2)) {
                    if (!inTime()) break
                    val f = videoFrames(m, dense = true, until)
                    if (f.isNotEmpty()) note(m, f.minOf { score(it) })
                }
            }
        }

        val ranked = scored.sortedBy { it.second }
        val best = ranked.firstOrNull()
        // A match has to stand out: either all but identical, or clearly closer than the next-closest picture (copies of
        // the same picture, like a chat's copy and the camera's original, don't count against each other). A near tie
        // means the screen region looks like several pictures, so it's none of them: a fresh screenshot goes instead.
        fun twins(a: Media, b: Media): Boolean {
            val pa = thumb(a) ?: return false
            val pb = thumb(b) ?: return false
            return dist(pa.bits, pb.bits) <= 16
        }
        val runnerUp = best?.let { (bm, _) -> ranked.drop(1).firstOrNull { (m, _) -> !twins(m, bm) } }
        val gap = best?.let { (runnerUp?.second ?: BITS) - it.second } ?: 0
        val standsOut = best != null && gap >= MARGIN
        // A plain picture (mostly dark or blank, text on a page) looks like many others to the fingerprint.
        val region = hit?.rect ?: Rect(0, 0, screen.width, screen.height)
        val plain = detail(small, Rect(region.left / 4, region.top / 4, region.right / 4, region.bottom / 4)) < MIN_STEPS
        val trusted = standsOut && !plain
        // Not clear-cut (a near tie, or a plain picture): a closer look at the nearest few, at 40 × 40. The one that
        // truly matches it, clearly better than the next, is it; otherwise nothing is.
        var closerNote = ""
        val closer: Media? = if (best != null && best.second <= 40 && (!standsOut || plain)) {
            val looks = ranked.filter { it.second <= 40 }.take(6).mapNotNull { (m, _) ->
                if (!inTime(BUDGET_MS + 800)) return@mapNotNull null
                val (g, aspect) = fineMedia(m) ?: return@mapNotNull null
                val s = fineScreen(screen, region, aspect) ?: return@mapNotNull null
                m to correlation(s, g)
            }.sortedByDescending { it.second }
            val top = looks.firstOrNull()
            val next = top?.let { (tm, _) -> looks.drop(1).firstOrNull { (m, _) -> !twins(m, tm) } }
            top?.let { closerNote = " · closer look ${it.first.name} %.2f (next %.2f)".format(it.second, next?.second ?: -1f) }
            top?.takeIf { it.second >= FINE_MIN && it.second - (next?.second ?: -1f) >= FINE_MARGIN }?.first
        } else null
        val bestStrong = ranked.firstOrNull { (m, _) -> strong.any { it.uri == m.uri } }
        val newest = all.firstOrNull { !video || it.isVideo }
        Log.i(
            "TapDrop",
            "match: video=$video len=${hit?.durationMs} time=${time != null} strong=${strong.size} pool=${pool.size} " +
                "best=${best?.second} strongBest=${bestStrong?.second}",
        )

        var found: Media? = when {
            atFrame != null && atFrame!!.second <= 44 -> atFrame!!.first
            // A frame of an indexed video: close, clearly closer than any other video, and at least as close as
            // anything else on screen. (Thousands of frames are compared, so a near miss isn't enough.)
            indexed != null && indexed.second <= (if (video) 40 else good) && indexed.third - indexed.second >= 16 &&
                (video || indexed.second <= 10 || indexed.second + 8 <= (best?.second ?: BITS)) -> indexed.first
            // Nothing on screen looks like a photo/video: that's an app's own screen, and a fresh screenshot is what's
            // wanted. A picture of a similar screen (an old screenshot, or one someone sent in a chat) must never stand
            // in for it. Only a gallery showing a photo full screen, unmistakably, sends the photo.
            hit == null -> best?.takeIf { gallery && it.second <= 20 && !isScreenshot(it.first) && trusted }?.first
                ?: closer?.takeIf { gallery && !isScreenshot(it) }
            best != null && best.second <= 36 && trusted -> best.first
            closer != null -> closer
            // A picture the user pointed at: a little looser, as long as it clearly beats every other photo.
            picked && best != null && best.second <= 52 && (ranked.getOrNull(1)?.second ?: BITS) - best.second >= 16 -> best.first
            bestStrong != null && bestStrong.second <= 64 -> bestStrong.first
            strong.size == 1 -> strong[0]
            // Just arrived (e.g. received in a chat a moment ago) and it looks alike → that one.
            best != null && best.second <= 52 && trusted && newest != null && best.first.uri == newest.uri &&
                now - max(newest.addedMs, newest.takenMs) < 30 * 60_000L -> best.first
            else -> null
        }
        // Chat viewers often hide a playing video's controls, so it may not have looked like one: the newest videos'
        // frames against the screen too.
        if (found == null && !video && allowVideos) found = byGrids(strict = true)
        var videoBest: Pair<Media, Int>? = null
        var videoNext = BITS
        if (found == null && !video && allowVideos) {
            // All of the newest few, so a clear winner can be told from a near tie (a plain screen looks like many).
            for (m in all.filter { it.isVideo }.take(6)) {
                if (!inTime()) break
                val f = videoFrames(m, dense = false, until)
                if (f.isEmpty()) continue
                val d = f.minOf { score(it) }
                if (videoBest == null || d < videoBest.second) {
                    videoNext = videoBest?.second ?: BITS
                    videoBest = m to d
                } else if (d < videoNext) {
                    videoNext = d
                }
            }
            // Close but not certain: about one frame a second for that video.
            videoBest?.takeIf { it.second > good && inTime() }?.let { (m, _) ->
                val f = videoFrames(m, dense = true, until)
                if (f.isNotEmpty()) videoBest = m to minOf(videoBest!!.second, f.minOf { score(it) })
            }
            videoBest?.takeIf { it.second <= (if (hit == null) good else 36) && videoNext - it.second >= MARGIN }?.let { found = it.first }
        }
        lastNote = buildString {
            append(if (video) "video on screen" else if (hit != null) "photo on screen" else "nothing photo-like on screen")
            hit?.durationMs?.let { append(", ${it / 1000}s long") } ?: if (video) append(", no length shown") else Unit
            hit?.positionMs?.let { append(" at ${it / 1000}s") }
            append(" · ${max(recentVideos().size, all.count { it.isVideo })} videos, ${max(photoIndex.size, all.count { !it.isVideo })} photos in library")
            best?.let { append(" · closest ${it.first.name} ${it.second}") }
            runnerUp?.let { append(" (next ${it.second})") }
            if (onlyPicture) append(" · picture only")
            if (plain) append(" · plain picture")
            append(closerNote)
            atFrame?.let { append(" · frame at position ${it.first.name} ${it.second}") }
            append(" · index ${indexEntries.size}/${recentVideos().size} videos")
            if (!allowVideos) append(" · videos not searched here")
            indexed?.let { append(" · closest indexed frame ${it.first.name} ${it.second} (next video ${it.third})") }
            append(gridNote)
            if (allowVideos) append(" · video grids ${grids.size}/${recentVideos().size}")
            videoBest?.let { append(" · closest video frame ${it.first.name} ${it.second} (next $videoNext)") }
            append(" → ${found?.name ?: "no match"} in ${SystemClock.elapsedRealtime() - start} ms (of 256)")
        }
        found?.let { Outcome.Found(it) } ?: Outcome.NotFound
        }

    private fun isScreenshot(m: Media) =
        m.name.startsWith("Screenshot", ignoreCase = true) || m.name.startsWith("Screen_Recording", ignoreCase = true)

    /** The library original of one picture the user tapped in the picker (e.g. a photo in a chat). */
    suspend fun findIn(screen: Bitmap, rect: Rect): Media? =
        (find(screen, emptyList(), MediaDetector.Hit(MediaDetector.Kind.IMAGE, rect, null), allowVideos = false, picked = true)
            as? Outcome.Found)?.media

    fun thumbnail(m: Media, px: Int = 200): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null) }.getOrNull()

    // MARK: library

    /** Every video, newest first (the index covers up to 400). */
    private fun recentVideos(force: Boolean = false): List<Media> {
        val now = SystemClock.elapsedRealtime()
        if (!force && videosList.isNotEmpty() && now - videosAt < 15_000) return videosList
        return queryMedia(videosOnly = true, limit = LIBRARY_LIMIT).also { videosList = it; videosAt = now }
    }

    private fun recentMedia(force: Boolean = false): List<Media> {
        val now = SystemClock.elapsedRealtime()
        if (!force && recent.isNotEmpty() && now - recentAt < 15_000) return recent
        return queryMedia(videosOnly = false, limit = 500).also { recent = it; recentAt = now }
    }

    private fun queryMedia(videosOnly: Boolean, limit: Int, imagesOnly: Boolean = false): List<Media> {
        val types = if (videosOnly) "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        else if (imagesOnly) "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
        else "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($types)")
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = mutableListOf<Media>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(
                    MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DURATION,
                ),
                args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    val added = c.getLong(6) * 1000
                    out += Media(
                        ContentUris.withAppendedId(base, c.getLong(0)),
                        c.getString(2) ?: if (isVideo) "Video" else "Photo",
                        c.getString(3) ?: if (isVideo) "video/mp4" else "image/jpeg",
                        c.getLong(4),
                        if (!c.isNull(5)) c.getLong(5) else added,
                        added,
                        isVideo,
                        if (!c.isNull(7)) c.getLong(7) else 0,
                    )
                }
            }
        }
        out.sortByDescending { max(it.addedMs, it.takenMs) }
        return out
    }

    // MARK: fingerprints

    private fun thumb(m: Media): Print? {
        thumbs[m.uri]?.let { return it.takeIf { p -> !p.aspect.isNaN() } }
        photoIndex[m.uri.toString()]?.takeIf { it.key == key(m) }?.let { return it.print }
        val t = thumbnail(m, 192) ?: return null
        val h = dHash(t, 0, 0, t.width, t.height)
        // A flat thumbnail is cached as such (NaN aspect) so it isn't loaded again, and never compared.
        val p = if (h != null) Print(h, t.width.toFloat() / t.height, m.isVideo) else Print(LongArray(WORDS), Float.NaN)
        t.recycle()
        thumbs[m.uri] = p
        return p.takeIf { h != null }
    }

    /**
     * Frames from the start (where people usually are) and across the video: 8 points, or with [dense] about one a
     * second (32 at most), so a frame from anywhere in a playing clip is close to one of them.
     */
    private fun videoFrames(m: Media, dense: Boolean, until: Long = Long.MAX_VALUE): List<Print> {
        val cache = if (dense) denseFrames else frames
        cache[m.uri]?.let { return it }
        if (m.durationMs <= 0 || SystemClock.elapsedRealtime() > until) return emptyList()
        val times = if (dense) {
            val step = max(1000L, m.durationMs / 32)
            (step until m.durationMs step step).toList()
        } else {
            listOf(0L, 600L, 1500L, 3000L) + listOf(0.2, 0.4, 0.6, 0.8).map { (m.durationMs * it).toLong() }
        }.filter { it < m.durationMs }.distinct()
        val out = mutableListOf<Print>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, m.uri)
            for (t in times) {
                if (SystemClock.elapsedRealtime() > until) return out  // out of time: use what we have, don't cache it
                val f = runCatching {
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                }.getOrNull() ?: continue
                // A black or blank frame (a fade-in, a title card) matches any dark screen: never a fingerprint.
                dHash(f, 0, 0, f.width, f.height)?.let { out += Print(it, f.width.toFloat() / f.height, video = true) }
                f.recycle()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        cache[m.uri] = out
        return out
    }

    // MARK: video index

    /** One video's fingerprint: a dHash about every second, and the frames' aspect. [key] changes if the file does. */
    private class Indexed(val key: String, val aspect: Float, val hashes: List<LongArray>)

    private val indexEntries = ConcurrentHashMap<String, Indexed>()  // media uri → fingerprint
    private val indexFile = File(ctx.filesDir, "video-index.bin")
    @Volatile private var indexLoaded = false
    @Volatile private var indexing = false

    private fun key(m: Media) = "${m.size}:${m.addedMs}:${m.durationMs}"

    /**
     * The indexed video whose frames come closest to the screen, its distance, and the next closest video's, not
     * counting copies of it (the same length to half a second: WhatsApp keeps a received video and, once forwarded,
     * a copy in Sent; either is the right file).
     */
    private fun indexBest(all: List<Media>, score: (Print) -> Int): Triple<Media, Int, Int>? {
        loadIndex()
        val each = ArrayList<Pair<Media, Int>>()
        for (m in all) {
            if (!m.isVideo) continue
            val e = indexEntries[m.uri.toString()]?.takeIf { it.key == key(m) } ?: continue
            if (e.hashes.isEmpty()) continue
            each += m to e.hashes.minOf { score(Print(it, e.aspect, video = true)) }
        }
        val best = each.minByOrNull { it.second } ?: return null
        val second = each.filter { (m, _) -> m.uri != best.first.uri && abs(m.durationMs - best.first.durationMs) > 500 }
            .minOfOrNull { it.second } ?: BITS
        return Triple(best.first, best.second, second)
    }

    /**
     * Fingerprints every video not yet in the index (newest first, up to 400), in the background at low priority,
     * saving as it goes: the keyframe nearest each second (60 points at most across longer videos), so a frame from
     * anywhere in a clip is close to one of them. Keyframes decode on their own, so a video takes well under a second.
     */
    private fun index(videos: List<Media>) {
        loadIndex()
        val eligible = videos.take(LIBRARY_LIMIT).filter { it.durationMs > 0 }
        val todo = eligible.filter { indexEntries[it.uri.toString()]?.key != key(it) }
        if (indexing) return
        if (todo.isEmpty()) return
        indexing = true
        thread(name = "TapDrop video index", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                var n = 0
                for (m in todo) {
                    if (!hasAccess()) break
                    // Go easy on the phone: decoding hundreds of videos warms it, and a warm phone caps its screen's
                    // brightness, which taps need. Wait while it's warm or a tap is happening, and rest between videos.
                    restWhileBusy()
                    // A video that can't be read (or hangs the decoder) is recorded empty, so it isn't retried (and
                    // counts as done).
                    indexEntries[m.uri.toString()] = limited(30_000) { runCatching { fingerprint(m) }.getOrNull() }
                        ?: Indexed(key(m), 1f, emptyList())
                    if (++n % 5 == 0) saveIndex()
                    Thread.sleep(250)
                }
                // Forget videos that are gone.
                val live = videos.map { it.uri.toString() }.toSet()
                if (videos.size < LIBRARY_LIMIT) indexEntries.keys.retainAll(live)
                saveIndex()
                Log.i("TapDrop", "video index: ${indexEntries.size} videos")
            } finally {
                indexing = false
            }
            gridVideos()
        }
    }

    // MARK: video grids

    private val grids = VideoGrids(ctx, File(ctx.filesDir, "video-grids.bin"))
    @Volatile private var gridding = false

    /**
     * Grids every video not yet gridded (newest first), each decoded once from start to end, in the background at
     * low priority, saving as it goes: what lets a tap find a video at any moment, not only near its keyframes.
     * (The phone's progress shows in Settings → Photos & videos for tap.)
     */
    private fun gridVideos() {
        if (gridding || indexing) return
        gridding = true
        thread(name = "TapDrop video grids", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                runCatching {
                    grids.load()
                    val videos = recentVideos(force = true).filter { it.durationMs > 0 }
                    val todo = videos.filter { !grids.has(it.uri, key(it)) }
                    var done = videos.size - todo.size
                    onIndex(done, videos.size)
                    val began = SystemClock.elapsedRealtime()
                    var n = 0
                    var unreadable = 0
                    for (m in todo) {
                        if (!hasAccess()) break
                        restWhileBusy()
                        // A long video gets longer; one that can't be read (or hangs the decoder) is recorded empty,
                        // so it isn't tried again, and the frame fingerprints still cover it.
                        val budget = 60_000L + m.durationMs / 2
                        val e = limited(budget + 5_000) {
                            runCatching { grids.build(m.uri, key(m), m.durationMs, SystemClock.elapsedRealtime() + budget) }.getOrNull()
                        }
                        if (e == null) unreadable++
                        grids.put(m.uri, e ?: VideoGrids.Entry(key(m), 0f, IntArray(0), ByteArray(0)))
                        onIndex(++done, videos.size)
                        if (++n % 25 == 0) Log.i("TapDrop", "video grids: $done of ${videos.size} (${n} in ${(SystemClock.elapsedRealtime() - began) / 1000} s, $unreadable unreadable)")
                        Thread.sleep(100)
                    }
                    if (videos.size < LIBRARY_LIMIT) grids.keepOnly(videos.map { it.uri.toString() }.toSet())
                    Log.i("TapDrop", "video grids: ${grids.size} videos")
                }.onFailure { Log.w("TapDrop", "video grids stopped", it) }
            } finally {
                gridding = false
            }
        }
    }

    // MARK: photo index

    /** One photo's fingerprint (null: too plain to compare), with what's needed to send it. */
    private class PhotoEntry(val media: Media, val key: String, val print: Print?)

    private val photoIndex = ConcurrentHashMap<String, PhotoEntry>()  // media uri → fingerprint
    private val photoFile = File(ctx.filesDir, "photo-index.bin")
    @Volatile private var photosLoaded = false
    @Volatile private var indexingPhotos = false

    /**
     * Fingerprints every photo in the library not yet in the index (newest first), in the background at low priority,
     * saving as it goes, so a tap compares the screen with the whole library at once, not just the newest photos.
     * A thumbnail each (the gallery keeps them), resting between photos and while the phone is warm or a tap runs.
     */
    private fun indexPhotos() {
        if (indexingPhotos) return
        indexingPhotos = true
        thread(name = "TapDrop photo index", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
              runCatching {
                loadPhotos()
                val photos = queryMedia(videosOnly = false, limit = LIBRARY_LIMIT, imagesOnly = true)
                val todo = photos.filter { photoIndex[it.uri.toString()]?.key != key(it) }
                var done = photos.size - todo.size
                onPhotos(done, photos.size)
                var n = 0
                for (m in todo) {
                    if (!hasAccess()) break
                    restWhileBusy()
                    // One odd file must never stop the rest (or the app): it's recorded as unmatchable.
                    val print = limited(10_000) { runCatching {
                        val t = thumbnail(m, 192) ?: return@runCatching null
                        try {
                            dHash(t, 0, 0, t.width, t.height)?.let { Print(it, t.width.toFloat() / t.height) }
                        } finally {
                            t.recycle()
                        }
                    }.getOrNull() }
                    photoIndex[m.uri.toString()] = PhotoEntry(m, key(m), print)
                    if (++n % 50 == 0) {
                        onPhotos(done + n, photos.size)
                        if (n % 500 == 0) savePhotos()
                    }
                    Thread.sleep(8)
                }
                onPhotos(photos.size, photos.size)
                // Forget photos that are gone.
                if (photos.size < LIBRARY_LIMIT) photoIndex.keys.retainAll(photos.map { it.uri.toString() }.toSet())
                savePhotos()
                Log.i("TapDrop", "photo index: ${photoIndex.size} photos")
              }.onFailure { Log.w("TapDrop", "photo index stopped", it) }
            } finally {
                indexingPhotos = false
            }
        }
    }

    private fun loadPhotos() {
        if (photosLoaded) return
        synchronized(photoIndex) {
            if (photosLoaded) return
            runCatching {
                DataInputStream(photoFile.inputStream().buffered()).use { d ->
                    if (d.readInt() != PHOTO_INDEX_VERSION) return@use
                    repeat(d.readInt()) {
                        val uri = d.readUTF()
                        val m = Media(Uri.parse(uri), d.readUTF(), d.readUTF(), d.readLong(), d.readLong(), d.readLong(), false, 0)
                        val key = d.readUTF()
                        val aspect = d.readFloat()
                        val print = if (aspect.isNaN()) null else Print(LongArray(WORDS) { d.readLong() }, aspect)
                        photoIndex[uri] = PhotoEntry(m, key, print)
                    }
                }
            }
            photosLoaded = true
        }
    }

    private fun savePhotos() = synchronized(photoFile) {
        runCatching {
            val tmp = File(photoFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                val snapshot = photoIndex.entries.toList()
                d.writeInt(PHOTO_INDEX_VERSION)
                d.writeInt(snapshot.size)
                for ((uri, e) in snapshot) {
                    d.writeUTF(uri)
                    d.writeUTF(e.media.name)
                    d.writeUTF(e.media.mime)
                    d.writeLong(e.media.size)
                    d.writeLong(e.media.takenMs)
                    d.writeLong(e.media.addedMs)
                    d.writeUTF(e.key)
                    d.writeFloat(e.print?.aspect ?: Float.NaN)
                    e.print?.bits?.forEach { d.writeLong(it) }
                }
            }
            tmp.renameTo(photoFile)
        }
    }

    private fun fingerprint(m: Media): Indexed? {
        val step = max(1000L, m.durationMs / 60)
        val times = (0L until m.durationMs step step).toList().take(61)
        val seen = HashSet<List<Long>>()
        val out = ArrayList<LongArray>(times.size)
        var aspect = 0f
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, m.uri)
            for (t in times) {
                val f = runCatching {
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                }.getOrNull() ?: continue
                aspect = f.width.toFloat() / f.height
                // Neighbouring seconds often land on the same keyframe: keep each hash once.
                dHash(f, 0, 0, f.width, f.height)?.let { if (seen.add(it.toList())) out += it }
                f.recycle()
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { r.release() }
        }
        return Indexed(key(m), aspect, out)
    }

    private fun loadIndex() {
        if (indexLoaded) return
        synchronized(indexEntries) {
            if (indexLoaded) return
            runCatching {
                DataInputStream(indexFile.inputStream().buffered()).use { d ->
                    if (d.readInt() != INDEX_VERSION) return@use
                    repeat(d.readInt()) {
                        val uri = d.readUTF()
                        val key = d.readUTF()
                        val aspect = d.readFloat()
                        val hashes = List(d.readInt()) { LongArray(WORDS) { d.readLong() } }
                        indexEntries[uri] = Indexed(key, aspect, hashes)
                    }
                }
            }
            indexLoaded = true
        }
    }

    private fun saveIndex() = synchronized(indexFile) {
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { d ->
                val snapshot = indexEntries.entries.toList()
                d.writeInt(INDEX_VERSION)
                d.writeInt(snapshot.size)
                for ((uri, e) in snapshot) {
                    d.writeUTF(uri)
                    d.writeUTF(e.key)
                    d.writeFloat(e.aspect)
                    d.writeInt(e.hashes.size)
                    e.hashes.forEach { h -> h.forEach { d.writeLong(it) } }
                }
            }
            tmp.renameTo(indexFile)
        }
    }

    /** The frame at [ms] exactly (the player's position). */
    private fun frameAt(m: Media, ms: Long): Print? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, m.uri)
            val f = r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 192, 192) ?: return null
            dHash(f, 0, 0, f.width, f.height)?.let { Print(it, f.width.toFloat() / f.height, video = true) }.also { f.recycle() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Where the media would sit if fit-centred in [band] (small-screenshot coords), then hashed. */
    /** The hash of where media of this [aspect] would sit fit-centred in [band] (null: too small, or one shade). */
    /**
     * How much real detail a region has: of the 256 neighbouring pairs the fingerprint compares, how many differ by a
     * real step. A plain picture (mostly dark, mostly white, text on a blank page) looks like many others to the
     * fingerprint, so its match needs a closer look.
     */
    private fun detail(src: Bitmap, band: Rect): Int {
        val b = Rect(band)
        if (!b.intersect(0, 0, src.width, src.height) || b.width() < 17 || b.height() < 16) return 0
        val region = Bitmap.createBitmap(src, b.left, b.top, b.width(), b.height())
        val tiny = Bitmap.createScaledBitmap(region, SIDE + 1, SIDE, true)
        var steps = 0
        for (row in 0 until SIDE) {
            var left = luma(tiny.getPixel(0, row))
            for (col in 1..SIDE) {
                val l = luma(tiny.getPixel(col, row))
                if (abs(left - l) >= STEP) steps++
                left = l
            }
        }
        if (region !== src) region.recycle()
        tiny.recycle()
        return steps
    }

    /**
     * The closer look: a picture's layout as a [FINE] × [FINE] grid of brightness, evened out (mean 0, spread 1) so
     * exposure doesn't matter. Two of these are compared by correlation: 1 is the same picture.
     */
    private fun fineGrid(src: Bitmap, x: Int, y: Int, w: Int, h: Int): FloatArray? {
        if (w < 8 || h < 8) return null
        val region = Bitmap.createBitmap(src, x, y, w, h)
        val tiny = Bitmap.createScaledBitmap(region, FINE, FINE, true)
        val g = FloatArray(FINE * FINE) { i -> luma(tiny.getPixel(i % FINE, i / FINE)).toFloat() }
        if (region !== src) region.recycle()
        tiny.recycle()
        val mean = g.average().toFloat()
        var v = 0f
        for (i in g.indices) {
            g[i] -= mean
            v += g[i] * g[i]
        }
        val sd = sqrt(v / g.size)
        if (sd < 1f) return null
        for (i in g.indices) g[i] /= sd
        return g
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return (s / a.size).toFloat()
    }

    /** The screen region at full resolution, cropped to [aspect] the way a picture of that shape shows in it, inset 5 %. */
    private fun fineScreen(screen: Bitmap, band: Rect, aspect: Float): FloatArray? {
        val b = Rect(band)
        if (!b.intersect(0, 0, screen.width, screen.height)) return null
        val (w, h) = if (b.width().toFloat() / b.height() > aspect) (b.height() * aspect).toInt() to b.height()
        else b.width() to (b.width() / aspect).toInt()
        val x = b.left + (b.width() - w) / 2 + w / 20
        val y = b.top + (b.height() - h) / 2 + h / 20
        return fineGrid(screen, x, y, w - w / 10, h - h / 10)
    }

    /** A library picture for the closer look, inset 5 % like the screen; with its shape. */
    private fun fineMedia(m: Media): Pair<FloatArray, Float>? {
        val t = thumbnail(m, 256) ?: return null
        val g = fineGrid(t, t.width / 20, t.height / 20, t.width - t.width / 10, t.height - t.height / 10)
        val aspect = t.width.toFloat() / t.height
        t.recycle()
        return g?.let { it to aspect }
    }

    private fun regionHash(screen: Bitmap, band: Rect, aspect: Float): LongArray? {
        val b = Rect(band)
        if (!b.intersect(0, 0, screen.width, screen.height)) return null
        val bw = b.width()
        val bh = b.height()
        if (bw < 17 || bh < 16) return null
        val (w, h) = if (bw.toFloat() / bh > aspect) (bh * aspect).toInt() to bh else bw to (bw / aspect).toInt()
        if (w < 17 || h < 16) return null
        return dHash(screen, b.left + (bw - w) / 2, b.top + (bh - h) / 2, w, h)
    }

    /** dHash of the central 70% of a region (ignores viewer chrome near the edges): 16 × 16 left-right steps. */
    private fun dHash(src: Bitmap, x: Int, y: Int, w: Int, h: Int): LongArray? {
        // Too small to fingerprint (a thin panorama's thumbnail can be a few pixels tall).
        if (src.width < SIDE + 1 || src.height < SIDE || w < 2 || h < 2) return null
        val cw = (w * 0.7f).toInt().coerceIn(17, src.width)
        val ch = (h * 0.7f).toInt().coerceIn(16, src.height)
        val cx = (x + (w * 0.15f).toInt()).coerceIn(0, src.width - cw)
        val cy = (y + (h * 0.15f).toInt()).coerceIn(0, src.height - ch)
        val region = Bitmap.createBitmap(src, cx, cy, cw, ch)
        val tiny = Bitmap.createScaledBitmap(region, SIDE + 1, SIDE, true)
        val bits = LongArray(WORDS)
        var bit = 0
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (row in 0 until SIDE) {
            var left = luma(tiny.getPixel(0, row))
            for (col in 1..SIDE) {
                val l = luma(tiny.getPixel(col, row))
                lo = minOf(lo, l, left)
                hi = maxOf(hi, l, left)
                if (left > l) bits[bit / 64] = bits[bit / 64] or (1L shl (bit % 64))
                left = l
                bit++
            }
        }
        if (region !== src) region.recycle()
        tiny.recycle()
        // Nearly one shade (black, white, a blank page): its bits are noise, so it can't identify anything.
        return bits.takeIf { hi - lo >= FLAT_RANGE }
    }

    private fun dist(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in 0 until WORDS) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    private fun luma(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114

    private companion object {
        /** 2: 256-bit fingerprints (a file from version 1 is rebuilt). */
        const val INDEX_VERSION = 2
        const val PHOTO_INDEX_VERSION = 1
        /** The whole library (photos and videos each), not just the newest. */
        const val LIBRARY_LIMIT = 100_000
        const val SIDE = 16
        const val WORDS = 4
        const val BITS = 256
        /** How long a match may read thumbnails and decode frames before it settles for what it has. */
        const val BUDGET_MS = 2600L
        /** Luma spread (in 0…255 000) below which a region counts as one shade: about 6 % of full range. */
        const val FLAT_RANGE = 15_000
        /** A real brightness step between neighbours (3 of 255), and how many of the 256 a picture needs to be told apart. */
        const val STEP = 3_000
        const val MIN_STEPS = 64
        /** The closer look's grid, and what it takes: a correlation of 0.80, and 0.05 more than the next picture. */
        const val FINE = 40
        const val FINE_MIN = 0.80f
        const val FINE_MARGIN = 0.05f
        /** How much closer the match must be than the next-closest picture that isn't a copy of it (of 256 bits). */
        const val MARGIN = 10
        /**
         * The video grids: a video whose closest moment correlates at least 0.6 with the screen gets a closer look
         * (the second around that moment decoded); its frames there must reach 0.95 (0.97 when the screen didn't look
         * like a video), and 0.03 more than the next video's if that one came within 0.08. Without the closer look in
         * time, only a grid match of 0.90 that's 0.08 clear of every other video counts. (From 180 simulated taps on
         * 60 real videos: no wrong video ever passed.)
         */
        const val GRID_TRY = 0.6f
        const val CONFIRM_MIN = 0.95f
        const val CONFIRM_STRICT = 0.97f
        const val CONFIRM_MARGIN = 0.03f
        const val GRID_ALONE = 0.90f
        const val GRID_MARGIN = 0.08f
    }
}
