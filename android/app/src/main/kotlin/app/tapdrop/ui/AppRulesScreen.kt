package app.tapdrop.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.ui.design.SwitchRow
import androidx.compose.material.icons.rounded.AccountBalance
import app.tapdrop.data.AppRules
import app.tapdrop.data.SendMode
import app.tapdrop.ui.design.ChoiceRow
import app.tapdrop.ui.design.EmptyState
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.RowChevron
import app.tapdrop.ui.design.RowValue
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SkeletonRow
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.TdBadge
import app.tapdrop.ui.design.TdSearchField
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.segmentShape
import app.tapdrop.ui.design.sheetSegment
import app.tapdrop.ui.design.tdSharedBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String)

/** A launcher icon drawn once, with the system's icon mask (scaled to [ICON_PX]) to outline it by. */
private class IconArt(val bitmap: ImageBitmap, val mask: android.graphics.Path?)

private const val ICON_PX = 144
private val iconCache = HashMap<String, IconArt>()

private fun loadIcon(ctx: Context, pkg: String): IconArt? = runCatching {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val bitmap = d.toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    val mask = (d as? AdaptiveIconDrawable)?.let {
        it.setBounds(0, 0, ICON_PX, ICON_PX)
        android.graphics.Path(it.iconMask)
    }
    IconArt(bitmap, mask)
}.getOrNull()

fun SendMode.icon(): ImageVector = when (this) {
    SendMode.SMART -> Icons.Rounded.AutoAwesome
    SendMode.LINK -> Icons.Rounded.Link
    SendMode.MEDIA -> Icons.Rounded.PhotoLibrary
    SendMode.LATEST_MEDIA -> Icons.Rounded.PhotoCamera
    SendMode.SCREENSHOT -> Icons.Rounded.Screenshot
    SendMode.TEXT -> Icons.Rounded.TextFields
    SendMode.OFF -> Icons.Rounded.Block
}

/** Each mode's chip colour: Lumen has no hues, so every mode is the text colour; Off is quieter. */
@Composable
fun SendMode.hue(): Color = if (this == SendMode.OFF) Td.colors.textTertiary else Td.colors.text

internal fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

/**
 * "What each app sends": every app with the mode a tap uses there (Smart unless changed), searchable. Tap an
 * app to choose its mode from a segmented list in a glass sheet. Its header chip arrives from Settings.
 */
