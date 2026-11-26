package io.github.nakanomk.stacklife.assistant

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.acceptance.exerciseSheetAnchors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPaneMotionTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)

    @Test fun readingEarlierMessagesDoesNotMoveUntilExplicitJumpOrOwnSend() {
        val rows = androidx.compose.runtime.mutableStateOf((1L..45L).map { ChatLine(it, HistoryRole.ASSISTANT, "Message $it") })
        val history = store.db.conversationHistory()
        val images = AttachmentStore(InstrumentationRegistry.getInstrumentation().targetContext, { history.attachmentIds().toSet() },
            "transcript-${java.util.UUID.randomUUID()}")
        ui.setContent { ChatMessages(rows.value, images, androidx.compose.ui.Modifier, "transcript", live = true) }
        fun awaitText(s: String): SemanticsNodeInteraction {
            ui.waitUntil(5_000) { ui.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty() }
            return ui.onNodeWithText(s)
        }
        val list = ui.onNodeWithTag("transcript")
        awaitText("Message 45")
        list.performScrollToNode(hasText("Message 1"))
        awaitText("Message 1").assertIsDisplayed()
        ui.runOnIdle { rows.value += ChatLine(46, HistoryRole.ASSISTANT, "New reply") }
        awaitText("新消息")
        awaitText("Message 1").assertIsDisplayed()
        awaitText("新消息").performClick()
        awaitText("New reply").assertIsDisplayed()
        list.performScrollToNode(hasText("Message 1"))
        ui.runOnIdle { rows.value += ChatLine(47, HistoryRole.USER, "My new turn") }
        awaitText("My new turn").assertIsDisplayed()
    }

    @Test fun listThreadAndBackKeepTheSameDialogAndTheRealScrolledRow() {
        val history = store.db.conversationHistory()
        val oldest = runBlocking {
            val first = history.sendUser(null, "Course", "Local conversation 1", null, emptyList()).conversationId
            for (n in 2..30) history.sendUser(null, "Course", "Local conversation $n", null, emptyList())
            first
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ui.setContent {
            val assistant = viewModel { AssistantModel(store.api, ConnectionStore(instrumentation.targetContext, "pane-motion"), OpenAiTransport(), history,
                AttachmentStore(instrumentation.targetContext, { history.attachmentIds().toSet() }, "pane-images-${java.util.UUID.randomUUID()}")) }
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, assistant)
        }
        fun tag(value: String): SemanticsNodeInteraction {
            ui.waitUntil(10_000) { ui.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
            return ui.onNodeWithTag(value)
        }
        tag("assistant-history").performClick()
        tag("assistant-history-list").assertIsDisplayed()
        ui.exerciseSheetAnchors("history")
        val rowTag = "assistant-history-thread-$oldest"
        tag("assistant-history-list").performScrollToNode(hasTestTag(rowTag))
        // Scrolling to an old thread gives the native nested scroll its expansion first.
        // Use the accessibility equivalent if the viewport fit the requested item directly.
        val handle = tag("planner-sheet-handle")
        val expand = androidx.compose.ui.semantics.SemanticsActions.Expand
        if (handle.fetchSemanticsNode().config.contains(expand)) handle.performSemanticsAction(expand)
        val row = tag(rowTag)
        val position = row.fetchSemanticsNode().boundsInRoot
        val dialog = ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id
        row.performClick()
        tag("assistant-history-messages").assertIsDisplayed()
        val title = ui.activity.getString(io.github.nakanomk.stacklife.R.string.assistant_transcript)
        val transcript = tag("assistant-history-messages").fetchSemanticsNode().boundsInRoot
        val message = ui.onNode(hasText("Local conversation 1") and hasAnyAncestor(hasTestTag("assistant-history-messages")))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("A short thread must start below its header, with the spare space below it",
            message.top - transcript.top < transcript.bottom - message.bottom)
        val description = ui.activity.getString(io.github.nakanomk.stacklife.R.string.setting_info, title)
        ui.onNodeWithContentDescription(description).performClick()
        val noService = ui.activity.getString(io.github.nakanomk.stacklife.R.string.assistant_configure_to_continue)
        val popup = hasText(noService) and hasAnyAncestor(isPopup())
        ui.waitUntil(5_000) { ui.onAllNodes(popup).fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        ui.waitUntil(5_000) { ui.onAllNodes(popup).fetchSemanticsNodes().isEmpty() }
        tag("assistant-history-messages").assertIsDisplayed()
        assertEquals(dialog, ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id)
        ui.exerciseSheetAnchors("history-thread", startsFull = true)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertEquals(position, tag(rowTag).fetchSemanticsNode().boundsInRoot)
        assertEquals(dialog, ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id)
    }
}
