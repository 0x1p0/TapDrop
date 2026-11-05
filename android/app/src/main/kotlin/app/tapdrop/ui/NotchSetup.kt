package app.tapdrop.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.tap.NotchTapService
import app.tapdrop.ui.design.AuroraBackdrop
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.HowToTapIllustration
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.ProgressRing
import app.tapdrop.ui.design.RowChevron
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SettingsRow
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.StepRow
import app.tapdrop.ui.design.StepState
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.tdSharedBounds
import app.tapdrop.update.rememberNotificationAsk

/**
 * The words for the tap (the same on the Mac). Use these everywhere the gesture is explained: there is one
 * gesture, the top of the screen against the notch, held until the phone buzzes.
 */
object TapCopy {
    const val PRIMARY = "Touch the top of your phone's screen to the notch."
    const val HOLD = "Hold it there until your phone buzzes."
    const val SAFE = "TapDrop only sends when your Mac's notch sees your phone's screen light up and go dark right against it. " +
        "A hand, a lamp or a tap anywhere else can't send."
    const val PRIVATE = "Stays on your Wi‑Fi, end‑to‑end encrypted, only to your Mac."

    /** The Mac didn't see the screen's light (not-seen, no-light-sent). */
    const val NOT_SEEN = "Your Mac didn't see your screen. Press the top of the screen flat on the notch."
    /** The light came, but the notch didn't go dark with the phone (stayed-lit, no-key-off). */
    const val KEEP_ON = "Keep your phone on the notch until it buzzes."
    /** The phone's back was to the notch (facing, screen-only). */
    const val TURN_AROUND = "Turn your phone around: its screen goes against the notch."
    /** Anything else that didn't count (other-phone, late, gone, busy, cooldown…). */
    const val TRY_AGAIN = "That tap didn't count. Try again."
}

/** Shared-element key for the tap illustration (Home card → setup screen). */
const val TAP_ILLUSTRATION_KEY = "tap-illustration"

// MARK: - state

/** Everything tap to send needs, as of the last time the screen resumed. */
@Immutable
data class TapSetupState(
    /** TapDrop is switched on in Accessibility. */
    val accessibility: Boolean,
    /** …and the system has it connected and running. */
    val running: Boolean,
    /** The "Allow restricted settings" step: true = blocked, false = allowed, null = can't tell. */
    val restricted: Boolean?,
    /** TapDrop was tried in Accessibility, which is what makes Android offer "Allow restricted settings". */
    val tried: Boolean,
    /** App info was opened after that. Newer Android doesn't let an app read whether it was allowed there. */
    val visited: Boolean,
    /** This brand stops background apps (Samsung, Xiaomi…), so battery must be unrestricted. */
    val needsBattery: Boolean,
    val battery: Boolean,
) {
    /** Turned on and running: taps work. */
    val ready: Boolean get() = accessibility && running
    /** Ready, and nothing will stop it in the background. */
    val complete: Boolean get() = ready && (!needsBattery || battery)
    /** Switched on but not running (Android stopped the service). */
    val stalled: Boolean get() = accessibility && !running
}

/** Reads [TapSetupState] now and again whenever the screen resumes (e.g. back from system settings). */
@Composable
fun rememberTapSetupState(): TapSetupState {
    val ctx = LocalContext.current
    val running by NotchTapService.running.collectAsStateWithLifecycle()
    val tried by app.tapdrop.TapDropApp.instance.prefs.restrictedTried.collectAsStateWithLifecycle()
    val visited by app.tapdrop.TapDropApp.instance.prefs.restrictedVisited.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(tick, running, tried, visited) {
        val keepAlive = DeviceSupport.keepAlive
        TapSetupState(
            accessibility = NotchTapService.isEnabled(ctx),
            running = running,
            restricted = restrictedSettingsBlocked(ctx),
            tried = tried,
            visited = visited,
            needsBattery = keepAlive != null,
            battery = keepAlive == null || DeviceSupport.batteryUnrestricted(ctx),
        )
    }
}

/**
 * Whether Android is blocking "restricted settings" (accessibility) for this app. It does that for apps
 * installed from a downloaded or local APK file until the user allows it in App info. Null when unknown.
 */
private fun restrictedSettingsBlocked(ctx: Context): Boolean? {
    val mode = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        // Deprecated in API 37 for a variant that doesn't exist on 33–36, which this app still supports.
        @Suppress("DEPRECATION")
        ops.unsafeCheckOpNoThrow("android:access_restricted_settings", ctx.applicationInfo.uid, ctx.packageName)
    }.getOrNull()
    val source = runCatching { ctx.packageManager.getInstallSourceInfo(ctx.packageName).packageSource }.getOrNull()
    // Newer Android doesn't let an app read this (mode null): then the install source is all there is to go on.
    android.util.Log.i("TapDrop", "restricted settings: mode=$mode source=$source")
    if (mode == AppOpsManager.MODE_ALLOWED) return false
    if (mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED) return true
    source ?: return null
    return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
}

