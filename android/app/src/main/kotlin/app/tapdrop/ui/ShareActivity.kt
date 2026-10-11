package app.tapdrop.ui

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.data.SavedMac
import app.tapdrop.net.Outgoing
import app.tapdrop.net.TState
import app.tapdrop.nfc.TapTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SharePayload(val uris: List<Uri>, val text: String?) {
    companion object {
        fun from(intent: Intent?): SharePayload? {
            intent ?: return null
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else -> emptyList()
            }.ifEmpty {
                intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
            }
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() && uris.isEmpty() }
            return if (uris.isEmpty() && text == null) null else SharePayload(uris, text)
        }
    }
}

/** The "Send to Mac" share-sheet target (also reached via the Mac's Direct Share shortcut). */
class ShareActivity : ComponentActivity() {
    private var payload by mutableStateOf<SharePayload?>(null)
    private var directMac by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            TapDropTheme {
                ShareSheet(payload, directMac, onFinished = ::leave)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        payload = SharePayload.from(intent)
        directMac = Shortcuts.macIdFrom(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
    }

    /** Our read grant on shared URIs lives as long as this activity: stay alive (hidden) until the send finishes. */
    private fun leave() {
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
}

@Composable
private fun ShareSheet(payload: SharePayload?, directMacId: String?, onFinished: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val items = remember(payload) {
        payload ?: return@remember emptyList<OutItem>()
        if (payload.text != null) listOf(OutItem.Text(payload.text))
        else payload.uris.mapNotNull { Outgoing.fromUri(ctx, it) }
    }
    val label = remember(items) { Outgoing.label(items) }
    var target by remember { mutableStateOf<String?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    var closeSignal by remember { mutableIntStateOf(0) }
    val presence = remember { mutableStateMapOf<String, Boolean>() }
    val nfcReady = remember { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true }

    LaunchedEffect(Unit) { app.transfers.reset() }
    LaunchedEffect(macs) {
        macs.forEach { m -> launch { presence[m.id] = app.transfers.ping(m) != null } }
    }

    fun send(mac: SavedMac) {
        if (target != null || items.isEmpty() || app.transfers.busy) return
        target = mac.id
        Shortcuts.reportUsed(ctx, mac.id)
        app.transfers.send(mac, items, label, payload?.uris?.firstOrNull(), (items.firstOrNull() as? OutItem.File)?.mime)
    }

    // Direct Share: the user already picked the Mac in Android's share sheet → go straight away.
    LaunchedEffect(directMacId, macs) {
        val m = macs.firstOrNull { it.id == directMacId } ?: return@LaunchedEffect
        delay(380)
        send(m)
    }

    NfcReader(enabled = target == null && macs.isNotEmpty() && items.isNotEmpty()) { tag ->
        val id = TapTag.read(tag)
        val m = macs.firstOrNull { it.id == id } ?: macs.singleOrNull()
        if (m != null) {
            burst++
            Haptics.tap(ctx)
            send(m)
        }
    }

    val mine = target != null && when (val s = state) {
        is TState.Working -> s.macId == target
        is TState.Done -> s.macId == target
        is TState.Failed -> s.macId == target
        else -> false
    }
    LaunchedEffect(mine, state) {
        if (mine && state is TState.Done) {
            delay(1150)
            closeSignal++
        }
    }

    SheetHost(onClosed = onFinished, overlay = { TapBurst(burst) }) { close ->
        LaunchedEffect(closeSignal) { if (closeSignal > 0) close() }

        PayloadPreview(payload, items, label)
        VSpace(22.dp)

        if (items.isEmpty()) {
            Text(
                "Nothing to send — this app didn't share any files or text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(16.dp)
            return@SheetHost
        }

        if (macs.isEmpty()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Mac paired yet", style = MaterialTheme.typography.titleMedium)
                VSpace(4.dp)
                Text(
                    "Open TapDrop to pair with your Mac — it takes a few seconds.",
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
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Open TapDrop") }
            }
            VSpace(8.dp)
            return@SheetHost
        }

        Text("Send to", style = sectionLabel, modifier = Modifier.padding(start = 2.dp))
        VSpace(4.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            macs.forEach { m ->
                val orb = when {
                    target != m.id || !mine -> OrbState.Idle
                    state is TState.Working -> OrbState.Working
                    state is TState.Done -> OrbState.Done
                    state is TState.Failed -> OrbState.Failed
                    else -> OrbState.Idle
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 170.dp)) {
                    DeviceOrb(
                        Icons.Rounded.LaptopMac, orb, (state as? TState.Working)?.progress,
                        size = 80.dp, pulsing = presence[m.id] == true && target == null,
                        contentDescription = "Send to ${m.name}",
                        onClick = if (target == null) ({ send(m) }) else null,
                    )
                    Text(m.name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VSpace(2.dp)
                    AnimatedContent(
                        targetState = targetStatus(m, target, state, presence[m.id]),
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                        label = "status",
                    ) { t ->
                        Text(
                            t,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (orb == OrbState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        val failed = (state as? TState.Failed)?.takeIf { mine }
        AnimatedVisibility(visible = failed != null) {
            Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    failed?.message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = {
                    val m = macs.firstOrNull { it.id == target }
                    target = null
                    app.transfers.reset()
                    if (m != null) send(m)
                }) { Text("Try again") }
            }
        }

        AnimatedVisibility(visible = target == null && nfcReady, exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier
                    .padding(top = 18.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapHint(Modifier.size(56.dp))
                Column(Modifier.padding(start = 6.dp)) {
                    Text("Or tap your phone on your Mac", style = MaterialTheme.typography.titleSmall)
                    Text("Uses the TapDrop sticker", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        VSpace(6.dp)
    }
}

private fun targetStatus(m: SavedMac, target: String?, state: TState, online: Boolean?): String {
    if (target == m.id) {
        when (state) {
            is TState.Working -> return state.progress?.let { "${(it * 100).toInt()}%" } ?: "Connecting…"
            is TState.Done -> return "Sent"
            is TState.Failed -> return "Didn't send"
            else -> Unit
        }
    }
    return when (online) {
        true -> "Tap to send"
        false -> "Not reachable"
        null -> "Checking…"
    }
}

/** Fanned stack of thumbnails (or a text/link card) for what's being shared. */
@Composable
private fun PayloadPreview(payload: SharePayload?, items: List<OutItem>, label: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val uris = payload?.uris.orEmpty()
        val files = items.filterIsInstance<OutItem.File>()
        if (uris.isNotEmpty() && files.any { it.mime.startsWith("image/") || it.mime.startsWith("video/") }) {
            val fan = remember { Animatable(0f) }
            LaunchedEffect(Unit) { fan.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
            val shown = uris.take(3)
            Box(Modifier.height(118.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                shown.forEachIndexed { i, u ->
                    val k = i - (shown.size - 1) / 2f
                    Thumb(
                        u,
                        Modifier
                            .graphicsLayer {
                                rotationZ = k * 9f * fan.value
                                translationX = k * 30.dp.toPx() * fan.value
                                translationY = kotlin.math.abs(k) * 6.dp.toPx() * fan.value
                            }
                            .shadow(10.dp, RoundedCornerShape(20.dp))
                            .size(96.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp)),
                    )
                }
                if (uris.size > 3) {
                    Box(
                        Modifier.offset(x = 58.dp, y = (-44).dp).clip(CircleShape).background(Brand.gradient).padding(horizontal = 9.dp, vertical = 3.dp),
                    ) { Text("+${uris.size - 3}", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                }
            }
        } else {
            val text = (items.firstOrNull() as? OutItem.Text)?.text
            val isLink = label == "link"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            isLink -> Icons.Rounded.Link
                            text != null -> Icons.Rounded.Notes
                            else -> Icons.Rounded.InsertDriveFile
                        },
                        null, tint = Color.White,
                    )
                }
                Text(
                    text?.trim() ?: files.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
        VSpace(14.dp)
        Text(
            when (label) {
                "link" -> "Send link"
                "text" -> "Send text"
                else -> "Send $label"
            },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}
