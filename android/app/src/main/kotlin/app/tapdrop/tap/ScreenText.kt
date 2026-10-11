package app.tapdrop.tap

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Reads the visible text of the foreground app straight from its accessibility tree (no OCR errors). */
object ScreenText {
    fun collect(root: AccessibilityNodeInfo?, max: Int = 600): String {
        val seen = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 80 || seen.size >= max) return
            if (n.isVisibleToUser && !n.isPassword) {
                n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it }
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return seen.joinToString("\n")
    }

    /** All labels (text + content descriptions), used to spot "Photo taken on …". */
    fun labels(root: AccessibilityNodeInfo?, max: Int = 300): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 80 || out.size >= max) return
            if (n.isVisibleToUser) {
                n.contentDescription?.toString()?.let { if (it.isNotBlank()) out += it }
                n.text?.toString()?.let { if (it.isNotBlank()) out += it }
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** A visible element and where it is on screen, for "tap / drag to pick". */
    data class Box(val rect: Rect, val text: String?, val cls: String = "", val desc: String? = null) {
        val area get() = rect.width().toLong() * rect.height()
    }

    fun boxes(root: AccessibilityNodeInfo?, max: Int = 1500): List<Box> {
        val out = mutableListOf<Box>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 80 || out.size >= max) return
            if (n.isVisibleToUser) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val t = if (n.isPassword) null else (n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                    out += Box(r, t, n.className?.toString() ?: "", n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                }
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /** Text of the elements whose centre sits inside [region], in reading order. */
    fun textIn(boxes: List<Box>, region: Rect): String =
        boxes.filter { !it.text.isNullOrBlank() && region.contains(it.rect.centerX(), it.rect.centerY()) }
            .sortedWith(compareBy({ it.rect.top / 24 }, { it.rect.left }))
            .mapNotNull { it.text }
            .distinct()
            .joinToString("\n")

    /**
     * The band between an app's top and bottom toolbars (e.g. a photo viewer's Share/Edit bar), so OCR
     * reads the content and not the app's own buttons.
     */
    fun contentBand(boxes: List<Box>, width: Int, height: Int): Rect {
        val small = boxes.filter { !it.text.isNullOrBlank() && it.rect.height() < height / 8 }
        val top = small.filter { it.rect.bottom < height * 0.18 }.maxOfOrNull { it.rect.bottom } ?: (height * 0.04).toInt()
        val bottom = small.filter { it.rect.top > height * 0.82 }.minOfOrNull { it.rect.top } ?: (height * 0.96).toInt()
        return if (bottom - top > height / 4) Rect(0, top, width, bottom) else Rect(0, 0, width, height)
    }
}
