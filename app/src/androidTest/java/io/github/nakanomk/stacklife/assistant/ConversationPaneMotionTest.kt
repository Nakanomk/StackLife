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

    @Test fun listThreadAndBackKeepTheSameDialogAndTheRealScrolledRow() {
        val history = store.db.conversationHistory()
        val oldest = runBlocking {
            val first = history.start("Course", "Local conversation 1")
            for (n in 2..30) history.start("Course", "Local conversation $n")
            first
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ui.setContent {
            val assistant = viewModel { AssistantModel(store.api, ConnectionStore(instrumentation.targetContext, "pane-motion"), OpenAiTransport(), history) }
            StackLifeApp(viewModel { WorkspaceViewModel(store.api) }, assistant)
        }
        fun tag(value: String): SemanticsNodeInteraction {
            ui.waitUntil(10_000) { ui.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
            return ui.onNodeWithTag(value)
        }
        tag("assistant-history").performClick()
        val rowTag = "assistant-history-thread-$oldest"
        tag("assistant-history-list").performScrollToNode(hasTestTag(rowTag))
        val row = tag(rowTag)
        val position = row.fetchSemanticsNode().boundsInRoot
        val dialog = ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id
        row.performClick()
        tag("assistant-history-messages").assertIsDisplayed()
        assertEquals(dialog, ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        assertEquals(position, tag(rowTag).fetchSemanticsNode().boundsInRoot)
        assertEquals(dialog, ui.onNode(isDialog(), useUnmergedTree = true).fetchSemanticsNode().id)
    }
}
