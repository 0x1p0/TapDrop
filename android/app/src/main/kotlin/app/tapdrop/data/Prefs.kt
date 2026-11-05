package app.tapdrop.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import app.tapdrop.core.Endpoint
import app.tapdrop.core.Identity
import app.tapdrop.core.MacKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SavedMac(val id: String, val name: String, val key: ByteArray, val host: String, val port: Int) {
    val macKey get() = MacKey(id, key)
    val endpoint get() = Endpoint(host, port)

    fun copy(name: String = this.name, host: String = this.host, port: Int = this.port) = SavedMac(id, name, key, host, port)

    override fun equals(other: Any?) =
        other is SavedMac && other.id == id && other.name == name && other.host == host && other.port == port

    override fun hashCode() = id.hashCode()
}

enum class TapAction(val label: String, val detail: String) {
    ASK("Show options", "Choose what to share each time"),
    LATEST_PHOTO("Send latest photo", "Your newest photo or video goes instantly"),
    CLIPBOARD("Send clipboard", "Whatever you last copied goes instantly"),
    MAC_CLIPBOARD("Get Mac clipboard", "Paste what you copied on the Mac"),
}

data class HistoryEntry(
    val outgoing: Boolean,
    val mac: String,
    val title: String,
    val time: Long,
    val uri: String?,
    val mime: String?,
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tapdrop", Context.MODE_PRIVATE)

    val identity: Identity

    private val _macs = MutableStateFlow(loadMacs())
    val macs: StateFlow<List<SavedMac>> = _macs.asStateFlow()

    private val _sticker = MutableStateFlow(sp.getString("stickerMac", null))
    val stickerMac: StateFlow<String?> = _sticker.asStateFlow()

    private val _tapAction = MutableStateFlow(
        runCatching { TapAction.valueOf(sp.getString("tapAction", null) ?: "") }.getOrDefault(TapAction.ASK)
    )
    val tapAction: StateFlow<TapAction> = _tapAction.asStateFlow()

    private val _appModes = MutableStateFlow(loadModes())
    /** Per-app overrides; apps not listed use [SendMode.SMART]. */
    val appModes: StateFlow<Map<String, SendMode>> = _appModes.asStateFlow()

    private val _smartOtp = MutableStateFlow(sp.getBoolean("smartOtp", true))
    val smartOtp: StateFlow<Boolean> = _smartOtp.asStateFlow()

    private val _pickFirst = MutableStateFlow(sp.getBoolean("pickFirst", true))
    /** After a notch tap, freeze the screen for a moment so a part of it can be tapped/selected. */
    val pickFirst: StateFlow<Boolean> = _pickFirst.asStateFlow()

    fun setPickFirst(on: Boolean) {
        _pickFirst.value = on
        sp.edit { putBoolean("pickFirst", on) }
    }

    private val _otpCopyOnPhone = MutableStateFlow(sp.getBoolean("otpCopyOnPhone", true))
    val otpCopyOnPhone: StateFlow<Boolean> = _otpCopyOnPhone.asStateFlow()

    private val _restrictedTried = MutableStateFlow(sp.getBoolean("restrictedTried", false))
    /**
     * Tap to send setup: TapDrop was tried in Accessibility once. Android only offers "Allow restricted settings" in
     * App info after the user has tried to switch a restricted app on there and been told it was denied.
     */
    val restrictedTried: StateFlow<Boolean> = _restrictedTried.asStateFlow()

    fun setRestrictedTried(on: Boolean) {
        _restrictedTried.value = on
        sp.edit { putBoolean("restrictedTried", on) }
    }

    private val _restrictedVisited = MutableStateFlow(sp.getBoolean("restrictedVisited", false))
    /** …and App info was opened after that, to allow it (Android 15+ doesn't let an app read whether it was). */
    val restrictedVisited: StateFlow<Boolean> = _restrictedVisited.asStateFlow()

    fun setRestrictedVisited(on: Boolean) {
        _restrictedVisited.value = on
        sp.edit { putBoolean("restrictedVisited", on) }
    }

    private val _autoUpdates = MutableStateFlow(sp.getBoolean("autoUpdates", true))
    /** "Check for updates automatically": once a day in the background (see app.tapdrop.update.Updates). */
    val autoUpdates: StateFlow<Boolean> = _autoUpdates.asStateFlow()

    fun setAutoUpdates(on: Boolean) {
        _autoUpdates.value = on
        sp.edit { putBoolean("autoUpdates", on) }
    }

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    init {
        val id = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also { v -> sp.edit { putString("deviceId", v) } }
        val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        identity = Identity(id, name)
        // A calibration value older versions kept; taps don't use it any more.
        if (sp.contains("spotPeak")) sp.edit { remove("spotPeak") }
    }

    // MARK: Macs

    @Synchronized
    fun saveMac(m: SavedMac) {
        _macs.value = _macs.value.filterNot { it.id == m.id } + m
        persistMacs()
    }

    @Synchronized
    fun removeMac(id: String) {
        _macs.value = _macs.value.filterNot { it.id == id }
        if (_sticker.value == id) setSticker(null)
        persistMacs()
    }

    @Synchronized
    fun updateEndpoint(id: String, host: String, port: Int) {
        val cur = _macs.value.firstOrNull { it.id == id } ?: return
        if (cur.host == host && cur.port == port) return
        _macs.value = _macs.value.map { if (it.id == id) it.copy(host = host, port = port) else it }
        persistMacs()
    }

    fun mac(id: String?): SavedMac? = _macs.value.firstOrNull { it.id == id }

    private fun persistMacs() {
        val arr = JSONArray()
        _macs.value.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port)
                    .put("key", Base64.encodeToString(it.key, Base64.NO_WRAP))
            )
        }
        sp.edit { putString("macs", arr.toString()) }
    }

    private fun loadMacs(): List<SavedMac> = runCatching {
        val arr = JSONArray(sp.getString("macs", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedMac(
                o.getString("id"), o.getString("name"), Base64.decode(o.getString("key"), Base64.NO_WRAP),
                o.getString("host"), o.getInt("port"),
            )
        }
    }.getOrDefault(emptyList())

    // MARK: settings

    fun setSticker(macId: String?) {
        _sticker.value = macId
        sp.edit { putString("stickerMac", macId) }
    }

    fun setTapAction(a: TapAction) {
        _tapAction.value = a
        sp.edit { putString("tapAction", a.name) }
    }

    // MARK: per-app sending & smart codes

    fun modeFor(pkg: String): SendMode = _appModes.value[pkg] ?: SendMode.SMART

    /** The mode that will actually run (Smart resolved for this app). */
    fun effectiveMode(pkg: String): SendMode = modeFor(pkg).let { if (it == SendMode.SMART) AppRules.smart(pkg) else it }

    @Synchronized
    fun setAppMode(pkg: String, mode: SendMode) {
        _appModes.value = if (mode == SendMode.SMART) _appModes.value - pkg else _appModes.value + (pkg to mode)
        val o = JSONObject()
        _appModes.value.forEach { (k, v) -> o.put(k, v.name) }
        sp.edit { putString("appModes", o.toString()) }
    }

    private fun loadModes(): Map<String, SendMode> = runCatching {
        val o = JSONObject(sp.getString("appModes", "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { k -> runCatching { k to SendMode.valueOf(o.getString(k)) }.getOrNull() }.toMap()
    }.getOrDefault(emptyMap())

    fun setSmartOtp(on: Boolean) {
        _smartOtp.value = on
        sp.edit { putBoolean("smartOtp", on) }
    }

    fun setOtpCopyOnPhone(on: Boolean) {
        _otpCopyOnPhone.value = on
        sp.edit { putBoolean("otpCopyOnPhone", on) }
    }

    // MARK: history

    @Synchronized
    fun addHistory(e: HistoryEntry) {
        _history.value = (listOf(e) + _history.value).take(30)
        val arr = JSONArray()
        _history.value.forEach {
            arr.put(
                JSONObject().put("o", it.outgoing).put("m", it.mac).put("t", it.title).put("time", it.time)
                    .put("u", it.uri ?: JSONObject.NULL).put("mime", it.mime ?: JSONObject.NULL)
            )
        }
        sp.edit { putString("history", arr.toString()) }
    }

    private fun loadHistory(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(sp.getString("history", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            HistoryEntry(
                o.getBoolean("o"), o.getString("m"), o.getString("t"), o.getLong("time"),
                o.optString("u").takeIf { u -> u.isNotEmpty() && u != "null" },
                o.optString("mime").takeIf { m -> m.isNotEmpty() && m != "null" },
            )
        }
    }.getOrDefault(emptyList())
}
