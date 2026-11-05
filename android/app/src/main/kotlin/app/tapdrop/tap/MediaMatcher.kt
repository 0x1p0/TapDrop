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
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

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
) {
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
    private class Print(val bits: LongArray, val aspect: Float)

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
        index(recentVideos(force = true))
        all.filter { it.isVideo }.take(10).forEach { videoFrames(it, dense = false) }
    }

    suspend fun latest(): Media? = withContext(Dispatchers.IO) { if (hasAccess()) recentMedia(force = true).firstOrNull() else null }

    /**
     * [allowVideos]: the library's videos may be what's on screen (a gallery, a chat, a video player, or a video
     * playing). Elsewhere (a social feed, a browser) its videos aren't there, so only an unmistakable photo counts.
     */
    suspend fun find(screen: Bitmap, labels: List<String>, hit: MediaDetector.Hit?, allowVideos: Boolean = true): Outcome =
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
        if (strong.size == 1 && (byLength.isNotEmpty() || time?.windowMs == 61_000L)) return@withContext Outcome.Found(strong[0])

        val small = Bitmap.createScaledBitmap(screen, max(9, screen.width / 4), max(8, screen.height / 4), true)
        val bands = buildList {
            hit?.rect?.let { r -> add(Rect(r.left / 4, r.top / 4, r.right / 4, r.bottom / 4)) }
            add(Rect(0, 0, small.width, small.height))
            add(Rect(0, (small.height * 0.08f).toInt(), small.width, (small.height * 0.88f).toInt()))
        }
        // The screen's hash per band and media shape, computed once (the index compares thousands of frames).
        val memo = HashMap<Long, LongArray?>()
        fun score(p: Print) = bands.indices.minOf { i ->
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

        // 1) thumbnails: cheap, mostly cached
        val pool = (strong + all.filter { if (video) it.isVideo else allowVideos || !it.isVideo }.take(if (video) 60 else 160))
            .distinctBy { it.uri }
        val scored = mutableListOf<Pair<Media, Int>>()
        for (m in pool) {
            if (!inTime(BUDGET_MS / 3)) break
            thumb(m)?.let { scored += m to score(it) }
        }
        fun note(m: Media, d: Int) {
            val i = scored.indexOfFirst { it.first.uri == m.uri }
            if (i >= 0) scored[i] = m to minOf(scored[i].second, d) else scored += m to d
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
                indexed.second + (if (video) 0 else 8) <= (best?.second ?: BITS) -> indexed.first
            // Nothing on screen looks like a photo/video: only an unmistakable match counts.
            hit == null -> best?.takeIf { it.second <= 20 }?.first
            best != null && best.second <= 36 -> best.first
            bestStrong != null && bestStrong.second <= 64 -> bestStrong.first
            strong.size == 1 -> strong[0]
            // Just arrived (e.g. received in a chat a moment ago) and it looks alike → that one.
            best != null && best.second <= 52 && newest != null && best.first.uri == newest.uri &&
                now - max(newest.addedMs, newest.takenMs) < 30 * 60_000L -> best.first
            else -> null
        }
        // Chat viewers often hide a playing video's controls, so it may not have looked like one: the newest videos'
        // frames against the screen too.
        var videoBest: Pair<Media, Int>? = null
        if (found == null && !video && allowVideos) {
            for (m in all.filter { it.isVideo }.take(6)) {
                if (!inTime()) break
                val f = videoFrames(m, dense = false, until)
                if (f.isEmpty()) continue
                val d = f.minOf { score(it) }
                if (videoBest == null || d < videoBest.second) videoBest = m to d
                if (d <= good) break
            }
            // Close but not certain: about one frame a second for that video.
            videoBest?.takeIf { it.second > good && inTime() }?.let { (m, _) ->
                val f = videoFrames(m, dense = true, until)
                if (f.isNotEmpty()) videoBest = m to minOf(videoBest!!.second, f.minOf { score(it) })
            }
            videoBest?.takeIf { it.second <= if (hit == null) good else 36 }?.let { found = it.first }
        }
        lastNote = buildString {
            append(if (video) "video on screen" else if (hit != null) "photo on screen" else "nothing photo-like on screen")
            hit?.durationMs?.let { append(", ${it / 1000}s long") } ?: if (video) append(", no length shown") else Unit
            hit?.positionMs?.let { append(" at ${it / 1000}s") }
            append(" · ${all.count { it.isVideo }} videos, ${all.count { !it.isVideo }} photos in library")
            best?.let { append(" · closest ${it.first.name} ${it.second}") }
            atFrame?.let { append(" · frame at position ${it.first.name} ${it.second}") }
            append(" · index ${indexEntries.size}/${recentVideos().size} videos")
            if (!allowVideos) append(" · videos not searched here")
            indexed?.let { append(" · closest indexed frame ${it.first.name} ${it.second} (next video ${it.third})") }
            videoBest?.let { append(" · closest video frame ${it.first.name} ${it.second}") }
            append(" → ${found?.name ?: "no match"} in ${SystemClock.elapsedRealtime() - start} ms (of 256)")
        }
        found?.let { Outcome.Found(it) } ?: Outcome.NotFound
        }

    /** The library original of one picture the user tapped in the picker (e.g. a photo in a chat). */
    suspend fun findIn(screen: Bitmap, rect: Rect): Media? =
        (find(screen, emptyList(), MediaDetector.Hit(MediaDetector.Kind.IMAGE, rect, null), allowVideos = false) as? Outcome.Found)?.media

    fun thumbnail(m: Media, px: Int = 200): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null) }.getOrNull()

    // MARK: library

    /** Every video, newest first (the index covers up to 400). */
    private fun recentVideos(force: Boolean = false): List<Media> {
        val now = SystemClock.elapsedRealtime()
        if (!force && videosList.isNotEmpty() && now - videosAt < 15_000) return videosList
        return queryMedia(videosOnly = true, limit = 400).also { videosList = it; videosAt = now }
    }

    private fun recentMedia(force: Boolean = false): List<Media> {
        val now = SystemClock.elapsedRealtime()
        if (!force && recent.isNotEmpty() && now - recentAt < 15_000) return recent
        return queryMedia(videosOnly = false, limit = 500).also { recent = it; recentAt = now }
    }

    private fun queryMedia(videosOnly: Boolean, limit: Int): List<Media> {
        val types = if (videosOnly) "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
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
        val t = thumbnail(m, 192) ?: return null
        val h = dHash(t, 0, 0, t.width, t.height)
        // A flat thumbnail is cached as such (NaN aspect) so it isn't loaded again, and never compared.
        val p = if (h != null) Print(h, t.width.toFloat() / t.height) else Print(LongArray(WORDS), Float.NaN)
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
        if (m.durationMs <= 0) return emptyList()
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
                dHash(f, 0, 0, f.width, f.height)?.let { out += Print(it, f.width.toFloat() / f.height) }
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

    /** The indexed video whose frames come closest to the screen, its distance, and the next closest video's. */
    private fun indexBest(all: List<Media>, score: (Print) -> Int): Triple<Media, Int, Int>? {
        loadIndex()
        var best: Pair<Media, Int>? = null
        var second = BITS
        for (m in all) {
            if (!m.isVideo) continue
            val e = indexEntries[m.uri.toString()]?.takeIf { it.key == key(m) } ?: continue
            if (e.hashes.isEmpty()) continue
            val d = e.hashes.minOf { score(Print(it, e.aspect)) }
            if (best == null || d < best.second) {
                best?.let { second = minOf(second, it.second) }
                best = m to d
            } else {
                second = minOf(second, d)
            }
        }
        return best?.let { Triple(it.first, it.second, second) }
    }

    /**
     * Fingerprints every video not yet in the index (newest first, up to 400), in the background at low priority,
     * saving as it goes: the keyframe nearest each second (60 points at most across longer videos), so a frame from
     * anywhere in a clip is close to one of them. Keyframes decode on their own, so a video takes well under a second.
     */
    private fun index(videos: List<Media>) {
        loadIndex()
        val eligible = videos.take(400).filter { it.durationMs > 0 }
        val todo = eligible.filter { indexEntries[it.uri.toString()]?.key != key(it) }
        if (indexing) return
        var done = eligible.size - todo.size
        onIndex(done, eligible.size)
        if (todo.isEmpty()) return
        indexing = true
        thread(name = "TapDrop video index", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            try {
                var n = 0
                for (m in todo) {
                    if (!hasAccess()) break
                    // A video that can't be read is recorded empty, so it isn't retried (and counts as done).
                    indexEntries[m.uri.toString()] = fingerprint(m) ?: Indexed(key(m), 1f, emptyList())
                    onIndex(++done, eligible.size)
                    if (++n % 5 == 0) saveIndex()
                }
                // Forget videos that are gone.
                val live = videos.map { it.uri.toString() }.toSet()
                if (videos.size < 400) indexEntries.keys.retainAll(live)
                saveIndex()
                Log.i("TapDrop", "video index: ${indexEntries.size} videos")
            } finally {
                indexing = false
            }
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
            dHash(f, 0, 0, f.width, f.height)?.let { Print(it, f.width.toFloat() / f.height) }.also { f.recycle() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Where the media would sit if fit-centred in [band] (small-screenshot coords), then hashed. */
    /** The hash of where media of this [aspect] would sit fit-centred in [band] (null: too small, or one shade). */
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
        const val SIDE = 16
        const val WORDS = 4
        const val BITS = 256
        /** How long a match may read thumbnails and decode frames before it settles for what it has. */
        const val BUDGET_MS = 2600L
        /** Luma spread (in 0…255 000) below which a region counts as one shade: about 6 % of full range. */
        const val FLAT_RANGE = 15_000
    }
}