@Composable
fun AppRulesScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val c = Td.colors
    val modes by app.prefs.appModes.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf<AppEntry?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // The mode sheet needs the whole screen: put the keyboard away (and leave the search box) as it opens.
    fun pick(a: AppEntry) {
        keyboard?.hide()
        focus.clearFocus(force = true)
        picking = a
    }
    LaunchedEffect(attempt) {
        failed = false
        withContext(Dispatchers.IO) { runCatching { loadApps(ctx) } }
            .onSuccess { apps = it }
            .onFailure { failed = true }
    }

    val all = apps.orEmpty()
    val q = query.trim()
    val filtered = if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
    val changed = if (q.isEmpty()) all.filter { it.pkg in modes } else emptyList()

    LargeTitleScaffold(
        title = "What each app sends",
        subtitle = "When you touch your phone to the notch",
        onBack = onClose,
        state = list,
        chrome = {
            picking?.let { a ->
                GlassSheet(onDismissed = { picking = null }) { close -> ModeSheet(a, modes[a.pkg] ?: SendMode.SMART, close) }
            }
        },
    ) {
        item("smart") { SmartCard(changed = modes.size) }
        item("search") {
            // Searching brings the field up under the bar, so the results get the room above the keyboard.
            TdSearchField(
                query, { query = it }, "Search apps",
                Modifier
                    .padding(top = 16.dp)
                    .onFocusChanged { if (it.isFocused) scope.launch { list.animateScrollToItem(SEARCH_INDEX) } },
            )
        }
        when {
            failed -> item("failed") {
                EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = "Couldn't list your apps",
                    body = "Android didn't answer in time.",
                    accent = c.danger,
                    action = { SmallButton("Try again", { attempt++ }, prominent = true) },
                )
            }
            apps == null -> {
                item("loadingHeader") { SectionHeader("All apps") }
                items(7) { i -> SkeletonRow(segmentShape(i, 7), Modifier.padding(bottom = 2.dp), titleWidth = 0.38f + (i % 3) * 0.12f) }
            }
            else -> {
                if (changed.isNotEmpty()) {
                    item("changedHeader") { SectionHeader("Changed") }
                    itemsIndexed(changed, key = { _, a -> "changed:" + a.pkg }) { i, a ->
                        AppRow(
                            a, modes[a.pkg] ?: SendMode.SMART, segmentShape(i, changed.size),
                            Modifier.animateItem().padding(bottom = 2.dp),
                        ) { pick(a) }
                    }
                }
                if (filtered.isNotEmpty()) item("allHeader") {
                    SectionHeader(
                        when {
                            q.isEmpty() -> "All apps"
                            filtered.size == 1 -> "1 app"
                            else -> "${filtered.size} apps"
                        }
                    )
                }
                if (filtered.isEmpty()) {
                    item("none") {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = "No apps match “$q”",
                            body = "Check the spelling, or search for part of the name.",
                            accent = Td.colors.accent,
                            shape = TdShapes.Cookie9,
                        )
                    }
                }
                itemsIndexed(filtered, key = { _, a -> a.pkg }) { i, a ->
                    AppRow(
                        a, modes[a.pkg] ?: SendMode.SMART, segmentShape(i, filtered.size),
                        Modifier.animateItem().padding(bottom = 2.dp),
                    ) { pick(a) }
                }
            }
        }
        // Room to scroll the last apps up above the keyboard.
        item("ime") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime)) }
    }
}

/** The search field's place in the list (after the large title and the Smart card). */
private const val SEARCH_INDEX = 2

/** The header: what Smart does, and how many apps are set to something else. */
@Composable
private fun SmartCard(changed: Int) {
    val c = Td.colors
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconChip(
                Icons.Rounded.AutoAwesome, Td.colors.text, size = 48.dp, iconSize = 24.dp,
                modifier = Modifier.tdSharedBounds(APP_RULES_CHIP_KEY),
            )
            Column(Modifier.padding(start = 14.dp)) {
                AnimatedContent(
                    targetState = changed,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "smartTitle",
                ) { n ->
                    Text(
                        when (n) {
                            0 -> "Smart in every app"
                            1 -> "Smart, except 1 app"
                            else -> "Smart, except $n apps"
                        },
                        style = TdType.subtitle, color = c.text,
                    )
                }
                Text(
                    "In a browser, the page. In a camera app, your newest shot. Elsewhere, the photo, video or " +
                        "file on screen, or a screenshot.",
                    style = TdType.bodySmall, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * One app: its icon and name, and the mode a tap uses there on the trailing side, where the eye runs down the
 * list: a quiet "Smart" for the default, a tinted badge for a mode you chose.
 */
@Composable
private fun AppRow(a: AppEntry, mode: SendMode, shape: Shape, modifier: Modifier, onClick: () -> Unit) {
    val c = Td.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(shape, pressedScale = 0.985f, onClickLabel = "Choose what ${a.label} sends", onClick = onClick)
            .background(c.surface, shape)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.pkg, 40.dp)
        Text(
            a.label, style = TdType.body.copy(fontWeight = FontWeight.Medium), color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 14.dp, end = 10.dp),
        )
        AnimatedContent(
            targetState = mode,
            transitionSpec = { (fadeIn(tween(180, delayMillis = 60)) + scaleIn(initialScale = 0.85f)) togetherWith fadeOut(tween(90)) },
            label = "appMode",
        ) { m ->
            if (m == SendMode.SMART) RowValue(m.short)
            else TdBadge(m.short, m.hue(), icon = m.icon(), ink = m.ink())
        }
        RowChevron()
    }
}

