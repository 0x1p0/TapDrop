package app.tapdrop.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.tapdrop.ui.design.LightSpill
import app.tapdrop.ui.design.LiveDot
import app.tapdrop.ui.design.NotchlineHero
import app.tapdrop.ui.design.Wordmark
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
import app.tapdrop.update.UpdateCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

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
                // ping() also answers null when cancelled: a newer run (the live link just came up) has the say.
                ensureActive()
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
    val unrecognised by app.transfers.unrecognised.collectAsStateWithLifecycle()
    val unknown = online == false && mac.id in unrecognised
    val (pillText, pillTone) = when {
        online == null -> "Looking for your Mac" to StatusTone.Searching
        unknown -> "Not paired anymore" to StatusTone.Offline
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
                        BrandMark(size = 28.dp)
                        Wordmark(Modifier.padding(start = 10.dp))
                    }
                },
                actions = { GlassIconButton(Icons.Rounded.Tune, "Settings", onOpenSettings) },
            )
        },
    ) {
        LightSpill(Modifier.fillMaxWidth().height(420.dp), strength = if (link == LinkState.Offline) 0.45f else 1f)
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
                    unknown = unknown,
                    // Removed here (the Mac already doesn't know this phone); pairing starts from the top.
                    onPairAgain = { app.prefs.removeMac(mac.id) },
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
            item("update") { UpdateCard(Modifier.entrance(1, enabled = intro)) }
            item("send") {
                QuickActions(
                    macName = mac.name,
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
                    modifier = Modifier.padding(top = 18.dp).entrance(2, enabled = intro),
                )
            }
            item("fromMac") {
                FromMacRow(
                    enabled = online != false,
                    onClick = { app.transfers.pullClipboard(mac) },
                    modifier = Modifier.entrance(3, enabled = intro),
                )
            }
            item("extras") { ExtrasChecklist(Modifier.padding(top = 24.dp).entrance(4, enabled = intro)) }
            item("recentHeader") {
                SectionHeader("Recent", Modifier.padding(top = 10.dp).entrance(5, enabled = intro)) {
                    Text("Sent & received", style = TdType.caption, color = Td.colors.textTertiary)
                }
            }
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
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = Td.space.gutter),
                        modifier = Modifier.layout { m, k ->
                            // Run to the screen's right edge (past the list's gutter), so cards peek in.
                            val extra = Td.space.gutter.roundToPx()
                            val p = m.measure(k.copy(maxWidth = k.maxWidth + extra))
                            layout(k.maxWidth, p.height) { p.place(0, 0) }
                        }.entrance(6, enabled = intro),
                    ) {
                        items(history.take(12), key = { "${it.time}${it.title}" }) { h -> RecentCard(h) }
                    }
                }
            }
            item("originals") {
                OriginalsPill(onOpenSettings, Modifier.padding(top = 28.dp).entrance(7, enabled = intro))
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
    unknown: Boolean,
    onPairAgain: () -> Unit,
    onRetry: () -> Unit,
    onHowTo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val c = Td.colors
    // The line of light: steady when ready, breathing while it looks, dim when the Mac is away, blooming when
    // something lands.
    val searching = link == LinkState.Searching
    val breathe = if (searching && !Td.reducedMotion) {
        rememberInfiniteTransition(label = "heroBreathe")
            .animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breathe").value
    } else 1f
    val glow by animateFloatAsState(
        when (link) {
            LinkState.Offline -> 0.15f
            LinkState.Sent, LinkState.Received -> 1.35f
            LinkState.Searching -> breathe
            else -> 1f
        },
        Td.spring(emphasized = true), label = "heroGlow",
    )
    Column(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${mac.name}: $pillText" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NotchlineHero(Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp), glow = glow)
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveDot(lit = online != false)
            Text(
                "${mac.name} · ${pillText}".uppercase(),
                style = TdType.eyebrow, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val (plain, lit) = when {
            online == null -> "Looking for your " to "Mac."
            unknown -> "Pair " to "again."
            online == false -> "Your Mac is " to "away."
            live -> "Ready to " to "tap."
            else -> "Connected, " to "ready."
        }
        AnimatedContent(plain + lit, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "heroTitle") { _ ->
            Text(
                buildAnnotatedString {
                    append(plain)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(lit) }
                },
                style = TdType.hero, color = c.text, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp),
            )
        }
        AnimatedContent(
            targetState = shown,
            contentKey = { s -> s::class.simpleName + (if (s is TState.Idle) "$online$wifi$live$tapReady$unknown" else "") },
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
                            unknown -> "${mac.name} doesn't know this phone anymore. It was probably forgotten on the Mac."
                            online == false && !wifi -> "Connect to the same Wi‑Fi as your Mac."
                            online == false -> "Open TapDrop on your Mac and make sure you're both on the same Wi‑Fi."
                            live -> TapCopy.PRIMARY
                            tapReady -> "Share from any app. Tap to send is connecting…"
                            else -> "Share from any app, or set up tap to send."
                        }
                        HeroLine(line)
                        when {
                            unknown -> SmallButton("Pair again", onPairAgain, Modifier.padding(top = 6.dp))
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
    macName: String,
    enabled: Boolean,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("Send to $macName")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.Outlined.Image, "Photos", "& videos", onPhotos, enabled, Modifier.weight(1f))
            RoundAction(Icons.Outlined.Description, "Files", "Any kind", onFiles, enabled, Modifier.weight(1f))
            RoundAction(Icons.Outlined.ContentPaste, "Clipboard", "What you copied", onClipboard, enabled, Modifier.weight(1f))
        }
    }
}

