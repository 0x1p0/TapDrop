package app.tapdrop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Contactless
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SettingsAccessibility
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.data.SavedMac
import app.tapdrop.data.TapAction
import app.tapdrop.tap.NotchTapService
import app.tapdrop.tap.TapTrace
import app.tapdrop.ui.design.BrandMark
import app.tapdrop.ui.design.ChoiceRow
import app.tapdrop.ui.design.DestructiveButton
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.MainSwitchRow
import app.tapdrop.ui.design.RowAllowed
import app.tapdrop.ui.design.RowChevron
import app.tapdrop.ui.design.SegmentedChoice
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SettingsRow
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.StatusPill
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.SwitchRow
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.sheetSegment
import app.tapdrop.ui.design.tdSharedBounds
import app.tapdrop.update.UpdatesSettingsGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared-element keys: a Settings row's icon chip flies into the header of the page it opens. */
const val TAP_TEST_CHIP_KEY = "tap-test-chip"
const val APP_RULES_CHIP_KEY = "app-rules-chip"
const val COMPAT_CHIP_KEY = "compat-chip"

/**
 * Everything adjustable, grouped the way people look for it, in Android 16 segmented groups: your Mac, tap to
 * send, one-time codes, permissions, the NFC sticker and help. Sub-pages open through the nav host (predictive
 * back, shared chips); the Mac and the sticker action open as glass sheets.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onPairAnother: () -> Unit,
    onNotchSetup: () -> Unit,
    onRules: () -> Unit,
    onSticker: () -> Unit,
    onTapTest: () -> Unit,
    onCompatibility: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val c = Td.colors
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    val lastTap by NotchTapService.lastTap.collectAsStateWithLifecycle()
    val videoIndex by NotchTapService.videoIndex.collectAsStateWithLifecycle()
    val photoIndex by NotchTapService.photoIndex.collectAsStateWithLifecycle()
    val indexPaused by NotchTapService.indexPaused.collectAsStateWithLifecycle()
    val selfCheck by NotchTapService.selfCheck.collectAsStateWithLifecycle()
    // Photos and videos together: what a tap can find as the original.
    val library = listOfNotNull(videoIndex, photoIndex).takeIf { it.isNotEmpty() }
        ?.let { l -> l.sumOf { it.first } to l.sumOf { it.second } }
    val modes by app.prefs.appModes.collectAsStateWithLifecycle()
    val pickOn by app.prefs.pickFirst.collectAsStateWithLifecycle()
    val appearance by app.prefs.appearance.collectAsStateWithLifecycle()
    val otpOn by app.prefs.smartOtp.collectAsStateWithLifecycle()
    val copyOnPhone by app.prefs.otpCopyOnPhone.collectAsStateWithLifecycle()
    val tapAction by app.prefs.tapAction.collectAsStateWithLifecycle()
    val sticker by app.prefs.stickerMac.collectAsStateWithLifecycle()
    val tap = rememberTapSetupState()
    val perms = rememberPermissions()
    val keepAlive = DeviceSupport.keepAlive
    val hasNfc = remember { DeviceSupport.hasNfc(ctx) }

    var macSheet by remember { mutableStateOf<String?>(null) }
    var forget by remember { mutableStateOf<String?>(null) }
    var actionSheet by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }

    LargeTitleScaffold(
        title = "Settings",
        onBack = onClose,
        chrome = {
            val shownMac = macs.firstOrNull { it.id == macSheet }
            if (shownMac != null) {
                GlassSheet(onDismissed = {
                    macSheet = null
                    // Forgotten here, and the Mac is told to forget this phone too.
                    forget?.let { id -> app.prefs.mac(id)?.let { app.transfers.forget(it) } }
                    forget = null
                }) { close ->
                    MacSheet(
                        mac = shownMac,
                        live = live && shownMac.id == macs.firstOrNull()?.id,
                        stickerHere = sticker == shownMac.id,
                        onForget = {
                            if (macs.size <= 1) {
                                // The last Mac: the app goes back to its welcome screen, sheet and all.
                                app.prefs.removeMac(shownMac.id)
                            } else {
                                forget = shownMac.id
                                close()
                            }
                        },
                        close = close,
                    )
                }
            }
            if (actionSheet) {
                GlassSheet(onDismissed = { actionSheet = false }) { close -> TapActionSheet(tapAction, close) }
            }
        },
    ) {
        item("mac") {
            SettingsGroup(title = if (macs.size > 1) "Your Macs" else "Your Mac") {
                macs.forEachIndexed { i, m ->
                    item { shape -> MacRow(m, live = live && i == 0, shape = shape) { macSheet = m.id } }
                }
                row(
                    "Pair another Mac",
                    icon = Icons.Rounded.Add,
                    accent = c.textSecondary,
                    onClick = onPairAnother,
                    trailing = { RowChevron() },
                )
            }
        }

        item("tap") {
            SettingsGroup(title = "Tap to send") {
                item { shape ->
                    MainSwitchRow(
                        title = "Use tap to send",
                        checked = tap.accessibility,
                        onCheckedChange = { on -> if (on) onNotchSetup() else confirmOff = true },
                        icon = Icons.Rounded.Sensors,
                        shape = shape,
                        subtitle = when {
                            !tap.accessibility -> "Off · takes a minute to set up"
                            tap.stalled -> "Stopped by Android · tap to fix"
                            live -> "On · ready to tap"
                            else -> "On · connects while your screen is on"
                        },
                        onClick = { if (tap.stalled) DeviceSupport.openAccessibility(ctx) else onNotchSetup() },
                    )
                }
                item { shape ->
                    SettingsRow(
                        title = "Test your tap",
                        subtitle = testSubtitle(lastTap),
                        shape = shape,
                        leading = {
                            IconChip(
                                Icons.Rounded.Flare, Td.colors.text, size = 36.dp, iconSize = 20.dp,
                                modifier = Modifier.tdSharedBounds(TAP_TEST_CHIP_KEY),
                            )
                        },
                        onClick = onTapTest,
                        trailing = { RowChevron() },
                    )
                }
                library?.takeIf { it.second > 0 }?.let { (done, total) ->
                    item { shape ->
                        SettingsRow(
                            title = "Photos & videos for tap",
                            subtitle = when {
                                done >= total && selfCheck != null -> selfCheck!!.let { c ->
                                    "All $total ready · self-check found ${c.photosRight} of ${c.photos} photos and " +
                                        "${c.videosRight} of ${c.videos} videos" +
                                        if (c.wrong == 0) ", none wrong" else ", ${c.wrong} wrong, so it's stricter on this phone now"
                                }
                                done >= total -> "All $total ready · a tap sends the original"
                                indexPaused == "warm" -> "Paused while your phone cools down · $done of $total"
                                indexPaused == "tap" -> "Paused during a tap · $done of $total"
                                else -> "Getting ready · $done of $total · keeps going while unlocked"
                            },
                            shape = shape,
                            leading = { IconChip(Icons.Rounded.VideoLibrary, Td.colors.text, size = 36.dp, iconSize = 20.dp) },
                            trailing = {
                                if (done >= total) {
                                    androidx.compose.material3.Icon(
                                        Icons.Rounded.CheckCircle, contentDescription = "Ready", tint = Td.colors.success,
                                        modifier = Modifier.size(22.dp),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        progress = { done / total.toFloat() },
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp,
                                        color = Td.colors.accent,
                                    )
                                }
                            },
                        )
                    }
                }
                item { shape ->
                    SettingsRow(
                        title = "What each app sends",
                        subtitle = when (modes.size) {
                            0 -> "Smart in every app"
                            1 -> "1 app changed · Smart for the rest"
                            else -> "${modes.size} apps changed · Smart for the rest"
                        },
                        shape = shape,
                        leading = {
                            IconChip(
                                Icons.Rounded.AutoAwesome, Td.colors.text, size = 36.dp, iconSize = 20.dp,
                                modifier = Modifier.tdSharedBounds(APP_RULES_CHIP_KEY),
                            )
                        },
                        onClick = onRules,
                        trailing = { RowChevron() },
                    )
                }
                item { shape ->
                    SwitchRow(
                        title = "Pick before sending",
                        checked = pickOn,
                        onCheckedChange = { app.prefs.setPickFirst(it) },
                        shape = shape,
                        subtitle = if (pickOn) "A moment to pick part of the screen" else "Taps send everything right away",
                        icon = Icons.Rounded.TouchApp,
                        accent = Brand.Bone,
                    )
                }
            }
        }

        item("appearance") {
            SettingsGroup(title = "Appearance") {
                item { shape ->
                    Box(Modifier.fillMaxWidth().background(Td.colors.surface, shape).padding(10.dp)) {
                        val values = listOf("system", "light", "dark")
                        SegmentedChoice(
                            options = listOf("System", "Light", "Dark"),
                            selected = values.indexOf(appearance).coerceAtLeast(0),
                            onSelect = { app.prefs.setAppearance(values[it]) },
                        )
                    }
                }
            }
        }

        item("codes") {
            SettingsGroup(title = "One-time codes") {
                item { shape ->
                    SwitchRow(
                        title = "Send codes to your Mac",
                        checked = otpOn,
                        onCheckedChange = { app.prefs.setSmartOtp(it) },
                        shape = shape,
                        subtitle = when {
                            !otpOn -> "Codes stay on your phone"
                            perms.sms -> "From texts and notifications, onto your Mac's clipboard"
                            else -> "From notifications · allow texts below for SMS codes"
                        },
                        icon = Icons.Rounded.Key,
                        accent = Brand.Warning,
                    )
                }
                item { shape ->
                    SwitchRow(
                        title = "Copy codes on this phone too",
                        checked = copyOnPhone,
                        onCheckedChange = { app.prefs.setOtpCopyOnPhone(it) },
                        shape = shape,
                        subtitle = "So you can paste them here as well",
                        icon = Icons.Rounded.ContentCopy,
                        accent = Brand.Bone,
                        enabled = otpOn,
                    )
                }
            }
        }

        item("permissions") {
            SettingsGroup(title = "Permissions", footer = "TapDrop only looks at your screen at the moment you tap.") {
                item { shape ->
                    PermissionRow(
                        Icons.Rounded.SettingsAccessibility, Td.colors.text, "Accessibility", "For tap to send",
                        ok = tap.accessibility, action = "Open", shape = shape,
                    ) { DeviceSupport.openAccessibility(ctx) }
                }
                item { shape ->
                    PermissionRow(
                        Icons.Rounded.PhotoLibrary, Td.colors.text, "Photos & videos",
                        when {
                            perms.media -> "Originals are sent, not screenshots"
                            perms.partial -> "Only some allowed · allow all so originals are found"
                            else -> "So originals are sent, not screenshots"
                        },
                        ok = perms.media, action = if (perms.partial) "Allow all" else "Allow", shape = shape,
                    ) { perms.askMedia() }
                }
                item { shape ->
                    PermissionRow(
                        Icons.Rounded.Folder, Td.colors.text, "All files", "Optional · tap a document anywhere to send it",
                        ok = perms.files, action = "Allow", shape = shape,
                    ) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
                item { shape ->
                    PermissionRow(
                        Icons.Rounded.Sms, Td.colors.text, "Text messages", "Optional · for codes sent by text",
                        ok = perms.sms, action = "Allow", shape = shape,
                    ) { perms.askSms() }
                }
                if (keepAlive != null) {
                    item { shape ->
                        PermissionRow(
                            Icons.Rounded.BatteryChargingFull, Td.colors.text, "Run in the background",
                            "${keepAlive.brand} phones stop apps to save battery",
                            ok = perms.battery, action = "Allow", shape = shape,
                        ) { DeviceSupport.requestUnrestricted(ctx) }
                    }
                    item { shape -> KeepAliveSteps(keepAlive, shape) }
                }
            }
        }

        if (hasNfc) {
            item("nfc") {
                SettingsGroup(title = "NFC sticker", footer = "Optional: another way to tap, with any NTAG213 or NTAG215 sticker.") {
                    row(
                        "NFC sticker",
                        subtitle = macs.firstOrNull { it.id == sticker }?.let { "Linked to ${it.name} · tap to rewrite" }
                            ?: "Stick one on your Mac, then tap it with your phone",
                        icon = Icons.Rounded.Nfc,
                        accent = Brand.Bone,
                        onClick = onSticker,
                        trailing = { RowChevron() },
                    )
                    row(
                        "When you tap the sticker",
                        subtitle = tapAction.label,
                        icon = Icons.Rounded.Contactless,
                        accent = Brand.Bone,
                        onClick = { actionSheet = true },
                        trailing = { RowChevron() },
                    )
                }
            }
        }

        item("updates") { UpdatesSettingsGroup() }

        item("help") {
            SettingsGroup(title = "Help") {
                item { shape ->
                    SettingsRow(
                        title = "Supported phones & Macs",
                        subtitle = "Android 13+ · macOS 15+ · what each needs",
                        shape = shape,
                        leading = {
                            IconChip(
                                Icons.Rounded.Devices, Td.colors.text, size = 36.dp, iconSize = 20.dp,
                                modifier = Modifier.tdSharedBounds(COMPAT_CHIP_KEY),
                            )
                        },
                        onClick = onCompatibility,
                        trailing = { RowChevron() },
                    )
                }
                row(
                    "How tapping works",
                    subtitle = "The gesture, and why a hand or a lamp can't send",
                    icon = Icons.Rounded.Lightbulb,
                    accent = Brand.Warning,
                    onClick = onNotchSetup,
                    trailing = { RowChevron() },
                )
                row(
                    "Private by design",
                    subtitle = "End-to-end encrypted. Stays on your Wi‑Fi, only to Macs you paired.",
                    icon = Icons.Rounded.Shield,
                    accent = Brand.Bone,
                )
            }
        }

        item("about") { AboutFooter() }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            icon = { IconChip(Icons.Rounded.Sensors, c.accent, size = 48.dp, shape = TdShapes.Cookie9, iconSize = 24.dp) },
            title = { Text("Turn off tap to send?", style = TdType.title, textAlign = TextAlign.Center) },
            text = {
                Text(
                    "Your phone stops listening for the notch. To turn it back on, you'll switch TapDrop on in Accessibility again.",
                    style = TdType.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center,
                )
            },
            confirmButton = {
                TdTextButton("Turn off", {
                    confirmOff = false
                    val service = NotchTapService.instance
                    if (service != null) service.disableSelf() else DeviceSupport.openAccessibility(ctx)
                }, color = c.dangerText)
            },
            dismissButton = { TdTextButton("Keep it on", { confirmOff = false }) },
            containerColor = c.surfaceRaised,
        )
    }
}

/** "Last tap counted · 2 min. ago", or what the page is for. */
@Composable
private fun testSubtitle(t: TapTrace?): String {
    val ago = t?.let { agoText(it.at) }
    return when {
        t == null -> "See what your Mac's notch sees"
        t.ok -> "Last tap counted · $ago"
        t.why == "calibrating" -> "Calibrating · $ago"
        else -> "Last tap didn't count · $ago"
    }
}

