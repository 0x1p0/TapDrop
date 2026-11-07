package app.tapdrop.tap

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import app.tapdrop.R
import app.tapdrop.TapDropApp

/**
 * Pausing tap to send for banking and payment apps, many of which won't run while another app's accessibility service
 * is on: tap to send pauses as such an app opens and resumes once it's gone. Nothing is hidden from the app: TapDrop's
 * service is really not running while it's open.
 *
 * - **Which apps:** the main banking and UPI apps ([KNOWN]), any added in Settings → Banking apps, and any that say so
 *   on screen the first time ("turn off accessibility…"), which are learned.
 * - **Before it opens:** a tap on its icon (any launcher, its folders and search, a notification, the "open with"
 *   sheet) pauses tap to send first, so the app never sees it. Its window appearing is the fallback.
 * - **Pausing needs no permission** ([pause]); with Usage access it resumes by itself when the app goes, otherwise
 *   with one tap on its notification.
 *
 * Nothing here is tied to one phone: launchers, settings apps, browsers and keyboards are looked up on the phone
 * itself, and an app's name is matched the way its launcher says it.
 */
object Guard {
    /**
     * Banking and payment apps TapDrop pauses for out of the box: the main UPI apps and banks in India, and banks whose
     * help pages say they won't run with another app's accessibility service on. Any can be taken off (and others
     * added) in Settings → Banking apps; others are learned the first time one says so on screen.
     */
    val KNOWN = setOf(
        // UPI and payments
        "in.org.npci.upiapp", // BHIM
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app", // PhonePe
        "net.one97.paytm", // Paytm
        "com.dreamplug.androidapp", // CRED
        "com.mobikwik_new", // MobiKwik
        "com.naviapp", // Navi
        "com.enstage.wibmo.hdfc", // PayZapp
        // Banks in India
        "com.sbi.lotusintouch", // YONO SBI
        "com.sbi.SBIFreedomPlus", // YONO Lite SBI
        "com.snapwork.hdfc", // HDFC Bank
        "com.csam.icici.bank.imobile", // ICICI iMobile
        "com.axis.mobile", // Axis Mobile
        "com.msf.kbank.mobile", // Kotak
        "com.idfcfirstbank.optimus", // IDFC FIRST Bank
        "com.Version1", // PNB ONE
        "com.bankofbaroda.mconnect", // bob World
        "com.canarabank.mobility", // Canara ai1
        "com.infrasoft.uboi", // Union Bank Vyom
        "com.fss.indus", // IndusInd
        "com.atomyes", // YES Bank
        "com.fedmobile", // Federal Bank
        "com.snapwork.IDBI", // IDBI Bank
        "com.rblbank.mobank", // RBL Bank
        "com.dbs.in.digitalbank", // DBS digibank India
        "money.jupiter", // Jupiter
        "com.epifi.paisa", // Fi
        "com.hsbc.hsbcindia", // HSBC India
        // Elsewhere
        "com.transferwise.android", // Wise
        "com.maybank2u.life", // MAE by Maybank
        "com.ocbc.mobile", // OCBC
        "com.dbs.sg.dbsmbanking", // DBS digibank Singapore
        "com.uob.mighty.app", // UOB TMRW
    )

    private const val CHANNEL = "guard"
    const val NOTE = 7401
    const val ACTION_RESUME = "app.tapdrop.action.RESUME_TAP"

    private val app get() = TapDropApp.instance

    // MARK: noticing

