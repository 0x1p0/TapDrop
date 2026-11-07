package app.tapdrop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tapdrop.TapDropApp
import app.tapdrop.tap.Guard
import app.tapdrop.ui.design.EmptyState
import app.tapdrop.ui.design.LargeTitleScaffold
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.SettingsRow
import app.tapdrop.ui.design.SkeletonRow
import app.tapdrop.ui.design.TdIconSwitch
import app.tapdrop.ui.design.TdSearchField
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.design.segmentShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Banking apps → Apps: the apps tap to send turns off for, and every other app, to add one. Apps that look
 * like banking or payments come first. A switch is all it takes either way.
 */
@Composable
fun BankingAppsScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val app = TapDropApp.instance
    val c = Td.colors
    val guarded by app.prefs.guarded.collectAsStateWithLifecycle()
    // The sections come from the list as it was when the screen opened, so a row doesn't jump away when switched.
    val opened = remember { app.prefs.guarded.value }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching { loadApps(ctx).filter { Guard.pausable(ctx, it.pkg) } }.getOrDefault(emptyList())
        }
    }

    val q = query.trim()
    val shown = apps.orEmpty().let { all -> if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) } }
    val on = shown.filter { it.pkg in opened }
    val money = shown.filter { it.pkg !in opened && Guard.looksLikeMoney(it.pkg, it.label) }
    val moneySet = money.mapTo(HashSet()) { it.pkg }
    val rest = shown.filter { it.pkg !in opened && it.pkg !in moneySet }

    LargeTitleScaffold(
        title = "Banking apps",
        subtitle = "Tap to send pauses while these are open",
        onBack = onClose,
        state = list,
    ) {
        item("search") {
            TdSearchField(
                query, { query = it }, "Search apps",
                Modifier
                    .padding(top = 8.dp)
                    .onFocusChanged { if (it.isFocused) scope.launch { list.animateScrollToItem(SEARCH_INDEX) } },
            )
        }
        when {
            apps == null -> {
                item("loadingHeader") { SectionHeader("Paused in") }
                items(5) { i -> SkeletonRow(segmentShape(i, 5), Modifier.padding(bottom = 2.dp), titleWidth = 0.38f + (i % 3) * 0.12f) }
            }
            shown.isEmpty() -> item("none") {
                EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "No apps match “$q”",
                    body = "Check the spelling, or search for part of the name.",
                    accent = c.accent,
                    shape = TdShapes.Cookie9,
                )
            }
            else -> {
                section("on", "Paused in", on, guarded)
                if (q.isEmpty() && on.isEmpty()) item("onEmpty") {
                    Text(
                        "None of the usual banking apps are on this phone. Add yours below, or let TapDrop learn it: " +
                            "the first time an app asks to turn accessibility off, it's added here.",
                        style = TdType.bodySmall, color = c.textSecondary,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp),
                    )
                }
                section("money", "Banking and payments", money, guarded)
                section("rest", if (q.isEmpty()) "All apps" else "Other apps", rest, guarded)
                item("footer") {
                    Text(
                        "Tap to send pauses for as long as one of these is open, and resumes when you leave it. An app " +
                            "you take off here is never added back by itself.",
                        style = TdType.bodySmall, color = c.textSecondary,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp),
                    )
                }
            }
        }
        // Room to scroll the last apps up above the keyboard.
        item("ime") { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime)) }
    }
}

/** The search field's place in the list (after the large title). */
private const val SEARCH_INDEX = 1

private fun LazyListScope.section(key: String, title: String, apps: List<AppEntry>, guarded: Set<String>) {
    if (apps.isEmpty()) return
    item("$key:header") { SectionHeader(title) }
    itemsIndexed(apps, key = { _, a -> "$key:${a.pkg}" }) { i, a ->
        GuardRow(a, a.pkg in guarded, segmentShape(i, apps.size), Modifier.animateItem().padding(bottom = 2.dp))
    }
}

/** One app with its switch: on, tap to send turns off while it's open. The whole row toggles it. */
@Composable
private fun GuardRow(a: AppEntry, on: Boolean, shape: Shape, modifier: Modifier) {
    val haptics = rememberHaptics()
    SettingsRow(
        title = a.label,
        subtitle = if (on) "Tap to send pauses while it's open" else null,
        shape = shape,
        leading = { AppIcon(a.pkg, 40.dp) },
        modifier = modifier
            .pressable(shape, pressedScale = 0.985f, haptic = false, role = Role.Switch) {
                haptics.toggle(!on)
                TapDropApp.instance.prefs.setGuarded(a.pkg, !on)
            }
            .semantics { toggleableState = ToggleableState(on) },
        trailing = { TdIconSwitch(on, null) },
    )
}

/** "BHIM", "BHIM and Wise", "BHIM, Wise and 2 more": the apps on the list that are on this phone. */
fun guardedSummary(names: List<String>): String = when (names.size) {
    0 -> "None on this phone yet"
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    3 -> "${names[0]}, ${names[1]} and ${names[2]}"
    else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
}
