package app.tapdrop.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.core.Endpoint
import app.tapdrop.data.SavedMac
import app.tapdrop.ui.pair.MacAddress
import app.tapdrop.ui.pair.NearbyMac
import app.tapdrop.ui.pair.PairContent
import app.tapdrop.ui.pair.PairStep
import app.tapdrop.ui.pair.openWifiSettings
import app.tapdrop.ui.pair.pairProblem
import app.tapdrop.ui.pair.rememberOnWifi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private class Attempt(val endpoint: Endpoint, val name: String, val manual: Boolean)

/**
 * First run (and "pair another Mac"): the Macs TapDrop can see on this Wi‑Fi, then the 6-digit code both
 * screens show, then a small celebration. Typing an address is the quiet fallback.
 *
 * Shown at the root after Welcome, and inside MainNav's NavHost (Dest.PairAnother). It never installs a
 * back handler to close itself (its host does that); only its sheet handles back, to close itself.
 */
@Composable
fun PairScreen(onPaired: (SavedMac) -> Unit, onClose: (() -> Unit)? = null) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val found by app.finder.found.collectAsStateWithLifecycle()
    val saved by app.prefs.macs.collectAsStateWithLifecycle()
    val wifi = rememberOnWifi()
    val nearby = remember(found, saved) {
        val paired = saved.mapTo(HashSet()) { it.id }
        found.values
            .filter { it.id != null }
            .distinctBy { it.id }
            .sortedBy { it.name.lowercase() }
            .map { NearbyMac(it.id!!, it.name, it.endpoint, it.id in paired) }
    }
    var slow by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf<PairStep?>(null) }
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var attempt by remember { mutableStateOf<Attempt?>(null) }
    var finishNow by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestOnPaired by rememberUpdatedState(onPaired)

    // Look for Macs while the screen is in front, and start over when the network changes.
    LifecycleStartEffect(wifi) {
        app.finder.start()
        onStopOrDispose { app.finder.stop() }
    }
    LaunchedEffect(wifi, nearby.isEmpty()) {
        slow = false
        if (wifi && nearby.isEmpty()) {
            delay(9000)
            slow = true
        }
    }
    // Leaving the screen hangs up, which also takes the prompt off the Mac's screen.
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun pair(endpoint: Endpoint, name: String, manual: Boolean) {
        job?.cancel()
        finishNow = null
        attempt = Attempt(endpoint, name, manual)
        step = PairStep.Connecting(name)
        job = scope.launch {
            var shownName = name
            var shownCode: String? = null
            var codeAt = 0L
            try {
                val attemptJob = coroutineContext[Job]
                val mac = app.transfers.pair(endpoint) { code, macName ->
                    shownCode = code
                    scope.launch {
                        // The code can land just after Cancel; then there's nothing to show it in.
                        if (attemptJob?.isActive != true) return@launch
                        shownName = macName.ifBlank { name }
                        codeAt = SystemClock.elapsedRealtime()
                        step = PairStep.Code(shownName, code)
                        Haptics.tick(ctx)
                    }
                }
                step = PairStep.Paired(mac.name.ifBlank { shownName }, shownCode)
                Haptics.success(ctx)
                var done = false
                val complete = {
                    if (!done) {
                        done = true
                        app.prefs.saveMac(mac)
                        latestOnPaired(mac)
                    }
                }
                finishNow = complete
                // Let the moment land, then carry on.
                delay(2100)
                complete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cancelled (Cancel, the sheet closing, leaving the screen): hanging up makes the socket throw,
                // and that error can surface instead of the cancellation. It's not a failure, so say nothing.
                ensureActive()
                Haptics.error(ctx)
                val waited = if (codeAt > 0L) SystemClock.elapsedRealtime() - codeAt else null
                step = PairStep.Failed(shownName, pairProblem(e, waited), manual)
            }
        }
    }

    PairContent(
        nearby = nearby,
        wifi = wifi,
        slow = slow,
        step = step,
        address = address,
        onAddressChange = {
            address = it
            if ((step as? PairStep.Address)?.error != null) step = PairStep.Address()
        },
        onBack = onClose,
        onPick = { mac -> pair(mac.endpoint, mac.name, manual = false) },
        onManual = { step = PairStep.Address() },
        onConnect = {
            when (val parsed = MacAddress.parse(address.text)) {
                MacAddress.Parsed.Empty -> step = PairStep.Address("Type your Mac's address first.")
                is MacAddress.Parsed.Invalid -> step = PairStep.Address(parsed.reason)
                is MacAddress.Parsed.Ok -> pair(parsed.endpoint, MacAddress.label(parsed.endpoint), manual = true)
            }
        },
        onRetry = { attempt?.let { pair(it.endpoint, it.name, it.manual) } },
        onEditAddress = { step = PairStep.Address() },
        onWifiSettings = { openWifiSettings(ctx) },
        onSheetDismissed = {
            if (step is PairStep.Paired) {
                // Dismissed while celebrating: just carry on.
                finishNow?.invoke()
            } else {
                job?.cancel()
                job = null
            }
            step = null
        },
    )
}