internal fun ago(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}

/** [ago], kept current while it's on screen ("just now" becomes "1 min. ago" without a recomposition). */
@Composable
internal fun agoText(time: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(time) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    return remember(time, now) { ago(time) }
}

// MARK: - your Mac

@Composable
private fun MacRow(mac: SavedMac, live: Boolean, shape: Shape, onClick: () -> Unit) {
    SettingsRow(
        title = mac.name,
        subtitle = if (live) "Connected · ready to tap" else "Paired",
        shape = shape,
        leading = {
            Box {
                IconChip(Icons.Rounded.LaptopMac, Td.colors.text, size = 36.dp, iconSize = 20.dp)
                if (live) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(0.dp)
                            .size(12.dp)
                            .background(Td.colors.surface, CircleShape)
                            .padding(2.dp)
                            .background(Td.colors.success, CircleShape)
                    )
                }
            }
        },
        onClick = onClick,
        trailing = { RowChevron() },
    )
}

@Composable
private fun MacSheet(mac: SavedMac, live: Boolean, stickerHere: Boolean, onForget: () -> Unit, close: () -> Unit) {
    val c = Td.colors
    var confirming by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(Icons.Rounded.LaptopMac, Td.colors.text, size = 72.dp, shape = TdShapes.Cookie9, iconSize = 34.dp, modifier = Modifier.padding(top = 8.dp))
        Text(mac.name, style = TdType.title, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        StatusPill(
            if (live) "Ready to tap" else "Paired",
            if (live) StatusTone.Live else StatusTone.Good,
            Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(22.dp))
        SettingsGroup {
            item { shape -> SheetValueRow("Address", "${mac.host}:${mac.port}", shape, mono = true) }
            if (stickerHere) item { shape -> SheetValueRow("NFC sticker", "Linked", shape) }
        }
        AnimatedContent(
            targetState = confirming,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "forget",
            modifier = Modifier.padding(top = 20.dp),
        ) { sure ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sure) {
                    Text(
                        "To use it again, you'll pair with a new code from your Mac.",
                        style = TdType.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 12.dp),
                    )
                }
                DestructiveButton(
                    if (sure) "Forget ${mac.name}" else "Forget this Mac",
                    {
                        if (sure) {
                            haptics.heavy()
                            onForget()
                        } else {
                            haptics.reject()
                            confirming = true
                        }
                    },
                    Modifier.fillMaxWidth(),
                    filled = sure,
                )
                TdTextButton(if (sure) "Cancel" else "Done", { if (sure) confirming = false else close() }, Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** A label and its value, on a segment that stands off the sheet. */
@Composable
private fun SheetValueRow(title: String, value: String, shape: Shape, mono: Boolean = false) {
    val c = Td.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(c.sheetSegment, shape)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = TdType.body.copy(fontWeight = FontWeight.Medium), color = c.text, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) TdType.mono.copy(fontSize = TdType.bodySmall.fontSize) else TdType.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

// MARK: - permissions

/** The optional permissions, re-read whenever the screen resumes (e.g. back from a system page). */
@Immutable
private class PermissionState(
    val media: Boolean,
    val partial: Boolean,
    val files: Boolean,
    val sms: Boolean,
    val battery: Boolean,
    val askMedia: () -> Unit,
    val askSms: () -> Unit,
)

@Composable
private fun rememberPermissions(): PermissionState {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    return remember(tick) {
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val all = granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        PermissionState(
            media = all,
            partial = !all && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            files = Environment.isExternalStorageManager(),
            sms = granted(Manifest.permission.RECEIVE_SMS),
            battery = DeviceSupport.batteryUnrestricted(ctx),
            askMedia = {
                media.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
            },
            askSms = { sms.launch(Manifest.permission.RECEIVE_SMS) },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    ok: Boolean,
    action: String,
    shape: Shape,
    onAction: () -> Unit,
) {
    val ctx = LocalContext.current
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = accent,
        shape = shape,
        // Granted: the row leads to App info, where it can be taken back.
        onClick = if (ok) ({ DeviceSupport.openAppInfo(ctx) }) else onAction,
        trailing = {
            AnimatedContent(ok, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "permission") { granted ->
                if (granted) RowAllowed() else SmallButton(action, onAction)
            }
        },
    )
}

/** On brands that stop background apps: the extra steps in their own settings, and a way there. */
@Composable
private fun KeepAliveSteps(keepAlive: DeviceSupport.KeepAlive, shape: Shape) {
    val c = Td.colors
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, shape)
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
    ) {
        Text("Also on ${keepAlive.brand} phones", style = TdType.body.copy(fontWeight = FontWeight.Medium), color = c.text)
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keepAlive.steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(22.dp).background(c.accentSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = TdType.caption, color = c.accentText) }
                    Text(step, style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(start = 12.dp, top = 1.dp))
                }
            }
        }
        TdTextButton("Open App info", { DeviceSupport.openAppInfo(ctx) }, Modifier.padding(top = 4.dp).align(Alignment.End))
    }
}

