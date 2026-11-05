package app.tapdrop.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.data.SavedMac
import app.tapdrop.net.Received
import app.tapdrop.nfc.TapTag
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.GlassLayers
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.MediaThumb
import app.tapdrop.ui.design.PlayBadge
import app.tapdrop.ui.design.NavHost
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.StatusPill
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.TdLoadingIndicator
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.entrance
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.rememberNavStack
import app.tapdrop.ui.home.HomeScreen
import app.tapdrop.ui.home.WelcomeScreen
import app.tapdrop.ui.sheet.fileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { TapDropTheme { AppRoot() } }
    }
}

private enum class Root { Welcome, Pair, Main }

/** Destinations inside the app once a Mac is paired. */
enum class Dest { Home, TapSetup, Settings, Rules, TapTest, Compatibility, PairAnother }

@Composable
private fun AppRoot() {
    val app = TapDropApp.instance
    val macs by app.prefs.macs.collectAsStateWithLifecycle()
    var pairing by rememberSaveable { mutableStateOf(false) }
    val root = when {
        macs.isNotEmpty() -> Root.Main
        pairing -> Root.Pair
        else -> Root.Welcome
    }
    BackHandler(enabled = root == Root.Pair) { pairing = false }
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = root,
            transitionSpec = { (fadeIn(tween(420)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(200)) },
            label = "root",
        ) { r ->
            when (r) {
                Root.Welcome -> WelcomeScreen(onPair = { pairing = true })
                Root.Pair -> PairScreen(onPaired = { pairing = false }, onClose = { pairing = false })
                Root.Main -> MainNav(macs)
            }
        }
    }
}

@Composable
private fun MainNav(macs: List<SavedMac>) {
    val stack = rememberNavStack(Dest.Home)
    var sticker by remember { mutableStateOf(false) }
    // Home's entrance plays once; coming back to it, it's simply there.
    var homeShown by rememberSaveable { mutableStateOf(false) }
    // Keep the last Mac while the screen animates away after the final one is forgotten.
    var lastMac by remember { mutableStateOf(macs.firstOrNull()) }
    macs.firstOrNull()?.let { lastMac = it }

    GlassLayers(
        Modifier.fillMaxSize(),
        chrome = {
            val mac = lastMac
            if (sticker && mac != null) {
                GlassSheet(onDismissed = { sticker = false }) { close -> StickerSheet(mac, close) }
            }
        },
    ) {
        NavHost(stack) { dest ->
            when (dest) {
                Dest.Home -> if (macs.isNotEmpty()) {
                    HomeScreen(
                        macs = macs,
                        onOpenSettings = { stack.push(Dest.Settings) },
                        onOpenTapSetup = { stack.push(Dest.TapSetup) },
                        intro = !homeShown,
                    )
                    LaunchedEffect(Unit) { homeShown = true }
                } else Box(Modifier.fillMaxSize())
                Dest.TapSetup -> TapSetupScreen(onClose = { stack.pop() }, onTest = { stack.push(Dest.TapTest) })
                Dest.Settings -> SettingsScreen(
                    onClose = { stack.pop() },
                    onPairAnother = { stack.push(Dest.PairAnother) },
                    onNotchSetup = { stack.push(Dest.TapSetup) },
                    onRules = { stack.push(Dest.Rules) },
                    onSticker = { sticker = true },
                    onTapTest = { stack.push(Dest.TapTest) },
                    onCompatibility = { stack.push(Dest.Compatibility) },
                )
                Dest.Rules -> AppRulesScreen(onClose = { stack.pop() })
                Dest.TapTest -> TapTestScreen(
                    onClose = { stack.pop() },
                    // From the setup page, go back to it rather than opening a second one.
                    onSetup = { if (stack.previous == Dest.TapSetup) stack.pop() else stack.push(Dest.TapSetup) },
                )
                Dest.Compatibility -> CompatibilityScreen(onClose = { stack.pop() })
                Dest.PairAnother -> PairScreen(onPaired = { stack.pop() }, onClose = { stack.pop() })
            }
        }
    }
}

/**
 * A row of tiles for what just arrived; tap one to open it. A photo shows as itself, a video as a frame with a
 * play badge, any other file by its kind. Everything but a photo carries its name and size.
 */
