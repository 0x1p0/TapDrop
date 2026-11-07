package app.tapdrop.core

import org.json.JSONObject

/**
 * Device names, kept in step between a phone and a Mac (PROTOCOL.md → "Names").
 *
 * Each device has one name. A person can change it on the device itself, or from the other side (rename the Mac
 * from the phone, or the phone from the Mac). Every name carries [Named.at], when a person last set it (epoch ms; 0 for
 * the system name nobody chose). Both sides send `{"t":"names","mac":{…},"phone":{…}}` (their view of both names), and
 * the newest change wins:
 *  - an entry about the **sender itself** is the owner speaking: taken when it's at least as new as ours (so a renamed
 *    computer or phone, still at 0, reaches the other side too);
 *  - an entry about the **receiver** is a rename made on the other side: taken only when strictly newer.
 * A side whose view still differs after taking what's newer answers with its own, so both settle on the same names.
 */
object Names {
    /** The longest name kept, in characters. */
    const val MAX = 40

    data class Named(val name: String, val at: Long) {
        fun json(): JSONObject = JSONObject().put("name", name).put("at", at)
    }

    /** What both sides send: [mac] and [phone], as the sender sees them. */
    fun message(mac: Named, phone: Named): JSONObject =
        JSONObject().put("t", "names").put("mac", mac.json()).put("phone", phone.json())

    /** The two entries of a `names` message (each null when missing or not a usable name). */
    fun parse(m: JSONObject): Pair<Named?, Named?> = entry(m.optJSONObject("mac")) to entry(m.optJSONObject("phone"))

    private fun entry(o: JSONObject?): Named? {
        o ?: return null
        val name = clean(o.optString("name")) ?: return null
        return Named(name, o.optLong("at", 0).coerceAtLeast(0))
    }

    /**
     * A name as people typed it, made safe to show: no control characters, spaces collapsed, at most [MAX]
     * characters (never splitting an emoji). Null when nothing is left.
     */
    fun clean(raw: String?): String? {
        raw ?: return null
        val s = buildString {
            for (ch in raw) append(if (ch.isISOControl() || ch == ' ' || ch == ' ') ' ' else ch)
        }.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        if (s.codePointCount(0, s.length) <= MAX) return s
        return s.substring(0, s.offsetByCodePoints(0, MAX)).trimEnd()
    }

    /** The sender's entry about itself: the owner's word, taken unless ours is newer. */
    fun takeFromOwner(local: Named, incoming: Named): Boolean = incoming.at >= local.at && incoming != local

    /** The sender's entry about us: a rename made over there, taken only when it's newer than ours. */
    fun takeFromPeer(local: Named, incoming: Named): Boolean = incoming.at > local.at
}
