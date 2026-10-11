package app.tapdrop.core

import kotlin.system.exitProcess

fun main() {
    val samples = listOf(
        "Your Uber code: 4821. Never share this code." to "4821",
        "731904 is your OTP for HDFC NetBanking login. Valid for 5 mins." to "731904",
        "Your Google verification code is G-482915. Don't share it." to "482915",
        "Use 559 012 to verify your sign-in to Amazon." to "559012",
        "WhatsApp code 123-456. You can also tap this link to verify your phone" to "123456",
        "Microsoft account security code: 2944133" to "2944133",
        "PIN for your card is 4417. Do not share" to "4417",
        "Your OTP is 6789 for transaction of Rs. 2,500.00 at AMAZON on 11/10/26" to "6789",
        "Rs. 4,500.00 debited from A/c XX1234 on 11-10-2026. Avl Bal Rs 12,345.67. Call 18002586161" to null,
        "Your bill of 1299.00 is due. Use code SAVE20 for 10% off" to null,
        "Dinner at 7:30? call me at 2026" to null,
        "Order #4471 has shipped! Track with code TRK" to null,
    )
    var ok = 0
    for ((t, want) in samples) {
        val got = Otp.find(t)
        println((if (got == want) "  PASS " else "  FAIL ") + "got=$got want=$want  | $t")
        if (got == want) ok++
    }
    println("$ok/${samples.size}")
    exitProcess(if (ok == samples.size) 0 else 1)
}
