package app.tapdrop.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Reads the date/time a viewer shows next to a photo or video ("Photo taken on Oct 11, 2026 4:19:13 PM",
 * "Today, 4:19 PM", "Yesterday 23:05", "11 Oct, 16:19") so the library can be narrowed to that moment.
 */
object TimeHints {
    /** A moment and how precise it is (± window in ms). */
    data class Hint(val timeMs: Long, val windowMs: Long)

    private val relative = Regex("(?i)\\b(today|yesterday)\\b[,\\s]*(?:at\\s+)?(\\d{1,2})[:.](\\d{2})\\s*([ap]\\.?\\s?m\\.?)?")

    private val withSeconds = listOf(
        "MMM d, yyyy h:mm:ss a", "MMM d, yyyy, h:mm:ss a", "MMMM d, yyyy h:mm:ss a", "MMMM d, yyyy, h:mm:ss a",
        "d MMM yyyy HH:mm:ss", "d MMM yyyy, HH:mm:ss", "d MMMM yyyy HH:mm:ss", "d MMMM yyyy, HH:mm:ss",
        "dd-MMM-yyyy h:mm:ss a", "yyyy-MM-dd HH:mm:ss",
    )
    private val withMinutes = listOf(
        "MMM d, yyyy h:mm a", "MMM d, yyyy, h:mm a", "MMMM d, yyyy h:mm a", "MMMM d, yyyy, h:mm a",
        "d MMM yyyy HH:mm", "d MMM yyyy, HH:mm", "d MMMM yyyy HH:mm", "d MMMM yyyy, HH:mm",
        "M/d/yy, h:mm a", "M/d/yy h:mm a", "dd/MM/yyyy, HH:mm", "dd/MM/yy, HH:mm", "d/M/yy, HH:mm",
        "MMM d, h:mm a", "MMMM d, h:mm a", "d MMM, HH:mm", "d MMMM, HH:mm", "EEE, MMM d, h:mm a",
    )

    fun parse(labels: List<String>, now: Long = System.currentTimeMillis()): Hint? {
        for (raw in labels) {
            val s = raw.replace(' ', ' ').replace(' ', ' ').trim()
            if (s.length > 160) continue

            relative.find(s)?.let { m ->
                val cal = Calendar.getInstance().apply { timeInMillis = now }
                if (m.groupValues[1].equals("yesterday", ignoreCase = true)) cal.add(Calendar.DAY_OF_YEAR, -1)
                var hour = m.groupValues[2].toInt()
                val ampm = m.groupValues[4].lowercase().replace(".", "").replace(" ", "")
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, m.groupValues[3].toInt())
                cal.set(Calendar.SECOND, 30)
                cal.set(Calendar.MILLISECOND, 0)
                return Hint(cal.timeInMillis, 20 * 60_000L)
            }

            // "Photo taken on …" / "Video taken on …" / any label holding a full date-time
            val tail = when {
                s.contains("taken on", ignoreCase = true) -> s.substring(s.indexOf("taken on", ignoreCase = true) + 8).trim()
                else -> s
            }.trimEnd('.')
            parseWith(tail, withSeconds, now)?.let { return Hint(it, 61_000L) }
            parseWith(tail, withMinutes, now)?.let { return Hint(it, 20 * 60_000L) }
        }
        return null
    }

    private fun parseWith(s: String, formats: List<String>, now: Long): Long? {
        for (f in formats) {
            for (loc in listOf(Locale.getDefault(), Locale.US)) {
                val fmt = SimpleDateFormat(f, loc).apply { isLenient = false }
                val pos = java.text.ParsePosition(0)
                val d = runCatching { fmt.parse(s, pos) }.getOrNull() ?: continue
                // Must consume most of the label, not just a fragment that happens to parse.
                if (pos.index < s.length * 0.6 && pos.index < 12) continue
                var t = d.time
                if (!f.contains("y")) {  // no year shown → this year (or last year if that would be in the future)
                    val cal = Calendar.getInstance().apply { timeInMillis = t }
                    val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
                    cal.set(Calendar.YEAR, year)
                    if (cal.timeInMillis > now + 86_400_000L) cal.set(Calendar.YEAR, year - 1)
                    t = cal.timeInMillis
                }
                return t
            }
        }
        return null
    }
}
