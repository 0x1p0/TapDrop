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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the library original of the photo/video on screen, in any app.
 *
 * Signals, strongest first: the player's total length, the date/time the viewer shows, how recently the
 * file arrived, and a perceptual hash (dHash) of what's on screen against thumbnails and — for videos —
 * several frames, so a video can be recognised mid-playback. Heavy work is pre-computed in the background.
 */
class MediaMatcher(private val ctx: Context) {
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

    private data class Print(val hash: Long, val aspect: Float)

    private val thumbs = ConcurrentHashMap<Uri, Print>()
    private val frames = ConcurrentHashMap<Uri, List<Print>>()
    @Volatile private var recent: List<Media> = emptyList()
    @Volatile private var recentAt = 0L
    @Volatile private var lastWarm = 0L

    fun hasAccess(): Boolean =
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Pre-computes fingerprints (recent photos + video frames) so a tap matches instantly. */
    suspend fun warm() = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        if (!hasAccess() || now - lastWarm < 45_000) return@withContext
        lastWarm = now
        val all = recentMedia(force = true)
        all.take(160).forEach { thumb(it) }
        all.filter { it.isVideo }.take(12).forEach { videoFrames(it) }
    }

    suspend fun latest(): Media? = withContext(Dispatchers.IO) { if (hasAccess()) recentMedia(force = true).firstOrNull() else null }

    suspend fun find(screen: Bitmap, labels: List<String>, hit: MediaDetector.Hit?): Outcome = withContext(Dispatchers.Default) {
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
        fun score(p: Print) = bands.minOf { distance(p, small, it) }

        // 1) thumbnails: cheap, cached
        val pool = (strong + all.filter { if (video) it.isVideo else true }.take(if (video) 80 else 200)).distinctBy { it.uri }
        val scored = pool.mapNotNull { m -> thumb(m)?.let { m to score(it) } }.toMutableList()

        // 2) videos: also compare against frames, for the likeliest few (strong signals, closest thumbnails, newest)
        if (video) {
            val focus = (strong + scored.sortedBy { it.second }.take(5).map { it.first } +
                all.filter { it.isVideo }.take(5)).distinctBy { it.uri }.filter { it.isVideo }
            for (m in focus) {
                val f = videoFrames(m)
                if (f.isEmpty()) continue
                val d = f.minOf { score(it) }
                val i = scored.indexOfFirst { it.first.uri == m.uri }
                if (i >= 0) scored[i] = m to minOf(scored[i].second, d) else scored += m to d
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

        val found: Media? = when {
            // Nothing on screen looks like a photo/video: only an unmistakable match counts.
            hit == null -> best?.takeIf { it.second <= 5 }?.first
            best != null && best.second <= 10 -> best.first
            bestStrong != null && bestStrong.second <= 20 -> bestStrong.first
            strong.size == 1 -> strong[0]
            // Just arrived (e.g. received in a chat a moment ago) and it looks alike → that one.
            best != null && best.second <= 17 && newest != null && best.first.uri == newest.uri &&
                now - max(newest.addedMs, newest.takenMs) < 30 * 60_000L -> best.first
            else -> null
        }
        if (found != null) Outcome.Found(found) else Outcome.NotFound
    }

    /** The library original of one picture the user tapped in the picker (e.g. a photo in a chat). */
    suspend fun findIn(screen: Bitmap, rect: Rect): Media? =
        (find(screen, emptyList(), MediaDetector.Hit(MediaDetector.Kind.IMAGE, rect, null)) as? Outcome.Found)?.media

    fun thumbnail(m: Media, px: Int = 200): Bitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(m.uri, Size(px, px), null) }.getOrNull()

    // MARK: library

    private fun recentMedia(force: Boolean = false): List<Media> {
        val now = SystemClock.elapsedRealtime()
        if (!force && recent.isNotEmpty() && now - recentAt < 15_000) return recent
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
            )
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 500)
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
        recent = out
        recentAt = now
        return out
    }

    // MARK: fingerprints

    private fun thumb(m: Media): Print? {
        thumbs[m.uri]?.let { return it }
        val t = thumbnail(m, 192) ?: return null
        val p = Print(dHash(t, 0, 0, t.width, t.height), t.width.toFloat() / t.height)
        t.recycle()
        thumbs[m.uri] = p
        return p
    }

    /** Frames from the start (where people usually are) and across the video. */
    private fun videoFrames(m: Media): List<Print> {
        frames[m.uri]?.let { return it }
        if (m.durationMs <= 0) return emptyList()
        val times = (listOf(0L, 600L, 1500L, 3000L) + listOf(0.2, 0.4, 0.6, 0.8).map { (m.durationMs * it).toLong() })
            .filter { it < m.durationMs }.distinct()
        val out = mutableListOf<Print>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, m.uri)
            for (t in times) {
                val f = runCatching {
                    r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 192)
                }.getOrNull() ?: continue
                out += Print(dHash(f, 0, 0, f.width, f.height), f.width.toFloat() / f.height)
                f.recycle()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { r.release() }
        }
        frames[m.uri] = out
        return out
    }

    /** Where the media would sit if fit-centred in [band] (small-screenshot coords), then hashed. */
    private fun distance(p: Print, screen: Bitmap, band: Rect): Int {
        val b = Rect(band)
        if (!b.intersect(0, 0, screen.width, screen.height)) return 64
        val bw = b.width()
        val bh = b.height()
        if (bw < 8 || bh < 8) return 64
        val (w, h) = if (bw.toFloat() / bh > p.aspect) (bh * p.aspect).toInt() to bh else bw to (bw / p.aspect).toInt()
        if (w < 8 || h < 8) return 64
        return java.lang.Long.bitCount(p.hash xor dHash(screen, b.left + (bw - w) / 2, b.top + (bh - h) / 2, w, h))
    }

    /** dHash of the central 70% of a region (ignores viewer chrome near the edges). */
    private fun dHash(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Long {
        val cw = (w * 0.7f).toInt().coerceIn(9, src.width)
        val ch = (h * 0.7f).toInt().coerceIn(8, src.height)
        val cx = (x + (w * 0.15f).toInt()).coerceIn(0, src.width - cw)
        val cy = (y + (h * 0.15f).toInt()).coerceIn(0, src.height - ch)
        val region = Bitmap.createBitmap(src, cx, cy, cw, ch)
        val tiny = Bitmap.createScaledBitmap(region, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            if (luma(tiny.getPixel(col, row)) > luma(tiny.getPixel(col + 1, row))) hash = hash or (1L shl bit)
            bit++
        }
        if (region !== src) region.recycle()
        tiny.recycle()
        return hash
    }

    private fun luma(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
}
