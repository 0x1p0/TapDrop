package app.tapdrop.update

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tapdrop.TapDropApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The daily check (JobScheduler: about every 24 hours, only with a network, kept across restarts). */
class UpdateCheckJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = TapDropApp.instance.scope.launch {
            val ok = Updates.scheduledCheck()
            // Couldn't reach GitHub: try again a little later rather than tomorrow.
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }
}

/** Android's answers about an install session: ask the person, done, or failed. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstaller.onStatus(context, intent)
    }
}

/** The new version's first moment after an update: tidy up and reopen TapDrop. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Updates.onPackageReplaced(context) { pending.finish() }
    }
}
