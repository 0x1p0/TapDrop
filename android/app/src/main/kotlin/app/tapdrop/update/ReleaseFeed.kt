package app.tapdrop.update

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where new versions come from: the latest release on GitHub, read without an account. The request carries only
 * the app's name and version, nothing about the phone or the person.
 */
object ReleaseFeed {
    const val LATEST = "https://api.github.com/repos/0x1p0/TapDrop/releases/latest"
    const val RELEASES_PAGE = "https://github.com/0x1p0/TapDrop/releases/latest"

    /** What a check found. */
    sealed interface Result {
        data class Found(val release: Release, val etag: String?) : Result
        /** Nothing changed since the release we already have (GitHub answered 304 to our ETag). */
        data object Unchanged : Result
        /** The repository has no published release yet. */
        data object NoRelease : Result
        data class Failed(val reason: Failure) : Result
    }

    enum class Failure {
        /** No connection, or GitHub didn't answer. */
        Offline,
        /** GitHub's limit for checks without an account (60 an hour per network). */
        Busy,
        /** An answer we couldn't read. */
        Unreadable,
    }

    /** Debug builds only: a feed URL set from adb (see UpdateLab in src/debug), for testing against a local server. */
    fun feedUrl(ctx: Context): String = debugFeed(ctx) ?: LATEST

    fun isDebuggable(ctx: Context) = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun debugFeed(ctx: Context): String? =
        if (isDebuggable(ctx)) Updates.store(ctx).getString(KEY_DEBUG_FEED, null)?.takeIf { it.isNotBlank() } else null

    /** Release builds only fetch over HTTPS; a debug build may use the plain-HTTP local test server. */
    fun allowed(ctx: Context, url: String): Boolean =
        url.startsWith("https://") || (debugFeed(ctx) != null && url.startsWith("http://"))

    fun userAgent(versionName: String) = "TapDrop/$versionName (Android)"

    /** Asks GitHub for the latest release. Blocking: call it off the main thread. */
    fun fetchLatest(ctx: Context, versionName: String, etag: String?): Result {
        val url = feedUrl(ctx)
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", userAgent(versionName))
                if (etag != null) setRequestProperty("If-None-Match", etag)
            }
        } catch (e: IOException) {
            return Result.Failed(Failure.Offline)
        }
        return try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    Release.parse(body)?.let { Result.Found(it, conn.getHeaderField("ETag")) } ?: Result.Failed(Failure.Unreadable)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> Result.Unchanged
                HttpURLConnection.HTTP_NOT_FOUND -> Result.NoRelease
                403, 429 -> Result.Failed(Failure.Busy)
                else -> Result.Failed(if (code >= 500) Failure.Offline else Failure.Unreadable)
            }
        } catch (e: IOException) {
            Result.Failed(Failure.Offline)
        } finally {
            conn.disconnect()
        }
    }

    internal const val KEY_DEBUG_FEED = "debugFeed"
}
