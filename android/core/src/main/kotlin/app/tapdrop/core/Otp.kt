package app.tapdrop.core

/**
 * Finds one-time codes in texts and notifications.
 *
 * Precision comes first: a missed code costs a few seconds of typing, a random number on the Mac costs trust. A
 * number only counts when the words around it say it's a code ("OTP is 482913", "482913 is your Instagram code",
 * "Use 482913 to verify", "G-482913"). Money, phone numbers, dates and times, card and account tails, order and
 * reference numbers, durations, versions and anything inside a link never count, and two codes that compete with
 * no clear winner give nothing.
 */
object Otp {
    /** Where a text came from. Friends send numbers all day in chat apps, so only the plainest wording counts there. */
    enum class From { SMS, NOTIFICATION, CHAT }

    /**
     * The code in [text] (digits only), or null. [title] is a notification's title: it may say "Verification code"
     * above a bare number, but a number in it never counts (it's usually a person, a group or a phone number).
     */
    fun find(text: String, from: From = From.SMS, title: String? = null): String? {
        val t = normalize(text)
        val low = lower(t)
        val cands = candidates(t, low)
        if (cands.isEmpty()) return null
        val chat = from == From.CHAT
        // In a chat, a message with two code-shaped numbers ("OTP 4821… no wait, use 7735") is never clear enough.
        if (chat && cands.distinctBy { it.code }.size > 1) return null
        val kws = keywords(t, low)
        val promo = promoWords.containsMatchIn(low)
        val picks = cands.mapNotNull { c ->
            val l = link(t, low, c, kws, cands, chat) ?: return@mapNotNull null
            val enough = if (chat) l.score == 3 && l.strong else l.score >= 2
            // A bare "code" in an offer is a coupon, and one followed by what it opens is that thing's code
            // ("Use code 4821 at the gate"); a year-like number needs to sit right next to its keyword.
            val weakOk = l.strong || (!promo && !hasBadWord(clauseAfter(t, c.end)))
            if (enough && weakOk && (!yearLike(c.code) || l.tight)) c to l.score else null
        }
        if (picks.isEmpty()) return titled(low, cands, from, title, promo)
        val top = picks.maxOf { it.second }
        // Two different codes tied this well: there's no telling which one is meant.
        val winner = picks.filter { it.second == top }.map { it.first }.distinctBy { it.code }.singleOrNull() ?: return null
        // "OTPs 4821 and 7735", "482913 (email) and 650214 (mobile)": a list of codes, not one.
        if (cands.any { it.code != winner.code && listed(t, winner, it) }) return null
        return winner.code
    }

    private val listJoin = Regex("(?:^|[^\\p{L}])(?:and|or|aur|ya|या|और|y|o|et|ou|und|oder|e)(?![\\p{L}])|[,/&]")

    private fun listed(t: String, a: Cand, b: Cand): Boolean {
        val between = if (a.end <= b.start) t.substring(a.end, b.start) else t.substring(b.end, a.start)
        return '\n' !in between && !crossesSentence(between) && words(between) <= 3 && listJoin.containsMatchIn(lower(between))
    }

    /** A code's digits, however it was written: "123 456", "123-456" and "123456" are one code. */
    fun digits(code: String): String = code.filter { it in '0'..'9' }

    /**
     * Chat and social apps: what shows there is mostly what people wrote, so a code needs the plainest wording.
     * (SMS apps aren't here: texts from anyone count, as long as they say they carry a code.)
     */
    fun isChatApp(pkg: String): Boolean = pkg in chatApps

