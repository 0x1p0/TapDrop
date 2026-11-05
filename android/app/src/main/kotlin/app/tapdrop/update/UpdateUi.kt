package app.tapdrop.update

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdType
import app.tapdrop.ui.agoText
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.ProgressRing
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SettingsRow
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.SwitchRow
import app.tapdrop.ui.design.TdLoadingIndicator
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.sheetSegment
import app.tapdrop.ui.fmtBytes
import app.tapdrop.ui.openLink
import app.tapdrop.ui.rememberHaptics
import app.tapdrop.update.UpdateInstaller.Phase
import app.tapdrop.update.UpdateInstaller.Problem

// MARK: - the What's new sheet

/**
 * Hosts the What's new sheet. Put it in a [app.tapdrop.ui.design.GlassLayers] chrome slot so the sheet is real
 * glass; anywhere else it's a solid surface.
 */
@Composable
fun UpdateSheetHost() {
    val open by Updates.sheet.collectAsStateWithLifecycle()
    val phase by UpdateInstaller.phase.collectAsStateWithLifecycle()
    if (!open) return
    GlassSheet(
        onDismissed = {
            Updates.closeSheet()
            UpdateInstaller.reset()
        },
        // While Android installs, the sheet stays: there's nothing to go back to.
        dismissible = phase != Phase.Installing,
    ) { close -> UpdateSheet(close) }
}

