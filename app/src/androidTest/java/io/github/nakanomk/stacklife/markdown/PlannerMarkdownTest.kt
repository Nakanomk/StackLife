package io.github.nakanomk.stacklife.markdown

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import java.net.InetAddress
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlannerMarkdownTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private fun read(source: String) {
        val platform = object : UriHandler { override fun openUri(uri: String) { opened.add(uri) } }
        ui.setContent { MaterialTheme { CompositionLocalProvider(LocalUriHandler provides platform) {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) { PlannerMarkdown(source) }
        } } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Reading").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun follow(label: String) {
        val link = ui.onNodeWithText(label).performScrollTo()
        val layout = mutableListOf<TextLayoutResult>()
        link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        link.performTouchInput { click(layout.single().getBoundingBox(1).center) }
    }

    @Test fun linksOnlyOpenOnTapAndRemoteImagesDoNotFetch() {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            read("# Reading\n\n![remote](${server.url("/picture.png")})\n\n[Blocked](file:///never-open.txt)\n\n[Web](https://example.org/a)\n\n[Mail](mailto:example@example.org)")
            ui.runOnIdle { assertTrue(opened.isEmpty()) }
            follow("Blocked")
            ui.onNodeWithText(ui.activity.getString(R.string.markdown_link_unavailable)).assertExists()
            ui.runOnIdle { assertTrue(opened.isEmpty()) }
            follow("Web"); follow("Mail")
            ui.runOnIdle { assertEquals(listOf("https://example.org/a", "mailto:example@example.org"), opened) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun wideTableAndCodeScrollInsideReaderAndClipboardKeepsIndentation() {
        val code = "    first\n        " + "wide_code_".repeat(12) + "\n"
        read("# Reading\n\n- [X] done\n- [ ] later\n\n```text\n${code}```\n\n| One | Two | Three | Four |\n|---|---|---|---|\n| 1 | 2 | 3 | 4 |")
        val checkbox = ui.onAllNodes(isToggleable(), useUnmergedTree = true)
        checkbox[0].assertIsOn().assertHasNoClickAction(); checkbox[1].assertIsOff().assertHasNoClickAction()
        val horizontal = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
        horizontal.assertCountEquals(2)
        for (node in horizontal.fetchSemanticsNodes()) {
            assertTrue(node.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
            assertTrue(node.size.width <= with(ui.density) { 280.dp.roundToPx() })
        }
        ui.onNodeWithText(ui.activity.getString(R.string.markdown_copy_code)).performScrollTo().performClick()
        val clipboard = ui.activity.getSystemService(ClipboardManager::class.java)
        ui.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == code }
    }
}
