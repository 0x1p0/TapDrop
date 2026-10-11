package app.tapdrop.tap

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Telephony
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.tapdrop.core.Otp

/** Copies a detected code on the phone, sends it to the Mac's clipboard, and shows a pill. */
object OtpRelay {
    private var last: Pair<String, Long>? = null

    fun handle(ctx: Context, text: String, source: String) {
        val app = TapDropApp.instance
        if (!app.prefs.smartOtp.value) return
        val code = Otp.find(text) ?: return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            val l = last
            if (l != null && l.first == code && now - l.second < 120_000) return  // same code via SMS + notification
            last = code to now
        }
        app.scope.launch {
            if (app.prefs.otpCopyOnPhone.value) {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("Code", code)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                runCatching { cm.setPrimaryClip(clip) }
            }
            val mac = app.prefs.macs.value.firstOrNull() ?: return@launch
            val pill = NotchTapService.instance?.overlayCode(code, source)
            val job = app.transfers.send(
                mac, listOf(OutItem.Text(code, clip = true, otpSource = source)), "code",
                record = false, haptics = false,
            )
            job.join()
            val ok = app.transfers.state.value is app.tapdrop.net.TState.Done
            withContext(Dispatchers.Main) { pill?.finish(ok, if (ok) "Code ${pretty(code)} on your Mac" else "Couldn't reach your Mac") }
        }
    }

    fun pretty(code: String) = if (code.length == 6) code.substring(0, 3) + " " + code.substring(3) else code

    /** "AX-HDFCBK" → "HDFCBK", "+15551234567" stays as is. */
    fun cleanSender(s: String): String = Regex("^[A-Z]{2}-([A-Z0-9]{3,})(-[A-Z])?$").find(s)?.groupValues?.get(1) ?: s
}

/** Incoming SMS → code detection (works even when the screen is off). */
class SmsOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val from = parts.firstOrNull()?.displayOriginatingAddress ?: "Messages"
        OtpRelay.handle(context.applicationContext, body, OtpRelay.cleanSender(from))
    }
}
