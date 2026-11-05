package app.tapdrop.tap

import android.graphics.Rect

/**
 * Recognises a photo or video filling the screen in *any* app, from the screen's own layout:
 * a large image element, or a player (video surface, or seek bar + time labels).
 */
object MediaDetector {
    enum class Kind { IMAGE, VIDEO }

    data class Hit(val kind: Kind, val rect: Rect, val durationMs: Long?, val positionMs: Long? = null)

    private val time = Regex("\\b(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})\\b")

    fun detect(boxes: List<ScreenText.Box>, w: Int, h: Int): Hit? {
        val screen = w.toLong() * h
        fun label(b: ScreenText.Box) = listOfNotNull(b.text, b.desc).joinToString(" ")

        val surface = boxes.filter {
            it.cls.endsWith("VideoView") || it.cls.endsWith("SurfaceView") || it.cls.endsWith("TextureView") ||
                (it.desc?.contains("video", ignoreCase = true) == true && it.area > screen / 5)
        }.maxByOrNull { it.area }
        val seek = boxes.any {
            it.cls.endsWith("SeekBar") || (it.cls.endsWith("ProgressBar") && it.rect.width() > w / 2) ||
                it.desc?.contains("seek", ignoreCase = true) == true
        }
        val times = boxes.flatMap { b -> time.findAll(label(b)).map { toMs(it) }.toList() }
        if ((surface != null && surface.area > screen / 5) || (seek && times.isNotEmpty())) {
            val total = times.maxOrNull()?.takeIf { it > 0 }
            // A player shows where it is and how long it is: the smaller time is the position.
            val at = times.minOrNull()?.takeIf { times.size >= 2 && total != null && it < total }
            return Hit(Kind.VIDEO, surface?.rect ?: Rect(0, 0, w, h), total, at)
        }

        val image = boxes.filter {
            it.area > screen * 0.3 && (
                it.cls.endsWith("ImageView") || it.cls.contains("Photo", ignoreCase = true) ||
                    it.desc?.let { d -> d.contains("photo", true) || d.contains("image", true) || d.contains("picture", true) } == true
                )
        }.maxByOrNull { it.area }
        if (image != null) return Hit(Kind.IMAGE, image.rect, null)
        return null
    }

    private fun toMs(m: MatchResult): Long {
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        return ((h * 60 + min) * 60 + s) * 1000
    }
}