/** A mode's name where room is short (the list's trailing badge). */
private val SendMode.short: String
    get() = when (this) {
        SendMode.SMART -> "Smart"
        SendMode.LINK -> "Link"
        SendMode.MEDIA -> "Photo on screen"
        SendMode.LATEST_MEDIA -> "Latest photo"
        SendMode.SCREENSHOT -> "Screenshot"
        SendMode.TEXT -> "Text"
        SendMode.OFF -> "Off"
    }

/** A mode's hue as legible text on the current surface. */
@Composable
private fun SendMode.ink(): Color {
    val c = Td.colors
    return if (this == SendMode.OFF) c.textSecondary else c.text
}

/**
 * The app's launcher icon (masked by the system, cached), on a quiet disc while it loads. In light mode a
 * hairline follows the icon's own mask, so white icons (Calendar, Drive, Maps…) keep their edge on a white row.
 */
@Composable
internal fun AppIcon(pkg: String, size: Dp) {
    val ctx = LocalContext.current
    val c = Td.colors
    val art by produceState(iconCache[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadIcon(ctx, pkg)?.also { iconCache[pkg] = it } }
        }
    }
    Box(Modifier.size(size)) {
        val a = art
        if (a != null) {
            Image(
                a.bitmap, null,
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val outline = a.mask?.takeIf { !c.isDark }?.let { mask ->
                            val k = this.size.width / ICON_PX
                            android.graphics.Path(mask).apply { transform(android.graphics.Matrix().apply { setScale(k, k) }) }.asComposePath()
                        }
                        val stroke = Stroke(1.dp.toPx())
                        onDrawWithContent {
                            drawContent()
                            if (outline != null) drawPath(outline, Color.Black.copy(alpha = 0.08f), style = stroke)
                        }
                    },
            )
        } else {
            Box(Modifier.fillMaxSize().background(c.surfaceSunken, CircleShape))
        }
    }
}

/** The mode picker for one app: its icon and name, then every mode as a segmented choice. */
@Composable
private fun ModeSheet(a: AppEntry, current: SendMode, close: () -> Unit) {
    val app = TapDropApp.instance
    val c = Td.colors
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(current) }
    val maxList = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg, 52.dp)
        Column(Modifier.padding(start = 14.dp)) {
            Text(a.label, style = TdType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("When you touch your phone to the notch", style = TdType.bodySmall, color = c.textSecondary)
        }
    }
    Column(Modifier.heightIn(max = maxList).verticalScroll(rememberScrollState())) {
        SettingsGroup {
            AppRules.options(a.pkg).forEach { m ->
                item { shape ->
                    ChoiceRow(
                        title = m.title,
                        selected = selected == m,
                        detail = m.blurb(a.pkg),
                        icon = m.icon(),
                        accent = m.hue(),
                        shape = shape,
                        color = c.sheetSegment,
                        onClick = {
                            selected = m
                            app.prefs.setAppMode(a.pkg, m)
                            scope.launch {
                                delay(260)
                                close()
                            }
                        },
                    )
                }
            }
        }
        val guarded by app.prefs.guarded.collectAsStateWithLifecycle()
        SettingsGroup(modifier = Modifier.padding(top = 14.dp)) {
            item { shape ->
                SwitchRow(
                    title = "Pause tap to send in this app",
                    checked = a.pkg in guarded,
                    onCheckedChange = { app.prefs.setGuarded(a.pkg, it) },
                    shape = shape,
                    subtitle = "For banking and UPI apps that won't open while it's on",
                    icon = Icons.Rounded.AccountBalance,
                    accent = c.text,
                )
            }
        }
    }
}

/** What a mode sends in this app, in a line. Smart says what it picks here (see [AppRules.smart]). */
private fun SendMode.blurb(pkg: String): String = when (this) {
    SendMode.SMART -> when (AppRules.smart(pkg)) {
        SendMode.LINK -> "Here: the page itself, so it opens on your Mac"
        SendMode.LATEST_MEDIA -> "Here: your newest photo or video, full quality"
        else -> "Here: the photo, video or file on screen, or else a screenshot"
    }
    SendMode.OFF -> "Touching your phone to the notch does nothing here"
    else -> detail
}
