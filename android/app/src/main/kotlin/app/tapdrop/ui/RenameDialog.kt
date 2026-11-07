package app.tapdrop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tapdrop.core.Names
import app.tapdrop.ui.design.IconChip
import app.tapdrop.ui.design.TdShapes
import app.tapdrop.ui.design.TdTextButton
import app.tapdrop.ui.design.pressable

/**
 * Renames this phone or a Mac. The field starts with the current name selected; Save is off until there's a new,
 * non-blank name. [systemName]: the name the device would have without one set in TapDrop, offered as "Use …" when
 * [custom] (a name was set). [onSave] gets the cleaned name, or null to go back to [systemName].
 */
@Composable
fun RenameDialog(
    title: String,
    message: String,
    current: String,
    icon: ImageVector,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
    systemName: String? = null,
    custom: Boolean = false,
) {
    val c = Td.colors
    var field by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val cleaned = Names.clean(field.text)
    val canSave = cleaned != null && cleaned != current
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val edge by animateColorAsState(if (focused) c.accent.copy(alpha = 0.6f) else c.hairline, label = "nameEdge")
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { IconChip(icon, c.text, size = 48.dp, shape = TdShapes.Cookie9, iconSize = 24.dp) },
        title = { Text(title, style = TdType.title, textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    message, style = TdType.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                BasicTextField(
                    value = field,
                    onValueChange = { v ->
                        // A name is one line, at most Names.MAX characters.
                        val text = v.text.replace('\n', ' ')
                        if (text.codePointCount(0, text.length) <= Names.MAX) field = v.copy(text = text)
                    },
                    singleLine = true,
                    textStyle = TdType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(cleaned) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .background(c.surface, RoundedCornerShape(16.dp))
                                .border(if (focused) 1.5.dp else 1.dp, edge, RoundedCornerShape(16.dp))
                                .padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (field.text.isEmpty()) {
                                    Text(systemName ?: "Name", style = TdType.body, color = c.textTertiary, maxLines = 1)
                                }
                                inner()
                            }
                            AnimatedVisibility(
                                visible = field.text.isNotEmpty(),
                                enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.6f),
                                exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.6f),
                            ) {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .pressable(CircleShape, pressedScale = 0.9f, onClickLabel = "Clear the name") {
                                            field = TextFieldValue("")
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier.size(24.dp).background(c.textTertiary.copy(alpha = 0.22f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Rounded.Close, "Clear the name", tint = c.textSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    },
                )
                if (custom && systemName != null) {
                    TdTextButton(
                        "Use “$systemName”",
                        { onSave(null) },
                        Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                        color = c.textSecondary,
                    )
                }
            }
        },
        confirmButton = { TdTextButton("Save", { if (canSave) onSave(cleaned) }, enabled = canSave) },
        dismissButton = { TdTextButton("Cancel", onDismiss) },
        containerColor = c.surfaceRaised,
    )
}
