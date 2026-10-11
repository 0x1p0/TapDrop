package app.tapdrop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.core.Endpoint
import app.tapdrop.core.TapDropClient
import app.tapdrop.data.SavedMac
import app.tapdrop.net.FoundMac
import app.tapdrop.net.Transfers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private sealed interface PairPhase {
    val macName: String
    data class Connecting(override val macName: String) : PairPhase
    data class Code(override val macName: String, val code: String) : PairPhase
    data class Success(override val macName: String) : PairPhase
    data class Error(override val macName: String, val message: String, val retry: () -> Unit) : PairPhase
}

/** First-run (and "pair another Mac") screen: live Bonjour radar → tap a Mac → compare codes. */
@Composable
fun PairScreen(onPaired: (SavedMac) -> Unit, onClose: (() -> Unit)? = null) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val found by app.finder.found.collectAsStateWithLifecycle()
    val pairedIds = app.prefs.macs.collectAsStateWithLifecycle().value.map { it.id }.toSet()
    val macs = found.values.filter { it.id != null }.distinctBy { it.id }.sortedBy { it.name }
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<PairPhase?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var manual by remember { mutableStateOf(false) }
    var waitedLong by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        app.finder.start()
        onDispose { app.finder.stop() }
    }
    LaunchedEffect(Unit) {
        delay(8000)
        waitedLong = true
    }

    fun start(ep: Endpoint, name: String) {
        job?.cancel()
        phase = PairPhase.Connecting(name)
        job = scope.launch {
            try {
                val saved = app.transfers.pair(ep) { code, macName ->
                    scope.launch { phase = PairPhase.Code(macName.ifBlank { name }, code) }
                }
                phase = PairPhase.Success(saved.name)
                Haptics.success(ctx)
                delay(1600)
                app.prefs.saveMac(saved)
                onPaired(saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Haptics.error(ctx)
                phase = PairPhase.Error(name, Transfers.friendly(e, name)) { start(ep, name) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlowBackdrop(Modifier.fillMaxWidth().height(520.dp), intensity = 1.1f)

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                if (onClose != null) {
                    FilledTonalIconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Rounded.Close, "Close")
                    }
                }
            }
            Text(
                "TAPDROP",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.enter(0),
            )
            VSpace(10.dp)
            Text(
                "Pair with your Mac",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.enter(1),
            )
            VSpace(10.dp)
            Text(
                "Open TapDrop on your Mac. It shows up here when you're both on the same Wi‑Fi.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp).enter(2),
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                DiscoveryRadar(macs, pairedIds) { m -> start(m.endpoint, m.name) }
            }

            AnimatedContent(
                targetState = when {
                    macs.isNotEmpty() -> 0
                    waitedLong -> 2
                    else -> 1
                },
                transitionSpec = {
                    (fadeIn(tween(300)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(150)) + slideOutVertically { -it / 3 })
                },
                label = "hint",
            ) { s ->
                Text(
                    when (s) {
                        0 -> if (macs.size == 1) "Tap your Mac to pair" else "Tap your Mac to pair · ${macs.size} found"
                        1 -> "Looking for Macs…"
                        else -> "Can't see it? Make sure the TapDrop icon is in your Mac's menu bar and both devices use the same Wi‑Fi."
                    },
                    style = if (s == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (s == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                )
            }
            TextButton(onClick = { manual = true }, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                Text("Enter address manually")
            }
        }

        phase?.let { p ->
            SheetHost(onClosed = {
                job?.cancel()
                phase = null
            }) { close ->
                PairSheetContent(p, close)
            }
        }
    }

    if (manual) {
        ManualAddressDialog(
            onDismiss = { manual = false },
            onConnect = { input ->
                manual = false
                val t = input.trim()
                // "192.168.1.5" or "192.168.1.5:47913"
                val port = t.substringAfterLast(':', "").toIntOrNull()?.takeIf { t.count { it == ':' } == 1 }
                val host = if (port != null) t.substringBeforeLast(':') else t
                start(Endpoint(host, port ?: TapDropClient.DEFAULT_PORT), host)
            },
        )
    }
}

@Composable
private fun DiscoveryRadar(macs: List<FoundMac>, pairedIds: Set<String>, onPick: (FoundMac) -> Unit) {
    BoxWithConstraints(Modifier.widthIn(max = 380.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        val side = min(maxWidth, maxHeight)
        val guide = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        Canvas(Modifier.size(side)) {
            listOf(0.34f, 0.62f, 0.9f).forEach { f ->
                drawCircle(guide, radius = size.minDimension / 2 * f, style = Stroke(1.dp.toPx()))
            }
        }
        Ripples(Modifier.size(side), color = MaterialTheme.colorScheme.primary, rings = 3, periodMs = 3400, start = 0.2f)

        // this phone, in the middle
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(84.dp).blur(24.dp, BlurredEdgeTreatment.Unbounded)
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Brand.A.copy(alpha = 0.7f), Brand.B.copy(alpha = 0.7f))), CircleShape)
            )
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(Brand.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.White.copy(alpha = 0.3f), 0.55f to Color.Transparent)))
                Icon(Icons.Rounded.Smartphone, "This phone", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }

        val radius = side.value / 2 * 0.62f
        macs.forEachIndexed { i, m ->
            key(m.id) {
                val angle = Math.toRadians(-120.0 + i * 137.5)
                MacBubble(
                    mac = m,
                    paired = m.id in pairedIds,
                    index = i,
                    modifier = Modifier.offset((radius * cos(angle)).dp, (radius * sin(angle)).dp),
                    onClick = { onPick(m) },
                )
            }
        }
    }
}

