package app.tapdrop.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.tap.NotchTapService
import app.tapdrop.data.HistoryEntry
import app.tapdrop.data.SavedMac
import app.tapdrop.data.TapAction
import app.tapdrop.net.Outgoing
import app.tapdrop.net.Received
import app.tapdrop.net.TState
import app.tapdrop.nfc.TapTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TapDropTheme { HomeRoute() } }
    }
}

@Composable
private fun HomeRoute() {
    val app = TapDropApp.instance
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = macs.isEmpty(),
        transitionSpec = { (fadeIn(tween(500)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(250)) },
        label = "root",
    ) { empty ->
        if (empty) PairScreen(onPaired = {}) else HomeScreen(macs)
    }
}

// MARK: - Home

@Composable
private fun HomeScreen(macs: List<SavedMac>) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    // Keep the last Mac while this screen animates away after the final one is forgotten.
    var lastMac by remember { mutableStateOf(macs.first()) }
    macs.firstOrNull()?.let { lastMac = it }
    val mac = lastMac
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val history by app.prefs.history.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSticker by remember { mutableStateOf(false) }
    var showNotch by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showSpot by remember { mutableStateOf(false) }
    var pairAnother by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Presence + auto-receive: while the app is open, anything queued on the Mac flows straight in.
    // With tap-to-send on, its live link already does both — no need to knock on the Mac's door.
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    LaunchedEffect(mac.id, live) {
        if (live) {
            online = true
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val pong = app.transfers.ping(mac)
                online = pong != null
                if (pong != null && pong.pending > 0 && !app.transfers.busy) {
                    app.transfers.receiveInbox(mac).join()
                    continue
                }
                delay(if (pong != null) 5000 else 8000)
            }
        }
    }
    // Let a finished result linger, then settle back to idle.
    LaunchedEffect(state) {
        if (state is TState.Done || state is TState.Failed) {
            delay(if ((state as? TState.Done)?.received?.isNotEmpty() == true) 9000 else 4500)
            app.transfers.reset()
        }
    }

    fun sendUris(uris: List<Uri>) {
        val items = uris.mapNotNull { Outgoing.fromUri(ctx, it) }
        if (items.isEmpty()) return
        app.transfers.send(mac, items, Outgoing.label(items), uris.first(), items.first().mime)
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { sendUris(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sendUris(it) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlowBackdrop(Modifier.fillMaxWidth().height(460.dp))

        val bars = WindowInsets.systemBars.asPaddingValues()
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = bars.calculateTopPadding() + 8.dp,
                bottom = bars.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("top") { TopBar(onSettings = { showSettings = true }) }
            item("hero") { MacHero(mac, online, state, Modifier.enter(0)) }
            item("label") { Text("Share", style = sectionLabel, modifier = Modifier.padding(start = 4.dp, top = 10.dp).enter(1)) }
            item("grid") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(
                            Icons.Rounded.PhotoLibrary, "Photos", "Pick photos & videos",
                            Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF4F8B))),
                            Modifier.weight(1f).enter(2),
                        ) { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                        ActionTile(
                            Icons.Rounded.Folder, "Files", "Any document",
                            Brush.linearGradient(listOf(Color(0xFF4FA8FF), Color(0xFF2F64F5))),
                            Modifier.weight(1f).enter(3),
                        ) { pickFiles.launch(arrayOf("*/*")) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionTile(
                            Icons.Rounded.ContentPaste, "Clipboard", "Send what you copied",
                            Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF6A3FF0))),
                            Modifier.weight(1f).enter(4),
                        ) {
                            val items = Outgoing.fromClip(ctx, ctx.getSystemService(ClipboardManager::class.java).primaryClip)
                            if (items.isEmpty()) Toast.makeText(ctx, "Your clipboard is empty", Toast.LENGTH_SHORT).show()
                            else app.transfers.send(mac, items, if (items.size == 1 && items[0] is app.tapdrop.core.OutItem.Text) "clipboard" else Outgoing.label(items))
                        }
                        ActionTile(
                            Icons.Rounded.ContentPasteGo, "Mac clipboard", "Paste it here",
                            Brush.linearGradient(listOf(Color(0xFF2ED3B0), Color(0xFF14A08F))),
                            Modifier.weight(1f).enter(5),
                        ) { app.transfers.pullClipboard(mac) }
                    }
                }
            }
            item("notch") { NotchCard(Modifier.enter(6), onFindSpot = { showSpot = true }) { showNotch = true } }
            item("setup") { SetupChecklist(Modifier.enter(7)) { showNotch = true } }
            if (history.isNotEmpty()) {
                item("recentLabel") { Text("Recent", style = sectionLabel, modifier = Modifier.padding(start = 4.dp, top = 10.dp).enter(8)) }
                item("recent") {
                    Column(
                        Modifier.enter(8).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        history.take(6).forEachIndexed { i, h ->
                            HistoryRow(h)
                            if (i < minOf(5, history.size - 1)) {
                                Box(Modifier.padding(start = 76.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                            }
                        }
                    }
                }
            }
        }

        StatusBarScrim()

        AnimatedVisibility(
            visible = showSettings,
            enter = fadeIn() + slideInVertically { it / 10 },
            exit = fadeOut() + slideOutVertically { it / 10 },
        ) {
            SettingsScreen(
                onClose = { showSettings = false },
                onPairAnother = { pairAnother = true },
                onNotchSetup = { showNotch = true },
                onRules = { showRules = true },
                onSpot = { showSpot = true },
                onSticker = { showSticker = true },
            )
        }
        AnimatedVisibility(
            visible = showRules,
            enter = fadeIn() + slideInVertically { it / 10 },
            exit = fadeOut() + slideOutVertically { it / 10 },
        ) {
            AppRulesScreen(onClose = { showRules = false })
        }
        AnimatedVisibility(
            visible = showSpot,
            enter = fadeIn() + slideInVertically { it / 10 },
            exit = fadeOut() + slideOutVertically { it / 10 },
        ) {
            TapSpotScreen(onClose = { showSpot = false })
        }
        AnimatedVisibility(
            visible = pairAnother,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut() + slideOutVertically { it / 8 },
        ) {
            PairScreen(onPaired = { pairAnother = false }, onClose = { pairAnother = false })
        }
        if (showSticker) {
            SheetHost(onClosed = { showSticker = false }) { close -> StickerSheet(mac, close) }
        }
        if (showNotch) {
            SheetHost(onClosed = { showNotch = false }) { close -> NotchSetupSheet(close) { close(); showSpot = true } }
        }
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Brand.gradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Sensors, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text("TapDrop", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        FilledTonalIconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@Composable
private fun MacHero(mac: SavedMac, online: Boolean?, state: TState, modifier: Modifier = Modifier) {
    val mine = when (state) {
        is TState.Working -> state.macId == mac.id
        is TState.Done -> state.macId == mac.id
        is TState.Failed -> state.macId == mac.id
        else -> false
    }
    val orb = when {
        !mine -> OrbState.Idle
        state is TState.Working -> OrbState.Working
        state is TState.Done -> if (state.empty) OrbState.Idle else OrbState.Done
        state is TState.Failed -> OrbState.Failed
        else -> OrbState.Idle
    }
    // A few ripples to say "connected", then still — a screen left open shouldn't keep redrawing.
    var greeting by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        greeting = online == true
        if (greeting) {
            delay(8_000)
            greeting = false
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
            .padding(top = 8.dp, bottom = 24.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeviceOrb(
            Icons.Rounded.LaptopMac, orb, (state as? TState.Working)?.progress,
            size = 92.dp, pulsing = greeting && orb == OrbState.Idle, contentDescription = mac.name,
        )
        Text(mac.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        VSpace(6.dp)
        PresencePill(online)
        VSpace(16.dp)
        AnimatedContent(
            targetState = if (mine) state else TState.Idle,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(280, 60)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it / 4 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically { -it / 4 }) using SizeTransform(clip = false)
            },
            label = "heroStatus",
        ) { s ->
            HeroStatus(s)
        }
    }
}

@Composable
private fun PresencePill(online: Boolean?) {
    val (color, text) = when (online) {
        true -> Brand.Green to "Connected"
        false -> MaterialTheme.colorScheme.onSurfaceVariant to "Not reachable"
        null -> MaterialTheme.colorScheme.primary to "Looking…"
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (online == true) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        } else {
            // Only blinks while it's actually looking.
            val pulse by rememberInfiniteTransition(label = "pill")
                .animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(color))
        }
        AnimatedContent(text, label = "pillText") { t ->
            Text(t, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

@Composable
private fun HeroStatus(s: TState) {
    val ctx = LocalContext.current
    when (s) {
        is TState.Idle -> Text(
            "Share from any app, or tap your phone on your Mac.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        is TState.Working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VSpace(2.dp)
            Text(
                s.progress?.let { "${(it * 100).toInt()}%  ·  ${fmtBytes(s.done)} of ${fmtBytes(s.total)}" } ?: "Connecting…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is TState.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.title, style = MaterialTheme.typography.titleSmall)
            if (s.subtitle.isNotEmpty()) {
                VSpace(2.dp)
                Text(
                    s.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp),
                )
            }
            if (s.received.any { it.uri != null }) {
                VSpace(14.dp)
                ReceivedStrip(s.received)
            } else if (s.received.firstOrNull()?.isLink == true) {
                VSpace(10.dp)
                FilledTonalButton(onClick = { openLink(ctx, s.received.first().text!!.trim()) }) { Text("Open link") }
            }
        }
        is TState.Failed -> Text(
            s.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

@Composable
fun ReceivedStrip(items: List<Received>) {
    val ctx = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        itemsIndexed(items.filter { it.uri != null }.take(10)) { i, r ->
            Column(Modifier.width(72.dp).enter(i), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumb(
                    r.uri, Modifier.size(72.dp).tappable(RoundedCornerShape(18.dp)) { openUri(ctx, r.uri!!, r.mime) },
                    fallback = Icons.Rounded.Notes,
                )
                VSpace(4.dp)
                Text(r.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, accent: Brush, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .tappable(RoundedCornerShape(26.dp), onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
            .padding(18.dp),
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(accent), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.25f), 0.6f to Color.Transparent)))
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        VSpace(18.dp)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry) {
    val ctx = LocalContext.current
    val uri = h.uri?.let(Uri::parse)
    val isLink = h.uri == null && (h.mime == "text/x-link" || h.title == "link" || h.title.startsWith("http"))
    Row(
        Modifier
            .fillMaxWidth()
            .tappable(pressedScale = 0.985f) { if (uri != null && uri.scheme != "file") openUri(ctx, uri, h.mime) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(
                uri, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)),
                fallback = if (isLink) Icons.Rounded.Link else Icons.Rounded.Notes,
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(0.dp).size(20.dp)
                    .graphicsLayer { translationX = 5.dp.toPx(); translationY = 5.dp.toPx() }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (h.outgoing) Brand.successGradient else Brand.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (h.outgoing) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (h.outgoing) "To" else "From"} ${h.mac} · ${
                    if (System.currentTimeMillis() - h.time < 60_000) "just now"
                    else DateUtils.getRelativeTimeSpanString(h.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - sticker setup

private sealed interface StickerPhase {
    data object Waiting : StickerPhase
    data object Writing : StickerPhase
    data object Done : StickerPhase
    data class Error(val message: String) : StickerPhase
}

@Composable
private fun StickerSheet(mac: SavedMac, close: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<StickerPhase>(StickerPhase.Waiting) }
    var burst by remember { mutableIntStateOf(0) }

    NfcReader(enabled = phase !is StickerPhase.Done && phase !is StickerPhase.Writing) { tag ->
        phase = StickerPhase.Writing
        burst++
        Haptics.tap(ctx)
        scope.launch {
            val err = withContext(Dispatchers.IO) { TapTag.write(tag, mac.id) }
            if (err == null) {
                app.prefs.setSticker(mac.id)
                phase = StickerPhase.Done
                Haptics.success(ctx)
            } else {
                phase = StickerPhase.Error(err)
                Haptics.error(ctx)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        VSpace(8.dp)
        Text(
            if (phase is StickerPhase.Done) "Sticker ready" else "Set up tap to share",
            style = MaterialTheme.typography.headlineSmall,
        )
        VSpace(8.dp)
        AnimatedContent(phase is StickerPhase.Done, label = "stickerText") { done ->
            Text(
                if (done) "Stick it on your Mac, next to the trackpad. Tap your phone on it any time to share."
                else "Hold an NFC sticker against the back of your phone, just below the camera bar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            val ring = when (phase) {
                is StickerPhase.Done -> Brand.Green
                is StickerPhase.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            if (phase !is StickerPhase.Done) Ripples(Modifier.fillMaxSize(), color = ring, periodMs = 2200, start = 0.35f)
            TapBurst(burst, Modifier.fillMaxSize())
            DeviceOrb(
                Icons.Rounded.Nfc,
                when (phase) {
                    is StickerPhase.Writing -> OrbState.Working
                    is StickerPhase.Done -> OrbState.Done
                    is StickerPhase.Error -> OrbState.Failed
                    else -> OrbState.Idle
                },
                null, size = 84.dp,
            )
        }
        AnimatedContent(phase, contentKey = { it::class }, label = "stickerStatus") { p ->
            Text(
                when (p) {
                    is StickerPhase.Waiting -> "Ready — hold the sticker to your phone"
                    is StickerPhase.Writing -> "Writing…"
                    is StickerPhase.Done -> "Linked to ${mac.name}"
                    is StickerPhase.Error -> p.message
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (p is StickerPhase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        VSpace(20.dp)
        if (phase is StickerPhase.Done) {
            Button(onClick = close, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("Done") }
        } else {
            Text(
                "Any NTAG213 / NTAG215 sticker works — they cost cents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(4.dp)
            TextButton(onClick = close) { Text("Not now") }
        }
    }
}

// MARK: - helpers

fun openUri(ctx: android.content.Context, uri: Uri, mime: String?) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: ctx.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun fmtBytes(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}
