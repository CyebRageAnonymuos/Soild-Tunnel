package com.soildtunnel.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * ROOT-CAUSE FIX for the "digits get shuffled while typing" bug in every
 * technical field (ip:port endpoints, CIDR ranges, URLs).
 *
 * The bug had TWO independent root causes, which is why it reproduced in
 * BOTH the English and the Persian locale:
 *
 * 1. ASYNC STATE ECHO (all locales — the actual reason English scrambled
 *    too): the field used to be driven directly by profile state that
 *    round-trips through DataStore (keystroke -> async save -> flow emits
 *    back). While typing fast, a keystroke was applied on top of a STALE
 *    value that echoed back from disk a moment later, so characters were
 *    dropped/reordered and the cursor jumped ("127.0.0.1" -> "27.0.0.11").
 *    Defense here (in addition to the synchronous state fix in
 *    MainActivity): the field owns a local [TextFieldValue] while focused —
 *    the single source of truth for what the user is typing. External
 *    changes (initial load, "reset settings") are only accepted while the
 *    field is NOT focused, so no async echo can ever clobber a keystroke.
 *
 * 2. BiDi RENDERING (Persian locale): the Unicode BiDi algorithm treats
 *    digits, dots and colons as direction-NEUTRAL, so inside an RTL layout
 *    a value like `162.159.192.1:443` gets visually reordered around the
 *    RTL base direction while typing. ip:port / CIDR / URLs are inherently
 *    LTR, so this field pins BOTH the layout direction and the text
 *    direction to LTR.
 *
 * Additionally, every character is normalized on input: Eastern-Arabic
 * digit variants become ASCII 0-9, and Arabic separators become '.' / ','.
 * and ',', and invisible BiDi control marks that some keyboards insert are
 * stripped — so a value typed with a Persian keyboard is still a valid
 * ip:port for the engine.
 *
 * Use this for EVERY technical text input in the app — never a bare
 * [OutlinedTextField] — so the bug cannot come back in any screen.
 */
@Composable
fun LtrOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    // Added in : lets Zero Trust secrets be masked while still getting the
    // LTR/BiDi handling every technical field in this app needs.
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Local, synchronous source of truth while the user is typing.
    var fieldValue by remember { mutableStateOf(TextFieldValue(normalizeTechnicalText(value))) }

    // Accept external value changes (initial profile load, reset-to-defaults)
    // ONLY while the field is not focused. While focused, keystrokes rule —
    // a stale async echo of an older value can never overwrite them.
    if (!isFocused && fieldValue.text != value) {
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { raw ->
                val normalized = normalizeTechnicalValue(raw)
                fieldValue = normalized
                if (normalized.text != value) onValueChange(normalized.text)
            },
            modifier = modifier,
            enabled = enabled,
            singleLine = singleLine,
            label = label,
            placeholder = placeholder,
            supportingText = supportingText,
            interactionSource = interactionSource,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = LocalTextStyle.current.copy(
                textDirection = TextDirection.Ltr,
                textAlign = TextAlign.Start,
            ),
        )
    }
}

/** Maps localized digits/separators to their ASCII equivalents. */
private fun normalizeTechnicalChar(c: Char): Char = when (c) {
    in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0') // Eastern-Arabic digit range
    in '\u0660'..'\u0669' -> '0' + (c - '\u0660') // Arabic-Indic digit range
    '\u066B' -> '.' // Arabic decimal separator
    '\u060C' -> ',' // Arabic comma
    else -> c
}

/** Invisible BiDi control marks some RTL keyboards insert around neutrals. */
private fun isBidiControl(c: Char): Boolean =
    c == '\u200E' || c == '\u200F' || c == '\u061C' ||
        (c in '\u202A'..'\u202E') || (c in '\u2066'..'\u2069')

private fun normalizeTechnicalText(text: String): String =
    buildString(text.length) {
        for (c in text) if (!isBidiControl(c)) append(normalizeTechnicalChar(c))
    }

/**
 * Normalizes the full [TextFieldValue], keeping the cursor/selection in the
 * right place when invisible control characters are stripped.
 */
private fun normalizeTechnicalValue(value: TextFieldValue): TextFieldValue {
    val text = value.text
    var changed = false
    var selStart = value.selection.start
    var selEnd = value.selection.end
    val sb = StringBuilder(text.length)
    text.forEachIndexed { i, c ->
        if (isBidiControl(c)) {
            changed = true
            if (i < value.selection.start) selStart--
            if (i < value.selection.end) selEnd--
        } else {
            val n = normalizeTechnicalChar(c)
            if (n != c) changed = true
            sb.append(n)
        }
    }
    if (!changed) return value
    val out = sb.toString()
    return TextFieldValue(
        text = out,
        selection = TextRange(
            selStart.coerceIn(0, out.length),
            selEnd.coerceIn(0, out.length),
        ),
    )
}
