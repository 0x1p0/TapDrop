package app.tapdrop.ui.pair

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapdrop.ui.Brand
import app.tapdrop.ui.Td
import app.tapdrop.ui.TdMotion
import app.tapdrop.ui.TdRadius
import app.tapdrop.ui.TdType
import app.tapdrop.ui.design.CheckBurst
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.MacGlyph
import app.tapdrop.ui.design.PrimaryButton
import app.tapdrop.ui.design.StatusPill
import app.tapdrop.ui.design.StatusTone
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import kotlinx.coroutines.delay

/**
 * Everything inside the pairing sheet. The sheet stays put while its content flows from step to step
 * (typing an address → connecting → the code → paired, or a problem), resizing on a spring.
 */
@Composable
fun PairSheetContent(
    step: PairStep,
    address: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onEditAddress: () -> Unit,
    onWifiSettings: () -> Unit,
    close: () -> Unit,
) {
    val reduced = Td.reducedMotion
    Column(Modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = step,
            contentKey = { s ->
                when (s) {
                    is PairStep.Address -> "address"
                    is PairStep.Failed -> "failed"
                    else -> "code"
                }
            },
            transitionSpec = {
                if (reduced) {
                    (fadeIn(tween(140)) togetherWith fadeOut(tween(140))).using(SizeTransform(clip = false))
                } else {
                    (fadeIn(tween(240, 100)) + slideInVertically(TdMotion.standard()) { it / 12 }) togetherWith
                        fadeOut(tween(110)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 420f) }
                }
            },
            label = "pairStep",
        ) { s ->
            when (s) {
                is PairStep.Address -> AddressStep(s.error, address, onAddressChange, onConnect, close)
                is PairStep.Failed -> FailedStep(s, onRetry, onEditAddress, onWifiSettings, close)
                else -> CodeStep(s, close)
            }
        }
        // Lifts the sheet over the keyboard (the sheet already clears the navigation bar).
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
    }
}

// MARK: - the code

@Composable
private fun CodeStep(step: PairStep, close: () -> Unit) {
    val macName = when (step) {
        is PairStep.Connecting -> step.macName
        is PairStep.Code -> step.macName
        is PairStep.Paired -> step.macName
        else -> ""
    }
    val code = when (step) {
        is PairStep.Code -> step.code
        is PairStep.Paired -> step.code
        else -> null
    }
    val paired = step is PairStep.Paired
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StepVisual {
            AnimatedContent(
                targetState = paired,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(TdMotion.emphasized(), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.85f))
                },
                contentAlignment = Alignment.Center,
                label = "codeVisual",
            ) { done ->
                if (done) CheckBurst(visible = true, size = 56.dp)
                else MacGlyph(width = 92.dp, lit = code != null)
            }
        }
        StepTitle(
            when (step) {
                is PairStep.Connecting -> "Connecting to $macName"
                is PairStep.Code -> "Check the code on your Mac"
                else -> "You're paired"
            }
        )
        StepBody(
            when (step) {
                is PairStep.Connecting -> "Getting a code from your Mac."
                is PairStep.Code -> "$macName should show the same code. Click Pair there to finish."
                else -> "$macName and this phone can now share with a tap."
            }
        )
        Spacer(Modifier.height(24.dp))
        CodeCells(code, confirmed = paired, modifier = Modifier.widthIn(max = 380.dp))
        Spacer(Modifier.height(22.dp))
        when (step) {
            is PairStep.Connecting -> StatusPill("Connecting", StatusTone.Searching)
            is PairStep.Code -> StatusPill("Waiting for your Mac", StatusTone.Searching)
            else -> StatusPill("Paired", StatusTone.Good)
        }
        // Cancel folds away once paired; the sheet settles a little as the check lands.
        AnimatedVisibility(
            visible = !paired,
            enter = fadeIn() + expandVertically(TdMotion.standard()),
            exit = fadeOut(tween(120)) + shrinkVertically(TdMotion.emphasized()),
        ) {
            TdTextButton("Cancel", close, color = Td.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        if (paired) Spacer(Modifier.height(16.dp))
    }
}

// MARK: - problems

private data class ProblemCopy(val icon: ImageVector, val accent: Color, val title: String, val body: String)

