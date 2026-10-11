package app.tapdrop.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.tap.NotchTapService
import kotlin.math.sqrt

/**
 * "Find your Mac's tap spot": a live magnetometer. The Mac's magnets (speakers, the trackpad's haptic
 * motor, the MagSafe port) are strong right under its case; the user slides the phone around and the
 * strongest point becomes the spot. Tapping there sends, in any light.
 */
@Composable
fun TapSpotScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val saved by app.prefs.spotPeak.collectAsStateWithLifecycle()
    var now by remember { mutableFloatStateOf(0f) }
    var best by remember { mutableFloatStateOf(0f) }
    var hasSensor by remember { mutableStateOf(true) }
    var justSaved by remember { mutableStateOf(false) }
    BackHandler { onClose() }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        hasSensor = sensor != null
        var lastTick = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val b = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                now = b
                if (b > best) {
                    best = b
                    // a tick each time it finds noticeably more: like a metal detector
                    if (b >= NotchTapService.MIN_SPOT && b - lastTick > 60f) {
                        lastTick = b
                        Haptics.tick(ctx)
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(l) }
    }

    val strength = when {
        best >= 400f -> "Great spot"
        best >= NotchTapService.MIN_SPOT -> "Good spot"
        best > 90f -> "Getting warmer…"
        else -> "Keep looking"
    }
    val good = best >= NotchTapService.MIN_SPOT
    val bars = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlowBackdrop(Modifier.fillMaxWidth().height(320.dp), intensity = 0.7f)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = bars.calculateTopPadding() + 8.dp, bottom = bars.calculateBottomPadding() + 24.dp),
        ) {
            FilledTonalIconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Find your Mac's tap spot", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 14.dp))
            VSpace(6.dp)
            Text(
                "Your Mac has strong magnets inside: its speakers, the motor under the trackpad, the MagSafe port. " +
                    "Your phone can feel them, so it knows it's touching your Mac even in the dark.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(22.dp)

            if (!hasSensor) {
                Text("This phone has no compass sensor, so it can't feel the Mac's magnets.", style = MaterialTheme.typography.titleMedium)
                return@Column
            }

            Gauge(now = now, best = best, modifier = Modifier.align(Alignment.CenterHorizontally))
            VSpace(10.dp)
            Text(
                strength,
                style = MaterialTheme.typography.titleLarge,
                color = if (good) Brand.Green else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Text(
                "Strongest so far: %.0f µT · Earth's field is about 45".format(best),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            VSpace(22.dp)

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Slowly slide the back of your phone over your Mac:", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "the palm rests, left and right of the trackpad",
                    "the trackpad itself",
                    "the strip above the keyboard, near the hinge",
                    "the left side, around the MagSafe charging port",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "Remember where the meter peaked: that's where you'll tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VSpace(20.dp)

            Button(
                onClick = {
                    app.prefs.setSpotPeak(best)
                    justSaved = true
                    Haptics.success(ctx)
                },
                enabled = good,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text(if (good) "Use this spot (%.0f µT)".format(best) else "Find a stronger spot first") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { best = 0f }) { Text("Start over") }
                if (saved > 0f) TextButton(onClick = { app.prefs.setSpotPeak(0f) }) { Text("Forget saved spot") }
            }
            if (justSaved || saved > 0f) {
                VSpace(4.dp)
                Text(
                    if (justSaved) "Saved. Tap your phone on that spot to send what's on its screen."
                    else "Saved spot: %.0f µT. A tap fires from %.0f µT up.".format(saved, NotchTapService.spotThreshold(saved)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.Green,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Live field strength on a ring (0…800 µT), with a marker at the strongest point so far. */
@Composable
private fun Gauge(now: Float, best: Float, modifier: Modifier) {
    val fill by animateFloatAsState((now / 800f).coerceIn(0f, 1f), spring(stiffness = 300f), label = "gauge")
    val mark = (best / 800f).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 18.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(Brand.A, Brand.B, Brand.C, Brand.A)),
                135f, 270f * fill, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round),
            )
            // strongest so far
            val a = Math.toRadians((135.0 + 270.0 * mark))
            val r = (size.width - w) / 2
            val c = Offset(size.width / 2 + (r * kotlin.math.cos(a)).toFloat(), size.height / 2 + (r * kotlin.math.sin(a)).toFloat())
            drawCircle(Color.White, w * 0.42f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%.0f".format(now), fontSize = 54.sp, fontWeight = FontWeight.Bold)
            Text("µT", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(8.dp).clip(CircleShape))
    }
}
