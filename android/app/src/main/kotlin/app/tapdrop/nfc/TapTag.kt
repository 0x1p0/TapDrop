package app.tapdrop.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * The NFC sticker on the Mac holds one MIME record ("m=<macId>") plus an Android Application
 * Record, so tapping it opens TapDrop straight into the quick-share sheet for that Mac.
 * Fits easily on the cheapest NTAG213 stickers (~45 bytes).
 */
object TapTag {
    const val MIME = "application/x-tapdrop"
    private const val PACKAGE = "app.tapdrop"

    fun message(macId: String) = NdefMessage(
        arrayOf(
            NdefRecord.createMime(MIME, "m=$macId".toByteArray()),
            NdefRecord.createApplicationRecord(PACKAGE),
        )
    )

    fun parse(msg: NdefMessage?): String? =
        msg?.records
            ?.firstOrNull { it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type) == MIME }
            ?.payload?.let { String(it) }
            ?.removePrefix("m=")
            ?.takeIf { it.isNotBlank() }

    fun read(tag: Tag): String? = runCatching {
        val ndef = Ndef.get(tag) ?: return null
        ndef.cachedNdefMessage ?: ndef.use { it.connect(); it.ndefMessage }
    }.getOrNull()?.let(::parse)

    fun fromIntent(intent: Intent?): String? =
        intent?.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            ?.firstNotNullOfOrNull { parse(it) }

    /** Writes the sticker. Returns null on success, or a human-readable error. Call off the main thread. */
    fun write(tag: Tag, macId: String): String? = try {
        val msg = message(macId)
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                when {
                    !it.isWritable -> "This sticker is locked and can't be rewritten."
                    it.maxSize < msg.byteArrayLength -> "This sticker is too small."
                    else -> { it.writeNdefMessage(msg); null }
                }
            }
        } else {
            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                formatable.use { it.connect(); it.format(msg) }
                null
            } else {
                "That isn't a writable NFC sticker. Try an NTAG213 or NTAG215 sticker."
            }
        }
    } catch (e: Exception) {
        "Hold the sticker still against the back of your phone and try again."
    }
}
