package app.tapdrop.core

import kotlin.math.abs

/** Finds one-time codes in message text. */
object Otp {
    private val keyword = Regex(
        "(?i)(\\botp\\b|one[- ]?time|pass ?code|verification|verify|security code|login code|log-?in code|sign[- ]?in code|" +
            "auth(entication|orization)? code|confirmation code|access code|\\b2fa\\b|two[- ]factor|\\bpin\\b|\\bcode\\b|" +
            "kode|código|codice|\\bcode d|ओटीपी|कोड)"
    )
    private val google = Regex("\\bG-(\\d{4,8})\\b")
    // Not part of a bigger number, decimal, amount or time: "4821." at a sentence end is fine, "1299.00" is not.
    private val candidate = Regex("(?<![\\d₹$€£¥#/-]|\\d[.,:])(\\d{3,4}[- ]\\d{3,4}|\\d{4,8})(?![\\d,/%-]|[.:]\\d)")
    private val money = Regex("(?i)(rs\\.?|inr|usd|eur|gbp|amount|amt|bal(ance)?)\\s*$")

    fun find(text: String): String? {
        google.find(text)?.let { return it.groupValues[1] }
        val kws = keyword.findAll(text).map { it.range.first }.toList()
        if (kws.isEmpty()) return null
        val cands = candidate.findAll(text).mapNotNull { m ->
            val code = m.value.replace("-", "").replace(" ", "")
            val before = text.substring((m.range.first - 8).coerceAtLeast(0), m.range.first)
            when {
                code.length !in 4..8 -> null
                code.length == 4 && code.toInt() in 1950..2099 -> null   // a year
                money.containsMatchIn(before) -> null                     // an amount
                else -> code to m.range.first
            }
        }.toList()
        if (cands.isEmpty()) return null
        return cands.minByOrNull { (code, pos) ->
            kws.minOf { abs(it - pos) } + (if (code.length == 6) 0 else 12)
        }?.first
    }
}
