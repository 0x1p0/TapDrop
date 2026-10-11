package app.tapdrop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.data.TapAction
import app.tapdrop.tap.NotchTapService

private object Accents {
    val blue = Brush.linearGradient(listOf(Color(0xFF4FA8FF), Color(0xFF2F64F5)))
    val orange = Brush.linearGradient(listOf(Color(0xFFFFC24A), Color(0xFFFF8A3D)))
    val pink = Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF4F8B)))
    val teal = Brush.linearGradient(listOf(Color(0xFF2ED3B0), Color(0xFF14A08F)))
    val violet = Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF6A3FF0)))
    val green = Brush.linearGradient(listOf(Color(0xFF4CD97B), Color(0xFF1FA463)))
    val gray = Brush.linearGradient(listOf(Color(0xFF9097A6), Color(0xFF6B7180)))
}

/** Everything adjustable, in one place, grouped the way people look for it. */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onPairAnother: () -> Unit,
    onNotchSetup: () -> Unit,
    onRules: () -> Unit,
    onSticker: () -> Unit,
    onSpot: () -> Unit,
) {
    var page by remember { mutableStateOf("main") }
    BackHandler { if (page == "main") onClose() else page = "main" }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlowBackdrop(Modifier.fillMaxWidth().height(300.dp), intensity = 0.6f)
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState != "main"
                (slideInHorizontally(tween(320)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(320)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
            },
            label = "settingsPage",
        ) { p ->
            when (p) {
                "compat" -> CompatibilityPage(onBack = { page = "main" })
                else -> SettingsMain(onClose, onPairAnother, onNotchSetup, onRules, onSticker, onSpot, onCompat = { page = "compat" })
            }
        }
        StatusBarScrim()
    }
}

