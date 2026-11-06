package app.tapdrop.ui.pair

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.AuroraBackdrop
import app.tapdrop.ui.design.AuroraMood
import app.tapdrop.ui.design.BackButton
import app.tapdrop.ui.design.DeviceHero
import app.tapdrop.ui.design.EmptyState
import app.tapdrop.ui.design.GlassCard
import app.tapdrop.ui.design.GlassLayers
import app.tapdrop.ui.design.GlassSheet
import app.tapdrop.ui.design.GlassTopBar
import app.tapdrop.ui.design.LinkState
import app.tapdrop.ui.design.LocalNavAnimatedScope
import app.tapdrop.ui.design.MacGlyph
import app.tapdrop.ui.design.RowChevron
import app.tapdrop.ui.design.SectionHeader
import app.tapdrop.ui.design.SettingsGroup
import app.tapdrop.ui.design.SmallButton
import app.tapdrop.ui.design.TdLoadingIndicator
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.entrance
import app.tapdrop.ui.design.pressable
import app.tapdrop.ui.rememberHaptics

/**
 * The pairing page, without any state of its own: the Macs TapDrop can see, how the search is going,
 * and the pairing sheet on top. [app.tapdrop.ui.PairScreen] drives it.
 *
 * @param slow nothing has turned up for a while: offer help.
 */
@Composable
fun PairContent(
    nearby: List<NearbyMac>,
    wifi: Boolean,
    slow: Boolean,
    step: PairStep?,
    address: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    onBack: (() -> Unit)?,
    onPick: (NearbyMac) -> Unit,
    onManual: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onEditAddress: () -> Unit,
    onWifiSettings: () -> Unit,
    onSheetDismissed: () -> Unit,
) {
    // Pages reached through the app's NavHost already get their entrance from the transition.
    val intro = LocalNavAnimatedScope.current == null
    val scroll = rememberScrollState()
    val bars = WindowInsets.systemBars.asPaddingValues()
    val barPx = with(LocalDensity.current) { (bars.calculateTopPadding() + 60.dp).toPx() }
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val scrolled by remember { derivedStateOf { (scroll.value / 120f).coerceIn(0f, 1f) } }
    // The bar takes the title over once the large one has scrolled under it.
    val titleGone by remember { derivedStateOf { titleBottom - scroll.value < barPx } }
    val titleAlpha by animateFloatAsState(if (titleGone) 1f else 0f, Td.spring(), label = "barTitle")

    val hero = when {
        step is PairStep.Paired -> LinkState.Sent
        step is PairStep.Code -> LinkState.Connected
        step is PairStep.Connecting -> LinkState.Searching
        !wifi -> LinkState.Offline
        nearby.isEmpty() -> LinkState.Searching
        else -> LinkState.Connected
    }
    val unpaired = nearby.count { !it.paired }

    GlassLayers(
        Modifier.fillMaxSize(),
        chrome = {
            GlassTopBar(
                scrolled = { scrolled },
                navigation = onBack?.let { back -> { BackButton(back) } },
                title = {
                    Text(
                        "Pair with your Mac",
                        style = TdType.subtitle,
                        color = Td.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            alpha = titleAlpha
                            translationY = (1f - titleAlpha) * 6.dp.toPx()
                        },
                    )
                },
            )
            if (step != null) {
                GlassSheet(onDismissed = onSheetDismissed, dismissible = step !is PairStep.Paired) { close ->
                    PairSheetContent(
                        step = step,
                        address = address,
                        onAddressChange = onAddressChange,
                        onConnect = onConnect,
                        onRetry = onRetry,
                        onEditAddress = onEditAddress,
                        onWifiSettings = onWifiSettings,
                        close = close,
                    )
                }
            }
        },
    ) {
        AuroraBackdrop(
            Modifier.fillMaxWidth().height(600.dp),
            mood = when {
                step is PairStep.Paired -> AuroraMood.Live
                !wifi -> AuroraMood.Quiet
                else -> AuroraMood.Brand
            },
        )
        // While a sheet is up the page recedes, so the glass frosts a calm backdrop instead of
        // smudging dark artwork into the sheet (most visible in light mode).
        val recede by animateFloatAsState(
            when {
                step == null -> 1f
                Td.colors.isDark -> 0.72f
                else -> 0.42f
            },
            Td.spring(), label = "recede",
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = recede }
                    .verticalScroll(scroll)
                    .heightIn(min = viewport)
                    .padding(
                        start = Td.space.gutter, end = Td.space.gutter,
                        top = bars.calculateTopPadding() + 60.dp,
                        bottom = bars.calculateBottomPadding() + 12.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DeviceHero(
                    state = hero,
                    contentDescription = when (hero) {
                        LinkState.Offline -> "Your phone and Mac, not connected"
                        LinkState.Searching -> "Looking for your Mac"
                        else -> "Your phone and Mac"
                    },
                    modifier = Modifier.widthIn(max = 320.dp).entrance(0, enabled = intro),
                )
                Text(
                    "Pair with your Mac",
                    style = TdType.display,
                    color = Td.colors.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .entrance(1, enabled = intro)
                        .onGloballyPositioned { titleBottom = it.positionInParent().y + it.size.height }
                        .semantics { heading() },
                )
                Text(
                    if (wifi) "Open TapDrop on your Mac. It shows up here when you're both on the same Wi‑Fi."
                    else "TapDrop finds your Mac over Wi‑Fi. Join the same network as your Mac.",
                    style = TdType.body.copy(lineBreak = LineBreak.Heading),
                    color = Td.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 340.dp).padding(top = 10.dp).entrance(1, enabled = intro),
                )
                SectionHeader(
                    "Nearby Macs",
                    Modifier.padding(top = 10.dp).entrance(2, enabled = intro),
                    action = if (wifi && nearby.isNotEmpty()) {
                        { StillLooking() }
                    } else null,
                )
                Column(Modifier.fillMaxWidth().animateContentSize(Td.motion.standard()).entrance(3, enabled = intro)) {
                    when {
                        !wifi -> NoWifiCard(onWifiSettings)
                        nearby.isEmpty() -> {
                            SearchingCard()
                            AnimatedVisibility(
                                visible = slow,
                                enter = fadeIn() + expandVertically(Td.motion.standard()),
                                exit = fadeOut() + shrinkVertically(Td.motion.standard()),
                            ) {
                                SettingsGroup(title = "Can't see your Mac?") {
                                    row("Open TapDrop on your Mac", "It lives in the menu bar.", Icons.Rounded.LaptopMac, Brand.Bone)
                                    row("Use the same Wi‑Fi", "Phone and Mac on one network.", Icons.Rounded.Wifi, Brand.Bone)
                                    row(
                                        "Enter its address",
                                        "Shown on your Mac in Set Up a Phone.",
                                        Icons.Rounded.Keyboard, Brand.Bone,
                                        onClick = onManual,
                                        trailing = { RowChevron() },
                                    )
                                }
                            }
                        }
                        else -> nearby.forEach { mac ->
                            key(mac.id) {
                                MacCard(
                                    mac = mac,
                                    prominent = !mac.paired && unpaired == 1,
                                    onClick = { onPick(mac) },
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            }
                        }
                    }
                }
                // The quiet way in, at the foot of the page. It steps aside while a sheet is up (the sheet is
                // where you are) and once the help above offers the same thing.
                Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
                val footer by animateFloatAsState(
                    if (step != null || (slow && nearby.isEmpty() && wifi)) 0f else 1f,
                    Td.spring(), label = "footer",
                )
                TdTextButton(
                    "Enter address manually", onManual,
                    icon = Icons.Rounded.Keyboard,
                    enabled = footer > 0.5f,
                    color = Td.colors.accentText,
                    modifier = Modifier.graphicsLayer { alpha = footer }.entrance(4, enabled = intro),
                )
            }
        }
    }
}

