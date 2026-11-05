package app.tapdrop.update

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import app.tapdrop.R
import app.tapdrop.TapDropApp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import androidx.compose.ui.graphics.toArgb

/**
 * Knowing about new versions: a daily background check, one when the app comes to the front (if the last was
 * over 20 hours ago) and the manual "Check for updates". It remembers the last check, the latest release and a
 * skipped version, posts one notification per new version, and asks the What's new sheet to open.
 * Installing is [UpdateInstaller]'s job.
 */
object Updates {
    const val CHANNEL = "updates"
    const val ACTION_SHOW = "app.tapdrop.action.SHOW_UPDATE"
    /** With [ACTION_SHOW]: start updating right away (the notification's Update button). */
    const val EXTRA_START = "app.tapdrop.extra.START_UPDATE"

    internal const val JOB_ID = 4210
    private const val NOTE_AVAILABLE = 4201
    private const val NOTE_UPDATED = 4202
    private const val HOUR = 60L * 60 * 1000
    /** An automatic check when the app comes to the front only if the last one is older than this. */
    private const val FOREGROUND_AFTER = 20 * HOUR

    /** What the last check said, for the Settings row. */
    sealed interface CheckState {
        data object Idle : CheckState
        data object Checking : CheckState
        data object UpToDate : CheckState
        data object Found : CheckState
        data class Failed(val reason: ReleaseFeed.Failure) : CheckState
    }

    private lateinit var app: Context
    private lateinit var sp: SharedPreferences

    /** This app's version ("2.1.0") and version code. */
    lateinit var installedName: String
        private set
    var installedCode = 0L
        private set
    val installed: Version? get() = Version.parse(installedName)

    private val _latest = MutableStateFlow<Release?>(null)
    /** The newest release we know of (it may be this very version). */
    val latest: StateFlow<Release?> = _latest.asStateFlow()

    private val _available = MutableStateFlow<Release?>(null)
    /** The newest release when it's newer than this app, skipped or not. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _skipped = MutableStateFlow<String?>(null)
    val skipped: StateFlow<String?> = _skipped.asStateFlow()

    private val _lastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    private val _check = MutableStateFlow<CheckState>(CheckState.Idle)
    val check: StateFlow<CheckState> = _check.asStateFlow()

    private val _sheet = MutableStateFlow(false)
    /** The What's new sheet should be on screen. */
    val sheet: StateFlow<Boolean> = _sheet.asStateFlow()

    private val _justUpdated = MutableStateFlow<String?>(null)
    /** The version this app was just updated to, until the Home card saying so is dismissed. */
    val justUpdated: StateFlow<String?> = _justUpdated.asStateFlow()

    private var resumed: WeakReference<Activity>? = null
    private var started = 0
    private var mainResumed = false
    @Volatile private var lastActivityStart = 0L

    /** The activity on screen right now, if it's one of ours. */
    internal val resumedActivity: Activity? get() = resumed?.get()

