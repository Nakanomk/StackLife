// Adapted from Kern's ui/editor/MarkdownDocumentInputTransformation.kt,
// Copyright 2026 Gary Keeler (Apache-2.0). Only a confirmed newline can continue a list.
package io.github.nakanomk.stacklife.markdown.kern

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange

internal class MarkdownDocumentInputTransformation(
    private val state: TextFieldState,
    private val cache: MarkdownDocumentCache,
) : InputTransformation {
    var hardwareEnter = false
    private var committedEnter = false

    override fun TextFieldBuffer.transformInput() {
        if (changes.changeCount == 0) {
            cache.selectionOnly(originalText, asCharSequence())
            return
        }
        if ((!hardwareEnter && !committedEnter) || state.composition != null || changes.changeCount != 1) return
        val changed = changes.getRange(0)
        val original = changes.getOriginalRange(0)
        if (!original.collapsed || !originalSelection.collapsed || selection != TextRange(changed.max)) return
        if (!(changed.length == 1 && charAt(changed.min) == '\n') &&
            !(changed.length == 2 && charAt(changed.min) == '\r' && charAt(changed.min + 1) == '\n')) return
        val continuation = MarkdownEditorEngine.continueDocumentList(originalText.toString(), original.min, cache) ?: return
        val prefix = continuation.nextPrefix
        if (prefix != null) {
            insert(changed.max, prefix)
            selection = TextRange(changed.max + prefix.length)
        } else {
            delete(continuation.markerStart, continuation.markerEnd)
            selection = TextRange(changed.max - (continuation.markerEnd - continuation.markerStart))
        }
    }

    /** InputTransformation sees only a text delta, not whether it originated in commitText,
     * setComposingText or a paste. Tag a committed newline without replacing the native IME
     * session, forwarding selection, annotations and Android's batch boundaries intact. */
    fun connection(target: InputConnection, sending: Boolean): InputConnection = object : InputConnectionWrapper(target, false) {
        private var depth = 0
        private var pendingBreak = false

        private inline fun edit(newline: Boolean, op: () -> Boolean): Boolean {
            pendingBreak = newline && depth > 0
            committedEnter = newline
            return try { op() } finally { committedEnter = false }
        }

        override fun beginBatchEdit(): Boolean = super.beginBatchEdit().also { if (it) depth++ }
        override fun endBatchEdit(): Boolean {
            committedEnter = depth == 1 && pendingBreak
            return try { super.endBatchEdit() } finally {
                committedEnter = false
                if (depth > 0) depth--
                if (depth == 0) pendingBreak = false
            }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            edit(text.isNewline()) { super.commitText(text, newCursorPosition) }
        @RequiresApi(33)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            edit(text.isNewline()) { super.commitText(text, newCursorPosition, textAttribute) }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            edit(false) { super.setComposingText(text, newCursorPosition) }
        @RequiresApi(33)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
            edit(false) { super.setComposingText(text, newCursorPosition, textAttribute) }

        // Compose's action handler does not carry the action ID. Do not interpret a Done/Next
        // candidate action from a physical IME as authorisation to send a message.
        override fun performEditorAction(editorAction: Int): Boolean =
            if (sending && editorAction != EditorInfo.IME_ACTION_SEND) false else super.performEditorAction(editorAction)
    }

    private fun CharSequence?.isNewline() = this != null &&
        ((length == 1 && this[0] == '\n') || (length == 2 && this[0] == '\r' && this[1] == '\n'))
}