    /**
     * Called first for every event the tap service gets. Returns true when it turned the service off (nothing else
     * should run for that event).
     */
    fun check(service: AccessibilityService, event: AccessibilityEvent): Boolean {
        val prefs = app.prefs
        if (!prefs.guardOn.value) return false
        val pkg = event.packageName?.toString() ?: return false
        if (pkg == service.packageName) return false
        val guarded = prefs.guarded.value
        if (guarded.isEmpty()) return false
        when (event.eventType) {
            // A tap on its icon (launcher, recents, a notification, the "open with" sheet): off before it starts.
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!opensApps(service, pkg)) return false
                val said = (event.text.joinToString(" ") + " " + (event.contentDescription ?: "")).trim()
                if (said.isEmpty() || said.length > 120) return false
                val hit = guarded.firstOrNull { p -> names(service, p).any { matches(said, it) } } ?: return false
                android.util.Log.i("TapDrop", "guard: $hit's icon tapped in $pkg; stepping aside before it opens")
                pause(service, hit, learned = false)
                return true
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in guarded) {
                    android.util.Log.i("TapDrop", "guard: $pkg came forward; stepping aside")
                    pause(service, pkg, learned = false)
                    return true
                }
            }
        }
        return false
    }

    private val looked = HashMap<String, Long>()
    @Volatile private var front: String? = null
    @Volatile private var frontAt = 0L

    /**
     * Learning, off the main thread: an app that tells the user to turn accessibility off ("Accessibility permission
     * detected… disable it") goes in the list, and tap to send steps aside for it at once. Its screen is read when
     * its window changes (at most once every 0.8 s) in the first seconds after it comes forward, and again a moment
     * later (warnings like BHIM's show up over its splash screen, sometimes without a window of their own).
     *
     * Never learned: the phone's own apps (Settings, TalkBack, the security app…), browsers, keyboards, launchers,
     * apps that are themselves accessibility services, and anything taken off the list by hand. Those talk about
     * accessibility for other reasons.
     */
    fun learn(service: AccessibilityService, event: AccessibilityEvent, launch: (suspend () -> Unit) -> Unit) {
        if (!app.prefs.guardOn.value || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg in keyboards(service)) return
        val now = System.currentTimeMillis()
        val fresh = pkg != front
        if (fresh) frontAt = now
        front = pkg
        // Warnings like these come as the app opens: later screens (a game's own accessibility menu) don't count.
        if (now - frontAt > 6_000) return
        if (pkg in app.prefs.guarded.value || pkg in app.prefs.guardRemoved.value || !learnable(service, pkg)) return
        if (says(event.text.joinToString(" "))) {
            act(service, pkg)
            return
        }
        synchronized(looked) {
            if (now - (looked[pkg] ?: 0L) < 800) return
            looked[pkg] = now
        }
        launch {
            var waited = 0L
            for (at in if (fresh) longArrayOf(250, 1_500, 3_500) else longArrayOf(250, 1_200)) {
                kotlinx.coroutines.delay(at - waited)
                waited = at
                if (front != pkg || pkg in app.prefs.guarded.value) return@launch
                val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: continue
                if (root.packageName?.toString() != pkg) continue
                val text = StringBuilder()
                collect(root, text, budget = intArrayOf(180), depth = 0)
                if (says(text.toString())) {
                    act(service, pkg)
                    return@launch
                }
            }
        }
    }

    private fun act(service: AccessibilityService, pkg: String) {
        android.util.Log.i("TapDrop", "guard: $pkg asks to turn accessibility off; learned it")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (pkg in app.prefs.guardRemoved.value) return@post
            app.prefs.setGuarded(pkg, true)
            pause(service, pkg, learned = true)
        }
    }

    /**
     * "BHIM", "BHIM, 2 notifications", "BHIM, New": the icon's label as a launcher says it (launchers differ only in
     * what they add after the name).
     */
    private fun matches(said: String, name: String): Boolean {
        val s = said.trim()
        if (name.isBlank()) return false
        return s.equals(name, ignoreCase = true) ||
            s.startsWith("$name,", ignoreCase = true) && s.length <= name.length + 40 ||
            s.startsWith("$name ", ignoreCase = true) && s.length <= name.length + 24 && !s.contains(':')
    }

    /** What a warning names: accessibility (also in Hindi), or "remote control software" that it detects. */
    private val SUBJECT = Regex(
        "accessibility|accessibilit|सुलभता|एक्सेसिबिलिटी|remote (control|access) (software|app)",
        RegexOption.IGNORE_CASE,
    )

    /** What a warning asks for, or says it found. */
    private val TURN_OFF = Regex(
        "disable|turn(ed)? (it |them )?off|switch(ed)? (it |them )?off|deactivate|revoke|not allowed|detected|" +
            "is enabled|are enabled|is on\\b|are on\\b|remove|uninstall|unsafe|risk|बंद|हटा|अक्षम",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A screen that asks to turn accessibility off: short (a warning, not an article or a chat), and the ask close to
     * the word "accessibility".
     */
    internal fun says(t: String): Boolean {
        if (t.length > 2_000) return false
        val subjects = SUBJECT.findAll(t).map { it.range.first }.toList()
        if (subjects.isEmpty()) return false
        return TURN_OFF.findAll(t).any { m -> subjects.any { kotlin.math.abs(it - m.range.first) < 200 } }
    }

    private fun collect(n: AccessibilityNodeInfo, out: StringBuilder, budget: IntArray, depth: Int) {
        if (budget[0]-- <= 0 || depth > 14 || out.length > 3000) return
        n.text?.let { out.append(it).append(' ') }
        n.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until n.childCount) {
            val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
            collect(c, out, budget, depth + 1)
        }
    }

    // MARK: the phone's own apps, looked up on the phone

    private class Cached<T>(val value: T, val at: Long)
    private const val FRESH_MS = 10 * 60_000L
    private val cache = HashMap<String, Cached<Any>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, load: () -> T): T = synchronized(cache) {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.at < FRESH_MS }?.let { return it.value as T }
        val v = runCatching(load).getOrNull()
        if (v != null) cache[key] = Cached(v, now)
        v ?: (cache[key]?.value as T?) ?: (emptySet<String>() as T)
    }

    private fun packagesFor(ctx: Context, intent: Intent): Set<String> =
        ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()

    /** Every launcher on the phone (whichever is in use), which also draws recents. */
    private fun homes(ctx: Context): Set<String> = cached("homes") {
        packagesFor(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    /** Where a tap opens an app: launchers, the status bar and notifications, and the "open with" sheet. */
    private fun opensApps(ctx: Context, pkg: String): Boolean =
        pkg in homes(ctx) || pkg in OPENERS

    private val OPENERS = setOf("com.android.systemui", "android", "com.android.intentresolver", "com.google.android.intentresolver")

    private fun keyboards(ctx: Context): Set<String> = cached("keyboards") {
        ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.inputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
    }

    /** Apps that talk about accessibility for their own reasons, so a warning in them is never learned. */
    private fun neverLearned(ctx: Context): Set<String> = cached("never") {
        val browser = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val services = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?.installedAccessibilityServiceList?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.orEmpty()
        homes(ctx) + packagesFor(ctx, browser) + services +
            packagesFor(ctx, Intent(Settings.ACTION_SETTINGS)) +
            packagesFor(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) +
            setOf("com.android.vending", "com.google.android.gms", "android")
    }

    /**
     * An app tap to send may pause for at all: never TapDrop itself, a launcher, the status bar or the phone's
     * settings (where tap to send is switched on).
     */
    fun pausable(ctx: Context, pkg: String): Boolean =
        pkg != ctx.packageName && pkg != "com.android.systemui" && pkg != "android" && pkg !in homes(ctx) &&
            pkg !in packagesFor(ctx, Intent(Settings.ACTION_SETTINGS)) &&
            pkg !in packagesFor(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Takes off the list anything tap to send must never pause for (an early test build could learn Settings). */
    fun tidy(ctx: Context) {
        val prefs = app.prefs
        prefs.guarded.value.filterNot { pausable(ctx, it) }.forEach {
            android.util.Log.i("TapDrop", "guard: $it can't be paused for; taken off the list")
            prefs.setGuarded(it, false)
        }
    }

    /** An app TapDrop may learn: one the user installed, that isn't a browser, a launcher or an accessibility app. */
    private fun learnable(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg in neverLearned(ctx)) return false
        val info = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0
    }

    /** An app's name, and every name its launcher icons go by ("BHIM", "BHIM UPI"). */
    fun names(ctx: Context, pkg: String): Set<String> = cached("names:$pkg") {
        val pm = ctx.packageManager
        val icons = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0)
            .map { it.loadLabel(pm).toString().trim() }
        (listOfNotNull(label(ctx, pkg)) + icons).filter { it.isNotBlank() }.toSet()
    }

    /**
     * Apps that look like banking or payments by their name, offered first when adding apps by hand. Only a hint:
     * nothing is added without the user.
     */
    fun looksLikeMoney(pkg: String, label: String): Boolean =
        MONEY.containsMatchIn(label) || MONEY_PKG.containsMatchIn(pkg)

    private val MONEY = Regex(
        "bank|\\bupi\\b|bhim|yono|\\bpay\\b|\\bpay[a-z]|wallet|imobile|net ?banking|\\bcred\\b|phonepe|paytm|demat|broking",
        RegexOption.IGNORE_CASE,
    )
    private val MONEY_PKG = Regex("bank|upi|npci|yono|wallet|paytm|phonepe|payment|finance|broking|mbanking", RegexOption.IGNORE_CASE)

    // MARK: pausing and resuming

    /**
     * Pauses tap to send for [pkg], which is opening or open. No special permission is needed for this: TapDrop ends
     * its own process. Android unbinds its accessibility service at once, so the app sees none, but keeps it switched
     * on in Accessibility and parks it until TapDrop next starts. While that app is still in front TapDrop exits again
     * as it starts ([stayDown]); once it's gone the watcher ([GuardService], in a process of its own) wakes TapDrop,
     * and tap to send is back within a second.
     *
     * Where TapDrop may write Accessibility settings itself (granted once from a computer), it switches its service off
     * and on there instead, which also hides it from apps that read the setting.
     */
    fun pause(service: AccessibilityService, pkg: String, learned: Boolean) {
        // Every millisecond counts here (the app is starting): note what's paused, hand the rest (notification,
        // watching) to the watcher's process, and go.
        markDown(service, pkg, learned)
        runCatching { service.startForegroundService(Intent(service, GuardService::class.java)) }
        if (canSwitchOn(service)) {
            runCatching { service.disableSelf() }
            app.prefs.setPausedFor(pkg)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * Called first as TapDrop's main process starts: true while it should stay paused (the caller then ends the
     * process before tap to send connects). That's while the paused-for app is in front, as far as Usage access tells;
     * and always for a few seconds after pausing, when Android restarts TapDrop on its own.
     */
    fun stayDown(ctx: Context): Boolean {
        val down = downFor(ctx) ?: return false
        if (canSwitchOn(ctx) && enabledInSettings(ctx).not()) return false // paused in Settings: nothing to hold back
        val age = System.currentTimeMillis() - down.at
        if (age < 0 || age > WATCH_LIMIT_MS) {
            clearDown(ctx)
            return false
        }
        // Started because someone opened TapDrop itself (not to reconnect tap to send): they've left that app.
        if (openedByUser(ctx)) return false
        if (age < RESTART_WINDOW_MS) return true
        if (!canWatch(ctx)) return false
        val front = cameForward(ctx, System.currentTimeMillis() - 6 * 60 * 60_000L, System.currentTimeMillis()).lastOrNull()
        return front == down.pkg
    }

    /** Back on: the pause is over, and TapDrop is woken (or switched back on in Settings, where it may). */
    fun resume(ctx: Context): Boolean {
        clearDown(ctx)
        cancelNote(ctx)
        if (!enabledInSettings(ctx)) {
            if (!canSwitchOn(ctx)) return false
            val me = ComponentName(ctx, NotchTapService::class.java).flattenToString()
            val cr = ctx.contentResolver
            val now = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = now.split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) } + me
            runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onFailure { return false }
        }
        android.util.Log.i("TapDrop", "guard: resumed")
        // Starting TapDrop's main process is all it takes: Android connects the parked service as it starts.
        runCatching { ctx.sendBroadcast(Intent(ctx, WakeReceiver::class.java)) }
        return true
    }

    /** Tap to send connected (resumed, or switched on by hand): nothing is paused any more. */
    fun resumed(service: AccessibilityService) {
        clearDown(service)
        runCatching { tidy(service) }
        if (app.prefs.pausedFor.value != null) app.prefs.setPausedFor(null)
        runCatching { service.stopService(Intent(service, GuardService::class.java)) }
        cancelNote(service)
        // Woken while a paused-for app is still in front (Resume tapped there, or TapDrop started for something
        // else): pause again before that app looks.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!app.prefs.guardOn.value) return@postDelayed
            val front = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull() ?: return@postDelayed
            if (front in app.prefs.guarded.value) pause(service, front, learned = false)
        }, 300)
    }

    /** This process was started to show one of TapDrop's screens (Android 15 and later say why a process started). */
    private fun openedByUser(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 35) return false
        val info = runCatching {
            ctx.getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessStartReasons(1).firstOrNull()
        }.getOrNull() ?: return false
        if (info.pid != android.os.Process.myPid()) return false
        return info.reason == android.app.ApplicationStartInfo.START_REASON_LAUNCHER ||
            info.reason == android.app.ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS ||
            info.reason == android.app.ApplicationStartInfo.START_REASON_START_ACTIVITY
    }

    private fun enabledInSettings(ctx: Context): Boolean {
        val me = ComponentName(ctx, NotchTapService::class.java).flattenToString()
        return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** What's paused: the app, since when, and whether TapDrop just learned it. Kept in a file both processes read. */
    class Down(val pkg: String, val at: Long, val learned: Boolean)

    private fun downFile(ctx: Context) = java.io.File(ctx.filesDir, "guard-paused")

    fun downFor(ctx: Context): Down? = runCatching {
        val lines = downFile(ctx).takeIf { it.exists() }?.readLines() ?: return null
        Down(lines[0], lines[1].toLong(), lines.getOrNull(2) == "learned")
    }.getOrNull()

    private fun markDown(ctx: Context, pkg: String, learned: Boolean) {
        runCatching {
            downFile(ctx).writeText("$pkg\n${System.currentTimeMillis()}\n${if (learned) "learned" else ""}")
        }
    }

    private fun clearDown(ctx: Context) {
        runCatching { downFile(ctx).delete() }
    }

    /** Android restarts a service it lost about a second later (longer if it happens again soon): stay down then. */
    private const val RESTART_WINDOW_MS = 8_000L
    /** The longest a pause lasts: after this tap to send comes back however things look. */
    const val WATCH_LIMIT_MS = 60 * 60_000L

    /** TapDrop may write Accessibility settings itself (granted once: `pm grant … WRITE_SECURE_SETTINGS`). */
    fun canSwitchOn(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** TapDrop may see which app is open (Usage access), so it knows when a paused-for app is closed. */
    fun canWatch(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** The apps that came to the front between [from] and [to] (epoch ms), in order, from Usage access. */
    fun cameForward(ctx: Context, from: Long, to: Long): List<String> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val events = runCatching { usm.queryEvents(from, to) }.getOrNull() ?: return emptyList()
        val e = UsageEvents.Event()
        val out = ArrayList<String>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) out += e.packageName
        }
        return out
    }

    // MARK: words and notifications

    private val labels = HashMap<String, String>()

    fun label(ctx: Context, pkg: String): String? = synchronized(labels) {
        labels[pkg] ?: runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.also { labels[pkg] = it }
    }

    /**
     * Accessibility settings, with TapDrop's row picked out where the phone supports it. (Its own page there is
     * only for the system to open.)
     */
    fun openSettingsIntent(ctx: Context): Intent {
        val key = ComponentName(ctx, NotchTapService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", key) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Banking apps", NotificationManager.IMPORTANCE_LOW).apply {
                description = "While tap to send is paused for a banking or payment app"
            }
        )
    }

    /** What the notification says while tap to send is paused for [pkg]. Its button (and the notification) resume. */
    fun note(ctx: Context, pkg: String, learned: Boolean, watching: Boolean): NotificationCompat.Builder {
        channel(ctx)
        val name = label(ctx, pkg) ?: "this app"
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val resume = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, GuardReceiver::class.java).setAction(ACTION_RESUME), flags)
        val title = "Tap to send is paused while $name is open"
        val text = when {
            learned -> "$name won't open while tap to send is on. Close it and open it again: tap to send stays paused " +
                "while you use it" + if (watching) ", and resumes when you leave." else "."
            watching -> "It resumes by itself when you leave $name."
            else -> "Tap Resume when you're done with $name. To have it resume by itself, allow Usage access in TapDrop's " +
                "Settings → Banking apps."
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(0xFF5F5E59.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(resume)
            .addAction(0, if (watching) "Resume now" else "Resume", resume)
    }

    private fun notifyPaused(ctx: Context, pkg: String, learned: Boolean, watching: Boolean) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTE, note(ctx, pkg, learned, watching).build()) }
    }

    fun cancelNote(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE)
    }
}
