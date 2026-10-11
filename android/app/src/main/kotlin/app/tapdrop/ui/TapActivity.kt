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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.data.SavedMac
import app.tapdrop.data.TapAction
import app.tapdrop.net.LatestMedia
import app.tapdrop.net.Outgoing
import app.tapdrop.net.TState
import app.tapdrop.nfc.TapTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Opened by tapping the TapDrop sticker on the Mac (or the Quick Settings tile).
 * Collects anything waiting on the Mac first, then offers one-tap actions.
 */
class TapActivity : ComponentActivity() {
    private var macId by mutableStateOf<String?>(null)
    private var taps by mutableIntStateOf(0)
    private var fromNfc by mutableStateOf(false)
    private var clip by mutableStateOf<ClipData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            TapDropTheme {
                TapSheet(macId, fromNfc, taps, clip, onFinished = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
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
private fun TapSheet(macId: String?, fromNfc: Boolean, taps: Int, clip: ClipData?, onFinished: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val mac = macs.firstOrNull { it.id == macId } ?: macs.firstOrNull()
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val tapAction by app.prefs.tapAction.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    var closeSignal by remember { mutableIntStateOf(0) }
    var sentSomething by remember { mutableStateOf(false) }

    val mediaPerms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    fun hasMedia() = mediaPerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var mediaGranted by remember { mutableStateOf(hasMedia()) }
    var latest by remember { mutableStateOf<LatestMedia?>(null) }
    LaunchedEffect(mediaGranted) { if (mediaGranted) latest = withContext(Dispatchers.IO) { Outgoing.latestMedia(ctx) } }

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

    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaGranted = hasMedia()
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        val items = uris.mapNotNull { Outgoing.fromUri(ctx, it) }
        send(items, Outgoing.label(items), uris.firstOrNull(), items.firstOrNull()?.mime)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.mapNotNull { Outgoing.fromUri(ctx, it) }
        send(items, Outgoing.label(items), uris.firstOrNull(), items.firstOrNull()?.mime)
    }

    // Each tap: shockwave + thunk, collect anything waiting on the Mac, then run the default action.
    LaunchedEffect(taps, mac?.id) {
        val m = mac ?: return@LaunchedEffect
        if (fromNfc && taps > 0) {
            burst++
            Haptics.tap(ctx)
        }
        if (!app.transfers.busy) app.transfers.reset()
        val pong = app.transfers.ping(m)
        online = pong != null
        if (pong == null) return@LaunchedEffect
        if (pong.pending > 0) {
            app.transfers.receiveInbox(m).join()
        } else if (fromNfc) {
            delay(250)
            when (tapAction) {
                TapAction.LATEST_PHOTO -> sendLatest()
                TapAction.CLIPBOARD -> sendClipboard()
                TapAction.MAC_CLIPBOARD -> app.transfers.pullClipboard(m)
                TapAction.ASK -> Unit
            }
        }
    }
    // A finished send closes the sheet, like AirDrop.
    LaunchedEffect(state, sentSomething) {
        val s = state
        if (sentSomething && s is TState.Done && s.outgoing) {
            delay(1200)
            closeSignal++
        }
    }

    SheetHost(onClosed = onFinished, overlay = { TapBurst(burst) }) { close ->
        LaunchedEffect(closeSignal) { if (closeSignal > 0) close() }

        if (mac == null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Pair with your Mac first", style = MaterialTheme.typography.titleLarge)
                VSpace(6.dp)
                Text(
                    "This sticker belongs to a Mac that isn't paired with this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                VSpace(16.dp)
                Button(
                    onClick = {
                        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        close()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Open TapDrop") }
            }
            return@SheetHost
        }

        val mine = when (val s = state) {
            is TState.Working -> s.macId == mac.id
            is TState.Done -> s.macId == mac.id
            is TState.Failed -> s.macId == mac.id
            else -> false
        }
        val orb = when {
            !mine -> OrbState.Idle
            state is TState.Working -> OrbState.Working
            state is TState.Done -> if ((state as TState.Done).empty) OrbState.Idle else OrbState.Done
            state is TState.Failed -> OrbState.Failed
            else -> OrbState.Idle
        }

        // Header: the Mac + live status
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeviceOrb(
                Icons.Rounded.LaptopMac, orb, (state as? TState.Working)?.progress,
                size = 54.dp, pulsing = online == true && orb == OrbState.Idle,
            )
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(mac.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedContent(
                    targetState = headerStatus(online, if (mine) state else TState.Idle),
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically { -it / 2 })
                    },
                    label = "tapStatus",
                ) { t ->
                    Text(
                        t,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (orb == OrbState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
        }

        // What just arrived from the Mac
        val done = (state as? TState.Done)?.takeIf { mine && !it.outgoing && it.received.isNotEmpty() }
        AnimatedVisibility(visible = done != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                done?.let { d ->
                    if (d.received.any { it.uri != null }) ReceivedStrip(d.received)
                    else if (d.received.first().isLink) {
                        FilledTonalButton(onClick = { openLink(ctx, d.received.first().text!!.trim()); close() }) {
                            Icon(Icons.Rounded.Link, null, Modifier.size(18.dp))
                            Text("Open link", Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        VSpace(18.dp)
        val enabled = online == true && !app.transfers.busy
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            if (mediaGranted) {
                ActionRow(
                    icon = Icons.Rounded.Image,
                    accent = Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF4F8B))),
                    title = "Latest photo",
                    subtitle = latest?.let {
                        "${it.name} · ${DateUtils.getRelativeTimeSpanString(it.addedSec * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)}"
                    } ?: "No photos yet",
                    thumb = latest?.uri,
                    enabled = enabled && latest != null,
                    index = 0,
                ) { sendLatest() }
            } else {
                ActionRow(
                    icon = Icons.Rounded.Image,
                    accent = Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF4F8B))),
                    title = "Latest photo",
                    subtitle = "Allow photo access to send your newest shot",
                    enabled = true,
                    index = 0,
                ) { askMedia.launch(mediaPerms) }
            }
            Divider()
            val clipItems = remember(clip) { Outgoing.fromClip(ctx, clip) }
            val clipText = (clipItems.singleOrNull() as? OutItem.Text)?.text?.trim()
            ActionRow(
                icon = if (clipText != null && Uri.parse(clipText).scheme?.startsWith("http") == true) Icons.Rounded.Link else Icons.Rounded.ContentPaste,
                accent = Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF6A3FF0))),
                title = "Clipboard",
                subtitle = when {
                    clipItems.isEmpty() -> "Nothing copied"
                    clipText != null -> clipText.replace('\n', ' ')
                    else -> Outgoing.label(clipItems)
                },
                enabled = enabled && clipItems.isNotEmpty(),
                index = 1,
            ) { sendClipboard() }
            Divider()
            ActionRow(
                icon = Icons.Rounded.ContentPasteGo,
                accent = Brush.linearGradient(listOf(Color(0xFF2ED3B0), Color(0xFF14A08F))),
                title = "Get Mac clipboard",
                subtitle = "Paste what you copied on the Mac",
                enabled = enabled,
                index = 2,
            ) { app.transfers.pullClipboard(mac) }
        }
        VSpace(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(Icons.Rounded.PhotoLibrary, "Photos", Modifier.weight(1f), enabled) {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
            PillButton(Icons.Rounded.Folder, "Files", Modifier.weight(1f), enabled) { pickFiles.launch(arrayOf("*/*")) }
        }
        VSpace(4.dp)
    }
}

private fun headerStatus(online: Boolean?, s: TState): String = when (s) {
    is TState.Working -> s.progress?.let { "${s.title} · ${(it * 100).toInt()}%" } ?: s.title
    is TState.Done -> if (s.subtitle.isNotEmpty() && !s.empty) "${s.title} · ${s.subtitle}" else s.title
    is TState.Failed -> s.message
    TState.Idle -> when (online) {
        true -> "Connected · ready to share"
        false -> "Can't reach your Mac. Is TapDrop running?"
        null -> "Connecting…"
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    accent: Brush,
    title: String,
    subtitle: String,
    enabled: Boolean,
    index: Int,
    thumb: Uri? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .enter(index)
            .tappable(enabled = enabled, pressedScale = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            Thumb(thumb, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
        } else {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent), contentAlignment = Alignment.Center) {
                Box(Modifier.size(48.dp).background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.25f), 0.6f to Color.Transparent)))
                Icon(icon, null, tint = Color.White)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.14f else 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier
            .tappable(RoundedCornerShape(18.dp), enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
}
