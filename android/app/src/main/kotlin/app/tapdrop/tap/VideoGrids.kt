package app.tapdrop.tap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Every video as about two frames a second, each a [G] × [G] grid of brightness evened out to mean 0 and spread 1,
 * so a tap can tell which video is on screen, and about where in it.
 *
 * Why grids, not the frame fingerprints alone: many videos (WhatsApp's among them) have a keyframe only every 10 s,
 * and a hand-held video looks different a second later. A fingerprint of the nearest keyframe can be far from the
 * frame on screen; a coarse grid, compared by correlation, still tells the video apart from every other one. Each
 * video is decoded once from start to end, in the background. A tap then confirms the likeliest video by decoding
 * the second around its closest moment ([confirm]): only a frame that truly matches the screen sends it.
 */
internal class VideoGrids(private val ctx: Context, private val file: File) {
    /** One video: its frames' times (ms), grids ([G]² signed bytes each) and their lengths; and its shape. */
    class Entry(val key: String, val aspect: Float, val times: IntArray, val grids: ByteArray) {
        val norms = FloatArray(times.size) { i -> norm(grids, i * CELLS) }
    }

    private val entries = ConcurrentHashMap<String, Entry>()  // media uri → its grids
    @Volatile private var loaded = false

    val size: Int get() = entries.size

    fun has(uri: Uri, key: String) = entries[uri.toString()]?.key == key

    // MARK: building

    /** Decodes the whole video once, keeping about two frames a second (at most [MAX_FRAMES]). null: unreadable. */
    fun build(uri: Uri, key: String, durationMs: Long, until: Long): Entry? {
        val step = max(STEP_MS.toLong(), durationMs / MAX_FRAMES) * 1000
        val times = ArrayList<Int>()
        val grids = java.io.ByteArrayOutputStream()
        var aspect = 0f
        val ok = decode(uri, 0, Long.MAX_VALUE, step, until) { us, luma ->
            luma.grid(INSET_FULL)?.let {
                times += (us / 1000).toInt()
                grids.write(it)
                aspect = luma.aspect
            }
        }
        if (!ok || times.isEmpty()) return null
        return Entry(key, aspect, times.toIntArray(), grids.toByteArray())
    }

    fun put(uri: Uri, e: Entry) {
        entries[uri.toString()] = e
        append(uri.toString(), e)
    }

    /** Forgets videos that are gone, and rewrites the file without them (and without replaced records). */
    fun keepOnly(live: Set<String>) {
        entries.keys.retainAll(live)
        rewrite()
    }

    // MARK: matching

    class Ranked(val uri: String, val score: Float, val atMs: Int, val aspect: Float)

    /**
     * Every gridded video in [videos] by its moment closest to the screen, best first. [screen] gives the screen's
     * grid where a video of a given shape would show (null: none there).
     */
    fun rank(videos: List<MediaMatcher.Media>, key: (MediaMatcher.Media) -> String, screen: (Float) -> ByteArray?): List<Ranked> {
        load()
        val memo = HashMap<Int, Pair<ByteArray, Float>?>()
        val out = ArrayList<Ranked>()
        for (m in videos) {
            val e = entries[m.uri.toString()]?.takeIf { it.key == key(m) && it.times.isNotEmpty() } ?: continue
            val q = memo.getOrPut((e.aspect * 100).roundToInt()) { screen(e.aspect)?.let { it to norm(it, 0) } } ?: continue
            var best = -1f
            var at = 0
            for (i in e.times.indices) {
                val n = e.norms[i]
                if (n == 0f) continue
                val s = dot(q.first, e.grids, i * CELLS) / (q.second * n)
                if (s > best) {
                    best = s
                    at = e.times[i]
                }
            }
            if (best > -1f) out += Ranked(m.uri.toString(), best, at, e.aspect)
        }
        out.sortByDescending { it.score }
        return out
    }

    /**
     * How well the video's frames within [SPAN_MS] of [atMs] match the screen: decoded frame by frame (from the
     * keyframe before), each compared whole and by its middle (where a player's bars and buttons don't reach).
     * null: it couldn't be decoded in time.
     */
    fun confirm(uri: Uri, atMs: Int, full: ByteArray, middle: ByteArray?, until: Long): Float? {
        val nf = norm(full, 0)
        val nm = middle?.let { norm(it, 0) } ?: 0f
        var best = -1f
        val from = max(0L, (atMs - SPAN_MS) * 1000L)
        val ok = decode(uri, from, (atMs + SPAN_MS) * 1000L, 0, until) { _, luma ->
            luma.grid(INSET_FULL)?.let { g -> norm(g, 0).takeIf { it > 0f }?.let { best = max(best, dot(full, g, 0) / (nf * it)) } }
            if (middle != null && nm > 0f) {
                luma.grid(INSET_MIDDLE)?.let { g -> norm(g, 0).takeIf { it > 0f }?.let { best = max(best, dot(middle, g, 0) / (nm * it)) } }
            }
        }
        return if (ok || best > -1f) best else null
    }

    // MARK: decoding

    /** A decoded frame's brightness plane, turned upright when its grid is taken. */
    private class Luma(val img: Image, val rotation: Int) {
        val aspect: Float
            get() = img.cropRect.let { r -> if (rotation % 180 == 0) r.width().toFloat() / r.height() else r.height().toFloat() / r.width() }

        /** The frame's grid, inset by [inset] of each side (null: one shade, like a black frame). */
        fun grid(inset: Float): ByteArray? {
            val crop = img.cropRect
            val p = img.planes[0]
            val buf = p.buffer
            val rs = p.rowStride
            val ps = p.pixelStride
            // 10-bit video (P010): each sample is two bytes, the high one holding its top 8 bits.
            val hi = if (img.format == ImageFormat.YCBCR_P010) 1 else 0
            val x0 = crop.left + (crop.width() * inset).toInt()
            val y0 = crop.top + (crop.height() * inset).toInt()
            val w = crop.width() - 2 * (crop.width() * inset).toInt()
            val h = crop.height() - 2 * (crop.height() * inset).toInt()
            if (w < G || h < G) return null
            // About 200 samples across the short side is plenty for 16 × 16 averages.
            val step = max(1, min(w, h) / 200)
            val sum = LongArray(CELLS)
            val count = IntArray(CELLS)
            var y = 0
            while (y < h) {
                val row = (y0 + y) * rs
                val gy = y * G / h
                var x = 0
                while (x < w) {
                    val c = gy * G + x * G / w
                    sum[c] += (buf.get(row + (x0 + x) * ps + hi).toInt() and 0xff).toLong()
                    count[c]++
                    x += step
                }
                y += step
            }
            val raw = FloatArray(CELLS) { if (count[it] == 0) 0f else sum[it].toFloat() / count[it] }
            return evened(upright(raw, rotation))
        }
    }

    /**
     * Decodes [uri] from the keyframe at or before [fromUs] up to [toUs], handing [take] the frames due: one every
     * [stepUs] (0: every frame) from [fromUs]. false: no decoder for it, it failed, or [until] passed.
     */
    private fun decode(uri: Uri, fromUs: Long, toUs: Long, stepUs: Long, until: Long, take: (Long, Luma) -> Unit): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Not a player: no real-time deadline.
            format.setInteger(MediaFormat.KEY_PRIORITY, 1)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var next = fromUs
            var inputDone = false
            while (true) {
                if (SystemClock.elapsedRealtime() > until) return false
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i) ?: return false, 0)
                        // Past the end (or well past [toUs]: frames come out up to a few behind what goes in).
                        if (n < 0 || ex.sampleTime - 1_000_000 > toUs) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 5_000)
                if (o < 0) continue
                val us = info.presentationTimeUs
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (us > toUs) {
                    c.releaseOutputBuffer(o, false)
                    return true
                }
                if (us >= next && (info.size > 0 || !end)) {
                    c.getOutputImage(o)?.use { take(us, Luma(it, rotation)) }
                    next = if (stepUs <= 0) us else max(next + stepUs, us - us % stepUs + stepUs)
                }
                c.releaseOutputBuffer(o, false)
                if (end) return true
            }
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    // MARK: file (append-only: one record per video, a later one replacing an earlier one)

    fun load() {
        if (loaded) return
        synchronized(entries) {
            if (loaded) return
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { d ->
                    // An older file (version 1 recorded every video as unreadable): start over.
                    if (d.readInt() != FILE_VERSION) {
                        d.close()
                        file.delete()
                        return@use
                    }
                    while (true) {
                        val uri = try {
                            d.readUTF()
                        } catch (_: EOFException) {
                            break
                        }
                        val key = d.readUTF()
                        val aspect = d.readFloat()
                        val n = d.readInt()
                        if (n !in 0..MAX_FRAMES * 2) break  // 0: a video that couldn't be decoded
                        val times = IntArray(n) { d.readInt() }
                        val grids = ByteArray(n * CELLS)
                        d.readFully(grids)
                        entries[uri] = Entry(key, aspect, times, grids)
                        records++
                    }
                }
            }
            loaded = true
        }
    }

    @Volatile private var records = 0

    private fun append(uri: String, e: Entry) = synchronized(file) {
        runCatching {
            val fresh = !file.exists() || file.length() == 0L
            DataOutputStream(FileOutputStream(file, true).buffered()).use { d ->
                if (fresh) d.writeInt(FILE_VERSION)
                write(d, uri, e)
            }
            records++
        }
    }

    private fun rewrite() = synchronized(file) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            val snapshot = entries.entries.toList()
            DataOutputStream(FileOutputStream(tmp).buffered()).use { d ->
                d.writeInt(FILE_VERSION)
                for ((uri, e) in snapshot) write(d, uri, e)
            }
            tmp.renameTo(file)
            records = snapshot.size
        }
    }

    private fun write(d: DataOutputStream, uri: String, e: Entry) {
        d.writeUTF(uri)
        d.writeUTF(e.key)
        d.writeFloat(e.aspect)
        d.writeInt(e.times.size)
        e.times.forEach { d.writeInt(it) }
        d.write(e.grids)
    }

    /**
     * Running sums of brightness (at a quarter of the screen's size), so any region's grid takes 256 lookups
     * instead of a pass over its pixels: a tap compares the screen with pictures of hundreds of shapes.
     */
    class Sums(lum: IntArray, val w: Int, val h: Int) {
        private val s = LongArray((w + 1) * (h + 1))

        init {
            for (y in 0 until h) {
                var row = 0L
                for (x in 0 until w) {
                    row += lum[y * w + x]
                    s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row
                }
            }
        }

        private fun box(x0: Int, y0: Int, x1: Int, y1: Int) =
            s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0]

        /** [region]'s grid ([n] × [n]), inset by [inset] of each side, like [gridOf]. */
        fun grid(region: Rect, inset: Float, n: Int = G): ByteArray? {
            val r = Rect(region)
            if (!r.intersect(0, 0, w, h)) return null
            val ix = (r.width() * inset).toInt()
            val iy = (r.height() * inset).toInt()
            val x0 = r.left + ix
            val y0 = r.top + iy
            val gw = r.width() - 2 * ix
            val gh = r.height() - 2 * iy
            if (gw < n || gh < n) return null
            val raw = FloatArray(n * n)
            for (j in 0 until n) {
                val ya = y0 + j * gh / n
                val yb = y0 + (j + 1) * gh / n
                for (i in 0 until n) {
                    val xa = x0 + i * gw / n
                    val xb = x0 + (i + 1) * gw / n
                    val area = (xb - xa) * (yb - ya)
                    raw[j * n + i] = if (area == 0) 0f else box(xa, ya, xb, yb).toFloat() / area
                }
            }
            return evened(raw)
        }
    }

    companion object {
        const val G = 16
        const val CELLS = G * G
        /** About two frames a second… */
        const val STEP_MS = 500
        /** …and at most this many per video (a longer one gets them further apart). */
        const val MAX_FRAMES = 240
        /** A grid leaves out this much of each side (a player's edges); the middle one, more (its bars and buttons). */
        const val INSET_FULL = 0.05f
        const val INSET_MIDDLE = 0.15f
        /** A tap confirms a video by the frames this close to its closest moment. */
        const val SPAN_MS = 700
        const val FILE_VERSION = 2

        /** A screen region's grid ([n] × [n]), from brightness values ([lum], [w] wide), inset like the frames'. */
        fun gridOf(lum: IntArray, w: Int, h: Int, region: Rect, inset: Float, n: Int = G): ByteArray? {
            val r = Rect(region)
            if (!r.intersect(0, 0, w, h)) return null
            val ix = (r.width() * inset).toInt()
            val iy = (r.height() * inset).toInt()
            val x0 = r.left + ix
            val y0 = r.top + iy
            val gw = r.width() - 2 * ix
            val gh = r.height() - 2 * iy
            if (gw < n || gh < n) return null
            val sum = LongArray(n * n)
            val count = IntArray(n * n)
            for (y in 0 until gh) {
                val row = (y0 + y) * w
                val gy = y * n / gh
                for (x in 0 until gw) {
                    val c = gy * n + x * n / gw
                    sum[c] += lum[row + x0 + x].toLong()
                    count[c]++
                }
            }
            return evened(FloatArray(n * n) { if (count[it] == 0) 0f else sum[it].toFloat() / count[it] })
        }

        /** A picture's grid ([n] × [n]) over all of it, inset by [inset] of each side (null: one shade). */
        fun gridOf(picture: Bitmap, inset: Float, n: Int = G): ByteArray? =
            region(picture, Rect(0, 0, picture.width, picture.height), inset, n)

        /** The grid of [region] of [bitmap] at full resolution. */
        fun region(bitmap: Bitmap, region: Rect, inset: Float, n: Int): ByteArray? {
            val r = Rect(region)
            if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.width() < n || r.height() < n) return null
            val px = IntArray(r.width() * r.height())
            bitmap.getPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
            for (i in px.indices) {
                val c = px[i]
                px[i] = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
            }
            return gridOf(px, r.width(), r.height(), Rect(0, 0, r.width(), r.height()), inset, n)
        }

        /**
         * How alike two square grids are by their edges (each cell against its right and lower neighbour): flat areas,
         * like a viewer's black bars, count for nothing, so a picture's own detail decides.
         */
        fun edgeCorrelation(a: ByteArray, b: ByteArray): Float {
            val n = sqrt(a.size.toFloat()).roundToInt()
            var s = 0L
            var na = 0L
            var nb = 0L
            for (y in 0 until n) for (x in 0 until n) {
                val i = y * n + x
                if (x + 1 < n) {
                    val p = a[i + 1] - a[i]
                    val q = b[i + 1] - b[i]
                    s += p * q; na += p * p; nb += q * q
                }
                if (y + 1 < n) {
                    val p = a[i + n] - a[i]
                    val q = b[i + n] - b[i]
                    s += p * q; na += p * p; nb += q * q
                }
            }
            return if (na == 0L || nb == 0L) 0f else (s / sqrt(na.toDouble() * nb.toDouble())).toFloat()
        }

        /** How alike two grids of the same size are: 1 is the same picture. */
        fun correlation(a: ByteArray, b: ByteArray): Float {
            var s = 0
            var na = 0
            var nb = 0
            for (i in a.indices) {
                val x = a[i].toInt()
                val y = b[i].toInt()
                s += x * y
                na += x * x
                nb += y * y
            }
            return if (na == 0 || nb == 0) 0f else s / sqrt(na.toFloat() * nb.toFloat())
        }

        /** The screen's brightness at a quarter of its size, each value the average of 4 × 4 pixels. */
        fun quarter(screen: Bitmap): Triple<IntArray, Int, Int> {
            val qw = screen.width / 4
            val qh = screen.height / 4
            val sum = IntArray(qw * qh)
            val row = IntArray(screen.width)
            for (y in 0 until qh * 4) {
                screen.getPixels(row, 0, screen.width, 0, y, screen.width, 1)
                val base = (y / 4) * qw
                for (x in 0 until qw * 4) {
                    val c = row[x]
                    sum[base + x / 4] += ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
                }
            }
            for (i in sum.indices) sum[i] /= 16
            return Triple(sum, qw, qh)
        }

        /** Mean 0, spread 1, as signed bytes (×32). null: one shade, nothing to compare. */
        private fun evened(raw: FloatArray): ByteArray? {
            val mean = raw.average().toFloat()
            var v = 0f
            for (x in raw) v += (x - mean) * (x - mean)
            val sd = sqrt(v / raw.size)
            if (sd < 2f) return null
            return ByteArray(raw.size) { (((raw[it] - mean) / sd) * 32f).roundToInt().coerceIn(-127, 127).toByte() }
        }

        /** The grid of a frame shown turned by [rotation]° clockwise. */
        private fun upright(g: FloatArray, rotation: Int): FloatArray {
            if (rotation % 360 == 0) return g
            val out = FloatArray(CELLS)
            for (y in 0 until G) for (x in 0 until G) {
                val v = g[y * G + x]
                when ((rotation % 360 + 360) % 360) {
                    90 -> out[x * G + (G - 1 - y)] = v
                    180 -> out[(G - 1 - y) * G + (G - 1 - x)] = v
                    270 -> out[(G - 1 - x) * G + y] = v
                    else -> out[y * G + x] = v
                }
            }
            return out
        }

        private fun norm(a: ByteArray, off: Int): Float {
            var s = 0
            for (i in 0 until CELLS) {
                val v = a[off + i].toInt()
                s += v * v
            }
            return sqrt(s.toFloat())
        }

        private fun dot(q: ByteArray, a: ByteArray, off: Int): Float {
            var s = 0
            for (i in 0 until CELLS) s += q[i] * a[off + i]
            return s.toFloat()
        }
    }
}