@Composable
private fun MacBubble(mac: FoundMac, paired: Boolean, index: Int, modifier: Modifier, onClick: () -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
    val bob = rememberInfiniteTransition(label = "bob")
    val y by bob.animateFloat(-3f, 3f, infiniteRepeatable(tween(1800 + index * 300), RepeatMode.Reverse), label = "y")

    Column(
        modifier.graphicsLayer {
            scaleX = pop.value; scaleY = pop.value
            alpha = pop.value.coerceIn(0f, 1f)
            translationY = y.dp.toPx()
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .tappable(CircleShape, onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.5.dp, Brand.gradient, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LaptopMac, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
        VSpace(6.dp)
        Text(
            mac.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(140.dp),
        )
        if (paired) {
            Text("Paired", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PairSheetContent(phase: PairPhase, close: () -> Unit) {
    AnimatedContent(
        targetState = phase,
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(tween(260, 80)) + slideInVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) { it / 6 }) togetherWith
                fadeOut(tween(120))
        },
        label = "pairPhase",
    ) { p ->
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (p) {
                is PairPhase.Connecting -> {
                    DeviceOrb(Icons.Rounded.LaptopMac, OrbState.Working, null, size = 76.dp)
                    Text("Connecting to ${p.macName}…", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    VSpace(28.dp)
                }
                is PairPhase.Code -> {
                    VSpace(10.dp)
                    Text("Confirm on your Mac", style = MaterialTheme.typography.headlineSmall)
                    VSpace(8.dp)
                    Text(
                        "Check that ${p.macName} shows this code, then click Pair on the Mac.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                    VSpace(24.dp)
                    CodeDigits(p.code)
                    VSpace(22.dp)
                    WaitingDots("Waiting for your Mac")
                    VSpace(18.dp)
                    TextButton(onClick = close) { Text("Cancel") }
                }
                is PairPhase.Success -> {
                    DeviceOrb(Icons.Rounded.LaptopMac, OrbState.Done, 1f, size = 84.dp)
                    Text("You're paired", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    VSpace(6.dp)
                    Text(p.macName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    VSpace(28.dp)
                }
                is PairPhase.Error -> {
                    VSpace(18.dp)
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                    }
                    VSpace(16.dp)
                    Text("Couldn't pair", style = MaterialTheme.typography.headlineSmall)
                    VSpace(6.dp)
                    Text(
                        p.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                    VSpace(22.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = close) { Text("Close") }
                        Button(onClick = p.retry) { Text("Try again") }
                    }
                    VSpace(8.dp)
                }
            }
        }
    }
}

@Composable
fun CodeDigits(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, ch ->
            if (i == 3) Box(Modifier.width(8.dp))
            Box(
                Modifier
                    .enter(i, key = code)
                    .size(width = 44.dp, height = 58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(ch.toString(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

@Composable
fun WaitingDots(label: String) {
    val t = rememberInfiniteTransition(label = "dots")
    val p by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "p")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val lit = (1f - kotlin.math.abs(p - i - 0.5f)).coerceIn(0.25f, 1f)
                Box(Modifier.size(5.dp).graphicsLayer { alpha = lit; scaleX = 0.7f + lit * 0.4f; scaleY = 0.7f + lit * 0.4f }.clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
private fun ManualAddressDialog(onDismiss: () -> Unit, onConnect: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by address") },
        text = {
            Column {
                Text(
                    "Find your Mac's address in TapDrop → Set Up a Phone (e.g. 192.168.1.5).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(14.dp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("192.168.1.5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(text) }, enabled = text.isNotBlank(), colors = ButtonDefaults.buttonColors()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(28.dp),
    )
}