    private val chatApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "org.thunderdog.challegram", "org.thoughtcrime.securesms", "com.facebook.orca", "com.facebook.mlite",
        "com.facebook.katana", "com.facebook.lite", "com.instagram.android", "com.instagram.barcelona", "com.discord",
        "com.snapchat.android", "com.Slack", "com.microsoft.teams", "com.skype.raider", "com.viber.voip",
        "jp.naver.line.android", "com.tencent.mm", "com.kakao.talk", "com.google.android.apps.dynamite",
        "com.twitter.android", "com.reddit.frontpage", "com.linkedin.android", "com.zhiliaoapp.musically",
        "com.google.android.youtube", "ch.threema.app", "im.vector.app", "com.wire", "network.loki.messenger",
        "com.beeper.android", "kik.android", "in.mohalla.sharechat", "com.pinterest", "com.tumblr", "org.joinmastodon.android",
        "com.bereal.ft", "com.imo.android.imoim", "com.hike.chat.stickers", "com.truecaller.messenger"
    )

    // MARK: reading the text

    /** Same length as the input, so positions line up: spaces, dashes and any script's digits made plain. */
    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s) append(
            when {
                c in '0'..'9' -> c
                Character.isDigit(c) -> '0' + Character.digit(c, 10)   // "४८२९१३" → "482913"
                c == '\r' || c == '\u2028' || c == '\u2029' -> '\n'
                c == '\t' || Character.isSpaceChar(c) -> ' '
                c in "\u2010\u2011\u2012\u2013\u2014\u2212" -> '-'
                c in "\u2018\u2019" -> '\''
                c in "\u201C\u201D" -> '"'
                else -> c
            }
        )
    }

    private fun lower(s: String) = String(CharArray(s.length) { s[it].lowercaseChar() })

    private fun Char.wordy() = isLetterOrDigit() || Character.getType(this).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun words(s: String) = s.split(' ', '\n').count { w -> w.any { it.isLetterOrDigit() } }

    private fun yearLike(code: String) = code.length == 4 && code.toInt() in 1900..2099

    private fun clockLike(code: String) = code.length == 4 && code.take(2).toInt() <= 23 && code.drop(2).toInt() <= 59

    // MARK: keywords

    private class Kw(val start: Int, val end: Int, val strong: Boolean)

    private const val W = "\\p{L}\\p{M}\\p{N}"

    /** Words that only ever mean a one-time code. */
    private val strongWords = Regex(
        listOf(
            "otps?", "o\\.t\\.p\\.?",
            "one[- ]?time[- ](?:password|passcode|pass code|pin|code|token|security code|verification code|login code|" +
                "sign[- ]?in code|auth(?:entication|ori[sz]ation)? code)",
            "single[- ]use (?:code|password|passcode)",
            "(?:verification|verify|validation|confirmation|security|login|log[- ]in|sign[- ]?in|sign[- ]on|sign[- ]?up|" +
                "registration|activation|authentication|2fa|2-step|two[- ]step|two[- ]factor|2-factor|mfa|temporary|temp) " +
                "(?:code|pin|otp|passcode)",
            "verification (?:number|no\\.?)", "pass ?code", "temporary password",
            "c[oó]digo de (?:verificaci[oó]n|verifica[cç][aã]o|seguridad|seguran[cç]a|confirmaci[oó]n|confirma[cç][aã]o|" +
                "acceso|acesso|autenticaci[oó]n|autentica[cç][aã]o|validaci[oó]n|valida[cç][aã]o|firma|operaci[oó]n|" +
                "activaci[oó]n|ativa[cç][aã]o|un solo uso|uso [uú]nico)",
            "clave (?:de )?(?:firma|operaci[oó]n|acceso|seguridad|din[aá]mica|sms)",
            "code (?:de |d')(?:v[ée]rification|s[ée]curit[ée]|confirmation|validation|connexion|acc[eè]s)",
            "code (?:à|a) usage unique",
            "codice (?:di verifica|di sicurezza|di conferma|di accesso|otp|monouso|dispositivo|sms)",
            "(?:best[äa]tigungs|bestaetigungs|verifizierungs|sicherheits|einmal|anmelde)code", "einmalpasswort",
            "(?:verificatie|beveiligings|bevestigings|inlog)code",
            "kod (?:weryfikacyjny|weryfikacji|bezpiecze[nń]stwa|sms|jednorazowy|potwierdzaj[aą]cy|dost[eę]pu)",
            "do[gğ]rulama kodu", "engangskode", "bekr[æa]ftelseskode", "verifieringskod", "eng[åa]ngskod", "vahvistuskoodi",
            "ओ\\.?\\s?टी\\.?\\s?पी\\.?", "वन टाइम पासवर्ड", "एक बार का पासवर्ड",
            "(?:सत्यापन|वेरिफिकेशन|पुष्टिकरण|सुरक्षा|लॉगिन|लॉग इन) कोड", "पासकोड",
        ).joinToString("|", "(?<![$W])(?:", ")(?![$W])")
    )

    /** German banks' "TAN", in capitals only ("tan" is a colour). */
    private val tan = Regex("(?<![$W])(?:m|SMS-?|push|chip)?TAN(?![$W])")

    /** Words that mean a code only in the right company: "your Uber code", not "promo code" or "PIN code". */
    private val weakWords = Regex("(?<![$W])(?:code|pin|codice|c[oó]digo|kod|kode|koodi|कोड|पिन)(?![$W])")

    /** What a bare "code" or "PIN" is when these come right before it (or sit between it and the number). */
    private val badModifiers = setOf(
        "promo", "promotional", "coupon", "discount", "voucher", "referral", "refer", "invite", "invitation", "gift",
        "redeem", "redemption", "offer", "deal", "sale", "cashback", "reward", "rewards", "bonus", "zip", "postal", "post",
        "postcode", "pin", "pincode", "area", "country", "std", "isd", "dial", "dialing", "dialling", "landline", "dress",
        "qr", "bar", "barcode", "source", "error", "status", "response", "reason", "exit", "ifsc", "swift", "sort", "bic",
        "iban", "branch", "bank", "mcc", "hsn", "sac", "product", "item", "sku", "style", "model", "colour", "color", "size",
        "course", "class", "subject", "exam", "roll", "employee", "booking", "reservation", "tracking", "ticket", "gate",
        "door", "wifi", "wi-fi", "lock", "locker", "lockbox", "alarm", "entry", "entrance", "building", "parking", "garage",
        "meeting", "conference", "participant", "host", "room", "cheat", "tax", "routing", "transit", "sharing", "group",
        "team", "join", "joining", "pairing", "tv", "morse", "merchant", "store", "outlet", "terminal", "approval",
        "authorization", "authorisation", "auth", "appointment", "event", "seat", "table", "order", "invoice", "member",
        "membership", "customer", "consumer", "unique", "lucky", "winning", "scratch", "contest", "quiz", "ussd",
        "short", "sender", "family", "parental", "screen", "vehicle", "flat", "house", "unit", "upi", "station", "batch",
        "ref", "reference", "emoji", "atm", "access", "lobby", "game", "party", "server", "match", "return", "cancellation",
        "claim", "lot", "dealer", "student", "client", "unlock", "puk", "recovery", "backup", "secret", "key", "keypad",
        "box", "install", "installation", "setup", "router", "पिन", "पोस्टल", "प्रोमो", "कूपन",
    )
    private val skipWords = setOf("your", "ur", "yr", "the", "this", "that", "a", "an", "my", "our", "its", "his", "her", "their")
    private val stopWords = setOf(
        "is", "are", "was", "were", "be", "enter", "use", "type", "input", "here", "here's", "heres", "with", "to", "for",
        "of", "and", "or", "by", "as", "at", "on", "in", "share", "send", "sent", "get", "got", "received", "reply",
        "below", "following", "same", "please", "kindly", "new", "requested", "आपका", "आपके", "आपकी", "का", "के", "की",
    )
    private val okModifiers = setOf(
        "sms", "text", "digit", "4-digit", "5-digit", "6-digit", "7-digit", "8-digit", "account", "app", "mobile", "phone",
        "email", "e-mail", "otp", "one-time", "onetime", "secure", "login", "id", "numeric", "verification", "security",
        "delivery",
    )
    private val codeNextBad = setOf(
        "review", "reviews", "base", "editor", "of", "red", "word", "name", "snippet", "repository", "repo", "quality",
        "coverage", "smell", "block", "generation", "de", "di", "do", "da", "postal", "promocional", "promo", "zip", "pin",
        "sharing", "words",
    )
    private val pinNextBad = setOf("code", "codes", "no", "number", "point", "board", "up", "it", "कोड", "kode")

    private fun keywords(t: String, low: String): List<Kw> {
        val out = ArrayList<Kw>()
        strongWords.findAll(low).forEach { out += Kw(it.range.first, it.range.last + 1, true) }
        tan.findAll(t).forEach { out += Kw(it.range.first, it.range.last + 1, true) }
        weakWords.findAll(low).forEach { m ->
            val s = m.range.first
            val e = m.range.last + 1
            if (out.none { it.strong && s < it.end && e > it.start } && weakOk(t, low, s, e)) out += Kw(s, e, false)
        }
        // A meeting's passcode or PIN stays the same for every call: it isn't a one-time code.
        return out.filter { k ->
            val w = low.substring(k.start, k.end)
            !(w in meetingWords && meeting.containsMatchIn(low.substring(maxOf(0, k.start - 60), minOf(low.length, k.end + 30))))
        }
    }

    private val meetingWords = setOf("passcode", "pass code", "code", "pin")
    private val meeting = Regex("\\b(?:meeting|zoom|webinar|conference|dial[- ]?in|join(?:ing)?|meet|call id|room)\\b")

    private fun nextWord(low: String, from: Int): String {
        var i = from
        while (i < low.length && low[i] == ' ') i++
        var j = i
        while (j < low.length && (low[j].wordy() || low[j] == '-')) j++
        return low.substring(i, j)
    }

    /** Whether a bare "code" or "PIN" here means a one-time code. */
    private fun weakOk(t: String, low: String, s: Int, e: Int): Boolean {
        val next = nextWord(low, e)
        val pin = low.substring(s, e).let { it == "pin" || it == "पिन" }
        if (next in (if (pin) pinNextBad else codeNextBad)) return false
        // The word before: "Uber code" and "your code" are codes, "gate code" and "promo code" aren't.
        var i = s
        repeat(4) {
            var j = i - 1
            while (j >= 0 && t[j] == ' ') j--
            if (j < 0 || !(t[j].wordy() || t[j] == '-')) return true
            var b = j
            while (b > 0 && (t[b - 1].wordy() || t[b - 1] == '-' || t[b - 1] == '\'')) b--
            val word = low.substring(b, j + 1)
            when (word) {
                in badModifiers -> return false
                in skipWords -> i = b
                in stopWords, in okModifiers -> return true
                // Brands are written with a capital ("Uber code"); a lowercase noun is some other code. Hindi has no case.
                else -> return !t[b].isLowerCase()
            }
        }
        return true
    }

    /** The rest of the number's sentence, up to 40 characters. */
    private fun clauseAfter(t: String, end: Int): String {
        var e = minOf(t.length, end + 40)
        for (i in end until e) if (t[i] in ".!?।|\n" && (i + 1 >= t.length || !t[i + 1].isDigit())) { e = i; break }
        return t.substring(end, e).lowercase()
    }

    private fun hasBadWord(low: String) = low.split(' ', ',', '.', ':', ';', '\n', '(', ')').any { it in badModifiers }

    // MARK: numbers

    private class Cand(val start: Int, val end: Int, val code: String, val branded: Boolean)

    /** Numbers shaped like a code that nothing around them rules out. */
    private fun candidates(t: String, low: String): List<Cand> {
        val out = ArrayList<Cand>()
        var i = 0
        while (i < t.length) {
            if (t[i] !in '0'..'9') { i++; continue }
            val start = i
            val groups = ArrayList<Int>()
            val seps = StringBuilder()
            var j = i
            while (true) {
                var k = j
                while (k < t.length && t[k] in '0'..'9') k++
                groups += k - j
                val next = joinAt(t, low, start, k, groups.last())
                if (next < 0) { j = k; break }
                seps.append(t[k])
                j = next
            }
            i = j
            val end = j
            if (start > 0 && t[start - 1] == '+') continue
            val code = when {
                groups.size == 1 && groups[0] in 4..8 -> t.substring(start, end)
                // "123 456" and "123-456"; longer chains are phone, card, Aadhaar and date numbers
                groups.size == 2 && seps[0] in " -" && groups.all { it in 3..4 } -> t.substring(start, end).filter { it in '0'..'9' }
                else -> continue
            }
            val edge = edges(t, low, start, end, code.length)
            if (edge == Edge.BAD) continue
            val branded = edge == Edge.BRANDED
            if (!branded && (ruledOut(t, low, start, end, code) || inLink(t, start, end))) continue
            out += Cand(start, end, code, branded)
        }
        return out
    }

    /** Where the next group of digits starts if the one ending at [k] runs on into it ("1,234.00", "98765 43210"), or -1. */
    private fun joinAt(t: String, low: String, start: Int, k: Int, last: Int): Int {
        if (k + 1 >= t.length) return -1
        val c = t[k]
        if (c in "-./:," && t[k + 1] in '0'..'9') return k + 1
        if (c == ')' && k + 2 < t.length && t[k + 1] == ' ' && t[k + 2] in '0'..'9') return k + 2
        if (c == ' ' && t[k + 1] in '0'..'9') {
            var m = k + 1
            while (m < t.length && t[m] in '0'..'9') m++
            val next = m - k - 1
            if (start > 0 && t[start - 1] in "+(" && next <= 5) return k + 1                           // "+91 98765 43210"
            if (last in 2..5 && next in 2..5 && !unitAfter.containsMatchIn(low.substring(m))) return k + 1  // "1800 425 3800"
        }
        return -1
    }

    private enum class Edge { OK, BAD, BRANDED }

    /** What the characters touching a number say: "XX1234", "₹4821", "#4821", "50%" and "4821@" aren't codes; "G-482913" is. */
    private fun edges(t: String, low: String, start: Int, end: Int, len: Int): Edge {
        val p = if (start > 0) t[start - 1] else ' '
        val q = if (end < t.length) t[end] else ' '
        if (q.wordy() || q in "%/@_\\&+#°=") return Edge.BAD
        if (p == '*' || q == '*') return if (p == q) Edge.OK else Edge.BAD    // *bold* is fine, a masked "*1234" isn't
        if (p.wordy() || p in "₹$€£¥#/@_\\&%~^|<>") return Edge.BAD
        if (p == '-') {
            var b = start - 1
            while (b > 0 && t[b - 1].isLetter()) b--
            val word = low.substring(b, start - 1)
            if (b > 0 && t[b - 1].wordy()) return Edge.BAD
            return when {
                word == "g" && t[b] == 'G' && len == 6 -> Edge.BRANDED    // Google's "G-482913"
                word == "fb" && len in 5..8 -> Edge.BRANDED               // Facebook's "FB-71204"
                word in setOf("otp", "code", "pin", "tan") -> Edge.OK      // "OTP-482913"
                else -> Edge.BAD                                           // "ORD-4471", "***-4821", "-4821"
            }
        }
        return Edge.OK
    }

    private val urlish = Regex("(?i)://|www\\.|[a-z0-9-]\\.(?:com|in|co|org|net|io|me|ly|gl|app|gov|edu|ai|dev|page|link|to|us|uk|de|fr|info|biz|xyz|site|online|store|shop)(?:/|\\?|$)")

    private fun inLink(t: String, s: Int, e: Int): Boolean {
        var a = s
        while (a > 0 && !t[a - 1].isWhitespace()) a--
        var b = e
        while (b < t.length && !t[b].isWhitespace()) b++
        return urlish.containsMatchIn(t.substring(a, b))
    }

    // What a number is when these words come right before it (each pattern ends at the number) or right after it.
    private const val NUM_MARK = "(?:(?:no|nos|number|num|id)\\.?\\s*)?[:.\\-#]?\\s*"
    private val moneyBefore = Regex("(?:₹|\\x24|€|£|¥|\\brs\\.?|\\binr|\\busd|\\beur|\\bgbp|\\baed|\\bsgd|\\bcad|\\baud|\\bchf|\\bjpy|\\brupees?|\\bamt\\.?|\\bamount|\\bbal\\.?|\\bbalance|\\bprice|\\bmrp|\\bfare|\\btotal|\\bcost|\\brefund|\\bcashback|\\bemi|\\blimit|\\bworth|\\bsalary|\\bfees?|\\bcharges?|\\bpaid|\\bpayable|रु\\.?|रुपये|राशि)\\s*(?:of|is|was|:|-|=|by|for)?\\s*[:\\-]?\\s*$")
    private val moneyVerb = Regex("(?:debited|credited|spent|received|withdrawn|deposited|charged|refunded|transferred|recharged|sent|due|डेबिट|क्रेडिट|जमा|भुगतान)\\s*(?:(?:with|by|for|of|to|amount|amt)\\s*)?(?:rs\\.?|inr|₹)?\\s*$")
    private val moneyAfter = Regex("^\\s*(?:/-|rs\\b|inr\\b|rupees?\\b|usd\\b|dollars?\\b|eur\\b|euros?\\b|₹|रुपये|रु|lakhs?\\b|crores?\\b|bucks\\b|k\\b)")
    private val phoneBefore = Regex("(?:\\bcall(?:ing)?|\\bdial(?:ling)?|\\btel(?:ephone)?|\\bph(?:one)?|\\bmob(?:ile)?|\\bcontact|\\bhelp ?line|\\bhelpdesk|\\bivr|\\bcare|\\btoll[- ]?free|\\bwhatsapp|\\bfax|\\bring|हेल्पलाइन|कॉल)\\.?\\s*(?:(?:us|me|now|on|at|to|no\\.?|number|nos?|our|the)\\s*)*[:.\\-#]?\\s*$")
    private val shortCode = Regex("(?:\\bsms|\\btext|\\bsend|\\breply|\\bmsg|\\bmessage|\\bmissed call)\\b[^.!?\\n]{0,30}?\\bto\\s*$")
    private val smsCommand = Regex("(?:\\bsms|\\btext|\\breply|\\bsend)\\s+(?!otp\\b|code\\b|pin\\b)[a-z]{2,12}\\s*$")
    private val cardBefore = Regex("(?:(?<![a-z])x{2,}|\\*+(?= )|\\bending(?: in| with)?|\\bends(?: in| with)|\\blast (?:\\d|four) digits?(?: of [\\p{L} ']{0,24})?|\\ba/?c|\\bacct|\\baccount|\\bcard|\\bac|terminad[ao] en|terminaci[oó]n|(?:se )?terminant par|terminante (?:in|con)|endend (?:auf|mit)|endet auf|eindigend op|final(?:izad[ao])? em|खाता|खाते|कार्ड)\\.?\\s*$NUM_MARK$")
    private val refBefore = Regex("(?:\\bref(?:erence)?|\\butr|\\brrn|\\bpnr|\\bawb|\\bsrn|\\bcrn|\\bcif|\\buan|\\bvid|\\beid|\\bimei|\\bvin|\\bumrn|\\bfolio|\\bdocket|\\bchallan|\\barn|\\btrn|\\bticket|\\binvoice|\\breceipt|\\bcomplaint|\\btracking|\\bconsignment|\\bshipment|\\bcase|संख्या|नंबर)\\.?\\s*(?:(?:no|nos|number|num|id|#|code|ref)\\.?\\s*)?[:.\\-#]?\\s*(?:is\\s*[:\\-]?\\s*)?$")
    private val nounBefore = Regex("(?:\\border|\\bord|\\bbooking|\\brequest|\\breq|\\bapplication|\\bappl|\\bpolicy|\\bloan|\\bclaim|\\btoken|\\bregistration|\\breg|\\broll|\\bemp|\\bserial|\\bflight|\\btrain|\\bbus|\\bseat|\\bgate|\\bplatform|\\broom|\\btable|\\bbatch|\\bmodel|\\bmsg|\\bvoucher|\\bcoupon|\\bpromo|\\bconsumer|\\bmandate|\\bsubscription|\\bdevice|\\bsession|\\bvehicle|\\brc|\\bplot|\\bhouse|\\bflat|\\bdoor|\\bunit|\\bblock|\\bsector|\\bward|\\bversion|\\bver|\\bv|\\bbuild|\\bandroid|\\bios|\\blevel|\\bchapter|\\bpage|\\bstep|\\bitem|\\bsku|\\btransaction|\\btxn|\\btrans|\\btrip|\\bride|\\bparcel|\\bpackage|\\bproduct|\\bid|\\bno|\\bnos|\\bnumber|\\bnum|#|ऑर्डर)\\.?\\s*$NUM_MARK$")
    private val markIs = Regex("(?:\\bno|\\bnumber|\\bnum|\\bid|#)\\.?\\s*(?:is|was)\\s*[:\\-]?\\s*$")
    private val keywordNo = Regex("(?:otp|verification|validation|pin|code|passcode)\\s*(?:no|number|num|id)\\.?\\s*(?:is|was)?\\s*[:\\-]?\\s*$")
    private val addressBefore = Regex("(?:pin ?code|pincode|postal ?code|zip ?code|\\bzip|post ?code|postcode|पिन ?कोड|पिनकोड)\\s*(?:no\\.?|number)?\\s*[:.\\-]?\\s*(?:is\\s*[:\\-]?\\s*)?$")
    private val pinBefore = Regex("\\bpin\\s*[:.\\-]?\\s*$")
    private val addressWords = Regex("\\b(?:address|road|rd|street|nagar|colony|sector|layout|marg|lane|district|dist|city|state|deliver(?:y|ing|ed)?|shipping|courier|india|bengaluru|bangalore|mumbai|delhi|chennai|kolkata|hyderabad|pune|village|tehsil|floor|apartment|apt)\\b")
    private val dateBefore = Regex("(?:\\bjan(?:uary)?|\\bfeb(?:ruary)?|\\bmar(?:ch)?|\\bapr(?:il)?|\\bmay|\\bjune?|\\bjuly?|\\baug(?:ust)?|\\bsept?(?:ember)?|\\boct(?:ober)?|\\bnov(?:ember)?|\\bdec(?:ember)?)\\.?,?\\s*(?:\\d{1,2}(?:st|nd|rd|th)?,?\\s*)?$")
    private val yearBefore = Regex("(?:\\byear|\\bsince|\\bin|\\bof|\\btill|\\buntil|\\bby|\\bfy|©|\\bcopyright|\\bclass of|\\bbatch|\\bfrom|\\bto|\\bbefore|\\bafter|\\bthe|\\bmodel|\\bfor)\\s*[:\\-]?\\s*$")
    private val clockBefore = Regex("(?:\\bat|\\bby|\\btill|\\buntil|\\bbefore|\\bafter|\\bfrom|\\bbetween)\\s*$")
    private val timeAfter = Regex("^\\s*(?:hrs?\\b|hours?\\b|h\\b|am\\b|pm\\b|a\\.m|p\\.m|ist\\b|gmt\\b|utc\\b|cet\\b|cest\\b|est\\b|edt\\b|pst\\b|pdt\\b|bst\\b|बजे)")
    private val unitAfter = Regex("^\\s*(?:mins?|minutes?|secs?|seconds?|hrs?|hours?|days?|weeks?|months?|yrs?|years?|kms?|kgs?|gb|mb|kb|tb|pts|points?|coins?|miles?|steps?|kcal|cal|calories|items?|units?|ml|mg|sq\\.? ?ft|sqft|people|persons?|members?|views?|likes?|followers?|times?|seats?|tickets?|orders?|rides?|trips?|reviews?|ratings?|users?|students?|words?|pages?|others?|ft|feet|metres?|meters?|mtrs?|lakhs?|crores?|reward|मिनट|सेकंड|घंटे|दिन|रुपये)(?![\\p{L}\\p{M}])")
    // Vehicle numbers: "DL3C AB 1234", "KA 01 AB 4821" (in capitals, as plates are written).
    private val plateBefore = Regex("(?<![\\p{L}\\p{N}])[A-Z]{2}[ -]?\\d{1,2}[ -]?(?:[A-Z]{1,3}[ -]?){1,2}$")
    private val numberBefore = Regex("(?<![\\d.,:/-])\\d+ $")
    private val numberAfter = Regex("^ \\d+(?![\\d.,:/])")

    /** Money, phone numbers, cards and accounts, references, addresses, dates, times, quantities. */
    private fun ruledOut(t: String, low: String, start: Int, end: Int, code: String): Boolean {
        // Only the number's own line: "account:" on the line above a code doesn't make it an account number.
        val from = maxOf(t.lastIndexOf('\n', start - 1) + 1, start - 48)
        val to = minOf(t.indexOf('\n', end).let { if (it < 0) t.length else it }, end + 32)
        val before = low.substring(from, start)
        val after = low.substring(end, to)
        val labelled = keywordNo.containsMatchIn(before)   // "OTP no. 482913", "verification number is 482913"
        return moneyBefore.containsMatchIn(before) || moneyVerb.containsMatchIn(before) || moneyAfter.containsMatchIn(after) ||
            phoneBefore.containsMatchIn(before) || shortCode.containsMatchIn(before) || smsCommand.containsMatchIn(before) ||
            cardBefore.containsMatchIn(before) ||
            (!labelled && (refBefore.containsMatchIn(before) || nounBefore.containsMatchIn(before) || markIs.containsMatchIn(before))) ||
            addressBefore.containsMatchIn(before) ||
            (code.length == 6 && pinBefore.containsMatchIn(before) && addressWords.containsMatchIn(low)) ||
            dateBefore.containsMatchIn(before) || timeAfter.containsMatchIn(after) || unitAfter.containsMatchIn(after) ||
            (yearLike(code) && yearBefore.containsMatchIn(before)) ||
            (clockLike(code) && clockBefore.containsMatchIn(before)) ||
            numberBefore.containsMatchIn(before) || plateBefore.containsMatchIn(t.substring(from, start)) ||
            numberAfter.find(after)?.let { !unitAfter.containsMatchIn(after.substring(it.range.last + 1)) } == true
    }

    // MARK: tying numbers to keywords

    /** How firmly a number is tied to a keyword: 3 plainly ("OTP is 482913"), 2 closely ("OTP for login 482913"). */
    private class Link(val score: Int, val strong: Boolean, val tight: Boolean)

    private fun better(a: Link?, b: Link?): Link? = when {
        a == null -> b
        b == null -> a
        a.score != b.score -> if (a.score > b.score) a else b
        a.strong != b.strong -> if (a.strong) a else b
        else -> if (a.tight || !b.tight) a else b
    }

    private fun link(t: String, low: String, c: Cand, kws: List<Kw>, cands: List<Cand>, chat: Boolean): Link? {
        if (c.branded) return Link(3, strong = true, tight = true)
        var best: Link? = null
        for (k in kws) {
            val l = when {
                k.end <= c.start -> forward(t, k, c, cands)
                c.end <= k.start -> backward(t, c, k, cands)
                else -> null
            }
            best = better(best, l)
        }
        best = better(best, use(low, c))
        if (!chat) best = better(best, ownLine(t, c, kws))
        return best
    }

    // "is", ":", "lautet", "है"… just before the number: the keyword's value follows.
    private val joinEnd = Regex("(?:(?<![\\p{L}\\p{M}])(?:is|are|was|as|lautet|lauten|ist|est|es|è|é|hai|है|हैं)|[:=-])\\s*[:\\-]?\\s*[(*\"']?\\s*$")
    private val shortJoin = Regex("\\s*(?:to|er|är)\\s*")
    // "is your", "-", "आपका"… just after the number: the keyword names what it is.
    private val joinStart = Regex("^\\s*[)*\"']?\\s*(?:(?:is|are|was|as|est|es|ist|è|é|है|हैं|आपका|आपकी|आपके|यह|aapka|hai)(?![\\p{L}\\p{M}])|[:=-])")
    private val labelEnd = Regex("(?:is|are|lautet|ist|est|es|è|é|है|hai)?\\s*[:\\-]?")
    // After the number, these make it something else: "4821 is the amount", "4821 is your order number".
    private val reanchor = Regex("\\b(?:amount|balance|total|number|digits?|order|ref|reference|id|ticket|pnr|date|time|year|phone|mobile|limit|fee|price|bill|invoice|points|ending)\\b")
    // A clause of its own before the number: "Never share your OTP, your request no is 482913".
    private val clauseNoun = Regex("\\b(?:balance|amount|total|number|no|id|ref|reference|request|order|ticket|pnr|limit|fee|price|bill)\\b")
    private val abbreviations = setOf(
        "rs", "no", "nos", "a/c", "ac", "dr", "mr", "mrs", "ms", "st", "vs", "approx", "avl", "bal", "amt", "ref", "mob", "ph",
        "tel", "incl", "govt", "sr", "jr", "max", "min", "e.g", "i.e", "p.o", "pvt", "ltd", "co", "inc", "a.m", "p.m", "u.s",
    )

    /** Whether [s] (original case) runs from one sentence into the next. */
    private fun crossesSentence(s: String): Boolean {
        for (i in s.indices) {
            when (s[i]) {
                '!', '?', '।', '|' -> return true
                '.' -> {
                    if (i + 1 < s.length && s[i + 1] != ' ') continue           // "Rs.500", "t.me"
                    var b = i
                    while (b > 0 && (s[b - 1].isLetter() || s[b - 1] == '/' || s[b - 1] == '.')) b--
                    val word = s.substring(b, i).lowercase()
                    if (word.length <= 1 || word in abbreviations) continue     // "Rs. 500", initials
                    var j = i + 1
                    while (j < s.length && s[j] == ' ') j++
                    if (j >= s.length || !s[j].isLowerCase()) return true
                }
            }
        }
        return false
    }

    private fun forward(t: String, k: Kw, c: Cand, cands: List<Cand>): Link? {
        // A number in between takes the keyword when the keyword plainly leads to it ("OTP 482913, 551203");
        // a stray one doesn't ("OTP for vehicle DL3C 4417 is 196307").
        if (cands.any { it !== c && it.start >= k.end && it.end <= c.start && leadsTo(t, k, it) }) return null
        if (c.start - k.end > 160) return null
        var gap = t.substring(k.end, c.start)
        val nl = gap.indexOf('\n')
        if (nl >= 0) {
            // Only "Your verification code is:" with the code on a line below.
            if (gap.substring(nl).isNotBlank() || !labelEnd.matches(gap.substring(0, nl).trim().lowercase())) return null
            gap = gap.substring(0, nl)
        }
        if (crossesSentence(gap)) return null
        val g = gap.lowercase()
        val n = words(g)
        if (n > 24 || (!k.strong && hasBadWord(g))) return null
        val trimmed = g.trim()
        if (trimmed.isEmpty() || (trimmed.length <= 3 && trimmed.all { it in ":-=(\"'*" })) {
            return Link(if (k.strong) 3 else 2, k.strong, tight = true)                    // "OTP 482913", "code: 4821"
        }
        val i = maxOf(g.lastIndexOf(", "), g.lastIndexOf("; "))
        if (i >= 0 && clauseNoun.containsMatchIn(g.substring(i))) return null
        val joined = joinEnd.containsMatchIn(g) || (k.strong && shortJoin.matches(g))
        return when {
            joined && k.strong -> Link(3, true, tight = n <= 4)                             // "OTP for login is 482913"
            joined && n <= 5 -> Link(2, false, tight = n <= 2)                              // "PIN for your card is 4417"
            k.strong && n <= 4 -> Link(2, true, tight = false)                              // "OTP for login 482913"
            else -> null
        }
    }

    private fun leadsTo(t: String, k: Kw, x: Cand): Boolean {
        val g = t.substring(k.end, x.start).lowercase()
        val trimmed = g.trim()
        return trimmed.isEmpty() || (trimmed.length <= 3 && trimmed.all { it in ":-=(\"'*\n" }) || joinEnd.containsMatchIn(g)
    }

    private fun backward(t: String, c: Cand, k: Kw, cands: List<Cand>): Link? {
        if (cands.any { it !== c && it.start >= c.end && it.end <= k.start }) return null
        if (k.start - c.end > 64) return null
        val gap = t.substring(c.end, k.start)
        if ('\n' in gap || crossesSentence(gap)) return null
        val g = gap.lowercase()
        val n = words(g)
        if (n > 8 || reanchor.containsMatchIn(g) || (!k.strong && hasBadWord(g))) return null
        if (joinStart.containsMatchIn(g)) {                                                 // "482913 is your Instagram code"
            return if (k.strong) Link(3, true, tight = n <= 4) else if (n <= 4) Link(2, false, tight = n <= 3) else null
        }
        if (g.isBlank()) return Link(2, k.strong, tight = false)                           // "482913 OTP"
        return null
    }

    private val useBefore = Regex("(?:^|[^\\p{L}])(?:use|enter|type|input|key in)\\s+(?:the\\s+)?(?:(?:otp|code|pin|passcode)\\s*)?[:\\-]?\\s*$")
    private val useAfter = Regex("^\\s*[)*\"']?\\s*(?:to|as|for|in|on|when|while|and)\\s[^.!?\\n]{0,60}?(?<![\\p{L}])(?:otps?|codes?|passcode|password|verify|verification|confirm|log ?in|login|log-in|sign ?in|signin|sign-in|sign ?up|signup|authenticate|authori[sz]e|complete|reset|validate|activate|register|registration|link|continue|proceed|recover|approve)(?![\\p{L}])")

    /** "Use 482913 to verify", "Enter 482913 to sign in". */
    private fun use(low: String, c: Cand): Link? {
        if (!useBefore.containsMatchIn(low.substring(maxOf(0, c.start - 32), c.start))) return null
        val m = useAfter.find(low.substring(c.end, minOf(low.length, c.end + 90))) ?: return null
        return Link(3, strong = strongWords.containsMatchIn(m.value), tight = true)
    }

    /** A number alone on its line, right under (or over) a line about a code, as emails lay it out. */
    private fun ownLine(t: String, c: Cand, kws: List<Kw>): Link? {
        val ls = t.lastIndexOf('\n', c.start - 1) + 1
        val le = t.indexOf('\n', c.end).let { if (it < 0) t.length else it }
        if (ls == 0 && le == t.length) return null
        if (t.substring(ls, le).trim(' ', '*', '.', '"', '\'') != t.substring(c.start, c.end)) return null
        var from = ls
        var lines = 0
        while (from > 0 && lines < 2) {
            val prev = t.lastIndexOf('\n', from - 2) + 1
            if (t.substring(prev, from - 1).isNotBlank()) lines++
            from = prev
        }
        var to = le
        while (to < t.length && t.substring(to, t.indexOf('\n', to + 1).let { if (it < 0) t.length else it }).isBlank()) {
            to = t.indexOf('\n', to + 1).let { if (it < 0) t.length else it }
        }
        if (to < t.length) to = t.indexOf('\n', to + 1).let { if (it < 0) t.length else it }
        val near = kws.filter { it.start >= from && it.end <= to && (it.end <= ls || it.start >= le) }
        if (near.isEmpty()) return null
        return Link(2, near.any { it.strong }, tight = false)
    }

    /** A notification whose title says "Verification code" over a bare number. */
    private fun titled(low: String, cands: List<Cand>, from: From, title: String?, promo: Boolean): String? {
        if (from != From.NOTIFICATION || title.isNullOrBlank() || promo) return null
        val t = normalize(title)
        if (!strongWords.containsMatchIn(lower(t)) && !tan.containsMatchIn(t)) return null
        val only = cands.singleOrNull() ?: return null
        return only.code.takeIf { words(low) <= 12 && !yearLike(it) }
    }

    private val promoWords = Regex("\\d+ ?% ?(?:off|cashback|discount)|\\boff\\b|discount|cash ?back|coupon|promo|\\boffers?\\b|\\bsale\\b|\\bdeals?\\b|voucher|\\bflat (?:rs|₹|inr|\\x24|\\d)|shop now|buy now|order now|hurry|limited time|t&c|free delivery|\\bsave (?:rs|₹|inr|\\x24|\\d|up to)|\\bearn\\b|\\bwin\\b|referral")
}
