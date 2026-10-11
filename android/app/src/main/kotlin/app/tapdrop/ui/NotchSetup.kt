package app.tapdrop.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.tap.NotchTapService

/** Phone rising to a MacBook's notch, light waves when it arrives. */
@Composable
fun NotchIllustration(modifier: Modifier = Modifier, width: Float = 150f) {
    val p = bump(loopPhase(2600), rise = 900 / 2600f, hold = 1600 / 2600f, fall = 2300 / 2600f)
    val screen = MaterialTheme.colorScheme.onSurfaceVariant
    val accentA = Brand.A
    val accentB = Brand.B
    Canvas(modifier.size(width.dp, (width * 0.72f).dp)) {
        val w = size.width
        val h = size.height
        // MacBook lid with notch
        val lidW = w * 0.86f
        val lidH = h * 0.62f
        val lidX = (w - lidW) / 2
        val lidY = h * 0.06f
        drawRoundRect(screen.copy(alpha = 0.14f), Offset(lidX, lidY), Size(lidW, lidH), CornerRadius(14f, 14f))
        drawRoundRect(screen.copy(alpha = 0.45f), Offset(lidX, lidY), Size(lidW, lidH), CornerRadius(14f, 14f), style = Stroke(3f))
        val notchW = lidW * 0.2f
        drawRoundRect(Color.Black, Offset(w / 2 - notchW / 2, lidY), Size(notchW, h * 0.07f), CornerRadius(10f, 10f))
        // base
        drawRoundRect(screen.copy(alpha = 0.45f), Offset(w * 0.02f, lidY + lidH + 4f), Size(w * 0.96f, h * 0.05f), CornerRadius(8f, 8f))
        // light waves when the phone arrives
        if (p > 0.92f) {
            val k = (p - 0.92f) / 0.08f
            for (i in 0..1) {
                drawCircle(
                    accentB.copy(alpha = (1f - k) * 0.7f / (i + 1)),
                    radius = notchW * (0.4f + 0.5f * k + i * 0.35f),
                    center = Offset(w / 2, lidY + h * 0.035f),
                    style = Stroke(3f),
                )
            }
        }
        // phone rising up to the notch
        val phW = w * 0.2f
        val phH = phW * 1.9f
        val startY = h - phH * 0.35f
        val endY = lidY - phH * 0.18f
        val y = startY + (endY - startY) * p
        drawRoundRect(
            Brush.linearGradient(listOf(accentA, accentB), Offset(w / 2 - phW / 2, y), Offset(w / 2 + phW / 2, y + phH)),
            Offset(w / 2 - phW / 2, y), Size(phW, phH), CornerRadius(12f, 12f),
        )
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(w / 2 - phW / 2 + 5f, y + 5f), Size(phW - 10f, phH * 0.4f), CornerRadius(8f, 8f))
    }
}

/** Home-screen card for the NFC-less "hold your phone to the notch" feature. */
@Composable
fun NotchCard(modifier: Modifier, onFindSpot: () -> Unit, onSetup: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(NotchTapService.isEnabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        enabled = NotchTapService.isEnabled(ctx)
        onPauseOrDispose { }
    }
    val live by NotchTapService.live.collectAsStateWithLifecycle()
    val spot by app.tapdrop.TapDropApp.instance.prefs.spotPeak.collectAsStateWithLifecycle()

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.surfaceContainerLow))
                else Brush.linearGradient(listOf(Brand.A.copy(alpha = 0.16f), Brand.B.copy(alpha = 0.18f)))
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
            .tappable(RoundedCornerShape(26.dp), pressedScale = 0.985f, onClick = onSetup)
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotchIllustration(width = 92f)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            AnimatedContent(
                targetState = when {
                    !enabled -> 0
                    spot <= 0f -> 3
                    live -> 2
                    else -> 1
                },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                label = "notchState",
            ) { s ->
                Column {
                    Text(
                        when (s) {
                            0 -> "Tap your Mac to send"
                            1 -> "Tap to send is on"
                            2 -> "Ready — tap your spot"
                            else -> "Find your Mac's tap spot"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s == 2) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Brand.Green))
                            Box(Modifier.size(6.dp))
                        }
                        Text(
                            when (s) {
                                0 -> "Tap your phone on your Mac and whatever's on screen lands on the Mac."
                                1 -> "Connecting to your Mac…"
                                2 -> "Touch your phone to the spot you found on the Mac. Works in the dark."
                                else -> "20 seconds: your phone feels the magnets inside your Mac."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (!enabled) Button(onClick = onSetup) { Text("Turn on") }
        else if (spot <= 0f) Button(onClick = onFindSpot) { Text("Find") }
    }
}

