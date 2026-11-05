package app.tapdrop.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit
import app.tapdrop.TapDropApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updating in place: downloads the release's APK into the cache, checks it three ways, then hands it to Android.
 *
 * 1. Its SHA-256 must match the release's SHA256SUMS.txt (the download arrived whole and unchanged).
 * 2. It must be signed with the same certificate as this app (it's an official build, not someone's copy).
 * 3. Its version code must be higher than ours.
 *
 * Android then installs it over this app (same package, same signature, so pairing and tap to send stay as they
 * are), after "Allow from this source" the first time and its own confirmation.
 */
object UpdateInstaller {
    internal const val DIR = "updates"
    private const val ACTION_STATUS = "app.tapdrop.action.INSTALL_STATUS"

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val done: Long, val total: Long) : Phase {
            val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
        }
        /** Checking the checksum, the signature and the version. */
        data object Verifying : Phase
        /** Android needs "Allow from this source" for TapDrop first. */
        data object NeedsPermission : Phase
        /** Handed to Android; waiting for it (and for the person, if it asks). */
        data object Installing : Phase
        data class Failed(val problem: Problem, val detail: String? = null) : Phase
    }

    enum class Problem {
        /** The download didn't finish (no connection, GitHub didn't answer). */
        Offline,
        /** The release has no Android app or no checksums to check it with. */
        Missing,
        /** The file doesn't match SHA256SUMS.txt. */
        Checksum,
        /** Signed with a different key: this copy of TapDrop was built by someone else. */
        Signature,
        /** Not newer than this app. */
        NotNewer,
        /** Not enough free space. */
        Storage,
        /** Android refused to install it. */
        Install,
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The release being installed, while there is one. */
    var release: Release? = null
        private set

    private var job: Job? = null
    /** Android's "Do you want to update this app?" screen, waiting for TapDrop to be on screen to show it. */
    @Volatile private var confirm: Intent? = null
    /** When Android last said something about the install session. */
    @Volatile private var lastStatus = 0L

    val busy: Boolean
        get() = _phase.value.let { it is Phase.Downloading || it == Phase.Verifying || it == Phase.Installing }

    /** Starts (or resumes) updating to [r]. Does nothing while an update is already under way. */
    fun start(ctx: Context, r: Release) {
        if (busy) return
        val app = ctx.applicationContext
        release = r
        _phase.value = Phase.Downloading(0, r.apk?.size ?: -1)
        job = TapDropApp.instance.scope.launch {
            val next = try {
                withContext(Dispatchers.IO) { prepare(app, r) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Phase.Failed(Problem.Offline)
            } catch (e: Exception) {
                Phase.Failed(Problem.Install, e.message)
            }
            _phase.value = next
        }
    }

    /** Stops a download in progress. */
    fun cancel(ctx: Context) {
        job?.cancel()
        job = null
        File(ctx.cacheDir, DIR).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        if (_phase.value !is Phase.Installing) _phase.value = Phase.Idle
        Updates.store(ctx).edit { remove(K_AWAIT_PERMISSION) }
    }

    /** Back to the start after a failure (the sheet's "Try again", or closing it). */
    fun reset() {
        if (!busy) _phase.value = Phase.Idle
    }

    /** Drops a skipped version's download. */
    internal fun forget(ctx: Context) {
        if (busy) return
        _phase.value = Phase.Idle
        release = null
        File(ctx.cacheDir, DIR).deleteRecursively()
    }

    /** Opens Android's "Install unknown apps" page for TapDrop ("Allow from this source"). */
    fun openPermission(ctx: Context) {
        Updates.store(ctx).edit { putString(K_AWAIT_PERMISSION, "${release?.version}@${System.currentTimeMillis()}") }
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * MainActivity came to the front: show Android's confirmation if it's waiting, and carry on after
     * "Allow from this source" (also when Android restarted TapDrop while the person was in Settings).
     */
    internal fun onResumed(activity: Activity) {
        confirm?.let { i ->
            confirm = null
            runCatching { activity.startActivity(i) }.onFailure { _phase.value = Phase.Failed(Problem.Install, it.message) }
            return
        }
        if (_phase.value == Phase.Installing) {
            // Back on screen while installing: normally Android stops this version within seconds. If it never
            // answers (its confirmation went away without one), don't leave the sheet stuck on "Installing…".
            val since = System.currentTimeMillis()
            TapDropApp.instance.scope.launch {
                delay(20_000)
                if (_phase.value == Phase.Installing && lastStatus < since && Updates.resumedActivity === activity) {
                    Updates.clearRelaunch()
                    _phase.value = Phase.Idle
                }
            }
        }
        val sp = Updates.store(activity)
        val waiting = sp.getString(K_AWAIT_PERMISSION, null) ?: return
        val at = waiting.substringAfter('@').toLongOrNull() ?: 0L
        if (!activity.packageManager.canRequestPackageInstalls()) {
            if (_phase.value == Phase.Idle && System.currentTimeMillis() - at < 10 * 60 * 1000) {
                // Restarted while away and still not allowed: show where things stand.
                Updates.available.value?.let { release = it }
                _phase.value = Phase.NeedsPermission
                Updates.openSheet()
            }
            return
        }
        sp.edit { remove(K_AWAIT_PERMISSION) }
        val r = release ?: Updates.available.value ?: return
        if (System.currentTimeMillis() - at > 60 * 60 * 1000 && _phase.value != Phase.NeedsPermission) return
        _phase.value = Phase.Idle
        Updates.openSheet()
        start(activity, r)
    }

    // MARK: download and checks

    private suspend fun prepare(ctx: Context, r: Release): Phase {
        val apk = r.apk ?: return Phase.Failed(Problem.Missing)
        val sumsAsset = r.sums ?: return Phase.Failed(Problem.Missing)
        if (!ReleaseFeed.allowed(ctx, apk.url) || !ReleaseFeed.allowed(ctx, sumsAsset.url)) return Phase.Failed(Problem.Missing)

        val sums = fetchText(ctx, sumsAsset.url)
        val expected = sha256For(sums, apk.name) ?: return Phase.Failed(Problem.Missing)

        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, apk.name)
        // Room for the download and Android's own copy of it while installing.
        val need = (if (apk.size > 0) apk.size else 40L shl 20) * 2 + (16L shl 20)
        if (!file.exists() && dir.usableSpace < need) return Phase.Failed(Problem.Storage)

        // A finished download from earlier (e.g. before "Allow from this source") is used again if it's intact.
        val cached = file.exists() && sha256(file) == expected
        if (!cached) {
            file.delete()
            val got = download(ctx, apk, file)
            if (got != expected) {
                file.delete()
                return Phase.Failed(Problem.Checksum)
            }
        }

        _phase.value = Phase.Verifying
        verify(ctx, file)?.let { return it }

        if (!ctx.packageManager.canRequestPackageInstalls()) return Phase.NeedsPermission
        Updates.store(ctx).edit { remove(K_AWAIT_PERMISSION) }
        install(ctx, file, r)
        return Phase.Installing
    }

    private fun connect(ctx: Context, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ReleaseFeed.userAgent(Updates.installedName))
        }.also { c ->
            val code = c.responseCode
            // GitHub sends files from another host; only follow it over the same kind of connection.
            if (code !in 200..299 || !ReleaseFeed.allowed(ctx, c.url.toString())) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
        }

    private fun fetchText(ctx: Context, url: String): String {
        val c = connect(ctx, url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [asset] to [file] (through a .part file), reporting progress. Returns the file's SHA-256. */
    private suspend fun download(ctx: Context, asset: Asset, file: File): String {
        val part = File(file.parentFile, file.name + ".part")
        val c = connect(ctx, asset.url)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var done = 0L
            var shown = 0L
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - shown >= 128 * 1024 || done == total) {
                            shown = done
                            _phase.value = Phase.Downloading(done, total)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(file)) throw IOException("rename")
        return digest.digest().hex()
    }

    /** The signature and version checks. Null when the file may be installed. */
    private fun verify(ctx: Context, file: File): Phase? {
        val pm = ctx.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val theirs = pm.getPackageArchiveInfo(file.path, flags)
        if (theirs == null || theirs.packageName != ctx.packageName) {
            file.delete()
            return Phase.Failed(Problem.Checksum)
        }
        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (!sameSigner(mine.signingInfo, theirs.signingInfo)) return Phase.Failed(Problem.Signature)
        if (theirs.longVersionCode <= mine.longVersionCode) return Phase.Failed(Problem.NotNewer)
        return null
    }

    /**
     * True when the new APK is signed by the key this app was signed with (or by a newer key whose proof of
     * rotation includes ours, which Android also accepts as an update).
     */
    private fun sameSigner(mine: SigningInfo?, theirs: SigningInfo?): Boolean {
        if (mine == null || theirs == null) return false
        val ours = digests(mine.apkContentsSigners)
        val now = digests(theirs.apkContentsSigners)
        if (ours.isEmpty() || now.isEmpty()) return false
        if (now == ours) return true
        return !theirs.hasMultipleSigners() && ours.size == 1 && ours.first() in digests(theirs.signingCertificateHistory)
    }

    private fun digests(certs: Array<Signature>?): Set<String> =
        certs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()

    // MARK: install

    private fun install(ctx: Context, file: File, r: Release) {
        val installer = ctx.packageManager.packageInstaller
        // An earlier attempt the person walked away from.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("TapDrop.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    s.fsync(out)
                }
                Updates.prepareRelaunch(r.version)
                val status = Intent(ctx, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
                val pi = PendingIntent.getBroadcast(ctx, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            Updates.clearRelaunch()
            throw e
        }
    }

    /** Android's answer about the install session ([InstallStatusReceiver]). */
    internal fun onStatus(ctx: Context, intent: Intent) {
        lastStatus = System.currentTimeMillis()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val ask = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (ask == null) {
                    _phase.value = Phase.Failed(Problem.Install, message)
                    return
                }
                _phase.value = Phase.Installing
                val on = Updates.resumedActivity
                if (on != null) {
                    runCatching { on.startActivity(ask) }.onFailure { _phase.value = Phase.Failed(Problem.Install, it.message) }
                } else {
                    // Not on screen: show it when TapDrop comes back, and say it's ready.
                    confirm = ask
                    release?.let { Updates.notifyReadyToInstall(it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually never seen: Android stops this version as the new one goes in.
                _phase.value = Phase.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // "Cancel" on Android's confirmation.
                Updates.clearRelaunch()
                _phase.value = Phase.Idle
            }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Updates.clearRelaunch()
                _phase.value = Phase.Failed(Problem.Storage, message)
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Updates.clearRelaunch()
                _phase.value = Phase.Failed(Problem.Signature, message)
            }
            else -> {
                Updates.clearRelaunch()
                _phase.value = Phase.Failed(Problem.Install, message)
            }
        }
    }

    private fun sha256(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().hex()
    }

    private fun ByteArray.hex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    private const val K_AWAIT_PERMISSION = "awaitPermission"
}
