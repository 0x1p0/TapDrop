package app.tapdrop.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.edit
import app.tapdrop.core.Endpoint
import app.tapdrop.core.Identity
import app.tapdrop.core.MacKey
import app.tapdrop.core.Names
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A paired Mac. [nameAt]: when a person last named it (epoch ms; 0 = the name it had when paired). */
class SavedMac(
    val id: String,
    val name: String,
    val key: ByteArray,
    val host: String,
    val port: Int,
    val nameAt: Long = 0,
) {
    val macKey get() = MacKey(id, key)
    val endpoint get() = Endpoint(host, port)
    val named get() = Names.Named(name, nameAt)

    fun copy(name: String = this.name, host: String = this.host, port: Int = this.port, nameAt: Long = this.nameAt) =
        SavedMac(id, name, key, host, port, nameAt)

    override fun equals(other: Any?) =
        other is SavedMac && other.id == id && other.name == name && other.host == host && other.port == port &&
            other.nameAt == nameAt

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

    private val deviceId: String =
        sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also { v -> sp.edit { putString("deviceId", v) } }

    /** Android's own name for this phone (Settings → About phone), or its model. */
    val systemName: String = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val _myName = MutableStateFlow(
        Names.Named(sp.getString("myName", null) ?: systemName, sp.getLong("myNameAt", 0))
    )
    /** This phone's name, as its Macs see it: one set in TapDrop (here or on a Mac), else [systemName]. */
    val myName: StateFlow<Names.Named> = _myName.asStateFlow()

    /** Whether this phone's name was set in TapDrop (rather than Android's own). */
    val hasCustomName: Boolean get() = sp.getString("myName", null) != null

    /** Who this phone is, right now (its name can change while TapDrop runs). */
    val identity: Identity get() = Identity(deviceId, _myName.value.name)

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

    private val _appearance = MutableStateFlow(sp.getString("appearance", "system") ?: "system")
    /** "system", "light" or "dark": the app's look. */
    val appearance: StateFlow<String> = _appearance.asStateFlow()

    fun setAppearance(value: String) {
        _appearance.value = value
        sp.edit { putString("appearance", value) }
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

    // MARK: apps TapDrop steps aside for (banking and UPI apps that won't run with accessibility on)

    private val _guardOn = MutableStateFlow(sp.getBoolean("guardOn", true))
    /** Turn tap to send off while an app in [guarded] is open (Settings → Banking apps). */
    val guardOn: StateFlow<Boolean> = _guardOn.asStateFlow()

    fun setGuardOn(on: Boolean) {
        _guardOn.value = on
        sp.edit { putBoolean("guardOn", on) }
    }

    // The list is the known apps (Guard.KNOWN, which can grow with updates), plus the ones added (by hand or learned),
    // minus the ones taken off by hand. A removed app is never learned again; adding it back is one switch.
    private val _guardAdded = MutableStateFlow(sp.getStringSet("guardAdded", null)?.toSet() ?: emptySet())
    private val _guardRemoved = MutableStateFlow(sp.getStringSet("guardRemoved", null)?.toSet() ?: emptySet())
    private fun guardList() = app.tapdrop.tap.Guard.KNOWN + _guardAdded.value - _guardRemoved.value
    private val _guarded = MutableStateFlow(guardList())
    /** Apps TapDrop turns itself off for: the ones known to refuse accessibility, plus any it learned or was given. */
    val guarded: StateFlow<Set<String>> = _guarded.asStateFlow()
    /** Apps taken off the list by hand: TapDrop doesn't learn them again. */
    val guardRemoved: StateFlow<Set<String>> = _guardRemoved.asStateFlow()

    @Synchronized
    fun setGuarded(pkg: String, on: Boolean) {
        if (on) {
            _guardRemoved.value -= pkg
            if (pkg !in app.tapdrop.tap.Guard.KNOWN) _guardAdded.value += pkg
        } else {
            _guardAdded.value -= pkg
            _guardRemoved.value += pkg
        }
        _guarded.value = guardList()
        // Written at once: a pause ends the process right after learning an app.
        sp.edit(commit = true) {
            putStringSet("guardAdded", _guardAdded.value)
            putStringSet("guardRemoved", _guardRemoved.value)
        }
    }

    init {
        // A test build kept the whole list under one key (when BHIM was the only known app); keep what it held.
        sp.getStringSet("guarded", null)?.toSet()?.let { old ->
            _guardAdded.value = old - app.tapdrop.tap.Guard.KNOWN
            _guardRemoved.value = setOf("in.org.npci.upiapp") - old
            _guarded.value = guardList()
            sp.edit {
                putStringSet("guardAdded", _guardAdded.value)
                putStringSet("guardRemoved", _guardRemoved.value)
                remove("guarded")
            }
        }
    }

    private val _pausedFor = MutableStateFlow(sp.getString("pausedFor", null))
    /** The app tap to send is off for right now (it turned itself off when that app opened), or null. */
    val pausedFor: StateFlow<String?> = _pausedFor.asStateFlow()

    fun setPausedFor(pkg: String?) {
        _pausedFor.value = pkg
        // Written at once (with everything else still waiting to be saved): a pause ends the process right after.
        sp.edit(commit = true) { putString("pausedFor", pkg) }
    }

    private val _showRecent = MutableStateFlow(sp.getBoolean("showRecent", true))
    /** Whether Home shows what was sent and received lately (Settings → Recent). */
    val showRecent: StateFlow<Boolean> = _showRecent.asStateFlow()

    fun setShowRecent(on: Boolean) {
        _showRecent.value = on
        sp.edit { putBoolean("showRecent", on) }
    }

    init {
        // A calibration value older versions kept; taps don't use it any more.
        if (sp.contains("spotPeak")) sp.edit { remove("spotPeak") }
    }

    // MARK: Macs

    @Synchronized
    fun saveMac(m: SavedMac) {
        _macs.value = _macs.value.filterNot { it.id == m.id } + m
        persistMacs()
        // Paired (again): nothing left to tell that Mac, and no reason to say it forgot this phone.
        dropUnpair(m.id)
        if (_forgottenBy.value != null) setForgottenBy(null)
    }

    /** The Mac that last forgot this phone (it said so), for the welcome screen; cleared on pairing. */
    private val _forgottenBy = MutableStateFlow(sp.getString("forgottenBy", null))
    val forgottenBy: StateFlow<String?> = _forgottenBy.asStateFlow()

    fun setForgottenBy(macName: String?) {
        _forgottenBy.value = macName
        sp.edit { putString("forgottenBy", macName) }
    }

    /** [mac] forgot this phone: it goes from here too. */
    @Synchronized
    fun macForgotUs(mac: SavedMac) {
        if (mac(mac.id)?.key?.contentEquals(mac.key) != true) return  // re-paired since: that's a new pairing
        removeMac(mac.id)
        setForgottenBy(mac.name)
    }

    /** Macs this phone forgot that haven't heard yet (they're told the next time they're reachable). */
    @Synchronized
    fun pendingUnpairs(): List<SavedMac> = parseMacs(sp.getString("unpairs", "[]"))

    @Synchronized
    fun addUnpair(m: SavedMac) = sp.edit { putString("unpairs", macsJson(pendingUnpairs().filterNot { it.id == m.id } + m)) }

    @Synchronized
    fun dropUnpair(id: String) {
        val now = pendingUnpairs()
        if (now.any { it.id == id }) sp.edit { putString("unpairs", macsJson(now.filterNot { it.id == id })) }
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

    // MARK: names (PROTOCOL.md → "Names")

    /**
     * Names this phone [name] (null: back to Android's own name), as set at [at]. Returns false when nothing changed.
     */
    @Synchronized
    fun setMyName(name: String?, at: Long = System.currentTimeMillis()): Boolean {
        val clean = Names.clean(name)
        val next = Names.Named(clean ?: systemName, at)
        if (next == _myName.value) return false
        _myName.value = next
        sp.edit {
            if (clean == null) remove("myName") else putString("myName", clean)
            putLong("myNameAt", at)
        }
        return true
    }

    /** Names Mac [id] [name], as set at [at]. Returns false when nothing changed. */
    @Synchronized
    fun setMacName(id: String, name: String, at: Long = System.currentTimeMillis()): Boolean {
        val clean = Names.clean(name) ?: return false
        val cur = mac(id) ?: return false
        if (cur.name == clean && cur.nameAt == at) return false
        _macs.value = _macs.value.map { if (it.id == id) it.copy(name = clean, nameAt = at) else it }
        persistMacs()
        return true
    }

    /** What this phone tells Mac [mac] about both names. */
    fun namesFor(mac: SavedMac): org.json.JSONObject = Names.message(mac = mac.named, phone = _myName.value)

    /**
     * Takes what's newer from Mac [macId]'s `names` (the Mac's own entry is its word; its entry about this phone is
     * a rename made on the Mac). Returns whether this phone's own name changed, and whether the Mac's view still
     * differs from ours (then it should hear ours).
     */
    @Synchronized
    fun takeNames(macId: String, m: org.json.JSONObject): NamesTaken {
        val (theirMac, theirPhone) = Names.parse(m)
        val mac = mac(macId) ?: return NamesTaken(false, false)
        if (theirMac != null && Names.takeFromOwner(mac.named, theirMac)) setMacName(macId, theirMac.name, theirMac.at)
        var mine = false
        if (theirPhone != null && Names.takeFromPeer(_myName.value, theirPhone)) {
            // Renamed on the Mac: that's this phone's name now (a name equal to Android's own stays "not set").
            mine = setMyName(theirPhone.name.takeIf { it != systemName }, theirPhone.at)
        }
        val now = mac(macId) ?: return NamesTaken(mine, false)
        val differs = theirMac != now.named || theirPhone != _myName.value
        return NamesTaken(mine, differs)
    }

    class NamesTaken(val myNameChanged: Boolean, val answer: Boolean)

    private fun persistMacs() = sp.edit { putString("macs", macsJson(_macs.value)) }

    private fun macsJson(macs: List<SavedMac>): String {
        val arr = JSONArray()
        macs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port)
                    .put("key", Base64.encodeToString(it.key, Base64.NO_WRAP)).put("nameAt", it.nameAt)
            )
        }
        return arr.toString()
    }

    private fun loadMacs(): List<SavedMac> = parseMacs(sp.getString("macs", "[]"))

    private fun parseMacs(json: String?): List<SavedMac> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedMac(
                o.getString("id"), o.getString("name"), Base64.decode(o.getString("key"), Base64.NO_WRAP),
                o.getString("host"), o.getInt("port"), o.optLong("nameAt", 0),
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

    /** Forgets the list of what was sent and received. The files themselves stay where they are. */
    @Synchronized
    fun clearHistory() {
        _history.value = emptyList()
        sp.edit { putString("history", "[]") }
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