@Composable
private fun problemCopy(step: PairStep.Failed): ProblemCopy {
    val c = Td.colors
    val mac = step.macName
    return when (step.problem) {
        PairProblem.Declined -> ProblemCopy(
            Icons.Rounded.Block, c.danger, "Pairing declined",
            "$mac said no. Try again, and click Pair on your Mac when the code shows up.",
        )
        PairProblem.Expired -> ProblemCopy(
            Icons.Rounded.Timer, c.warning, "The code expired",
            "Nobody clicked Pair on $mac in time. Try again and confirm within two minutes.",
        )
        PairProblem.Unreachable -> ProblemCopy(
            Icons.Rounded.WifiFind, c.warning, "Can't reach your Mac",
            if (step.manual) "Nothing answered at $mac. Make sure TapDrop is open on your Mac, and that your phone and Mac are on the same Wi‑Fi."
            else "$mac didn't answer. Make sure TapDrop is open on your Mac, and that your phone and Mac are on the same Wi‑Fi.",
        )
        PairProblem.Lost -> ProblemCopy(
            Icons.Rounded.LinkOff, c.danger, "Lost the connection",
            "$mac stopped answering. Check that TapDrop is still open on your Mac, then try again.",
        )
        PairProblem.NotTapDrop -> ProblemCopy(
            Icons.Rounded.SearchOff, c.warning, "That isn't TapDrop",
            "Something answered at $mac, but it isn't TapDrop. Check the address on your Mac.",
        )
        PairProblem.Other -> ProblemCopy(
            Icons.Rounded.ErrorOutline, c.danger, "Couldn't pair",
            "Something went wrong while pairing with $mac. Try again.",
        )
    }
}

@Composable
private fun FailedStep(
    step: PairStep.Failed,
    onRetry: () -> Unit,
    onEditAddress: () -> Unit,
    onWifiSettings: () -> Unit,
    close: () -> Unit,
) {
    val copy = problemCopy(step)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StepVisual { IconChip(copy.icon, copy.accent, size = 72.dp, shape = TdShapes.Cookie12, iconSize = 32.dp) }
        StepTitle(copy.title)
        StepBody(copy.body)
        Spacer(Modifier.height(26.dp))
        PrimaryButton("Try again", onRetry, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        when {
            step.manual -> TdTextButton("Edit address", onEditAddress)
            step.problem == PairProblem.Unreachable -> TdTextButton("Wi‑Fi settings", onWifiSettings)
            else -> TdTextButton("Not now", close, color = Td.colors.textSecondary)
        }
    }
}

// MARK: - typing an address

@Composable
private fun AddressStep(
    error: String?,
    address: TextFieldValue,
    onAddressChange: (TextFieldValue) -> Unit,
    onConnect: () -> Unit,
    close: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StepVisual { IconChip(Icons.Rounded.Lan, Brand.Blue, size = 72.dp, shape = TdShapes.Cookie9, iconSize = 32.dp) }
        StepTitle("Connect by address")
        StepBody("Type the address TapDrop shows on your Mac, under Set Up a Phone.")
        Spacer(Modifier.height(22.dp))
        AddressField(address, onAddressChange, error, onGo = { keyboard?.hide(); onConnect() })
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Connect", { keyboard?.hide(); onConnect() }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        TdTextButton("Cancel", { keyboard?.hide(); close() }, color = Td.colors.textSecondary)
    }
}

@Composable
private fun AddressField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: String?,
    onGo: () -> Unit,
) {
    val c = Td.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Bring the keyboard up once the sheet has settled.
    LaunchedEffect(Unit) {
        delay(320)
        runCatching { focus.requestFocus() }
    }
    val shape = RoundedCornerShape(TdRadius.field)
    val edge = when {
        error != null -> c.danger
        focused -> c.accent
        else -> c.hairline
    }
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TdType.mono.copy(fontSize = 20.sp, lineHeight = 28.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = "Mac address"
                    if (error != null) error(error)
                },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .background(if (c.isDark) Color.White.copy(alpha = 0.06f) else c.surfaceSunken, shape)
                        .border(if (focused || error != null) 1.5.dp else 1.dp, edge, shape)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty()) {
                            Text(
                                "192.168.1.20",
                                style = TdType.mono.copy(fontSize = 20.sp, lineHeight = 28.sp),
                                color = c.textTertiary,
                            )
                        }
                        inner()
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = error != null,
            enter = fadeIn() + expandVertically(TdMotion.standard()),
            exit = fadeOut() + shrinkVertically(TdMotion.standard()),
        ) {
            Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.dangerText, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                Text(
                    error.orEmpty(),
                    style = TdType.bodySmall,
                    color = c.dangerText,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// MARK: - shared bits

/** The fixed-height visual at the top of every step, so the sheet keeps its rhythm as steps change. */
@Composable
private fun StepVisual(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(104.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun StepTitle(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(100)) },
        label = "stepTitle",
    ) { t ->
        Text(
            t,
            style = TdType.title.copy(lineBreak = LineBreak.Heading),
            color = Td.colors.text,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun StepBody(text: String) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(220, 80)) togetherWith fadeOut(tween(100)) },
        label = "stepBody",
    ) { t ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                t,
                style = TdType.bodySmall.copy(lineBreak = LineBreak.Heading),
                color = Td.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 330.dp).padding(top = 6.dp),
            )
        }
    }
}
