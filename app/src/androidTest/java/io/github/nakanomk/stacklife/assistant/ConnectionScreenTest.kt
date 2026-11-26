package io.github.nakanomk.stacklife.assistant

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nakanomk.stacklife.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val store by lazy { ConnectionStore(ui.activity, "provider-test-${UUID.randomUUID()}") }
    private val closed = AtomicBoolean()
    private fun field(tag: String) = ui.onNodeWithTag("provider-$tag")
    private fun option(name: String) = ui.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    private fun show(frame: Modifier = Modifier, connectionStore: ConnectionStore = store) {
        ui.setContent { Box(frame, propagateMinConstraints = true) { ConnectionScreen(connectionStore) { closed.set(true) } } }
        ui.waitUntil(timeoutMillis = 5_000) {
            ui.onAllNodes(hasText("自定义") and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun select(name: String) {
        option(name).performScrollTo().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        option(name).assertIsSelected()
        field("key").assertIsFocused()
    }

    private fun save() {
        field("save").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(timeoutMillis = 5_000) { closed.get() }
    }

    @After fun clear() = runBlocking { store.clear() }

    @Test fun oldEditorCannotReuseOrDeleteAReplacedSameOriginCredential() {
        val name = "editor-${UUID.randomUUID()}"
        val context = ui.activity.applicationContext
        val db = io.github.nakanomk.stacklife.storage.PlannerDatabase.open(context, "$name.db")
        val live = ConnectionStore(context, name, db.backupDeviceDao())
        try {
            val base = "https://same-origin.invalid/v1"
            runBlocking { live.save(base, "original", "first-key") }
            show(connectionStore = live)
            field("model").performTextReplacement("stale form")
            runBlocking { live.save(base, "replacement", "new-key") }
            field("save").performScrollTo().performClick()
            val stale = io.github.nakanomk.stacklife.i18n.uiText(
                "The service settings have changed. Reopen this page before making another change.", "连接配置已变更，请重新打开此页再修改。")
            ui.waitUntil { ui.onAllNodesWithText(stale).fetchSemanticsNodes().isNotEmpty() }
            val remove = ui.activity.getString(R.string.assistant_remove_service)
            ui.onNodeWithText(remove).performScrollTo().performClick()
            ui.onNodeWithText(ui.activity.getString(R.string.assistant_remove)).performClick()
            ui.waitUntil { ui.onAllNodesWithText(stale).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(closed.get())
            runBlocking { assertEquals("replacement", live.profile()?.model); assertTrue(live.load()?.key == "new-key") }
        } finally {
            runBlocking { live.clear() }; db.close(); context.deleteDatabase("$name.db")
            java.io.File(context.noBackupFilesDir, "$name.generations").delete()
        }
    }

    @Test fun privateFieldsScrollInASeparateViewportBelowTheSecureHeader() {
        runBlocking { store.save("https://sample.invalid/v1", "sample-model", UUID.randomUUID().toString()) }
        show(Modifier.height(320.dp))
        val inOptions = hasAnyAncestor(hasTestTag("provider-options"))
        ui.onAllNodes(inOptions and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(ServicePreset.entries.size)
        for (name in listOf("url", "model", "key", "save")) {
            ui.onNode(inOptions and hasTestTag("provider-$name")).assertDoesNotExist()
        }
        val title = ui.onNodeWithText(ui.activity.getString(R.string.assistant_service_title))
        val viewport = ui.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("provider-save")))
        val heading = title.fetchSemanticsNode().boundsInRoot
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.top >= heading.bottom)
        for (name in listOf("url", "model", "key", "save")) {
            val input = field(name).performScrollTo().assertIsDisplayed()
            assertTrue(input.fetchSemanticsNode().boundsInRoot.top >= heading.bottom)
            assertEquals(heading, title.fetchSemanticsNode().boundsInRoot)
        }
        val offset = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        ui.runOnIdle {
            assertTrue("Fixture must exercise actual scrolling", offset.value() > 0f)
            assertNotEquals(0, ui.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test fun radioSelectionSetsOfficialDefaultsAndNeverCarriesAnUnsavedKey() {
        show()
        for ((label, base, model) in listOf(
            Triple("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
            Triple("Claude · Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
            Triple("GPT · OpenAI", "https://api.openai.com/v1", "gpt-5.4-mini"),
        )) {
            select(label)
            ui.onNodeWithText(base).assertExists()
            ui.onNodeWithText(model).assertExists()
            field("url").assertDoesNotExist()
            field("model").assertDoesNotExist()
            field("save").assertIsNotEnabled()
            field("key").performTextReplacement(UUID.randomUUID().toString())
            field("save").assertIsEnabled()
            assertNull(runBlocking { store.profile() })
        }
        val replacement = UUID.randomUUID().toString()
        ui.onNodeWithText("更改模型").performScrollTo().performClick()
        field("model").performTextReplacement("my-enabled-model")
        field("images").performScrollTo().assertIsOff().performClick()
        field("images").assertIsOn()
        field("model").performTextReplacement("another-model")
        field("images").assertIsOff()
        field("model").performTextReplacement("my-enabled-model")
        field("images").performScrollTo().assertIsOff().performClick()
        field("key").performTextReplacement(replacement)
        save()
        val persisted = runBlocking { store.load() }!!
        assertEquals(ServiceProfile("https://api.openai.com/v1", "my-enabled-model", "https://api.openai.com", supportsImages = true), persisted.profile)
        assertEquals(replacement, persisted.key)
    }

    @Test fun customProfileRoundTripsAndChangingOriginRequiresFreshInput() {
        val base = "https://api.openai.com:8443/private/gateway"
        val original = UUID.randomUUID().toString()
        val previous = runBlocking { store.save(base, "legacy", original) }
        show()
        option("自定义").assertIsSelected()
        field("url").assertTextContains(base)
        field("model").assertTextContains("legacy")
        field("save").assertIsEnabled()
        field("key").performTextReplacement(UUID.randomUUID().toString())
        field("url").performTextReplacement("https://another.invalid/v1")
        field("save").assertIsNotEnabled()
        select("GPT · OpenAI")
        field("save").assertIsNotEnabled()
        assertEquals(previous, runBlocking { store.profile() })
        select("自定义")
        field("url").assertTextContains(base)
        field("model").performTextReplacement("changed")
        save()
        val persisted = runBlocking { store.load() }!!
        assertEquals(previous.copy(model = "changed"), persisted.profile)
        assertEquals(original, persisted.key)
    }
}