/** A Mac that turned up on the network: springs in, ticks, and pairs on tap. */
@Composable
private fun MacCard(mac: NearbyMac, prominent: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Td.colors
    val haptics = rememberHaptics()
    val reduced = Td.reducedMotion
    val arrive = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        haptics.tick()
        arrive.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 300f))
    }
    val shape = RoundedCornerShape(TdRadius.card)
    Row(
        modifier
            .graphicsLayer {
                val a = arrive.value
                alpha = a.coerceIn(0f, 1f)
                val s = 0.86f + 0.14f * a
                scaleX = s
                scaleY = s
                translationY = (1f - a) * 26.dp.toPx()
            }
            .fillMaxWidth()
            .then(
                if (c.isDark) Modifier
                else Modifier.shadow(12.dp, shape, ambientColor = c.shadow.copy(alpha = 0.08f), spotColor = c.shadow.copy(alpha = 0.08f))
            )
            .pressable(shape, onClickLabel = "Pair", onClick = onClick)
            .background(c.surface)
            .then(if (c.isDark) Modifier.border(1.dp, c.hairline, shape) else Modifier)
            .heightIn(min = 84.dp)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacGlyph(width = 58.dp, lit = true)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 10.dp)) {
            Text(mac.name, style = TdType.subtitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mac.paired) {
                    Icon(Icons.Rounded.Check, null, tint = c.successText, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                }
                Text(
                    if (mac.paired) "Paired with this phone" else "Ready to pair",
                    style = TdType.bodySmall,
                    color = c.textSecondary,
                )
            }
        }
        if (mac.paired) RowChevron() else PairPill(prominent)
    }
}

/** The "Pair" pill on a Mac card. The whole card is the button, so this is only its label. */
@Composable
private fun PairPill(prominent: Boolean) {
    val c = Td.colors
    Box(
        Modifier
            .height(36.dp)
            .then(if (prominent) Modifier.background(c.accent, CircleShape) else Modifier.background(c.accentSoft, CircleShape))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Pair",
            style = TdType.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (prominent) Color.White else c.accentText,
            maxLines = 1,
        )
    }
}

/** The header's quiet reminder that the list is live: more Macs can still turn up. */
@Composable
private fun StillLooking() {
    Row(
        Modifier
            .heightIn(min = 24.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Still looking for Macs" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TdLoadingIndicator(Modifier.size(22.dp), color = Td.colors.textTertiary)
        Text(
            "Looking",
            style = TdType.caption,
            color = Td.colors.textTertiary,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp).clearAndSetSemantics { },
        )
    }
}

/** No Macs yet: the expressive loader and what to check. */
@Composable
private fun SearchingCard(modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TdLoadingIndicator(Modifier.size(56.dp), contained = true)
            Column(Modifier.padding(start = 14.dp)) {
                Text("Looking for Macs…", style = TdType.subtitle, color = Td.colors.text)
                Text(
                    "This usually takes a moment.",
                    style = TdType.bodySmall,
                    color = Td.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Not on Wi‑Fi: say so plainly and offer the Wi‑Fi panel. */
@Composable
private fun NoWifiCard(onWifiSettings: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        EmptyState(
            icon = Icons.Rounded.WifiOff,
            title = "This phone isn't on Wi‑Fi",
            body = "Join the same Wi‑Fi network as your Mac, and it will show up here.",
            accent = Td.colors.warning,
            shape = TdShapes.Cookie9,
            action = { SmallButton("Wi‑Fi settings", onWifiSettings, prominent = true) },
        )
    }
}
