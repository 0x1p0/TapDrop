package app.tapdrop.ui.home

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.data.HistoryEntry
import app.tapdrop.data.SavedMac
import app.tapdrop.net.Outgoing
import app.tapdrop.net.TState
import app.tapdrop.tap.NotchTapService
import app.tapdrop.ui.Brand
import app.tapdrop.ui.DeviceSupport
import app.tapdrop.ui.ExtrasChecklist
import app.tapdrop.ui.ReceivedStrip
import app.tapdrop.ui.TapCopy
import app.tapdrop.ui.TapSetupCard
import app.tapdrop.ui.TapStalledCard
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.ActionTile
import app.tapdrop.ui.design.AuroraBackdrop
import app.tapdrop.ui.design.AuroraMood
import app.tapdrop.ui.design.BrandMark
import app.tapdrop.ui.design.DeviceHero
import app.tapdrop.ui.design.EmptyState
import app.tapdrop.ui.design.GlassIconButton
import app.tapdrop.ui.design.GlassLayers
import app.tapdrop.ui.design.GlassTopBar
import app.tapdrop.ui.design.LinkState
import app.tapdrop.ui.design.MediaThumb
import app.tapdrop.ui.design.PlayBadge
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.StatusPill
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.entrance
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.fmtBytes
import app.tapdrop.ui.openLink
import app.tapdrop.ui.openUri
import app.tapdrop.ui.rememberHaptics
import app.tapdrop.ui.rememberTapSetupState
import app.tapdrop.ui.sheet.fileKind
import kotlinx.coroutines.delay

/**
 * Home: the paired Mac as the hero (with the live link drawn as light), tap-to-send setup or status,
 * quick actions, optional setup extras, and recent transfers.
 *
 * @param intro play the staggered entrance (first appearance only; not when navigating back here).
 */
@Composable
fun HomeScreen(
    macs: List<SavedMac>,
    onOpenSettings: () -> Unit,
    onOpenTapSetup: () -> Unit,
    intro: Boolean = true,
) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    // Keep the last Mac while this screen animates away after the final one is forgotten.
    var lastMac by remember { mutableStateOf(macs.first()) }
    macs.firstOrNull()?.let { lastMac = it }
    val mac = lastMac
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val history by app.prefs.history.collectAsStateWithLifecycle()
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    val tap = rememberTapSetupState()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var wifi by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Presence + auto-receive: while the app is open, anything queued on the Mac flows straight in.
    // With tap to send on, its live link already does both.
    LaunchedEffect(mac.id, live, retry) {
        if (live) {
            online = true
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                wifi = DeviceSupport.onWifi(ctx)
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
    // A small tick when the link comes up (or back), so the change is felt as well as seen.
    var wasUp by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(online, live) {
        val up = online == true
        if (wasUp == false && up) haptics.tick()
        if (online != null) wasUp = up
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

    val mine = when (val s = state) {
        is TState.Working -> s.macId == mac.id
        is TState.Done -> s.macId == mac.id
        is TState.Failed -> s.macId == mac.id
        TState.Idle -> false
    }
    val shown = if (mine) state else TState.Idle
    val link = when (val s = shown) {
        is TState.Working -> if (s.outgoing) LinkState.Sending else LinkState.Receiving
        is TState.Done -> when {
            s.empty -> linkFor(online, live)
            s.outgoing -> LinkState.Sent
            else -> LinkState.Received
        }
        is TState.Failed -> LinkState.Failed
        TState.Idle -> linkFor(online, live)
    }
    val (pillText, pillTone) = when {
        online == null -> "Looking for your Mac" to StatusTone.Searching
        online == false -> (if (wifi) "Not reachable" else "Not on Wi‑Fi") to StatusTone.Offline
        live -> "Ready to tap" to StatusTone.Live
        else -> "Connected" to StatusTone.Good
    }

    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / 120f).coerceIn(0f, 1f)
        }
    }
    val bars = WindowInsets.systemBars.asPaddingValues()

    GlassLayers(
        Modifier.fillMaxSize(),
        chrome = {
            GlassTopBar(
                scrolled = { scrolled },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 30.dp)
                        Text("TapDrop", style = TdType.subtitle, color = Td.colors.text, modifier = Modifier.padding(start = 10.dp))
                    }
                },
                actions = { GlassIconButton(Icons.Rounded.Settings, "Settings", onOpenSettings) },
            )
        },
    ) {
        AuroraBackdrop(
            Modifier.fillMaxWidth().height(620.dp),
            mood = when (link) {
                LinkState.Offline -> AuroraMood.Quiet
                LinkState.Live, LinkState.Sent, LinkState.Received -> AuroraMood.Live
                else -> AuroraMood.Brand
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Td.space.gutter, end = Td.space.gutter,
                top = bars.calculateTopPadding() + 60.dp,
                bottom = bars.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item("hero") {
                Hero(
                    mac = mac,
                    link = link,
                    shown = shown,
                    pillText = pillText,
                    pillTone = pillTone,
                    online = online,
                    wifi = wifi,
                    live = live,
                    tapReady = tap.ready,
                    onRetry = {
                        online = null
                        retry++
                    },
                    onHowTo = onOpenTapSetup,
                    modifier = Modifier.entrance(0, enabled = intro),
                )
            }
            when {
                !tap.accessibility -> item("tapSetup") {
                    TapSetupCard(onSetup = onOpenTapSetup, modifier = Modifier.padding(top = 18.dp).entrance(1, enabled = intro))
                }
                tap.stalled -> item("tapStalled") {
                    TapStalledCard(Modifier.padding(top = 18.dp).entrance(1, enabled = intro))
                }
            }
            item("sendHeader") { SectionHeader("Send", Modifier.entrance(2, enabled = intro)) }
            item("send") {
                QuickActions(
                    enabled = online != false,
                    onPhotos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onClipboard = {
                        val items = Outgoing.fromClip(ctx, ctx.getSystemService(ClipboardManager::class.java).primaryClip)
                        if (items.isEmpty()) {
                            haptics.reject()
                            Toast.makeText(ctx, "Your clipboard is empty", Toast.LENGTH_SHORT).show()
                        } else {
                            app.transfers.send(mac, items, if (items.size == 1 && items[0] is OutItem.Text) "clipboard" else Outgoing.label(items))
                        }
                    },
                    onMacClipboard = { app.transfers.pullClipboard(mac) },
                    modifier = Modifier.entrance(3, enabled = intro),
                )
            }
            item("extras") { ExtrasChecklist(Modifier.padding(top = 24.dp).entrance(4, enabled = intro)) }
            item("recentHeader") { SectionHeader("Recent", Modifier.entrance(5, enabled = intro)) }
            if (history.isEmpty()) {
                item("recentEmpty") {
                    EmptyState(
                        icon = Icons.Rounded.History,
                        title = "Nothing sent yet",
                        body = "Photos, links and files you send or receive show up here.",
                        modifier = Modifier.entrance(5, enabled = intro),
                    )
                }
            } else {
                item("recent") {
                    SettingsGroup(Modifier.entrance(6, enabled = intro)) {
                        history.take(6).forEach { h -> item { shape -> HistoryRow(h, shape) } }
                    }
                }
            }
        }
    }
}

