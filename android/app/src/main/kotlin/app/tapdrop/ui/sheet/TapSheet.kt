package app.tapdrop.ui.sheet

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tapdrop.net.Received
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.MacGlyph
import app.tapdrop.ui.design.MediaThumb
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.SecondaryButton
import app.tapdrop.ui.design.SendButton
import app.tapdrop.ui.design.SendButtonState
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.segmentShape

/** What the sticker sheet's transfer area shows. */
@Immutable
sealed interface TapTransfer {
    data object Idle : TapTransfer

    /** Light fills the pill as [progress] goes 0 → 1; null while connecting. */
    data class Working(val label: String, val progress: Float?) : TapTransfer

    data class Sent(val label: String) : TapTransfer

    /** Something arrived from the Mac ([title] "Received 3 photos", [subtitle] "Saved to Photos"). */
    data class Got(val title: String, val subtitle: String, val items: List<Received>) : TapTransfer

    /** Finished with nothing to show ("Nothing waiting on your Mac"). */
    data class Nothing(val message: String) : TapTransfer

    data class Failed(val message: String) : TapTransfer
}

/** The newest photo or video in the gallery, for the one-tap row. [ago] is "2 min. ago". */
@Immutable
data class LatestShot(val uri: Uri, val name: String, val ago: String, val isVideo: Boolean)

/** What's on the clipboard, in a line: the text itself, or "2 photos". */
@Immutable
data class ClipSummary(val line: String, val isLink: Boolean)

/**
 * The sheet that opens when you tap your phone on the TapDrop sticker (or use the Quick Settings tile): the Mac
 * you tapped and whether it's there, whatever is on its way, and one-tap ways to send. Stateless; see TapActivity.
 *
 * @param mac the Mac, with [MacChoice.online] null while checking and false when it didn't answer.
 * @param mediaAllowed whether TapDrop may see the gallery (for "Latest photo").
 */
@Composable
fun TapSheetBody(
    mac: MacChoice?,
    transfer: TapTransfer,
    mediaAllowed: Boolean,
    latest: LatestShot?,
    clip: ClipSummary?,
    onSendLatest: () -> Unit,
    onAllowPhotos: () -> Unit,
    onSendClipboard: () -> Unit,
    onGetMacClipboard: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    onOpen: (Received) -> Unit,
    onOpenApp: () -> Unit,
) {
    if (mac == null) {
        NoMacSheet(onOpenApp)
        return
    }
    val online = mac.online
    val busy = transfer is TapTransfer.Working
    val canAct = online == true && !busy
    val reduced = Td.reducedMotion
    Column(Modifier.sheetBacking().fillMaxWidth().padding(top = 2.dp)) {
        MacHeader(mac.name, online)
        AnimatedContent(
            targetState = when {
                transfer !is TapTransfer.Idle -> transfer
                online == false -> Offline
                else -> TapTransfer.Idle
            },
            contentKey = { it::class },
            transitionSpec = {
                if (reduced) fadeIn(tween(140)) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false)
                else (fadeIn(tween(220, 90)) + slideInVertically(TdMotion.standard()) { it / 10 }) togetherWith
                    fadeOut(tween(110)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 420f) }
            },
            label = "tapTransfer",
        ) { t ->
            when (t) {
                Offline -> OfflineNote(mac.name, onReconnect, Modifier.padding(top = 18.dp))
                is TapTransfer.Working -> TransferPill(SendButtonState.Working(t.label, t.progress))
                is TapTransfer.Sent -> TransferPill(SendButtonState.Done(t.label))
                is TapTransfer.Nothing -> TransferPill(SendButtonState.Notice(t.message))
                is TapTransfer.Failed -> Column {
                    ProblemLine(t.message, Modifier.padding(top = 18.dp))
                    TransferPill(SendButtonState.Failed("Try again"), onRetry, top = 12.dp)
                }
                is TapTransfer.Got -> GotCard(t, onOpen, Modifier.padding(top = 18.dp))
                else -> Spacer(Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(22.dp))
        SheetLabel("Share with your Mac")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mediaAllowed) {
                ActionRow(
                    title = if (latest?.isVideo == true) "Latest video" else "Latest photo",
                    subtitle = latest?.let { "${it.name} · ${it.ago}" } ?: "No photos yet",
                    subtitleLines = 1,
                    shape = segmentShape(0, 3),
                    enabled = canAct && latest != null,
                    leading = { LatestThumb(latest) },
                    onClick = onSendLatest,
                )
            } else {
                ActionRow(
                    title = "Latest photo",
                    subtitle = "Let TapDrop see your photos",
                    shape = segmentShape(0, 3),
                    enabled = true,
                    leading = { IconChip(Icons.Rounded.Image, Brand.Orchid, size = 44.dp, shape = TdShapes.Cookie9, iconSize = 22.dp) },
                    trailing = { SmallButton("Allow", onAllowPhotos) },
                    onClick = onAllowPhotos,
                )
            }
            ActionRow(
                title = "Clipboard",
                subtitle = clip?.line ?: "Nothing copied yet",
                subtitleLines = 1,
                shape = segmentShape(1, 3),
                enabled = canAct && clip != null,
                leading = {
                    IconChip(
                        if (clip?.isLink == true) Icons.Rounded.Link else Icons.Rounded.ContentPaste,
                        Brand.Violet, size = 44.dp, shape = TdShapes.Clover4, iconSize = 22.dp,
                    )
                },
                onClick = onSendClipboard,
            )
            ActionRow(
                title = "Get Mac clipboard",
                subtitle = "Paste what you copied on your Mac",
                shape = segmentShape(2, 3),
                enabled = canAct,
                incoming = true,
                leading = { IconChip(Icons.Rounded.ContentPasteGo, Brand.Teal, size = 44.dp, shape = TdShapes.Sunny, iconSize = 22.dp) },
                onClick = onGetMacClipboard,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Photos", onPickPhotos, Modifier.weight(1f), icon = Icons.Rounded.PhotoLibrary, enabled = canAct)
            SecondaryButton("Files", onPickFiles, Modifier.weight(1f), icon = Icons.Rounded.Folder, enabled = canAct)
        }
    }
}

