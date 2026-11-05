package app.tapdrop.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.data.TapAction
import app.tapdrop.net.LatestMedia
import app.tapdrop.net.Outgoing
import app.tapdrop.net.TState
import app.tapdrop.nfc.TapTag
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.sheet.ClipSummary
import app.tapdrop.ui.sheet.LatestShot
import app.tapdrop.ui.sheet.MacChoice
import app.tapdrop.ui.sheet.ShareContent
import app.tapdrop.ui.sheet.SheetScene
import app.tapdrop.ui.sheet.TapSheetBody
import app.tapdrop.ui.sheet.TapTransfer
import app.tapdrop.ui.sheet.finishWhenSent
import app.tapdrop.ui.sheet.installSheetWindow
import app.tapdrop.ui.sheet.macId
import app.tapdrop.ui.sheet.transferLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Opened by tapping the TapDrop sticker on the Mac (or the Quick Settings tile): a glass sheet over whatever
 * you were doing. It collects anything waiting on the Mac first, runs your chosen tap action, and offers
 * one-tap ways to send.
 */
class TapActivity : ComponentActivity() {
    private var macId by mutableStateOf<String?>(null)
    private var taps by mutableIntStateOf(0)
    private var fromNfc by mutableStateOf(false)
    private var clip by mutableStateOf<ClipData?>(null)
    private var shown by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSheetWindow()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            TapDropTheme {
                SheetScene(shown, onHidden = ::finishWhenSent) {
                    TapSheet(macId, fromNfc, taps, clip, onDismissed = { shown = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        shown = true
    }

    private fun handle(intent: Intent?) {
        val nfc = intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED
        fromNfc = nfc
        macId = TapTag.fromIntent(intent) ?: TapDropApp.instance.prefs.macs.value.firstOrNull()?.id
        if (nfc) taps++
    }

    // Android only lets the focused app read the clipboard.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) clip = getSystemService(ClipboardManager::class.java).primaryClip
    }

    companion object {
        const val EXTRA_SOURCE = "source"
    }
}

@Composable
private fun TapSheet(macId: String?, fromNfc: Boolean, taps: Int, clip: ClipData?, onDismissed: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val mac = macs.firstOrNull { it.id == macId } ?: macs.firstOrNull()
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val tapAction by app.prefs.tapAction.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var pings by remember { mutableIntStateOf(0) }
    var burstTap by remember { mutableIntStateOf(0) }
    var handledTap by remember { mutableIntStateOf(0) }
    var burst by remember { mutableIntStateOf(0) }
    var closeSignal by remember { mutableIntStateOf(0) }
    var sentSomething by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }

    val mediaPerms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    fun hasMedia() = mediaPerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var mediaGranted by remember { mutableStateOf(hasMedia()) }
    var latest by remember { mutableStateOf<LatestMedia?>(null) }
    // Read again after something arrives: the newest photo may have just come from the Mac.
    val arrived = (state as? TState.Done)?.received?.size ?: 0
    LaunchedEffect(mediaGranted, arrived) { if (mediaGranted) latest = withContext(Dispatchers.IO) { Outgoing.latestMedia(ctx) } }
    val clipItems by produceState<List<OutItem>>(emptyList(), clip) {
        value = withContext(Dispatchers.IO) { Outgoing.fromClip(ctx, clip) }
    }

    fun act(action: () -> Unit) {
        retry = action
        action()
    }

    fun send(items: List<OutItem>, label: String, preview: Uri? = null, mime: String? = null) {
        val m = mac ?: return
        if (items.isEmpty() || app.transfers.busy) return
        sentSomething = true
        app.transfers.send(m, items, label, preview, mime)
    }

    fun sendLatest() {
        val l = latest ?: return
        Outgoing.fromUri(ctx, l.uri)?.let { send(listOf(it), it.name, l.uri, l.mime) }
    }

    fun sendClipboard() {
        val items = Outgoing.fromClip(ctx, clip)
        send(items, if (items.singleOrNull() is OutItem.Text) "clipboard" else Outgoing.label(items))
    }

    fun getMacClipboard() {
        val m = mac ?: return
        if (!app.transfers.busy) app.transfers.pullClipboard(m)
    }

    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaGranted = hasMedia()
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val items = uris.mapNotNull { Outgoing.fromUri(ctx, it) }
        act { send(items, Outgoing.label(items), uris.firstOrNull(), items.firstOrNull()?.mime) }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val items = uris.mapNotNull { Outgoing.fromUri(ctx, it) }
        act { send(items, Outgoing.label(items), uris.firstOrNull(), items.firstOrNull()?.mime) }
    }

