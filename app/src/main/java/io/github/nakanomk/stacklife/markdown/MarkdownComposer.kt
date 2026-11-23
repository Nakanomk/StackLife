package io.github.nakanomk.stacklife.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.text.input.ImeAction
import io.github.nakanomk.stacklife.markdown.kern.*

private val compactLines = TextFieldLineLimits.MultiLine(maxHeightInLines = 5)
// Window focus restoration must not start a conversation or expand a read-only detail sheet.
private val sendKeyboard = KeyboardOptions(imeAction = ImeAction.Send, showKeyboardOnFocus = false)

@Composable
internal fun MarkdownComposer(state: TextFieldState, modifier: Modifier = Modifier, onSend: (() -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val cache = remember(state) { MarkdownDocumentCache() }
    val input = remember(state) { MarkdownDocumentInputTransformation(state, cache) }
    val focused = remember(state) { mutableStateOf(false) }
    val output = remember(state, colors, typography) {
        MarkdownDocumentOutputTransformation(state, cache, MarkdownSpanStyleProvider(typography, colors)) { focused.value }
    }
    val sending = onSend != null
    val currentSend by rememberUpdatedState(onSend)
    val action = remember(state, sending) {
        if (!sending) null else KeyboardActionHandler { if (state.composition == null) currentSend?.invoke() }
    }
    val interceptor = remember(input, sending) { PlatformTextInputInterceptor { request, next ->
        next.startInputMethod { attributes -> input.connection(request.createInputConnection(attributes), sending) }
    } }
    InterceptPlatformTextInput(interceptor) {
        BasicTextField(
            state = state,
            modifier = modifier.onFocusChanged {
                focused.value = it.isFocused
                if (!it.isFocused) input.hardwareEnter = false
            }.onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                val down = event.type == KeyEventType.KeyDown
                input.hardwareEnter = enter && down && !event.isCtrlPressed && state.composition == null
                if (sending && enter && event.isCtrlPressed) {
                    if (down && event.nativeKeyEvent.repeatCount == 0 && state.composition == null) currentSend?.invoke()
                    true
                } else false // Multiline BasicTextField handles ordinary Enter, including undo grouping.
            },
            inputTransformation = input,
            outputTransformation = output,
            textStyle = typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            lineLimits = if (sending) compactLines else TextFieldLineLimits.Default,
            keyboardOptions = if (sending) sendKeyboard else KeyboardOptions.Default,
            onKeyboardAction = action,
            decorator = { inner ->
                if (placeholder == null) inner()
                else Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) placeholder()
                    inner()
                }
            },
        )
    }
}

/** A presentation-only summary, never a draft or a database write. */
internal fun markdownSummary(source: String): String = MarkdownDocumentScanner.scan(source).plainText().trim()