/** Marker for the transfer area's "can't reach your Mac" note. */
private data object Offline : TapTransfer

/** A transfer's progress, result or retry, in the same pill the share sheet's Send turns into. */
@Composable
private fun TransferPill(state: SendButtonState, onClick: () -> Unit = {}, top: Dp = 18.dp) {
    SendButton(state, text = "", onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = top))
}

/** The Mac you tapped: its glyph lights up once it answers. */
@Composable
private fun MacHeader(name: String, online: Boolean?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 4.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacGlyph(width = 64.dp, lit = online == true, dim = online == false)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                name,
                style = TdType.title,
                color = Td.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Presence(online, Modifier.padding(top = 3.dp))
        }
    }
}

/** The Mac didn't answer: what to check, and a way to look again (it also keeps looking on its own). */
@Composable
private fun OfflineNote(macName: String, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    val c = Td.colors
    Row(
        modifier
            .fillMaxWidth()
            .platter(RoundedCornerShape(24.dp))
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconChip(Icons.Rounded.WifiFind, c.warning, size = 40.dp, shape = TdShapes.Cookie9, iconSize = 20.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                "Can't reach your Mac",
                style = TdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = c.text,
                modifier = Modifier.padding(top = 1.dp),
            )
            Text(
                "Make sure TapDrop is open on $macName and you're on the same Wi‑Fi.",
                style = TdType.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
            TdTextButton("Try again", onReconnect, Modifier.offset(x = (-16).dp))
        }
    }
}

