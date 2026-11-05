package app.tapdrop.update

import org.json.JSONArray
import org.json.JSONObject

/** A release version, "2.1.0" or the tag "v2.1.0", compared part by part as numbers (so 2.10.0 > 2.9.0). */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** "v2.1.0", "2.1", "2.1.0-beta" (the suffix is ignored) → a version; anything else → null. */
        fun parse(text: String?): Version? {
            val core = text?.trim()?.removePrefix("v")?.removePrefix("V")?.substringBefore('-')?.substringBefore('+')
            if (core.isNullOrEmpty()) return null
            val parts = core.split('.')
            if (parts.size > 3) return null
            val n = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..9999 } ?: return null }
            return Version(n[0], n.getOrElse(1) { 0 }, n.getOrElse(2) { 0 })
        }
    }
}

/** A file attached to a release. */
data class Asset(val name: String, val url: String, val size: Long)

/**
 * One published release, as GitHub's `releases/latest` describes it: the version, its page, the "What's new"
 * notes (simple Markdown) and the two files an update needs, the Android APK and SHA256SUMS.txt.
 */
data class Release(
    val version: Version,
    val tag: String,
    val title: String,
    val pageUrl: String,
    val notes: String,
    val apk: Asset?,
    val sums: Asset?,
) {
    fun toJson(): String = JSONObject()
        .put("tag_name", tag)
        .put("name", title)
        .put("html_url", pageUrl)
        .put("body", notes)
        .put("assets", JSONArray().apply { listOfNotNull(apk, sums).forEach { put(it.toJson()) } })
        .toString()

    companion object {
        const val SUMS_NAME = "SHA256SUMS.txt"

        fun apkName(v: Version) = "TapDrop-$v-android.apk"

        /** Reads a release object; null when it isn't a finished release with a version we can read. */
        fun parse(json: String): Release? = runCatching {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.getString("tag_name")
            val version = Version.parse(tag) ?: return null
            val assets = o.optJSONArray("assets") ?: JSONArray()
            val all = (0 until assets.length()).mapNotNull { i ->
                val a = assets.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.isEmpty() || url.isEmpty()) null else Asset(name, url, a.optLong("size", -1L))
            }
            Release(
                version = version,
                tag = tag,
                title = o.optString("name").ifBlank { "TapDrop $version" },
                pageUrl = o.optString("html_url").ifBlank { "https://github.com/0x1p0/TapDrop/releases/tag/$tag" },
                notes = o.optString("body").replace("\r\n", "\n"),
                apk = all.firstOrNull { it.name == apkName(version) } ?: all.firstOrNull { it.name.endsWith("-android.apk") },
                sums = all.firstOrNull { it.name == SUMS_NAME },
            )
        }.getOrNull()

        private fun Asset.toJson() = JSONObject().put("name", name).put("browser_download_url", url).put("size", size)
    }
}

/**
 * The SHA-256 a `SHA256SUMS.txt` lists for [fileName] (lines of `<64 hex>  <name>`, as `shasum -a 256` writes
 * them; a `*` before the name marks binary mode), in lower case. Null when the file isn't listed.
 */
fun sha256For(sums: String, fileName: String): String? = sums.lineSequence()
    .map { it.trim() }
    .mapNotNull { line ->
        val m = SUMS_LINE.matchEntire(line) ?: return@mapNotNull null
        m.groupValues[2].trim() to m.groupValues[1].lowercase()
    }
    .firstOrNull { (name, _) -> name == fileName }
    ?.second

private val SUMS_LINE = Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""")
