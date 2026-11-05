package app.tapdrop.core

import kotlin.system.exitProcess

/**
 * Runs [Otp.find] over [otpCodes] and [otpNotCodes] and reports precision and recall. Fails on any number shown
 * for a message without a code, any wrong code, or recall under 97%.
 */
fun main() {
    fun one(s: String) = s.replace("\n", " / ")
    fun label(c: OtpCase) = (if (c.from == Otp.From.SMS) "" else "${c.from.name.lowercase()} [${c.title}] ") + one(c.text)

    var found = 0
    var missed = 0
    var wrong = 0
    var falses = 0
    for (c in otpCodes) {
        val got = Otp.find(c.text, c.from, c.title)
        when (got) {
            c.want -> found++
            null -> { missed++; println("  MISS  want=${c.want}  | ${label(c)}") }
            else -> { wrong++; println("  WRONG got=$got want=${c.want}  | ${label(c)}") }
        }
    }
    for (c in otpNotCodes) {
        val got = Otp.find(c.text, c.from, c.title) ?: continue
        falses++
        println("  FALSE got=$got  | ${label(c)}")
    }

    // The rest of the rules the phone relies on.
    val rules = listOf(
        "same code spaced or not" to (Otp.digits("123 456") == "123456" && Otp.digits("123-456") == Otp.digits("123456")),
        "chat title never gives a code" to (Otp.find("lol", Otp.From.CHAT, "OTP 482913") == null),
        "notification title never gives a code" to (Otp.find("New message", Otp.From.NOTIFICATION, "Code 482913") == null),
        "title can name a bare code" to (Otp.find("482913", Otp.From.NOTIFICATION, "Your verification code") == "482913"),
        "chat title can't name a bare code" to (Otp.find("482913", Otp.From.CHAT, "Verification code") == null),
        "WhatsApp is a chat app" to (Otp.isChatApp("com.whatsapp") && Otp.isChatApp("org.telegram.messenger")),
        "SMS apps aren't chat apps" to (!Otp.isChatApp("com.google.android.apps.messaging") && !Otp.isChatApp("com.samsung.android.messaging")),
    )
    var broken = 0
    for ((name, ok) in rules) if (!ok) { broken++; println("  RULE  $name") }

    val shown = found + wrong + falses
    val precision = if (shown == 0) 100.0 else 100.0 * found / shown
    val recall = 100.0 * found / otpCodes.size
    println()
    println("Messages with a code: ${otpCodes.size} (found $found, missed $missed, wrong $wrong)")
    println("Messages without one: ${otpNotCodes.size} (showed a number for $falses)")
    println("Rules: ${rules.size - broken}/${rules.size}")
    println("Precision %.1f%%, recall %.1f%%".format(precision, recall))
    exitProcess(if (wrong == 0 && falses == 0 && broken == 0 && recall >= 97.0) 0 else 1)
}
