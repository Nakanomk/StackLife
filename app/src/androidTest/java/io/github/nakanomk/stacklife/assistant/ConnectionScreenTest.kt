package io.github.nakanomk.stacklife.assistant

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun show() {
        val connectionStore = store
        ui.setContent { ConnectionScreen(connectionStore) { closed.set(true) } }
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
        field("key").performTextReplacement(replacement)
        save()
        val persisted = runBlocking { store.load() }!!
        assertEquals(ServiceProfile("https://api.openai.com/v1", "my-enabled-model", "https://api.openai.com"), persisted.profile)
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
