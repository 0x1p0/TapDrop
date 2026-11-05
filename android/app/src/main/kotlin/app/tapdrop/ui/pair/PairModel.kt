package app.tapdrop.ui.pair

import androidx.compose.runtime.Immutable
import app.tapdrop.core.Endpoint
import app.tapdrop.core.PairRejectedException
import app.tapdrop.core.ProtocolException
import app.tapdrop.core.TapDropClient
import app.tapdrop.core.UnreachableException
import org.json.JSONException
import java.io.IOException
import java.net.SocketTimeoutException

/** A Mac running TapDrop that this phone can see on the network. */
@Immutable
data class NearbyMac(val id: String, val name: String, val endpoint: Endpoint, val paired: Boolean)

/** Why pairing stopped. Each one gets its own words and its own way forward. */
enum class PairProblem {
    /** Someone chose not to pair on the Mac. */
    Declined,

    /** The code sat on both screens until the Mac's prompt gave up (about two minutes). */
    Expired,

    /** Nothing answered at that address: usually a different Wi‑Fi, or TapDrop isn't open. */
    Unreachable,

    /** The Mac stopped answering halfway (it quit, slept or changed networks). */
    Lost,

    /** Something answered, but it isn't TapDrop (a mistyped address or port). */
    NotTapDrop,

    /** Anything else. */
    Other,
}

/** The pairing sheet, one step at a time. */
@Immutable
sealed interface PairStep {
    /** Typing a Mac's address by hand. [error] is shown under the field. */
    data class Address(val error: String? = null) : PairStep

    /** Connecting to [macName]; its code hasn't arrived yet. */
    data class Connecting(val macName: String) : PairStep

    /** Both screens show [code]; waiting for Pair on the Mac. */
    data class Code(val macName: String, val code: String) : PairStep

    /** Paired. [code] keeps the cells filled while the sheet celebrates. */
    data class Paired(val macName: String, val code: String?) : PairStep

    /** Stopped. [manual] is true when the Mac came from a typed address. */
    data class Failed(val macName: String, val problem: PairProblem, val manual: Boolean) : PairStep
}

/** Sorts an exception from [app.tapdrop.net.Transfers.pair] into a [PairProblem]. */
fun pairProblem(e: Throwable, codeShownForMs: Long?): PairProblem = when (e) {
    // The Mac answers "no" both when someone declines and when its prompt times out after two minutes.
    is PairRejectedException -> if ((codeShownForMs ?: 0L) >= 110_000L) PairProblem.Expired else PairProblem.Declined
    is UnreachableException -> PairProblem.Unreachable
    is SocketTimeoutException -> if (codeShownForMs != null) PairProblem.Expired else PairProblem.Lost
    is ProtocolException, is JSONException -> PairProblem.NotTapDrop
    is IOException -> PairProblem.Lost
    else -> PairProblem.Other
}

/**
 * Turns what someone typed into an endpoint. Forgiving on purpose: "192.168.1.20", "192.168.1.20:47913",
 * "studio.local", "[fe80::1]:47913", or the whole "http://192.168.1.20:47914/" from the Mac's Set Up a Phone
 * window all work (that page runs next door to TapDrop, so its port means TapDrop's).
 */
object MacAddress {
    sealed interface Parsed {
        data object Empty : Parsed
        data class Ok(val endpoint: Endpoint) : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    /** The Mac's Set Up a Phone page. */
    private const val SETUP_PAGE_PORT = 47914

    private const val NOT_AN_ADDRESS = "That doesn't look like an address. It's four numbers with dots, like 192.168.1.20."
    private const val BAD_PORT = "The part after the colon should be a port number, like 47913."

    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val scheme = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://""")

    fun parse(input: String): Parsed {
        var t = input.trim().replace(" ", "")
        if (t.isEmpty()) return Parsed.Empty
        t = t.replaceFirst(scheme, "").substringBefore('/').substringBefore('?').substringBefore('#')
        val host: String
        var port: Int? = null
        when {
            t.startsWith("[") -> {
                val end = t.indexOf(']')
                if (end < 0) return Parsed.Invalid(NOT_AN_ADDRESS)
                host = t.substring(1, end)
                val rest = t.substring(end + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return Parsed.Invalid(NOT_AN_ADDRESS)
                    port = rest.drop(1).toIntOrNull() ?: return Parsed.Invalid(BAD_PORT)
                }
            }
            t.count { it == ':' } == 1 -> {
                host = t.substringBefore(':')
                port = t.substringAfter(':').toIntOrNull() ?: return Parsed.Invalid(BAD_PORT)
            }
            else -> host = t
        }
        if (port != null && port !in 1..65535) return Parsed.Invalid(BAD_PORT)
        if (!validHost(host)) return Parsed.Invalid(NOT_AN_ADDRESS)
        val p = if (port == null || port == SETUP_PAGE_PORT) TapDropClient.DEFAULT_PORT else port
        return Parsed.Ok(Endpoint(host, p))
    }

    private fun validHost(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        if (h.contains(':')) return validIpv6(h)
        // Anything made of digits and dots must be a whole IPv4 address ("192.168.1" is half of one).
        if (h.all { it.isDigit() || it == '.' }) return validIpv4(h)
        return h.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun validIpv4(h: String): Boolean =
        ipv4.matchEntire(h)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

    /** An IPv6 literal: up to eight hex groups, one "::" at most, an IPv4 tail and a zone ("%wlan0") allowed. */
    private fun validIpv6(raw: String): Boolean {
        val h = raw.substringBefore('%')
        if (raw.contains('%') && raw.substringAfter('%').isEmpty()) return false
        if (h.indexOf("::") != h.lastIndexOf("::")) return false
        val compressed = h.contains("::")
        val parts = h.split(':')
        var groups = 0
        parts.forEachIndexed { i, p ->
            when {
                p.isEmpty() -> if (!compressed) return false
                i == parts.lastIndex && p.contains('.') -> {
                    if (!validIpv4(p)) return false
                    groups += 2
                }
                p.length <= 4 && p.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> groups++
                else -> return false
            }
        }
        return if (compressed) groups < 8 else groups == 8
    }

    /** "192.168.1.20" for the default port, "192.168.1.20:5000" otherwise; what the sheet calls an unnamed Mac. */
    fun label(ep: Endpoint): String {
        val host = if (ep.host.contains(':')) "[${ep.host}]" else ep.host
        return if (ep.port == TapDropClient.DEFAULT_PORT) ep.host else "$host:${ep.port}"
    }
}