// MARK: - NFC sticker action

private val TapAction.icon: ImageVector
    get() = when (this) {
        TapAction.ASK -> Icons.Rounded.Apps
        TapAction.LATEST_PHOTO -> Icons.Rounded.PhotoLibrary
        TapAction.CLIPBOARD -> Icons.Rounded.ContentPaste
        TapAction.MAC_CLIPBOARD -> Icons.Rounded.ContentPasteGo
    }

private val TapAction.hue: Color
    get() = when (this) {
        TapAction.ASK -> Brand.Bone
        TapAction.LATEST_PHOTO -> Brand.Bone
        TapAction.CLIPBOARD -> Brand.Bone
        TapAction.MAC_CLIPBOARD -> Brand.Bone
    }

@Composable
private fun TapActionSheet(current: TapAction, close: () -> Unit) {
    val app = TapDropApp.instance
    val c = Td.colors
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(current) }
    Text("When you tap the sticker", style = TdType.title, color = c.text, modifier = Modifier.padding(top = 4.dp))
    Text(
        "Hold your phone to the NFC sticker on your Mac.",
        style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
    )
    SettingsGroup {
        TapAction.entries.forEach { a ->
            item { shape ->
                ChoiceRow(
                    title = a.label,
                    selected = chosen == a,
                    detail = a.detail,
                    icon = a.icon,
                    accent = a.hue,
                    shape = shape,
                    color = c.sheetSegment,
                    onClick = {
                        chosen = a
                        app.prefs.setTapAction(a)
                        scope.launch {
                            delay(260)
                            close()
                        }
                    },
                )
            }
        }
    }
}

// MARK: - about

@Composable
private fun AboutFooter() {
    val c = Td.colors
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 36.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 40.dp)
        Text("TapDrop ${appVersion(ctx)}", style = TdType.label, color = c.textSecondary, modifier = Modifier.padding(top = 12.dp))
        Text(
            "On ${TapDropApp.instance.prefs.identity.deviceName}",
            style = TdType.caption, color = c.textTertiary,
            modifier = Modifier.heightIn(min = 16.dp).padding(top = 2.dp),
        )
    }
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
