package app.tapdrop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.tapdrop.TapDropApp
import app.tapdrop.core.OutItem
import app.tapdrop.data.SavedMac
import app.tapdrop.net.Outgoing
import app.tapdrop.net.TState
import app.tapdrop.nfc.TapTag
import app.tapdrop.ui.design.EmptyState
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.MacGlyph
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.SecondaryButton
import app.tapdrop.ui.design.SendButton
import app.tapdrop.ui.design.SendButtonState
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.sheet.MacChoice
import app.tapdrop.ui.sheet.MacTargets
import app.tapdrop.ui.sheet.PreviewPlaceholder
import app.tapdrop.ui.sheet.ShareContent
import app.tapdrop.ui.sheet.SharePreview
import app.tapdrop.ui.sheet.SharedFile
import app.tapdrop.ui.sheet.SheetScene
import app.tapdrop.ui.sheet.platter
import app.tapdrop.ui.sheet.sheetBacking
import app.tapdrop.ui.sheet.installSheetWindow
import app.tapdrop.ui.sheet.transferLabel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What another app handed to "Send to Mac". [subject] is the page title browsers send with a link. */
data class SharePayload(val uris: List<Uri>, val text: String?, val subject: String? = null) {
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
            val subject = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE))
                ?.trim()?.takeIf { it.isNotEmpty() }
            return if (uris.isEmpty() && text == null) null else SharePayload(uris, text, subject)
        }
    }
}

/**
 * The "Send to Mac" share target (also reached through each Mac's Direct Share shortcut): a glass sheet over
 * the app you were in, with a rich preview of what you're sharing, where it's going, and one big Send that
 * turns into its own progress bar, then a check.
 *
 * Android hands a new share to this same activity while it is up (it's single-task), even while its sheet is
 * still closing after a send. Each share gets a fresh sheet; the one closing never takes the new one with it.
 */
class ShareActivity : ComponentActivity() {
    private var payload by mutableStateOf<SharePayload?>(null)
    private var directMac by mutableStateOf<String?>(null)
    private var arrival by mutableIntStateOf(0)
    private var shown by mutableStateOf(true)