@Composable
private fun SettingsMain(
    onClose: () -> Unit,
    onPairAnother: () -> Unit,
    onNotchSetup: () -> Unit,
    onRules: () -> Unit,
    onSticker: () -> Unit,
    onSpot: () -> Unit,
    onCompat: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    val modes by app.prefs.appModes.collectAsStateWithLifecycle()
    val pickOn by app.prefs.pickFirst.collectAsStateWithLifecycle()
    val otpOn by app.prefs.smartOtp.collectAsStateWithLifecycle()
    val copyOnPhone by app.prefs.otpCopyOnPhone.collectAsStateWithLifecycle()
    val tapAction by app.prefs.tapAction.collectAsStateWithLifecycle()
    val sticker by app.prefs.stickerMac.collectAsStateWithLifecycle()
    val spot by app.prefs.spotPeak.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf<String?>(null) }

    // Re-check permissions whenever we come back from a system settings page.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val notch = remember(tick) { NotchTapService.isEnabled(ctx) }
    val media = remember(tick) { granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) }
    val files = remember(tick) { Environment.isExternalStorageManager() }
    val sms = remember(tick) { granted(Manifest.permission.RECEIVE_SMS) }
    val battery = remember(tick) { DeviceSupport.batteryUnrestricted(ctx) }
    val keepAlive = DeviceSupport.keepAlive
    val hasNfc = remember { DeviceSupport.hasNfc(ctx) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val bars = WindowInsets.systemBars.asPaddingValues()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 8.dp,
            bottom = bars.calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        pageHeader("Settings", "${DeviceSupport.modelName} · ${DeviceSupport.androidVersion}", onClose)

        group("Your Mac", 1) {
            macs.forEachIndexed { i, m ->
                if (i > 0) GroupDivider()
                SettingRow(
                    icon = Icons.Rounded.LaptopMac, accent = Brand.gradient,
                    title = m.name,
                    subtitle = if (live && i == 0) "Connected · ready for taps" else "Paired · ${m.host}",
                ) {
                    TextButton(onClick = {
                        if (confirmForget == m.id) {
                            app.prefs.removeMac(m.id)
                            confirmForget = null
                        } else confirmForget = m.id
                    }) {
                        Text(if (confirmForget == m.id) "Confirm" else "Forget", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            GroupDivider()
            SettingRow(Icons.Rounded.Add, Accents.gray, "Pair another Mac", null, onClick = onPairAnother) { Chevron() }
        }

        group("Tap to send", 2) {
            SettingRow(
                Icons.Rounded.Sensors, Brand.gradient, "Tap your Mac to send",
                when {
                    !notch -> "Off · turn it on in Accessibility"
                    live -> "On · hold your phone over the notch"
                    else -> "On · connects while your screen is on"
                },
                onClick = onNotchSetup,
            ) { if (notch) StatusDot(true) else SmallButton("Turn on", onNotchSetup) }
            GroupDivider()
            SettingRow(
                Icons.Rounded.Explore, Accents.teal, "Tap spot on your Mac",
                if (spot > 0f) "Found (%.0f µT) · tap your phone there, in any light".format(spot)
                else "Not set · let your phone find the Mac's magnets (20 s)",
                onClick = onSpot,
            ) { if (spot > 0f) Chevron() else SmallButton("Find", onSpot) }
            GroupDivider()
            SettingRow(
                Icons.Rounded.AutoAwesome, Accents.violet, "What each app sends",
                if (modes.isEmpty()) "Smart in every app" else "${modes.size} customized · Smart for the rest",
                onClick = onRules,
            ) { Chevron() }
            GroupDivider()
            SettingRow(
                Icons.Rounded.TouchApp, Accents.blue, "Pick before sending",
                if (pickOn) "A second to tap or drag over part of the screen" else "Taps send right away",
                onClick = { app.prefs.setPickFirst(!pickOn) },
            ) { Switch(checked = pickOn, onCheckedChange = { app.prefs.setPickFirst(it) }) }
        }

        group("One-time codes", 3) {
            SettingRow(
                Icons.Rounded.Key, Accents.orange, "Send codes to your Mac",
                when {
                    !otpOn -> "Codes stay on your phone"
                    sms -> "From texts and notifications, onto the Mac's clipboard"
                    else -> "From notifications · allow texts below for SMS codes"
                },
                onClick = { app.prefs.setSmartOtp(!otpOn) },
            ) { Switch(checked = otpOn, onCheckedChange = { app.prefs.setSmartOtp(it) }) }
            GroupDivider()
            SettingRow(
                Icons.Rounded.ContentCopy, Accents.gray, "Copy codes on this phone too",
                "So you can paste them here as well",
                onClick = { app.prefs.setOtpCopyOnPhone(!copyOnPhone) },
            ) { Switch(checked = copyOnPhone, onCheckedChange = { app.prefs.setOtpCopyOnPhone(it) }) }
        }

        group("Permissions", 4) {
            PermissionRow(Icons.Rounded.Sensors, Brand.gradient, "Accessibility", "Needed for tap to send", notch, "Open") {
                DeviceSupport.openAccessibility(ctx)
            }
            GroupDivider()
            PermissionRow(Icons.Rounded.PhotoLibrary, Accents.pink, "Photos & videos", "So originals are sent, not screenshots", media, "Allow") {
                askMedia.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
            }
            GroupDivider()
            PermissionRow(Icons.Rounded.Folder, Accents.blue, "All files", "Optional · tap a document anywhere to send it", files, "Allow") {
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            GroupDivider()
            PermissionRow(Icons.Rounded.Sms, Accents.teal, "Text messages", "Optional · for codes sent by SMS", sms, "Allow") {
                askSms.launch(Manifest.permission.RECEIVE_SMS)
            }
            if (keepAlive != null) {
                GroupDivider()
                PermissionRow(
                    Icons.Rounded.BatteryChargingFull, Accents.green, "Run in the background",
                    "${keepAlive.brand} phones stop apps to save battery", battery, "Allow",
                ) { DeviceSupport.requestUnrestricted(ctx) }
            }
        }

        if (keepAlive != null) {
            item("keepAlive") {
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                ) {
                    Text("On ${keepAlive.brand} phones, also:", style = MaterialTheme.typography.titleSmall)
                    keepAlive.steps.forEach { step ->
                        Row(Modifier.padding(top = 6.dp)) {
                            Text("•  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { DeviceSupport.openAppInfo(ctx) }, contentPadding = PaddingValues(0.dp)) { Text("Open App info") }
                }
            }
        }

        if (hasNfc) {
            group("NFC sticker (optional)", 5) {
                SettingRow(
                    Icons.Rounded.Nfc, Accents.violet, "NFC sticker",
                    if (sticker != null) "Set up · tap to rewrite" else "Another way to tap, with a cheap NTAG213/215 sticker",
                    onClick = onSticker,
                ) { Chevron() }
                TapAction.entries.forEach { a ->
                    GroupDivider()
                    Row(
                        Modifier.fillMaxWidth().tappable(pressedScale = 0.99f) { app.prefs.setTapAction(a) }
                            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = tapAction == a, onClick = { app.prefs.setTapAction(a) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text("On tap: ${a.label}", style = MaterialTheme.typography.bodyLarge)
                            Text(a.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        group("Help", 6) {
            SettingRow(
                Icons.Rounded.Devices, Accents.teal, "Supported phones & Macs",
                "Android 13+ · macOS 15+ · what each needs",
                onClick = onCompat,
            ) { Chevron() }
            GroupDivider()
            SettingRow(
                Icons.Rounded.Lock, Accents.gray, "Privacy",
                "End-to-end encrypted. Stays on your Wi-Fi, only to Macs you paired.",
            ) {}
        }

        item("footer") {
            Text(
                "TapDrop ${appVersion(ctx)} · ${app.prefs.identity.deviceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// MARK: - compatibility

@Composable
private fun CompatibilityPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val bars = WindowInsets.systemBars.asPaddingValues()
    val wifi = remember { DeviceSupport.onWifi(ctx) }
    val motion = remember { DeviceSupport.hasMotionSensor(ctx) }
    val nfc = remember { DeviceSupport.hasNfc(ctx) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = bars.calculateTopPadding() + 8.dp,
            bottom = bars.calculateBottomPadding() + 28.dp,
        ),
    ) {
        pageHeader("Supported devices", "Which phones and Macs TapDrop works with, and what they need", onBack)

        group("This phone", 1) {
            SettingRow(
                Icons.Rounded.Devices, if (DeviceSupport.supported) Accents.green else Accents.gray,
                DeviceSupport.modelName, "${DeviceSupport.androidVersion} · ${if (DeviceSupport.supported) "Supported" else "Needs Android 13 or newer"}",
            ) { StatusDot(DeviceSupport.supported) }
            GroupDivider()
            CheckLine("Android 13 or newer", DeviceSupport.supported)
            CheckLine("Motion sensor (tells a real tap from a hand)", motion)
            CheckLine("On Wi-Fi right now", wifi)
            CheckLine(if (nfc) "NFC (optional sticker)" else "No NFC: the sticker option is hidden (everything else works)", nfc, optional = true)
        }

        group("Android phones · ${Compatibility.androidVersions}", 2) {
            ListBlock("Google Pixel", Compatibility.pixels.joinToString("\n"))
            Compatibility.brands.forEach { g ->
                GroupDivider(16.dp)
                ListBlock(g.name, g.models)
            }
            GroupDivider(16.dp)
            ListBlock("Not supported", Compatibility.notSupported.joinToString("\n"), muted = true)
        }

        group("Macs · macOS 15 Sequoia or newer", 3) {
            ListBlock("Requirement", Compatibility.macRequirement)
            Compatibility.macsWithTap.forEach { g ->
                GroupDivider(16.dp)
                ListBlock(g.name, g.models)
            }
            Compatibility.macsOther.forEach { g ->
                GroupDivider(16.dp)
                ListBlock(g.name, g.models, muted = g.name.startsWith("Intel"))
            }
        }

        group("What you need", 4) {
            Compatibility.needs.forEachIndexed { i, n ->
                if (i > 0) GroupDivider(52.dp)
                CheckLine(n, true, bullet = true)
            }
        }

        item("tested") {
            Text(
                Compatibility.tested,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

// MARK: - building blocks

private fun LazyListScope.pageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    item("bar") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
    }
    item("title") {
        Column(Modifier.padding(top = 14.dp, bottom = 4.dp).enter(0)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.group(title: String, index: Int, content: @Composable ColumnScope.() -> Unit) {
    item("g_$title") {
        Column(Modifier.enter(index)) {
            Text(title, style = sectionLabel, modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),
                content = content,
            )
        }
    }
}

@Composable
fun SettingRow(
    icon: ImageVector,
    accent: Brush,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.tappable(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.6f to Color.Transparent)))
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, accent: Brush, title: String, subtitle: String, ok: Boolean, action: String, onClick: () -> Unit) {
    SettingRow(icon, accent, title, subtitle, onClick = if (ok) null else onClick) {
        if (ok) StatusDot(true) else SmallButton(action, onClick)
    }
}

@Composable
fun GroupDivider(inset: androidx.compose.ui.unit.Dp = 66.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) = FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text) }

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (ok) Brand.successGradient else Accents.gray),
        contentAlignment = Alignment.Center,
    ) { Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
}

@Composable
private fun CheckLine(text: String, ok: Boolean, optional: Boolean = false, bullet: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(
                when {
                    bullet -> Brand.gradient
                    ok -> Brand.successGradient
                    optional -> Accents.gray
                    else -> Brush.linearGradient(listOf(Brand.Red, Brand.Red))
                }
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(if (ok || bullet) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun ListBlock(title: String, body: String, muted: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