/** Two-step setup: (1) allow restricted settings for sideloaded apps, (2) enable the service. */
@Composable
fun NotchSetupSheet(close: () -> Unit, onFindSpot: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(NotchTapService.isEnabled(ctx)) }
    var battery by remember { mutableStateOf(DeviceSupport.batteryUnrestricted(ctx)) }
    LifecycleResumeEffect(Unit) {
        enabled = NotchTapService.isEnabled(ctx)
        battery = DeviceSupport.batteryUnrestricted(ctx)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        NotchIllustration(width = 210f, modifier = Modifier.padding(top = 4.dp))
        VSpace(10.dp)
        Text(if (enabled) "You're all set" else "Tap your Mac to send", style = MaterialTheme.typography.headlineSmall)
        VSpace(6.dp)
        Text(
            if (enabled) "Last step: let your phone find its tap spot on your Mac (it feels the magnets inside). Then open anything and touch the phone there: it shows up on the Mac in a second, in any light."
            else "Tap your phone on your Mac and whatever is on your screen appears there: the page, the photo, the file, or a screenshot.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        VSpace(18.dp)

        if (enabled) {
            val keepAlive = DeviceSupport.keepAlive
            if (keepAlive != null && !battery) {
                SetupStep(
                    n = 3,
                    title = "Keep it running",
                    body = "${keepAlive.brand} phones stop background apps to save battery. Allow TapDrop to run so taps always work.",
                    action = "Allow",
                    primary = true,
                ) { DeviceSupport.requestUnrestricted(ctx) }
                VSpace(12.dp)
            }
            Button(onClick = onFindSpot, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) { Text("Find your Mac's tap spot") }
            TextButton(onClick = close) { Text("Done") }
        } else {
            SetupStep(
                n = 1,
                title = "Allow restricted settings",
                body = "Android blocks this for apps installed outside the Play Store. In App info, tap ⋮ (top right) → Allow restricted settings. Skip if you don't see it.",
                action = "App info",
            ) { DeviceSupport.openAppInfo(ctx) }
            VSpace(10.dp)
            SetupStep(
                n = 2,
                title = "Turn on TapDrop",
                body = "${DeviceSupport.accessibilityPath}. It only looks at your screen at the moment you tap.",
                action = "Open",
                primary = true,
            ) { DeviceSupport.openAccessibility(ctx) }
            VSpace(14.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    "  Stays on your Wi‑Fi, end‑to‑end encrypted, only to your Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = close) { Text("Not now") }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, body: String, action: String, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Brand.gradient),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelLarge, color = Color.White) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (primary) Button(onClick = onClick) { Text(action) } else FilledTonalButton(onClick = onClick) { Text(action) }
    }
}

/** One place for everything TapDrop needs, with what's missing and a one-tap fix. Collapses when done. */
@Composable
fun SetupChecklist(modifier: Modifier, onNotchSetup: () -> Unit) {
    val ctx = LocalContext.current
    fun granted(p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun mediaAll() = granted(android.Manifest.permission.READ_MEDIA_IMAGES) && granted(android.Manifest.permission.READ_MEDIA_VIDEO)
    fun mediaSome() = granted(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    var tick by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val notch = remember(tick) { NotchTapService.isEnabled(ctx) }
    val media = remember(tick) { mediaAll() }
    val partial = remember(tick) { !mediaAll() && mediaSome() }
    val filesOk = remember(tick) { android.os.Environment.isExternalStorageManager() }
    val sms = remember(tick) { granted(android.Manifest.permission.RECEIVE_SMS) }
    val keepAlive = DeviceSupport.keepAlive
    val battery = remember(tick) { keepAlive == null || DeviceSupport.batteryUnrestricted(ctx) }
    val askMedia = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    val askSms = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { tick++ }

    val checks = listOf(notch, media, filesOk, sms, battery)
    val total = if (keepAlive != null) 5 else 4
    val done = checks.count { it } - (if (keepAlive == null) 1 else 0)
    var expanded by remember { mutableStateOf<Boolean?>(null) }
    val open = expanded ?: true
    // All done: the checklist steps aside (everything stays reachable in Settings → Permissions).
    if (done >= total) return

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().tappable(pressedScale = 0.99f) { expanded = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { done / total.toFloat() },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Text("$done/$total", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${total - done} thing${if (total - done == 1) "" else "s"} left for the full experience",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column {
                CheckRow(
                    ok = notch, title = "Tap your Mac to send",
                    detail = if (notch) "Hold your phone to the notch" else "Turns on holding your phone to the notch",
                    action = "Turn on", onClick = onNotchSetup,
                )
                CheckRow(
                    ok = media, title = "Photos & videos",
                    detail = when {
                        media -> "Originals are sent, not screenshots"
                        partial -> "Only some photos allowed — allow all so originals can be found"
                        else -> "So the photo or video you're viewing is sent as the original"
                    },
                    action = if (partial) "Allow all" else "Allow",
                ) {
                    askMedia.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO))
                }
                CheckRow(
                    ok = filesOk, title = "Files",
                    detail = if (filesOk) "Tap a document anywhere to send it" else "Optional · tap a PDF or ZIP in Files or a chat to send it",
                    action = "Allow",
                ) {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                CheckRow(
                    ok = sms, title = "Codes from texts",
                    detail = if (sms) "One-time codes land on your Mac's clipboard" else "So codes in text messages go to your Mac",
                    action = "Allow", last = keepAlive == null,
                ) { askSms.launch(android.Manifest.permission.RECEIVE_SMS) }
                if (keepAlive != null) {
                    CheckRow(
                        ok = battery, title = "Run in the background",
                        detail = if (battery) "TapDrop won't be stopped to save battery" else "${keepAlive.brand} phones stop apps in the background — allow TapDrop",
                        action = "Allow", last = true,
                    ) { DeviceSupport.requestUnrestricted(ctx) }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, detail: String, action: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = if (last) 16.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (ok) Brand.successGradient else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest))),
            contentAlignment = Alignment.Center,
        ) {
            if (ok) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) androidx.compose.material3.FilledTonalButton(onClick = onClick) { Text(action) }
    }
}
