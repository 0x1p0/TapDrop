package app.tapdrop.ui.sheet

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.tapdrop.TapDropApp
import app.tapdrop.net.TState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Mac a transfer state belongs to, or null when idle. */
val TState.macId: String?
    get() = when (this) {
        is TState.Working -> macId
        is TState.Done -> macId
        is TState.Failed -> macId
        TState.Idle -> null
    }

/** The sheets' words for a transfer in flight: "Sending 42%", "Connecting…", "Finishing…". */
fun transferLabel(s: TState.Working): String {
    val p = s.progress ?: return "Connecting…"
    if (p >= 0.995f) return "Finishing…"
    return "${if (s.outgoing) "Sending" else "Receiving"}  ${(p * 100).toInt()}%"
}

/**
 * Finishes a sheet activity once nothing is being sent. Our read grant on shared or picked files lives as long
 * as the activity does, so while a send is still running the activity moves out of sight and waits for it.
 */
fun ComponentActivity.finishWhenSent() {
    val transfers = TapDropApp.instance.transfers
    if (!transfers.busy) {
        finish()
        return
    }
    moveTaskToBack(true)
    lifecycleScope.launch {
        transfers.state.first { it !is TState.Working }
        finish()
    }
}