/** Where TapDrop sits in this phone's Accessibility list, for when the system opens the list. */
private val accessibilityHint: String
    get() {
        val p = DeviceSupport.accessibilityPath
        return when {
            "(or Installed apps)" in p -> "If you land on the list, it's under Downloaded apps (or Installed apps)."
            "Installed apps" in p -> "If you land on the list, it's under Installed apps."
            else -> "If you land on the list, it's under Downloaded apps."
        }
    }

// MARK: - setup screen

/**
 * "Tap to send": how the gesture works, the setup checklist (restricted settings, accessibility, battery)
 * and why it's safe. Shown from Home and Settings. Back is handled by the nav host (predictive).
 */
@Composable
fun TapSetupScreen(onClose: () -> Unit, onTest: () -> Unit = {}) {
    val ctx = LocalContext.current
    val s = rememberTapSetupState()
    val haptics = rememberHaptics()
    var celebrated by rememberSaveable { mutableStateOf(s.complete) }
    if (s.complete && !celebrated) {
        celebrated = true
        haptics.confirm()
    }

    LargeTitleScaffold(
        title = "Tap to send",
        subtitle = if (s.complete) "On and ready" else "A minute to set up, once",
        onBack = onClose,
        background = { AuroraBackdrop(Modifier.fillMaxWidth().height(520.dp), intensity = 0.75f) },
    ) {
        item("illustration") {
            Box(
                Modifier
                    .tdSharedBounds(TAP_ILLUSTRATION_KEY)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Td.colors.surface)
                    .border(1.dp, Td.colors.hairline, RoundedCornerShape(28.dp)),
            ) {
                HowToTapIllustration(loops = 2, startAtRest = true)
            }
        }
        item("copy") {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp)) {
                Text(TapCopy.PRIMARY, style = TdType.title, color = Td.colors.text)
                Text(TapCopy.HOLD, style = TdType.body, color = Td.colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item("steps") {
            Column {
                AnimatedContent(
                    targetState = s.complete,
                    transitionSpec = { fadeIn(tween(260, 80)) togetherWith fadeOut(tween(140)) },
                    label = "setupDone",
                ) { done ->
                    if (done) AllSetCard(Modifier.padding(top = 24.dp)) else SetupSteps(s, ctx)
                }
            }
        }
        if (s.ready) item("test") {
            SettingsRow(
                title = "Test your tap",
                subtitle = "See what your Mac's notch sees",
                modifier = Modifier.padding(top = 12.dp),
                leading = {
                    IconChip(
                        Icons.Rounded.Flare, Brand.Blue, size = 36.dp, iconSize = 20.dp,
                        modifier = Modifier.tdSharedBounds(TAP_TEST_CHIP_KEY),
                    )
                },
                onClick = onTest,
                trailing = { RowChevron() },
            )
        }
        item("safe") {
            Column {
                SectionHeader("Why it's safe")
                GlassCard(padding = PaddingValues(18.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconChip(Icons.Rounded.Shield, Brand.Success, size = 40.dp, shape = TdShapes.Cookie6, iconSize = 20.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Only your phone's screen can send", style = TdType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Td.colors.text)
                            Text(TapCopy.SAFE, style = TdType.bodySmall, color = Td.colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        IconChip(Icons.Rounded.Lock, Brand.Blue, size = 40.dp, shape = TdShapes.Cookie6, iconSize = 20.dp)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Private", style = TdType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Td.colors.text)
                            Text(
                                "TapDrop only looks at your screen at the moment you tap. ${TapCopy.PRIVATE}",
                                style = TdType.bodySmall, color = Td.colors.textSecondary, modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupSteps(s: TapSetupState, ctx: Context) {
    // Apps installed from a file are restricted in Accessibility, and Android only offers "Allow restricted settings"
    // (App info → ⋮) after TapDrop has been tried there once and refused. So, where it's restricted: try it (and close
    // "App was denied access"), allow it in App info, then switch it on. These steps go once it's switched on.
    val showRestricted = !s.accessibility && (s.restricted != false || s.tried)
    val known = s.restricted == true
    val tryState = when {
        s.accessibility || s.restricted == false -> StepState.Done
        s.tried -> StepState.Done
        known -> StepState.Current
        else -> StepState.Optional
    }
    // Newer Android doesn't let TapDrop read whether it was allowed, so once App info has been visited the next step
    // leads, and this one stays at hand (dashed) in case Android still refuses.
    val allowState = when {
        s.accessibility || s.restricted == false -> StepState.Done
        !known || s.visited -> StepState.Optional
        s.tried -> StepState.Current
        else -> StepState.Pending
    }
    val a11yState = when {
        s.ready -> StepState.Done
        s.restricted == true && !s.visited -> StepState.Pending
        else -> StepState.Current
    }
    val batteryState = when {
        s.battery -> StepState.Done
        s.ready -> StepState.Current
        else -> StepState.Pending
    }
    val prefs = app.tapdrop.TapDropApp.instance.prefs
    var n = 0
    SettingsGroup(title = "Setup") {
        if (showRestricted) {
            val tryNum = ++n
            item { shape ->
                StepRow(
                    number = tryNum,
                    title = "Try TapDrop in Accessibility",
                    body = (if (known) "Tap" else "Only if Android restricts TapDrop: tap") +
                        " TapDrop in the list. Android says it's restricted (“App was denied access” or “Restricted setting”): " +
                        "close that. It unlocks the next step.",
                    state = tryState,
                    shape = shape,
                    action = "Open",
                    onAction = {
                        prefs.setRestrictedTried(true)
                        DeviceSupport.openAccessibility(ctx)
                    },
                )
            }
            val allowNum = ++n
            item { shape ->
                StepRow(
                    number = allowNum,
                    title = "Allow restricted settings",
                    body = if (s.visited && known) {
                        "If the next step still says it's restricted: App info, ⋮ at the top right, " +
                            "Allow restricted settings, and confirm."
                    } else {
                        "In App info, tap ⋮ at the top right, then Allow restricted settings, and confirm. " +
                            "(The ⋮ menu only offers it after the step before.)"
                    },
                    state = allowState,
                    shape = shape,
                    action = "App info",
                    onAction = {
                        if (s.tried) prefs.setRestrictedVisited(true)
                        DeviceSupport.openAppInfo(ctx)
                    },
                )
            }
        }
        val a11yNum = ++n
        item { shape ->
            StepRow(
                number = a11yNum,
                title = if (s.stalled) "Restart TapDrop in Accessibility" else "Turn on TapDrop in Accessibility",
                body = if (s.stalled) "It's switched on, but Android stopped it. Turn it off and on again."
                else "Tap TapDrop, switch on Use TapDrop, then tap Allow. $accessibilityHint",
                state = a11yState,
                shape = shape,
                action = "Open",
                onAction = { DeviceSupport.openAccessibility(ctx) },
            )
        }
        if (s.needsBattery) {
            val num = ++n
            val brand = DeviceSupport.keepAlive?.brand ?: "Your"
            item { shape ->
                StepRow(
                    number = num,
                    title = "Let it run in the background",
                    body = "$brand phones stop apps to save battery. Allow TapDrop so taps always work.",
                    state = batteryState,
                    shape = shape,
                    action = "Allow",
                    onAction = { DeviceSupport.requestUnrestricted(ctx) },
                )
            }
        }
    }
}

@Composable
private fun AllSetCard(modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), padding = PaddingValues(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckBurst(visible = true, size = 40.dp)
            Column(Modifier.padding(start = 4.dp)) {
                Text("You're all set", style = TdType.subtitle, color = Td.colors.text)
                Text(
                    "Open anything, then touch the top of your phone's screen to the notch and hold it until it buzzes.",
                    style = TdType.bodySmall, color = Td.colors.textSecondary,
                )
            }
        }
    }
}

// MARK: - Home cards

/**
 * Home card while tap to send is off: the how-to-tap illustration, one line of explanation and the
 * "Turn on" button. The illustration is a shared element into [TapSetupScreen].
 */
@Composable
fun TapSetupCard(onSetup: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), padding = PaddingValues(0.dp), onClick = onSetup) {
        Box(
            Modifier
                .tdSharedBounds(TAP_ILLUSTRATION_KEY)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        ) {
            HowToTapIllustration(loops = 1)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)) {
            Text("Tap your Mac to send", style = TdType.title, color = Td.colors.text)
            Text(
                "${TapCopy.PRIMARY} ${TapCopy.HOLD} Whatever's on screen lands on your Mac.",
                style = TdType.bodySmall,
                color = Td.colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Turn on tap to send", onSetup, Modifier.fillMaxWidth())
        }
    }
}

/** Home card when tap to send is switched on but Android stopped it. */
@Composable
fun TapStalledCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    SettingsRow(
        title = "Tap to send stopped",
        subtitle = "Android stopped it in the background. Turn TapDrop off and on in Accessibility.",
        modifier = modifier,
        leading = { IconChip(Icons.Rounded.WarningAmber, Brand.Warning, size = 40.dp, shape = TdShapes.Cookie6, iconSize = 20.dp) },
        trailing = { SmallButton("Open", { DeviceSupport.openAccessibility(ctx) }, prominent = true) },
    )
}

// MARK: - optional extras checklist

@Immutable
private data class Extras(val media: Boolean, val partial: Boolean, val files: Boolean, val sms: Boolean, val battery: Boolean?)

/**
 * "Get the most out of TapDrop": the optional permissions (original photos, codes from texts, any file,
 * background running on brands that need it) as a collapsible checklist. Steps aside once everything is done
 * (everything stays reachable in Settings).
 */
@Composable
fun ExtrasChecklist(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val e = remember(tick) {
        val all = granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        Extras(
            media = all,
            partial = !all && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            files = Environment.isExternalStorageManager(),
            sms = granted(Manifest.permission.RECEIVE_SMS),
            battery = if (DeviceSupport.keepAlive != null) DeviceSupport.batteryUnrestricted(ctx) else null,
        )
    }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    // Update notifications only matter while TapDrop checks for updates by itself.
    val autoUpdates by app.tapdrop.TapDropApp.instance.prefs.autoUpdates.collectAsStateWithLifecycle()
    val notify = rememberNotificationAsk()
    val notifyOk = if (autoUpdates) notify.allowed else null

    val checks = listOfNotNull(e.media, e.sms, e.files, notifyOk, e.battery)
    val done = checks.count { it }
    val total = checks.size
    if (done >= total) return
    var open by rememberSaveable { mutableStateOf(true) }

    Column(modifier.fillMaxWidth()) {
        SettingsGroup {
            item { shape ->
                SettingsRow(
                    title = "Get the most out of TapDrop",
                    subtitle = "Original photos, codes from texts and more",
                    shape = shape,
                    modifier = Modifier.semantics {
                        stateDescription = "$done of $total done, " + if (open) "expanded" else "collapsed"
                    },
                    onClick = { open = !open },
                    leading = {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            ProgressRing(done / total.toFloat(), Modifier.size(36.dp), stroke = 4.dp)
                            // Part of the ring's drawing, so it keeps its size at large font scales (it would overrun
                            // the ring); the row says the same in words to TalkBack.
                            val label = with(LocalDensity.current) { 11.dp.toSp() }
                            Text(
                                "$done/$total",
                                style = TdType.caption.copy(fontFeatureSettings = "tnum", fontSize = label, lineHeight = label),
                                color = Td.colors.text,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                    },
                    trailing = {
                        val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, TdMotion.standard(), label = "chev")
                        Icon(
                            Icons.Rounded.KeyboardArrowDown, null,
                            tint = Td.colors.textTertiary,
                            modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rot },
                        )
                    },
                )
            }
            if (open) {
                item { shape ->
                    ExtraRow(
                        Icons.Rounded.PhotoLibrary, Brand.Orchid, "Original photos & videos",
                        when {
                            e.media -> "The photo you're viewing is sent as the original"
                            e.partial -> "Only some photos allowed: allow all so originals can be found"
                            else -> "So what you're viewing is sent as the original, not a screenshot"
                        },
                        e.media, if (e.partial) "Allow all" else "Allow", shape,
                    ) {
                        askMedia.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
                    }
                }
                item { shape ->
                    ExtraRow(
                        Icons.Rounded.Sms, Brand.Warning, "Codes from texts",
                        if (e.sms) "One-time codes land on your Mac's clipboard" else "So codes in text messages go to your Mac",
                        e.sms, "Allow", shape,
                    ) { askSms.launch(Manifest.permission.RECEIVE_SMS) }
                }
                item { shape ->
                    ExtraRow(
                        Icons.Rounded.Folder, Brand.Blue, "Any file",
                        if (e.files) "Tap a document anywhere to send it" else "Optional: tap a PDF or ZIP in Files or a chat to send it",
                        e.files, "Allow", shape,
                    ) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
                if (notifyOk != null) {
                    item { shape ->
                        ExtraRow(
                            Icons.Rounded.NotificationsActive, Brand.Violet, "Update notifications",
                            if (notifyOk) "You'll hear when a new version of TapDrop is out" else "So you hear when a new version of TapDrop is out",
                            notifyOk, "Allow", shape, notify.ask,
                        )
                    }
                }
                if (e.battery != null) {
                    item { shape ->
                        ExtraRow(
                            Icons.Rounded.BatteryChargingFull, Brand.Success, "Run in the background",
                            if (e.battery) "TapDrop won't be stopped to save battery"
                            else "${DeviceSupport.keepAlive?.brand} phones stop apps in the background: allow TapDrop",
                            e.battery, "Allow", shape,
                        ) { DeviceSupport.requestUnrestricted(ctx) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    detail: String,
    ok: Boolean,
    action: String,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = detail,
        icon = icon,
        accent = accent,
        shape = shape,
        trailing = {
            AnimatedContent(ok, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "extraOk") { isOk ->
                if (isOk) Text("Done", style = TdType.label, color = Td.colors.successText, modifier = Modifier.padding(horizontal = 8.dp))
                else SmallButton(action, onClick)
            }
        },
    )
}