    /** Sends started from this activity's sheets. They can outlive their sheet (see [onSheetHidden]). */
    private val sends = mutableListOf<Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSheetWindow()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            TapDropTheme {
                SheetScene(shown, onHidden = ::onSheetHidden) {
                    key(arrival) {
                        val mine = arrival
                        ShareSheet(
                            payload, directMac,
                            onSend = { sends += it },
                            // Only this share's sheet closes the scene: an older one finishing its exit doesn't.
                            onDismissed = { if (mine == arrival) shown = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isFinishing) {
            // The share arrived just as this sheet finished closing: open it in a sheet of its own.
            relaunch(intent)
            return
        }
        handle(intent)
        arrival++
        shown = true
    }

    private fun handle(intent: Intent?) {
        payload = SharePayload.from(intent)
        directMac = Shortcuts.macIdFrom(intent?.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
    }

    /** Starts a new ShareActivity for [intent], passing on our read access to what it shares. */
    private fun relaunch(intent: Intent) {
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val again = Intent(intent)
            .setClass(this, ShareActivity::class.java)
            .setFlags((intent.flags and grants) or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(again) }
    }

    /**
     * The sheet has gone. Finish, unless another share brought up a new sheet meanwhile. Our read grant on the
     * shared files lives as long as this activity, so while a send is still running it moves out of sight and
     * finishes when the sends are done.
     */
    private fun onSheetHidden() {
        if (shown) return
        sends.removeAll { it.isCompleted }
        if (sends.isEmpty()) {
            finish()
            return
        }
        moveTaskToBack(true)
        lifecycleScope.launch {
            while (true) {
                val left = sends.filter { !it.isCompleted }
                if (left.isEmpty()) break
                left.joinAll()
            }
            if (!shown) finish()
        }
    }
}

/** The shared items, read once (content resolvers can be slow, so off the main thread). */
private class Resolved(val items: List<OutItem>, val content: ShareContent)

private fun resolve(ctx: Context, payload: SharePayload?): Resolved {
    payload ?: return Resolved(emptyList(), ShareContent())
    payload.text?.let { return Resolved(listOf(OutItem.Text(it)), ShareContent(text = it, subject = payload.subject)) }
    val pairs = payload.uris.mapNotNull { uri -> Outgoing.fromUri(ctx, uri)?.let { uri to it } }
    return Resolved(
        pairs.map { it.second },
        ShareContent(files = pairs.map { (uri, f) -> SharedFile(uri, f.name, f.mime, f.size) }),
    )
}

/** A send this sheet started: [id] picks its progress out of the shared transfer state; [result] is how it ended. */
private class Sending(val id: Long, val result: Deferred<TState>)

@Composable
private fun ShareSheet(payload: SharePayload?, directMacId: String?, onSend: (Job) -> Unit, onDismissed: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val history by app.prefs.history.collectAsStateWithLifecycle()
    val state by app.transfers.state.collectAsStateWithLifecycle()
    val sticker by app.prefs.stickerMac.collectAsStateWithLifecycle()
    val resolved by produceState<Resolved?>(null, payload) {
        value = withContext(Dispatchers.IO) { resolve(ctx, payload) }
    }
    val presence = remember { mutableStateMapOf<String, Boolean>() }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf<Sending?>(null) }
    var outcome by remember { mutableStateOf<TState?>(null) }
    var burst by remember { mutableIntStateOf(0) }
    var closeSignal by remember { mutableIntStateOf(0) }
    val nfcOn = remember { NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true }

    // Default to the Mac picked in Android's share sheet, else the one you last sent to.
    val lastUsed = remember(history, macs) {
        history.firstOrNull { it.outgoing }?.mac?.let { name -> macs.firstOrNull { it.name == name }?.id }
    }
    val target = macs.firstOrNull { it.id == (picked ?: directMacId ?: lastUsed) } ?: macs.firstOrNull()

    LaunchedEffect(Unit) { app.transfers.reset() }
    LaunchedEffect(macs) {
        // Look again every few seconds while a Mac is out of reach, so its row turns Ready when it's back.
        macs.forEach { m ->
            launch {
                while (true) {
                    val up = app.transfers.ping(m) != null
                    presence[m.id] = up
                    if (up) break
                    delay(5000)
                }
            }
        }
    }

    // Transfers run one at a time: a send started while another is running waits its turn (shown as Waiting).
    fun send(mac: SavedMac) {
        val r = resolved ?: return
        if (r.items.isEmpty() || (sending != null && outcome !is TState.Failed)) return
        outcome = null
        Shortcuts.reportUsed(ctx, mac.id)
        val first = r.content.files.firstOrNull()
        val id = app.transfers.newId()
        val job = app.transfers.send(mac, r.items, Outgoing.label(r.items), first?.uri, first?.mime, id = id)
        onSend(job)
        sending = Sending(id, job)
    }
    LaunchedEffect(sending) { sending?.let { outcome = it.result.await() } }

    // Direct Share: the Mac was already chosen in Android's share sheet, so go as soon as the sheet is up.
    LaunchedEffect(directMacId, resolved != null) {
        val m = macs.firstOrNull { it.id == directMacId } ?: return@LaunchedEffect
        if (resolved == null || sending != null) return@LaunchedEffect
        delay(420)
        send(m)
    }

    // The TapDrop sticker on the Mac also sends.
    NfcReader(enabled = sending == null && macs.isNotEmpty() && resolved?.items?.isNotEmpty() == true) { tag ->
        val id = TapTag.read(tag)
        val m = macs.firstOrNull { it.id == id } ?: macs.singleOrNull()
        if (m != null) {
            burst++
            Haptics.tap(ctx)
            picked = m.id
            send(m)
        }
    }

    // This sheet's own send: how it ended, else its live progress once it's running.
    val shown: TState? = outcome ?: (state as? TState.Working)?.takeIf { it.id == sending?.id }
    val button = when {
        shown is TState.Working -> SendButtonState.Working(transferLabel(shown), shown.progress)
        shown is TState.Done -> SendButtonState.Done("Sent")
        shown is TState.Failed -> SendButtonState.Failed("Try again")
        sending != null -> SendButtonState.Working("Waiting…")
        else -> SendButtonState.Ready
    }
    // Sent: let the check land, then the sheet goes, like AirDrop.
    LaunchedEffect(shown is TState.Done) {
        if (shown is TState.Done) {
            presence[shown.macId] = true
            delay(1300)
            closeSignal++
        }
    }

    GlassSheet(onDismissed = onDismissed, overlay = { TapBurst(burst) }) { close ->
        LaunchedEffect(closeSignal) { if (closeSignal > 0) close() }
        ShareSheetBody(
            content = resolved?.content,
            macs = macs.map { MacChoice(it.id, it.name, presence[it.id]) },
            targetId = target?.id,
            onSelect = { id ->
                // After a failure you can pick another Mac, which starts over.
                if (shown is TState.Failed) {
                    app.transfers.reset()
                    sending = null
                    outcome = null
                }
                if (sending == null) picked = id
            },
            button = button,
            error = (shown as? TState.Failed)?.message,
            onSend = {
                val m = target ?: return@ShareSheetBody
                if (shown is TState.Failed) app.transfers.reset()
                send(m)
            },
            stickerHint = nfcOn && sticker != null && sending == null,
            onOpenApp = {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                close()
            },
            close = close,
        )
    }
}

/** The share sheet's content, without state: preview, destination, the Send pill and its outcome. */
@Composable
internal fun ShareSheetBody(
    content: ShareContent?,
    macs: List<MacChoice>,
    targetId: String?,
    onSelect: (String) -> Unit,
    button: SendButtonState,
    error: String?,
    onSend: () -> Unit,
    stickerHint: Boolean,
    onOpenApp: () -> Unit,
    close: () -> Unit,
) {
    val c = Td.colors
    Column(Modifier.sheetBacking().fillMaxWidth().padding(top = 6.dp)) {
        if (content != null && content.isEmpty) {
            EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = "Nothing to send",
                body = "This app didn't share a photo, file, link or text that TapDrop can send.",
                shape = TdShapes.Cookie9,
                action = { SecondaryButton("Close", close) },
            )
            return@Column
        }
        if (content == null) PreviewPlaceholder() else SharePreview(content)
        Spacer(Modifier.height(14.dp))
        if (macs.isEmpty()) {
            NoMacYet(onOpenApp)
            return@Column
        }
        MacTargets(macs, targetId, onSelect, enabled = button == SendButtonState.Ready || button is SendButtonState.Failed)
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn() + expandVertically(Td.motion.standard()),
            exit = fadeOut() + shrinkVertically(Td.motion.standard()),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 14.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.dangerText, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(error.orEmpty(), style = TdType.bodySmall, color = c.dangerText, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        SendButton(
            state = button,
            text = content?.action ?: "Send",
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = content != null && targetId != null,
        )
        AnimatedVisibility(visible = stickerHint, exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Nfc, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                Text(
                    "Or tap your phone on the TapDrop sticker",
                    style = TdType.caption,
                    color = c.textTertiary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** No Mac yet: say so next to a dimmed Mac, and offer the way to pair. */
@Composable
private fun NoMacYet(onOpenApp: () -> Unit) {
    val c = Td.colors
    Row(
        Modifier
            .fillMaxWidth()
            .platter(RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacGlyph(width = 50.dp, lit = false, dim = true)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("No Mac paired yet", style = TdType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text("Pair your Mac in TapDrop first.", style = TdType.bodySmall, color = c.textSecondary)
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Open TapDrop", onOpenApp, Modifier.fillMaxWidth())
}
