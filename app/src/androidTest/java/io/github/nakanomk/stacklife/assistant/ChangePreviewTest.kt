package io.github.nakanomk.stacklife.assistant

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.PlannerCommand.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChangePreviewTest {
    @get:Rule val store = WorkspaceFixture()
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun transactionIsCoalescedAndTheRecordedDiagramStillShowsDeletedDependenciesAfterReopen() {
        val changes = runBlocking {
            val setup = store.proposal(TaskAdd(TaskInput("Editing", "**private original**", ref = "t")),
                GraphCreate("Release", "g"), GraphPatch("@g", listOf(
                    GraphEdit.AddNode("Review", x = 12f, y = 32f, ref = "a"), GraphEdit.AddNode("Ship", x = 260f, y = 90f, ref = "b"),
                    GraphEdit.AddEdge("@a", "@b"))))
            assertTrue(store.api.commit(setup.proposalId) is CommitResult.Applied)
            val ids = setup.refs
            val ready = store.proposal(TaskEdit(ids.getValue("t"), title = "intermediate"), TaskEdit(ids.getValue("t"), title = "Reviewed", notes = "# Final note"),
                GraphPatch(ids.getValue("g"), listOf(GraphEdit.RemoveEdge(ids.getValue("a"), ids.getValue("b")), GraphEdit.MoveNode(ids.getValue("b"), 390f, 125f))))
            assertEquals(2, ready.changes.size)
            val json = Json.encodeToString(ChatEvent(ProposalStatus.PROPOSED, ready.changes))
            assertFalse(json.contains(ready.proposalId)); assertFalse(json.contains("intermediate"))
            assertTrue(store.api.commit(ready.proposalId) is CommitResult.Applied)
            store.apply(GraphRemove(ids.getValue("g")), TaskRemove(ids.getValue("t"))); store.reopen()
            Json.decodeFromString<ChatEvent>(json).changes
        }
        val graph = changes.single { it.after is GraphSnapshot }
        val task = changes.single { it.after is TaskSnapshot }
        assertEquals("**private original**", (task.before as TaskSnapshot).notes)
        assertEquals("# Final note", (task.after as TaskSnapshot).notes)
        ui.setContent {
            LazyColumn(Modifier.fillMaxSize().testTag("changes")) {
                items(listOf(graph, task), key = { it.entityId }) { ChangePreview(it) }
            }
        }
        ui.onAllNodesWithText("展开全图与有序清单")[0].performClick()
        ui.onNodeWithTag("changes").performScrollToNode(hasText("「Review」是「Ship」的前置"))
        ui.onNodeWithText("「Review」是「Ship」的前置").assertIsDisplayed()
        val inst = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("captureM5") == "yes") {
            val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot()); try {
                java.io.File(inst.targetContext.getExternalFilesDir(null), "u3-graph-comparison.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        ui.onNodeWithTag("changes").performScrollToNode(hasText("Final note")); ui.onNodeWithText("Final note").assertIsDisplayed()
        ui.onNodeWithTag("assistant-apply").assertDoesNotExist()
    }
}