/** The one thing that comes the other way: the Mac's clipboard, onto this phone. */
@Composable
private fun FromMacRow(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Td.colors
    val shape = RoundedCornerShape(22.dp)
    Column(modifier) {
        SectionHeader("From your Mac")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .pressable(shape, enabled = enabled, pressedScale = 0.98f, onClick = onClick)
                .background(c.surface, shape)
                .border(1.dp, c.hairline, shape)
                .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Laptop, null, tint = if (enabled) c.text else c.textTertiary, modifier = Modifier.size(21.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text("Your Mac’s clipboard", style = TdType.subtitle, color = if (enabled) c.text else c.textTertiary)
                Text("Bring what you copied on the Mac to this phone", style = TdType.bodySmall, color = c.textSecondary)
            }
            Icon(Icons.Rounded.SouthWest, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

/** A round glass button with its label below: the home screen's send actions. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, detail: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = Td.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .then(
                    if (c.isDark) Modifier
                    else Modifier.shadow(10.dp, CircleShape, ambientColor = c.shadow.copy(alpha = 0.10f), spotColor = c.shadow.copy(alpha = 0.10f))
                )
                .pressable(CircleShape, enabled = enabled, pressedScale = 0.92f, onClick = onClick)
                .background(if (c.isDark) Color.White.copy(alpha = 0.055f) else Color.White, CircleShape)
                .border(1.dp, c.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (enabled) c.text else c.textTertiary, modifier = Modifier.size(24.dp))
        }
        Text(label, style = TdType.label, color = if (enabled) c.text else c.textTertiary, modifier = Modifier.padding(top = 10.dp))
        Text(detail, style = TdType.caption, color = c.textTertiary)
    }
}

/** One recent transfer as a card: the photo itself for pictures, a quiet card for links, text and files. */
@Composable
private fun RecentCard(h: HistoryEntry) {
    val ctx = LocalContext.current
    val c = Td.colors
    val uri = h.uri?.let(Uri::parse)
    val isLink = h.uri == null && (h.mime == "text/x-link" || h.title == "link" || h.title.startsWith("http"))
    val openable = uri != null && uri.scheme != "file"
    val mime = h.mime.orEmpty()
    val kind = fileKind(h.title, mime)
    val visual = uri != null && (mime.startsWith("image/") || mime.startsWith("video/"))
    val shape = RoundedCornerShape(22.dp)
    val title = if (h.title in KIND_LABELS) h.title.replaceFirstChar { it.uppercase() } else h.title
    val sub = when {
        visual && !h.outgoing -> "The original · ${relativeTime(h.time)}"
        h.outgoing -> "To ${h.mac} · ${relativeTime(h.time)}"
        else -> "From ${h.mac} · ${relativeTime(h.time)}"
    }
    // Which way it went, said on the card itself.
    val direction: @Composable (onPhoto: Boolean) -> Unit = { onPhoto ->
        Row(
            Modifier
                .padding(10.dp)
                .background(if (onPhoto) Color(0x8C08080A) else c.accentSoft, CircleShape)
                .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (h.outgoing) Icons.Rounded.NorthEast else Icons.Rounded.SouthWest, null,
                tint = if (onPhoto) Brand.Bone else c.text, modifier = Modifier.size(12.dp),
            )
            Text(
                if (h.outgoing) "Sent" else "Received",
                style = TdType.caption.copy(fontSize = 11.sp), color = if (onPhoto) Brand.Bone else c.text,
                modifier = Modifier.padding(start = 3.dp),
            )
        }
    }
    Box(
        Modifier
            .size(width = 168.dp, height = 196.dp)
            .then(if (!c.isDark && visual) Modifier.shadow(14.dp, shape, ambientColor = c.shadow.copy(alpha = 0.12f), spotColor = c.shadow.copy(alpha = 0.12f)) else Modifier)
            .clip(shape)
            .then(if (openable) Modifier.pressable(shape, pressedScale = 0.97f) { openUri(ctx, uri, h.mime) } else Modifier)
            .background(c.surface)
            .border(1.dp, c.hairline, shape),
    ) {
        if (visual) {
            MediaThumb(uri, Modifier.fillMaxSize(), fallback = kind.icon, mime = h.mime) { loaded ->
                if (loaded && mime.startsWith("video/")) PlayBadge(Modifier.align(Alignment.Center), size = 28.dp)
            }
            Box(Modifier.align(Alignment.TopStart)) { direction(true) }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC708080A))))
                    .padding(start = 14.dp, end = 14.dp, top = 36.dp, bottom = 13.dp),
            ) {
                Text(title, style = TdType.label, color = Brand.Bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = TdType.caption, color = Color(0xFFD4D3CE), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Box(Modifier.align(Alignment.TopEnd)) { direction(false) }
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    when {
                        isLink -> Icons.Outlined.Language
                        uri == null -> Icons.AutoMirrored.Rounded.Notes
                        else -> kind.icon
                    },
                    null, tint = c.text, modifier = Modifier.size(26.dp),
                )
                Column {
                    Text(title, style = TdType.label, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = TdType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** How ready originals are: every photo and video fingerprinted on this phone, so a tap sends the file itself. */
@Composable
private fun OriginalsPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Td.colors
    val videos by NotchTapService.videoIndex.collectAsStateWithLifecycle()
    val photos by NotchTapService.photoIndex.collectAsStateWithLifecycle()
    val parts = listOfNotNull(photos, videos)
    if (parts.isEmpty()) return
    val done = parts.sumOf { it.first }
    val total = parts.sumOf { it.second }
    val ready = done >= total
    val text = if (ready) {
        "Originals ready · ${"%,d".format(photos?.second ?: 0)} photos · ${"%,d".format(videos?.second ?: 0)} videos"
    } else {
        "Getting originals ready · ${"%,d".format(done)} of ${"%,d".format(total)}"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            modifier
                .height(38.dp)
                .pressable(CircleShape, pressedScale = 0.97f, onClick = onClick)
                .background(if (c.isDark) Color.White.copy(alpha = 0.05f) else Color.White, CircleShape)
                .border(1.dp, c.hairline, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(size = 6.dp, lit = ready)
            Text(text, style = TdType.caption.copy(fontSize = 13.sp), color = c.textSecondary, maxLines = 1)
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