/** A failure in a sentence, in the danger color. */
@Composable
internal fun ProblemLine(message: String, modifier: Modifier = Modifier) {
    val c = Td.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.dangerText, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(message, style = TdType.bodySmall, color = c.dangerText, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What just arrived from the Mac: thumbnails to open, or the link or text itself. */
@Composable
private fun GotCard(t: TapTransfer.Got, onOpen: (Received) -> Unit, modifier: Modifier = Modifier) {
    val c = Td.colors
    val files = t.items.filter { it.uri != null }
    val text = t.items.firstOrNull { it.text != null }
    Column(
        modifier
            .fillMaxWidth()
            .platter(RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { }) {
            CheckBurst(visible = true, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.title, style = TdType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.subtitle.isNotEmpty() && text?.isLink != true) {
                    Text(t.subtitle, style = TdType.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when {
            files.isNotEmpty() -> LazyRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(files.take(12)) { _, r -> ReceivedThumb(r) { onOpen(r) } }
            }
            text?.isLink == true -> {
                Spacer(Modifier.height(14.dp))
                LinkCard(text.text!!.trim(), null, platter = false)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Open link", { onOpen(text) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.ArrowForward)
            }
            text != null -> Text(
                text.text!!.trim(),
                style = TdType.body,
                color = c.text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ReceivedThumb(r: Received, onClick: () -> Unit) {
    val c = Td.colors
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .pressable(shape, onClickLabel = "Open", onClick = onClick)
                .clip(shape),
        ) {
            MediaThumb(r.uri, Modifier.fillMaxSize(), fallback = fileKind(r.name, r.mime).icon, contentDescription = r.name)
            if (r.isVideo) PlayBadge(Modifier.align(Alignment.BottomEnd))
        }
        if (!r.isImage && !r.isVideo) {
            Text(
                r.name, style = TdType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** One quick action: a visual, what it sends, and an arrow that says which way it goes. */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    shape: Shape,
    enabled: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    incoming: Boolean = false,
    subtitleLines: Int = 2,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Td.colors
    val fade by animateFloatAsState(if (enabled) 1f else 0.45f, TdMotion.standard(), label = "rowFade")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .pressable(shape, enabled = enabled, pressedScale = 0.985f, role = Role.Button, onClick = onClick)
            .platter(shape)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.graphicsLayer { alpha = fade }) { leading() }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 10.dp)
                .graphicsLayer { alpha = fade },
        ) {
            Text(title, style = TdType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = TdType.bodySmall, color = c.textSecondary, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else DirectionChip(incoming, Modifier.graphicsLayer { alpha = fade })
    }
}

/** A small tonal circle with an arrow: up for "to your Mac", down for "from your Mac". */
@Composable
private fun DirectionChip(incoming: Boolean, modifier: Modifier = Modifier) {
    val c = Td.colors
    Box(
        modifier
            .size(36.dp)
            .background(c.accentSoft, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (incoming) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
            null,
            tint = c.accentText,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun LatestThumb(latest: LatestShot?) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.size(44.dp).clip(shape)) {
        MediaThumb(latest?.uri, Modifier.fillMaxSize(), fallback = Icons.Rounded.Image)
        if (latest?.isVideo == true) PlayBadge(Modifier.align(Alignment.BottomEnd), small = true)
    }
}

@Composable
private fun PlayBadge(modifier: Modifier = Modifier, small: Boolean = false) {
    val d = if (small) 16.dp else 24.dp
    Box(
        modifier
            .padding(if (small) 3.dp else 6.dp)
            .size(d)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(d * 0.66f))
    }
}

/** A quiet label over a group inside a sheet. */
@Composable
internal fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TdType.label,
        color = Td.colors.textSecondary,
        modifier = modifier.padding(start = 6.dp).semantics { heading() },
    )
}

/** No Mac paired: the sticker can't do anything yet. */
@Composable
private fun NoMacSheet(onOpenApp: () -> Unit) {
    val c = Td.colors
    Column(Modifier.sheetBacking().fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MacGlyph(width = 96.dp, lit = false, dim = true)
        Text(
            "Pair your Mac first",
            style = TdType.title.copy(lineBreak = LineBreak.Heading),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp).semantics { heading() },
        )
        Text(
            "Then a tap on the sticker opens this sheet, ready to send. Pairing takes a few seconds.",
            style = TdType.bodySmall.copy(lineBreak = LineBreak.Heading),
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Open TapDrop", onOpenApp, Modifier.fillMaxWidth())
    }
}