    fun store(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val auto: Boolean get() = TapDropApp.instance.prefs.autoUpdates.value

    fun init(application: Application) {
        app = application
        sp = store(application)
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        installedName = info.versionName ?: "0"
        installedCode = info.longVersionCode
        load()

        // A new version since the last run (from this updater or installed by hand): say so once on Home.
        val before = Version.parse(sp.getString(K_LAST_RUN, null))
        val now = installed
        if (before != null && now != null && now > before) sp.edit { putString(K_JUST_UPDATED, now.toString()) }
        sp.edit { putString(K_LAST_RUN, installedName) }
        _justUpdated.value = sp.getString(K_JUST_UPDATED, null)

        createChannel(application)
        schedule(application, auto)
        application.registerActivityLifecycleCallbacks(Lifecycle)
    }

    /** Reads what was saved (also after a debug build's reset from adb). */
    internal fun load() {
        _latest.value = sp.getString(K_RELEASE, null)?.let(Release::parse)
        _skipped.value = sp.getString(K_SKIPPED, null)
        _lastCheck.value = sp.getLong(K_LAST_CHECK, 0L)
        _check.value = CheckState.Idle
        refreshAvailable()
    }

    // MARK: checking

    /** Turns the daily check on or off (Settings), and checks now when it's turned on and due. */
    fun setAutomatic(on: Boolean) {
        TapDropApp.instance.prefs.setAutoUpdates(on)
        schedule(app, on)
        if (on && System.currentTimeMillis() - _lastCheck.value > FOREGROUND_AFTER) check(manual = false)
    }

    /**
     * Asks GitHub for the latest release. A [manual] check reports errors and opens the sheet when it finds
     * something; an automatic one stays quiet and notifies instead (unless TapDrop is on screen).
     */
    fun check(manual: Boolean): Job? {
        if (_check.value == CheckState.Checking) return null
        _check.value = CheckState.Checking
        return TapDropApp.instance.scope.launch {
            val ok = withContext(Dispatchers.IO) { runCheck(manual) }
            if (!ok && !manual) _check.value = CheckState.Idle
        }
    }

    /** The daily job: skipped when a check already ran recently. Returns false when it couldn't reach GitHub. */
    internal suspend fun scheduledCheck(): Boolean {
        if (!auto) return true
        if (System.currentTimeMillis() - _lastCheck.value < FOREGROUND_AFTER) return true
        if (_check.value == CheckState.Checking) return true
        _check.value = CheckState.Checking
        val ok = withContext(Dispatchers.IO) { runCheck(manual = false) }
        if (!ok) _check.value = CheckState.Idle
        return ok
    }

    private suspend fun runCheck(manual: Boolean): Boolean {
        val etag = if (_latest.value != null) sp.getString(K_ETAG, null) else null
        when (val r = ReleaseFeed.fetchLatest(app, installedName, etag)) {
            is ReleaseFeed.Result.Found -> sp.edit {
                putString(K_RELEASE, r.release.toJson())
                putString(K_ETAG, r.etag)
                _latest.value = r.release
            }
            ReleaseFeed.Result.Unchanged -> Unit
            ReleaseFeed.Result.NoRelease -> sp.edit {
                remove(K_RELEASE)
                remove(K_ETAG)
                _latest.value = null
            }
            is ReleaseFeed.Result.Failed -> {
                if (manual) _check.value = CheckState.Failed(r.reason)
                return false
            }
        }
        val now = System.currentTimeMillis()
        sp.edit { putLong(K_LAST_CHECK, now) }
        _lastCheck.value = now
        refreshAvailable()
        val found = _available.value
        _check.value = if (found != null) CheckState.Found else CheckState.UpToDate
        withContext(Dispatchers.Main) {
            when {
                found == null -> Unit
                manual -> openSheet()
                mainResumed -> Unit // the Home card shows it
                else -> notifyAvailable(found)
            }
        }
        return true
    }

    private fun refreshAvailable() {
        val r = _latest.value
        val mine = installed
        _available.value = r?.takeIf { mine == null || it.version > mine }
        if (_available.value == null) cancel(NOTE_AVAILABLE)
    }

    /** The available release, unless it's the version the user skipped. */
    fun offered(): Release? = _available.value?.takeIf { it.version.toString() != _skipped.value }

    fun skip(r: Release) {
        val v = r.version.toString()
        sp.edit { putString(K_SKIPPED, v) }
        _skipped.value = v
        cancel(NOTE_AVAILABLE)
        UpdateInstaller.forget(app)
    }

    private fun schedule(ctx: Context, on: Boolean) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!on) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(24 * HOUR, 4 * HOUR)
                .setPersisted(true)
                .build()
        )
    }

    // MARK: the sheet

    /** Opens the What's new sheet; with [startUpdate], starts downloading too. */
    fun openSheet(startUpdate: Boolean = false) {
        cancel(NOTE_AVAILABLE)
        _sheet.value = true
        if (startUpdate) (offered() ?: _available.value)?.let { UpdateInstaller.start(app, it) }
    }

    fun closeSheet() {
        _sheet.value = false
    }

    /** MainActivity passes every intent here: notification taps open the sheet. */
    fun handleIntent(intent: Intent?) {
        cancel(NOTE_UPDATED)
        if (intent?.action != ACTION_SHOW) return
        openSheet(startUpdate = intent.getBooleanExtra(EXTRA_START, false))
        // Handled once: a configuration change or "recents" relaunch shouldn't reopen it.
        intent.action = null
    }

    fun dismissJustUpdated() {
        sp.edit { remove(K_JUST_UPDATED) }
        _justUpdated.value = null
    }

    // MARK: after an update

    /** Called just before the new version is handed to Android, so the new version can reopen itself. */
    internal fun prepareRelaunch(v: Version) {
        sp.edit(commit = true) { putString(K_RELAUNCH, "$v@${System.currentTimeMillis()}") }
    }

    internal fun clearRelaunch() {
        sp.edit { remove(K_RELAUNCH) }
    }

    /**
     * Runs in the new version right after Android replaced the app. If this updater installed it (within the
     * last 15 minutes), reopen TapDrop. Android may refuse to start an app that isn't on screen, so if nothing
     * opened within two seconds, a notification offers to.
     */
    internal fun onPackageReplaced(ctx: Context, done: () -> Unit) {
        File(ctx.cacheDir, UpdateInstaller.DIR).deleteRecursively()
        val mark = sp.getString(K_RELAUNCH, null)
        val at = mark?.substringAfter('@')?.toLongOrNull() ?: 0L
        sp.edit { remove(K_RELAUNCH) }
        if (mark == null || System.currentTimeMillis() - at > 15 * 60 * 1000) return done()
        val asked = System.currentTimeMillis()
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        TapDropApp.instance.scope.launch {
            kotlinx.coroutines.delay(2000)
            if (lastActivityStart < asked) {
                notify(
                    ctx, NOTE_UPDATED,
                    "TapDrop is updated to $installedName",
                    "Tap to open it",
                    Intent(ctx, MainActivity::class.java),
                )
            }
            done()
        }
    }

    // MARK: notifications

    fun notificationsAllowed(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!nm.areNotificationsEnabled()) return false
        return nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of TapDrop is out"
            }
        )
    }

    /** "TapDrop 2.1.1 is available": once per version, never for a skipped one. */
    private fun notifyAvailable(r: Release) {
        if (!auto) return
        val v = r.version.toString()
        if (v == _skipped.value || v == sp.getString(K_NOTIFIED, null)) return
        if (!notificationsAllowed(app)) return
        notify(
            app, NOTE_AVAILABLE,
            "TapDrop $v is available",
            "Tap to see what's new and update",
            Intent(app, MainActivity::class.java).setAction(ACTION_SHOW),
            action = "Update" to Intent(app, MainActivity::class.java).setAction(ACTION_SHOW).putExtra(EXTRA_START, true),
        )
        sp.edit { putString(K_NOTIFIED, v) }
    }

    /** The download finished while TapDrop wasn't on screen: Android's install prompt waits for a tap. */
    internal fun notifyReadyToInstall(r: Release) {
        if (!notificationsAllowed(app)) return
        notify(
            app, NOTE_AVAILABLE,
            "TapDrop ${r.version} is ready to install",
            "Tap to finish updating",
            Intent(app, MainActivity::class.java).setAction(ACTION_SHOW),
        )
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, open: Intent, action: Pair<String, Intent>? = null) {
        if (!notificationsAllowed(ctx)) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setColor(Brand.Violet.toArgb())
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(PendingIntent.getActivity(ctx, id, open, flags))
        if (action != null) {
            n.addAction(0, action.first, PendingIntent.getActivity(ctx, id + 100, action.second, flags))
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(id, n.build()) }
    }

    private fun cancel(id: Int) {
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    // MARK: foreground

    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            lastActivityStart = System.currentTimeMillis()
            if (started++ == 0 && auto && System.currentTimeMillis() - _lastCheck.value > FOREGROUND_AFTER) {
                check(manual = false)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
        }

        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            if (activity is MainActivity) {
                mainResumed = true
                UpdateInstaller.onResumed(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
            if (activity is MainActivity) mainResumed = false
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private const val K_RELEASE = "release"
    private const val K_ETAG = "etag"
    private const val K_LAST_CHECK = "lastCheck"
    private const val K_SKIPPED = "skipped"
    private const val K_NOTIFIED = "notified"
    private const val K_RELAUNCH = "relaunch"
    private const val K_LAST_RUN = "lastRunVersion"
    private const val K_JUST_UPDATED = "justUpdated"
}