    // Each tap: a ripple of light and a thunk, then whatever is waiting on the Mac, else the default action.
    // If the Mac doesn't answer, the tap stays pending and runs once "Retry" reaches it.
    LaunchedEffect(taps, mac?.id, pings) {
        val m = mac ?: return@LaunchedEffect
        if (fromNfc && taps > burstTap) {
            burstTap = taps
            burst++
            Haptics.tap(ctx)
        }
        if (!app.transfers.busy) app.transfers.reset()
        online = null
        var pong = app.transfers.ping(m)
        online = pong != null
        // Out of reach: keep looking quietly, and carry on (pending tap included) once it answers.
        while (pong == null) {
            delay(5000)
            pong = app.transfers.ping(m)
            if (pong != null) online = true
        }
        val tapNow = fromNfc && taps > handledTap
        handledTap = taps
        if (pong.pending > 0) {
            act { app.transfers.receiveInbox(m) }
        } else if (tapNow) {
            delay(250)
            when (tapAction) {
                TapAction.LATEST_PHOTO -> act(::sendLatest)
                TapAction.CLIPBOARD -> act(::sendClipboard)
                TapAction.MAC_CLIPBOARD -> act(::getMacClipboard)
                TapAction.ASK -> Unit
            }
        }
    }
    // A finished send closes the sheet, like AirDrop.
    LaunchedEffect(state, sentSomething) {
        val s = state
        if (sentSomething && s is TState.Done && s.outgoing) {
            delay(1300)
            closeSignal++
        }
    }

    val mine = mac != null && state.macId == mac.id
    val transfer = if (!mine) TapTransfer.Idle else when (val s = state) {
        is TState.Working -> TapTransfer.Working(transferLabel(s), s.progress)
        is TState.Done -> when {
            s.outgoing -> TapTransfer.Sent("Sent")
            s.empty -> TapTransfer.Nothing(s.title)
            else -> TapTransfer.Got(s.title, s.subtitle, s.received)
        }
        is TState.Failed -> TapTransfer.Failed(s.message)
        TState.Idle -> TapTransfer.Idle
    }
    val clipSummary = remember(clipItems) {
        val text = (clipItems.singleOrNull() as? OutItem.Text)?.text?.trim()
        when {
            clipItems.isEmpty() -> null
            text != null -> ClipSummary(text.replace(Regex("\\s+"), " "), isLink = ShareContent.isWebLink(text))
            else -> ClipSummary(Outgoing.label(clipItems).replaceFirstChar { it.uppercase() }, isLink = false)
        }
    }

    GlassSheet(onDismissed = onDismissed, overlay = { TapBurst(burst) }) { close ->
        LaunchedEffect(closeSignal) { if (closeSignal > 0) close() }
        TapSheetBody(
            mac = mac?.let { MacChoice(it.id, it.name, online) },
            transfer = transfer,
            mediaAllowed = mediaGranted,
            latest = latest?.let { LatestShot(it.uri, it.name, photoAgo(it.addedSec * 1000), it.mime.startsWith("video/")) },
            clip = clipSummary,
            onSendLatest = { act(::sendLatest) },
            onAllowPhotos = { askMedia.launch(mediaPerms) },
            onSendClipboard = { act(::sendClipboard) },
            onGetMacClipboard = { act(::getMacClipboard) },
            onPickPhotos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
            onRetry = {
                app.transfers.reset()
                retry?.invoke()
            },
            onReconnect = { pings++ },
            onOpen = { r ->
                val url = r.text?.trim()
                when {
                    r.uri != null -> openUri(ctx, r.uri, r.mime)
                    url != null && r.isLink -> {
                        openLink(ctx, url)
                        close()
                    }
                }
            },
            onOpenApp = {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                close()
            },
        )
    }
}

/** "Just now", "5 min. ago", "Yesterday"… for the latest photo. */
private fun photoAgo(ms: Long): String {
    val now = System.currentTimeMillis()
    if (now - ms < DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