@Composable
private fun UpdateSheet(close: () -> Unit) {
    val c = Td.colors
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    val available by Updates.available.collectAsStateWithLifecycle()
    val latest by Updates.latest.collectAsStateWithLifecycle()
    val skipped by Updates.skipped.collectAsStateWithLifecycle()
    val phase by UpdateInstaller.phase.collectAsStateWithLifecycle()
    val r = (if (phase != Phase.Idle) UpdateInstaller.release else null) ?: available ?: latest
    if (r == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val mine = Updates.installed
    val isNew = mine == null || r.version > mine
    LaunchedEffect(phase is Phase.Failed) { if (phase is Phase.Failed) haptics.reject() }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(
            if (isNew) Icons.Rounded.SystemUpdate else Icons.Rounded.TaskAlt,
            if (isNew) Brand.Violet else Brand.Success,
            size = 64.dp, shape = TdShapes.Cookie9, iconSize = 30.dp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            if (isNew) "TapDrop ${r.version}" else "You're on TapDrop ${r.version}",
            style = TdType.title, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            if (isNew) listOfNotNull("You have ${Updates.installedName}", r.apk?.size?.takeIf { it > 0 }?.let(::fmtBytes)).joinToString(" · ")
            else "Here's what changed",
            style = TdType.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "What's new",
            style = TdType.caption, color = c.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 20.dp, bottom = 8.dp),
        )
        val maxNotes = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
        val scroll = rememberScrollState()
        val notesShape = RoundedCornerShape(24.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxNotes)
                .clip(notesShape)
                .background(c.sheetSegment),
        ) {
            Box(Modifier.verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 16.dp)) {
                if (r.notes.isBlank()) {
                    Text("This version doesn't have notes.", style = TdType.bodySmall, color = c.textSecondary)
                } else {
                    MarkdownText(r.notes, dropHeading = "What's new")
                }
            }
            // More below: the notes fade out instead of stopping mid-line.
            if (scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(Brush.verticalGradient(listOf(c.sheetSegment.copy(alpha = 0f), c.sheetSegment))),
                )
            }
        }
        AnimatedContent(
            targetState = if (isNew) phase else Phase.Idle,
            contentKey = { it::class },
            transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) },
            label = "updatePhase",
            modifier = Modifier.padding(top = 20.dp),
        ) { p ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    !isNew -> PrimaryButton("Done", close, Modifier.fillMaxWidth())
                    p is Phase.Idle -> {
                        PrimaryButton("Update", { UpdateInstaller.start(ctx, r) }, Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
                            TdTextButton("Later", close)
                            if (skipped != r.version.toString()) {
                                TdTextButton("Skip this version", {
                                    Updates.skip(r)
                                    close()
                                }, color = c.textSecondary)
                            }
                        }
                    }
                    p is Phase.Downloading -> ProgressPanel(
                        title = "Downloading…",
                        detail = p.fraction?.let { "${(it * 100).toInt()}%  ·  ${fmtBytes(p.done)} of ${fmtBytes(p.total)}" } ?: fmtBytes(p.done),
                        progress = p.fraction,
                        action = { TdTextButton("Cancel", { UpdateInstaller.cancel(ctx) }, color = c.textSecondary) },
                    )
                    p == Phase.Verifying -> ProgressPanel(title = "Checking the download…", detail = "Making sure it's the real TapDrop")
                    p == Phase.Installing -> ProgressPanel(
                        title = "Installing…",
                        detail = "Confirm in the window Android shows. TapDrop opens again when it's done.",
                    )
                    p == Phase.NeedsPermission -> {
                        Message("Android needs your OK first: turn on Allow from this source for TapDrop, then come back here.")
                        PrimaryButton("Open settings", { UpdateInstaller.openPermission(ctx) }, Modifier.fillMaxWidth().padding(top = 16.dp))
                        TdTextButton("Later", close, Modifier.padding(top = 4.dp))
                    }
                    p is Phase.Failed -> {
                        LaunchedEffect(p) { Log.w("TapDrop", "update failed: ${p.problem} ${p.detail.orEmpty()}") }
                        Message(problemText(p.problem), error = true)
                        val retry = p.problem in setOf(Problem.Offline, Problem.Checksum, Problem.Storage, Problem.Install)
                        if (p.problem == Problem.Offline || p.problem == Problem.Storage) {
                            PrimaryButton("Try again", { UpdateInstaller.reset(); UpdateInstaller.start(ctx, r) }, Modifier.fillMaxWidth().padding(top = 16.dp))
                            TdTextButton("Download from GitHub", { openLink(ctx, r.pageUrl) }, Modifier.padding(top = 4.dp), icon = Icons.AutoMirrored.Rounded.OpenInNew)
                        } else {
                            PrimaryButton(
                                "Download from GitHub", { openLink(ctx, r.pageUrl) }, Modifier.fillMaxWidth().padding(top = 16.dp),
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            )
                            if (retry) {
                                TdTextButton("Try again", { UpdateInstaller.reset(); UpdateInstaller.start(ctx, r) }, Modifier.padding(top = 4.dp))
                            } else {
                                TdTextButton("Close", close, Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What went wrong, in a sentence or two, and what to do about it. */
private fun problemText(p: Problem): String = when (p) {
    Problem.Offline -> "The download stopped. Check your connection and try again."
    Problem.Missing -> "This release can't be installed from inside TapDrop. Download it from GitHub instead."
    Problem.Checksum -> "The download didn't match the release's checksum, so it wasn't installed. Try again, or download it from GitHub."
    Problem.Signature -> "This update isn't signed with the same key as the TapDrop on your phone (a copy built from the source, for example), so it can't install over it. Download it from GitHub instead."
    Problem.NotNewer -> "That download isn't newer than the TapDrop you have."
    Problem.Storage -> "There isn't enough free space for the update. Free up some room and try again."
    Problem.Install -> "Android couldn't install the update. Try again, or download it from GitHub."
}

@Composable
private fun Message(text: String, error: Boolean = false) {
    Text(
        text,
        style = TdType.bodySmall,
        color = if (error) Td.colors.dangerText else Td.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 340.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A step under way: a ring (or the loading shape), what's happening, and an optional action. */
@Composable
private fun ProgressPanel(title: String, detail: String, progress: Float? = null, action: (@Composable () -> Unit)? = null) {
    val c = Td.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(c.sheetSegment, RoundedCornerShape(24.dp))
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (progress != null) ProgressRing(progress, Modifier.size(34.dp), stroke = 4.dp)
            else TdLoadingIndicator(Modifier.size(40.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, style = TdType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.text)
            Text(detail, style = TdType.numeric.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = c.textSecondary)
        }
        action?.invoke()
    }
}

// MARK: - Home card

/**
 * Home: "TapDrop 2.1.1 is available" with What's new and Update, or, right after an update, a word that it
 * worked. Takes no room when there's nothing to say.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val available by Updates.available.collectAsStateWithLifecycle()
    val skipped by Updates.skipped.collectAsStateWithLifecycle()
    val just by Updates.justUpdated.collectAsStateWithLifecycle()
    val latest by Updates.latest.collectAsStateWithLifecycle()
    val phase by UpdateInstaller.phase.collectAsStateWithLifecycle()
    val offered = available?.takeIf { it.version.toString() != skipped }
    AnimatedVisibility(
        visible = offered != null || just != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        val c = Td.colors
        Box(Modifier.padding(top = 18.dp)) {
            if (offered != null) {
                GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 18.dp, end = 10.dp, top = 18.dp, bottom = 6.dp), onClick = { Updates.openSheet() }) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 8.dp)) {
                        IconChip(Icons.Rounded.SystemUpdate, Brand.Violet, size = 44.dp, shape = TdShapes.Cookie9, iconSize = 22.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text("TapDrop ${offered.version} is available", style = TdType.subtitle, color = c.text)
                            val line = when (val p = phase) {
                                is Phase.Downloading -> "Downloading · ${p.fraction?.let { "${(it * 100).toInt()}%" } ?: fmtBytes(p.done)}"
                                Phase.Verifying -> "Checking the download…"
                                Phase.Installing -> "Installing…"
                                Phase.NeedsPermission -> "Waiting for Allow from this source"
                                else -> notesSummary(offered.notes) ?: "A new version is ready to install."
                            }
                            Text(
                                line, style = TdType.bodySmall, color = c.textSecondary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TdTextButton("What's new", { Updates.openSheet() })
                        SmallButton("Update", { Updates.openSheet(startUpdate = true) }, prominent = true, modifier = Modifier.padding(end = 4.dp))
                    }
                }
            } else if (just != null) {
                GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(start = 18.dp, end = 10.dp, top = 18.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 8.dp)) {
                        IconChip(Icons.Rounded.TaskAlt, Brand.Success, size = 44.dp, shape = TdShapes.Cookie9, iconSize = 22.dp)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text("Updated to TapDrop $just", style = TdType.subtitle, color = c.text)
                            Text(
                                "Your Mac and tap to send are just as you left them.",
                                style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        if (latest?.version?.toString() == just && latest?.notes?.isNotBlank() == true) {
                            TdTextButton("What's new", { Updates.openSheet() })
                        }
                        TdTextButton("Done", { Updates.dismissJustUpdated() })
                    }
                }
            }
        }
    }
}

// MARK: - Settings

/** Settings → Updates: this version, "Check for updates" with when it last ran, and the daily check. */
@Composable
fun UpdatesSettingsGroup(modifier: Modifier = Modifier) {
    val c = Td.colors
    val auto by TapDropApp.instance.prefs.autoUpdates.collectAsStateWithLifecycle()
    val available by Updates.available.collectAsStateWithLifecycle()
    val skipped by Updates.skipped.collectAsStateWithLifecycle()
    val last by Updates.lastCheck.collectAsStateWithLifecycle()
    val check by Updates.check.collectAsStateWithLifecycle()
    val notify = rememberNotificationAsk()
    val lastText = if (last > 0) agoText(last) else null
    val r = available
    val isSkipped = r != null && r.version.toString() == skipped

    SettingsGroup(
        modifier,
        title = "Updates",
        footer = "New versions come from TapDrop's releases on GitHub. Each one is checked before it installs.",
    ) {
        item { shape ->
            SettingsRow(
                title = "Version ${Updates.installedName}",
                subtitle = when {
                    r == null && last > 0 -> "You're up to date"
                    r == null -> "The version on this phone"
                    isSkipped -> "${r.version} is available · you skipped it"
                    else -> "${r.version} is available"
                },
                icon = Icons.Rounded.SystemUpdate,
                accent = Brand.Violet,
                shape = shape,
                onClick = if (r != null) ({ Updates.openSheet() }) else null,
                trailing = if (r != null) {
                    { SmallButton(if (isSkipped) "See" else "Update", { Updates.openSheet() }, prominent = !isSkipped) }
                } else null,
            )
        }
        item { shape ->
            val checking = check == Updates.CheckState.Checking
            SettingsRow(
                title = "Check for updates",
                subtitle = when (val s = check) {
                    Updates.CheckState.Checking -> "Checking…"
                    is Updates.CheckState.Failed -> when (s.reason) {
                        ReleaseFeed.Failure.Offline -> "Couldn't reach GitHub. Check your connection and try again."
                        ReleaseFeed.Failure.Busy -> "GitHub is busy right now. Try again in a little while."
                        ReleaseFeed.Failure.Unreadable -> "Couldn't read the latest release. Try again later."
                    }
                    else -> lastText?.let { "Last checked $it" } ?: "Not checked yet"
                },
                icon = Icons.Rounded.Refresh,
                accent = Brand.Blue,
                shape = shape,
                onClick = { Updates.check(manual = true) },
                enabled = !checking,
                trailing = if (checking) {
                    { TdLoadingIndicator(Modifier.size(32.dp)) }
                } else null,
            )
        }
        item { shape ->
            SwitchRow(
                title = "Check for updates automatically",
                checked = auto,
                onCheckedChange = { on ->
                    Updates.setAutomatic(on)
                    if (on && !notify.allowed) notify.ask()
                },
                shape = shape,
                subtitle = if (auto) "Once a day, with a notification when there's a new version" else "Only when you check",
                icon = Icons.Rounded.Autorenew,
                accent = Brand.Teal,
            )
        }
        if (auto && !notify.allowed) {
            item { shape ->
                SettingsRow(
                    title = "Update notifications",
                    subtitle = "Off · allow them to hear about new versions",
                    icon = Icons.Rounded.NotificationsActive,
                    accent = Brand.Warning,
                    shape = shape,
                    trailing = { SmallButton("Allow", notify.ask) },
                )
            }
        }
    }
}

// MARK: - notification permission

/** Whether update notifications can show, and a way to allow them. Re-read whenever the screen resumes. */
@Stable
class NotificationAsk(val allowed: Boolean, val ask: () -> Unit)

/**
 * Asks for Android 13's notification permission the first time; after Android stops showing that prompt (it
 * was declined twice), or when notifications are off in Settings, opens TapDrop's notification settings instead.
 */
@Composable
fun rememberNotificationAsk(): NotificationAsk {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    return remember(tick) {
        NotificationAsk(Updates.notificationsAllowed(ctx)) {
            val sp = Updates.store(ctx)
            val granted = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val asked = sp.getBoolean(K_ASKED, false)
            val canPrompt = !asked || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
            if (!granted && canPrompt) {
                sp.edit { putBoolean(K_ASKED, true) }
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings(ctx, appWide = !granted)
            }
        }
    }
}

private fun openNotificationSettings(ctx: Context, appWide: Boolean) {
    val channelOff = !appWide && Updates.notificationsAllowed(ctx).not() &&
        ctx.getSystemService(android.app.NotificationManager::class.java)?.areNotificationsEnabled() == true
    val intent = if (channelOff) {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Updates.CHANNEL)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    }
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val K_ASKED = "askedNotifications"
