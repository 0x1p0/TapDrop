package app.tapdrop.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.data.AppRules
import app.tapdrop.data.SendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

private val iconCache = HashMap<String, ImageBitmap>()

fun SendMode.icon(): ImageVector = when (this) {
    SendMode.SMART -> Icons.Rounded.AutoAwesome
    SendMode.LINK -> Icons.Rounded.Link
    SendMode.MEDIA -> Icons.Rounded.PhotoLibrary
    SendMode.LATEST_MEDIA -> Icons.Rounded.PhotoCamera
    SendMode.SCREENSHOT -> Icons.Rounded.Screenshot
    SendMode.TEXT -> Icons.Rounded.TextFields
    SendMode.OFF -> Icons.Rounded.Block
}

fun SendMode.accent(): Brush = when (this) {
    SendMode.SMART -> Brand.gradient
    SendMode.LINK -> Brush.linearGradient(listOf(Color(0xFF4FA8FF), Color(0xFF2F64F5)))
    SendMode.MEDIA -> Brush.linearGradient(listOf(Color(0xFFFF9A5A), Color(0xFFFF4F8B)))
    SendMode.LATEST_MEDIA -> Brush.linearGradient(listOf(Color(0xFFFFC24A), Color(0xFFFF8A3D)))
    SendMode.SCREENSHOT -> Brush.linearGradient(listOf(Color(0xFF2ED3B0), Color(0xFF14A08F)))
    SendMode.TEXT -> Brush.linearGradient(listOf(Color(0xFF9B7BFF), Color(0xFF6A3FF0)))
    SendMode.OFF -> Brush.linearGradient(listOf(Color(0xFF9097A6), Color(0xFF6B7180)))
}

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun AppIcon(pkg: String, size: Int = 44) {
    val ctx = LocalContext.current
    val img by produceState(iconCache[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }.getOrNull()
                    ?.also { iconCache[pkg] = it }
            }
        }
    }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        img?.let { Image(it, null, Modifier.fillMaxSize()) }
    }
}

/** "What each app sends" — per-app mode for holding the phone to the Mac's notch. */
@Composable
fun AppRulesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val modes by app.prefs.appModes.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<AppEntry?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    BackHandler(enabled = picking == null) { onClose() }

    val all = apps.orEmpty()
    val filtered = if (query.isBlank()) all else all.filter { it.label.contains(query, ignoreCase = true) }
    val customized = if (query.isBlank()) all.filter { it.pkg in modes } else emptyList()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GlowBackdrop(Modifier.fillMaxWidth().height(300.dp), intensity = 0.7f)
        val bars = WindowInsets.systemBars.asPaddingValues()
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = bars.calculateTopPadding() + 8.dp,
                bottom = bars.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item("bar") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                }
            }
            item("title") {
                Column(Modifier.padding(top = 14.dp, bottom = 18.dp).enter(0)) {
                    Text("What each app sends", style = MaterialTheme.typography.headlineMedium)
                    VSpace(6.dp)
                    Text(
                        "When you hold your phone to your Mac's notch. Smart picks for you — change any app here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("search") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .enter(1)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.padding(start = 12.dp).weight(1f)) {
                        if (query.isEmpty()) Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                VSpace(20.dp)
            }
            if (customized.isNotEmpty()) {
                item("customLabel") { Text("Customized", style = sectionLabel, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
                itemsIndexed(customized, key = { _, a -> "c_" + a.pkg }) { i, a ->
                    AppRow(a, modes[a.pkg] ?: SendMode.SMART, i, customized.size, Modifier.animateItem()) { picking = a }
                }
                item("gap") { VSpace(20.dp) }
            }
            item("allLabel") {
                Text(
                    when {
                        apps == null -> "Loading apps…"
                        query.isBlank() -> "All apps"
                        filtered.isEmpty() -> "No apps match “$query”"
                        filtered.size == 1 -> "1 app"
                        else -> "${filtered.size} apps"
                    },
                    style = sectionLabel, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(filtered, key = { _, a -> a.pkg }) { i, a ->
                AppRow(a, modes[a.pkg] ?: SendMode.SMART, i, filtered.size, Modifier.animateItem()) { picking = a }
            }
        }

        StatusBarScrim()
        picking?.let { a ->
            SheetHost(onClosed = { picking = null }) { close -> ModePicker(a, modes[a.pkg] ?: SendMode.SMART, close) }
        }
    }
}

@Composable
private fun AppRow(a: AppEntry, mode: SendMode, index: Int, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val top = if (index == 0) 22.dp else 4.dp
    val bottom = if (index == count - 1) 22.dp else 4.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val resolved = if (mode == SendMode.SMART) AppRules.smart(a.pkg) else mode
    Row(
        modifier
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .tappable(shape, pressedScale = 0.985f, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    mode != SendMode.SMART -> mode.title
                    resolved == SendMode.MEDIA -> "Smart · photo, video or screenshot"
                    else -> "Smart · ${resolved.title.lowercase()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (mode == SendMode.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        val shown = if (mode == SendMode.SMART && resolved == SendMode.MEDIA) SendMode.SMART else resolved
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(shown.accent()), contentAlignment = Alignment.Center) {
            Icon(shown.icon(), null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ModePicker(a: AppEntry, current: SendMode, close: () -> Unit) {
    val app = TapDropApp.instance
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var selected by remember { mutableStateOf(current) }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg, size = 48)
        Column(Modifier.padding(start = 14.dp)) {
            Text(a.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("When you hold your phone to the notch", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppRules.options(a.pkg).forEachIndexed { i, m ->
            val on = m == selected
            val border by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, tween(180), label = "b")
            Row(
                Modifier
                    .fillMaxWidth()
                    .enter(i)
                    .tappable(RoundedCornerShape(20.dp), pressedScale = 0.98f) {
                        selected = m
                        app.prefs.setAppMode(a.pkg, m)
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        scope.launch {
                            delay(280)
                            close()
                        }
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.5.dp, border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(m.accent()), contentAlignment = Alignment.Center) {
                    Icon(m.icon(), null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (m == SendMode.SMART) m.detail else m.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(on, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
    VSpace(8.dp)
}
