package app.tapdrop.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.system.exitProcess

fun main() {
    val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 11, 17, 30, 0) }.timeInMillis
    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val cases = listOf(
        "Photo taken on Oct 11, 2026 4:19:13 PM" to at(2026, 9, 11, 16, 19),
        "Video taken on Oct 11, 2026, 4:19:13\u202FPM" to at(2026, 9, 11, 16, 19),
        "Today, 4:19 PM" to at(2026, 9, 11, 16, 19),
        "Yesterday 23:05" to at(2026, 9, 10, 23, 5),
        "11 Oct, 16:19" to at(2026, 9, 11, 16, 19),
        "10/11/26, 4:19 PM" to at(2026, 9, 11, 16, 19),
        "Nikhil" to null,
        "0:05" to null,
    )
    var ok = 0
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    for ((label, want) in cases) {
        val got = TimeHints.parse(listOf(label), now)?.timeMs
        val pass = if (want == null) got == null else got != null && kotlin.math.abs(got - want) < 60_000
        println((if (pass) "  PASS " else "  FAIL ") + "\"$label\" → ${got?.let { f.format(it) }}")
        if (pass) ok++
    }
    println("$ok/${cases.size}")
    exitProcess(if (ok == cases.size) 0 else 1)
}