private fun linkFor(online: Boolean?, live: Boolean) = when {
    online == null -> LinkState.Searching
    online == false -> LinkState.Offline
    live -> LinkState.Live
    else -> LinkState.Connected
}

@Composable
private fun Hero(
    mac: SavedMac,
    link: LinkState,
    shown: TState,
    pillText: String,
    pillTone: StatusTone,
    online: Boolean?,
    wifi: Boolean,
    live: Boolean,
    tapReady: Boolean,
    onRetry: () -> Unit,
    onHowTo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        DeviceHero(
            state = link,
            progress = (shown as? TState.Working)?.progress,
            contentDescription = "${mac.name}: $pillText",
            modifier = Modifier
                .widthIn(max = 340.dp),
        )
        Text(
            mac.name,
            style = TdType.headline,
            color = Td.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
        )
        StatusPill(pillText, pillTone, Modifier.padding(top = 10.dp))
        AnimatedContent(
            targetState = shown,
            contentKey = { s -> s::class.simpleName + (if (s is TState.Idle) "$online$wifi$live$tapReady" else "") },
            transitionSpec = {
                (fadeIn(tween(260, 60)) + slideInVertically(tween(320)) { it / 5 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it / 5 }) using SizeTransform(clip = false)
            },
            label = "heroStatus",
            modifier = Modifier.padding(top = 14.dp),
        ) { s ->
            Column(Modifier.fillMaxWidth().heightIn(min = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
                    TState.Idle -> {
                        val line = when {
                            online == null -> "Connecting over your Wi‑Fi…"
                            online == false && !wifi -> "Connect to the same Wi‑Fi as your Mac."
                            online == false -> "Open TapDrop on your Mac and make sure you're both on the same Wi‑Fi."
                            live -> TapCopy.PRIMARY
                            tapReady -> "Share from any app. Tap to send is connecting…"
                            else -> "Share from any app, or set up tap to send."
                        }
                        HeroLine(line)
                        when {
                            online == false && !wifi -> SmallButton("Wi‑Fi settings", {
                                runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }, Modifier.padding(top = 6.dp))
                            online == false -> SmallButton("Try again", onRetry, Modifier.padding(top = 6.dp))
                            tapReady && online == true -> TdTextButton("How tapping works", onHowTo, Modifier.padding(top = 2.dp))
                        }
                    }
                    is TState.Working -> {
                        Text(s.title, style = TdType.subtitle, color = Td.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            s.progress?.let { "${(it * 100).toInt()}%  ·  ${fmtBytes(s.done)} of ${fmtBytes(s.total)}" } ?: "Connecting…",
                            style = TdType.numeric,
                            color = Td.colors.textSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    is TState.Done -> {
                        Text(s.title, style = TdType.subtitle, color = Td.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (s.subtitle.isNotEmpty()) {
                            Text(
                                s.subtitle, style = TdType.bodySmall, color = Td.colors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp).padding(top = 2.dp),
                            )
                        }
                        if (s.received.any { it.uri != null }) {
                            Spacer(Modifier.height(14.dp))
                            ReceivedStrip(s.received)
                        } else if (s.received.firstOrNull()?.isLink == true) {
                            SmallButton("Open link", { openLink(ctx, s.received.first().text!!.trim()) }, Modifier.padding(top = 6.dp))
                        }
                    }
                    is TState.Failed -> {
                        Text(
                            s.message, style = TdType.bodySmall, color = Td.colors.dangerText,
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroLine(text: String) {
    Text(
        text,
        // Balanced lines: no lonely last word under a centered line.
        style = TdType.bodySmall.copy(lineBreak = LineBreak.Heading),
        color = Td.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp),
    )
}

@Composable
private fun QuickActions(
    enabled: Boolean,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onClipboard: () -> Unit,
    onMacClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(
                Icons.Rounded.PhotoLibrary, "Photos", "Photos & videos", Brand.Orchid, onPhotos,
                Modifier.weight(1f).fillMaxHeight(), shape = TdShapes.Cookie9, enabled = enabled,
            )
            ActionTile(
                Icons.Rounded.Folder, "Files", "Any document", Brand.Blue, onFiles,
                Modifier.weight(1f).fillMaxHeight(), shape = TdShapes.Cookie6, enabled = enabled,
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(
                Icons.Rounded.ContentPaste, "Clipboard", "Send what you copied", Brand.Violet, onClipboard,
                Modifier.weight(1f).fillMaxHeight(), shape = TdShapes.Clover4, enabled = enabled,
            )
            ActionTile(
                Icons.Rounded.ContentPasteGo, "Mac clipboard", "Paste it here", Brand.Teal, onMacClipboard,
                Modifier.weight(1f).fillMaxHeight(), shape = TdShapes.Sunny, enabled = enabled,
            )
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry, shape: Shape) {
    val ctx = LocalContext.current
    val c = Td.colors
    val uri = h.uri?.let(Uri::parse)
    val isLink = h.uri == null && (h.mime == "text/x-link" || h.title == "link" || h.title.startsWith("http"))
    val openable = uri != null && uri.scheme != "file"
    // Files by kind (a video's frame, a PDF's mark); text and links have no file.
    val mime = h.mime.orEmpty()
    val kind = fileKind(h.title, mime)
    val visual = mime.startsWith("image/") || mime.startsWith("video/")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (openable) Modifier.pressable(shape, pressedScale = 0.985f) { openUri(ctx, uri, h.mime) } else Modifier)
            .background(c.surface, shape)
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MediaThumb(
                uri,
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)),
                fallback = when {
                    isLink -> Icons.Rounded.Link
                    uri == null -> Icons.AutoMirrored.Rounded.Notes
                    else -> kind.icon
                },
                mime = h.mime?.takeIf { uri != null },
                accent = kind.accent.takeIf { uri != null && !visual },
            ) { loaded ->
                if (loaded && mime.startsWith("video/")) PlayBadge(Modifier.align(Alignment.Center), size = 20.dp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 5.dp, y = 5.dp)
                    .size(20.dp)
                    .background(c.surface, CircleShape)
                    .padding(2.dp)
                    .background(if (h.outgoing) c.accent else c.success, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (h.outgoing) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    if (h.outgoing) "Sent" else "Received",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                // Sends of text are recorded by kind ("text", "link"), which reads as a sentence fragment here.
                if (h.title in KIND_LABELS) h.title.replaceFirstChar { it.uppercase() } else h.title,
                style = TdType.body.copy(fontWeight = FontWeight.Medium), color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${if (h.outgoing) "To" else "From"} ${h.mac} · ${relativeTime(h.time)}",
                style = TdType.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** History labels that name a kind of thing rather than the thing (see `Outgoing.label`). */
private val KIND_LABELS = setOf("text", "link", "clipboard", "code")

private fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