@Composable
fun ReceivedStrip(items: List<Received>) {
    val ctx = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        itemsIndexed(items.filter { it.uri != null }.take(10)) { i, r ->
            ReceivedTile(r, Modifier.entrance(i)) { openUri(ctx, r.uri!!, r.mime) }
        }
    }
}

@Composable
private fun ReceivedTile(r: Received, modifier: Modifier, onOpen: () -> Unit) {
    val c = Td.colors
    val kind = fileKind(r.name, r.mime)
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MediaThumb(
            r.uri,
            Modifier.size(76.dp).pressable(shape, onClickLabel = "Open", onClick = onOpen),
            fallback = kind.icon,
            contentDescription = r.name,
            mime = r.mime,
            // A document has no picture: its kind's colour says what it is. Photos and videos wait on a quiet well.
            accent = kind.accent.takeIf { !r.isImage && !r.isVideo },
            iconSize = 28.dp,
        ) {
            if (r.isVideo) PlayBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        if (!r.isImage) {
            Text(
                r.name, style = TdType.caption, color = c.textSecondary,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(top = 6.dp),
            )
            if (r.size >= 0) {
                Text(fmtBytes(r.size), style = TdType.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary, maxLines = 1)
            }
        }
    }
}

// MARK: - NFC sticker

private sealed interface StickerPhase {
    data object Waiting : StickerPhase
    data object Writing : StickerPhase
    data object Done : StickerPhase
    data class Error(val message: String) : StickerPhase
}

@Composable
private fun StickerSheet(mac: SavedMac, close: () -> Unit) {
    val app = TapDropApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<StickerPhase>(StickerPhase.Waiting) }

    NfcReader(enabled = phase !is StickerPhase.Done && phase !is StickerPhase.Writing) { tag ->
        phase = StickerPhase.Writing
        Haptics.tap(ctx)
        scope.launch {
            val err = withContext(Dispatchers.IO) { TapTag.write(tag, mac.id) }
            if (err == null) {
                app.prefs.setSticker(mac.id)
                phase = StickerPhase.Done
                Haptics.success(ctx)
            } else {
                phase = StickerPhase.Error(err)
                Haptics.error(ctx)
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            when (phase) {
                is StickerPhase.Done -> CheckBurst(visible = true, size = 72.dp)
                is StickerPhase.Writing -> TdLoadingIndicator(Modifier.size(72.dp))
                else -> IconChip(Icons.Rounded.Nfc, Brand.Violet, size = 84.dp, shape = TdShapes.Cookie12, iconSize = 38.dp)
            }
        }
        Text(
            if (phase is StickerPhase.Done) "Sticker ready" else "Set up an NFC sticker",
            style = TdType.title, color = Td.colors.text,
        )
        Text(
            if (phase is StickerPhase.Done) "Stick it on your Mac, next to the trackpad. Tap your phone on it any time to share."
            else "Hold an NFC sticker against the back of your phone, just below the camera bar.",
            style = TdType.bodySmall,
            color = Td.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp).padding(top = 6.dp),
        )
        AnimatedContent(phase, contentKey = { it::class }, label = "stickerStatus", modifier = Modifier.padding(top = 18.dp)) { p ->
            when (p) {
                is StickerPhase.Waiting -> StatusPill("Ready to write", StatusTone.Searching)
                is StickerPhase.Writing -> StatusPill("Writing…", StatusTone.Searching)
                is StickerPhase.Done -> StatusPill("Linked to ${mac.name}", StatusTone.Good)
                is StickerPhase.Error -> StatusPill(p.message, StatusTone.Error)
            }
        }
        if (phase is StickerPhase.Done) {
            PrimaryButton("Done", close, Modifier.fillMaxWidth().padding(top = 24.dp))
        } else {
            Text(
                "Any NTAG213 or NTAG215 sticker works.",
                style = TdType.caption, color = Td.colors.textTertiary,
                modifier = Modifier.padding(top = 18.dp),
            )
            TdTextButton("Not now", close)
        }
    }
}

// MARK: - helpers

fun openUri(ctx: android.content.Context, uri: Uri, mime: String?) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: ctx.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show() }
}

fun openLink(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun fmtBytes(b: Long): String = when {
    b < 0 -> "?"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
    else -> "%.2f GB".format(b / 1024.0 / 1024.0 / 1024.0)
}
