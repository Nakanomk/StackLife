package io.github.nakanomk.stacklife.markdown

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.StackLifeTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Android Compose InputConnection, not SemanticsActions.InsertTextAtCursor.
 * No installed IME, application database, service configuration or network is touched. */
@RunWith(AndroidJUnit4::class)
class MarkdownComposerImeTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val connection = AtomicReference<InputConnection?>()
    private val attributes = EditorInfo()
    private var focused = false
    private val field get() = ui.onNodeWithTag("input")

    private fun open(state: TextFieldState, enabled: () -> Boolean = { true }, send: (() -> Unit)? = null) {
        val intercept = PlatformTextInputInterceptor { request, _ ->
            val ic = request.createInputConnection(attributes)
            connection.set(ic)
            try { awaitCancellation() } finally { connection.compareAndSet(ic, null); ic.closeConnection() }
        }
        ui.setContent { StackLifeTheme { InterceptPlatformTextInput(intercept) {
            MarkdownComposer(state, Modifier.testTag("input").onFocusChanged { focused = it.isFocused }, send, enabled = enabled())
        } } }
        field.performClick()
        ui.waitUntil(5_000) { connection.get() != null }
        ui.runOnIdle { state.edit { selection = TextRange(length) } }
    }

    private fun ime(block: InputConnection.() -> Unit) = ui.runOnIdle { requireNotNull(connection.get()).block() }
    private fun InputConnection.visible(): String = requireNotNull(getExtractedText(ExtractedTextRequest(), 0)).text.toString()

    @Test fun coveredDockDisconnectsInputAndRestoresTheOriginalSelection() {
        val text = "private unsent draft"
        val state = TextFieldState(text)
        val enabled = mutableStateOf(true)
        open(state, enabled = { enabled.value }, send = {})
        val selection = TextRange(2, 7)
        ime { setSelection(selection.start, selection.end) }
        val at = field.fetchSemanticsNode().boundsInRoot.center
        ui.runOnIdle {
            assertTrue(focused)
            assertEquals(selection, state.selection)
            enabled.value = false
        }
        ui.waitUntil(5_000) { connection.get() == null }
        ui.onRoot().performTouchInput { click(at) }
        ui.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true).assertCountEquals(0)
        ui.onAllNodes(hasText(text), useUnmergedTree = true).assertCountEquals(0)
        ui.runOnIdle {
            assertFalse(focused)
            assertNull(connection.get())
            assertEquals(text, state.text.toString())
            assertEquals(selection, state.selection)
            enabled.value = true
        }
        field.assertIsNotFocused().assert(hasSetTextAction())
        ui.runOnIdle { assertNull(connection.get()); assertEquals(selection, state.selection) }
        field.performClick()
        ui.waitUntil(5_000) { connection.get() != null }
        ime {
            setSelection(text.length, text.length)
            commitText("!", 1)
            assertEquals("$text!", state.text.toString())
        }
    }

    @Test fun cjkPreeditAndCandidateConfirmationDoNotSendOrOverwriteMarkdown() {
        val original = "__记录__ 😀 "
        val state = TextFieldState(original)
        var sends = 0
        open(state) { sends++ }
        assertEquals(EditorInfo.IME_ACTION_NONE, attributes.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(attributes.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        var expected = original
        for ((reading, candidate) in listOf("pinyin" to "拼音", "にほん" to "日本", "ㅎ" to "한글")) {
            ime {
                beginBatchEdit()
                setComposingText(reading, 1)
                endBatchEdit()
                assertEquals(expected + reading, state.text.toString())
                assertEquals(TextRange(expected.length, expected.length + reading.length), state.composition)
                performEditorAction(EditorInfo.IME_ACTION_DONE)
                performEditorAction(EditorInfo.IME_ACTION_SEND)
                assertEquals(0, sends)
                commitText(candidate, 1)
                expected += candidate
                assertNull(state.composition)
                assertEquals(expected, state.text.toString())
            }
        }
        ime { commitText("\n", 1) }
        field.performKeyInput { pressKey(Key.Enter) }
        ui.runOnIdle { assertEquals(expected + "\n\n", state.text.toString()); assertEquals(0, sends) }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        ime {
            assertEquals(1, sends)
            performEditorAction(EditorInfo.IME_ACTION_NEXT)
            assertEquals(1, sends)
            performEditorAction(EditorInfo.IME_ACTION_SEND)
            assertEquals(1, sends)
        }
    }

    @Test fun rawEmojiSelectionSurvivesProjectionAndReplacementCanBeUndone() {
        val source = "__a😀b__ end"
        val state = TextFieldState(source)
        open(state)
        ime {
            assertEquals("a😀b end", visible())
            setSelection(1, 3) // Two UTF-16 units in the *displayed* string.
            assertEquals(TextRange(3, 5), state.selection)
            assertEquals(source, visible())
            assertEquals("😀", getSelectedText(0).toString())
            commitText("替换", 1)
            assertEquals("__a替换b__ end", state.text.toString())
        }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        ui.runOnIdle { assertEquals(source, state.text.toString()) }
    }

    @Test fun onlyCommittedSingleNewlineContinuesListAndUndoIncludesThePrefix() {
        val source = "- [x] 已做"
        val state = TextFieldState(source)
        open(state)
        ime {
            setComposingText("\n", 1)
            assertEquals(source + "\n", state.text.toString())
            assertEquals(TextRange(source.length, source.length + 1), state.composition)
            commitText("", 1)
            assertEquals(source, state.text.toString())
            beginBatchEdit(); commitText("\n", 1); endBatchEdit()
            assertEquals(source + "\n- [ ] ", state.text.toString())
        }
        field.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        ui.runOnIdle { assertEquals(source, state.text.toString()) }
        ime {
            commitText("\n- pasted\n- second", 1)
            assertEquals(source + "\n- pasted\n- second", state.text.toString())
        }
    }
}
