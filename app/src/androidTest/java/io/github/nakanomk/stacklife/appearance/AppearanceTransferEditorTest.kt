package io.github.nakanomk.stacklife.appearance

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.workspace.SettingsHelpHost
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceTransferEditorTest {
    private val fixture = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(ui)

    @Test fun createDocumentThenOpenDocumentShowsActualDifferencesAndOnlyConfirmationSaves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "share-editor-").toFile()
        val file = File(folder, "appearance.zip"); val uri = Uri.fromFile(file)
        val store = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { emptySet() }
        val launches = mutableListOf<String>()
        // Stand in only for the system picker's choice; real SAF contracts, resolver streams and Planner.
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    val action = requireNotNull(contract.createIntent(context, input).action)
                    require(contract is ActivityResultContracts.OpenDocument || contract is ActivityResultContracts.CreateDocument)
                    launches += action
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
        }
        fun label(id: Int) = context.getString(id)
        fun action(id: Int) {
            val row = hasText(label(id)) and hasClickAction()
            ui.onNodeWithTag("appearance-scroll").performScrollToNode(row)
            ui.onNode(row).performScrollTo().performClick()
        }
        fun dialog() = hasText(label(R.string.transfer_review)) and hasAnyAncestor(isDialog())
        fun waitFor(matcher: SemanticsMatcher) = ui.waitUntil(7_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val exported = Appearance(theme = ThemeMode.DARK); val other = exported.copy(theme = ThemeMode.LIGHT)
        fun set(a: Appearance) = runBlocking { fixture.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(a))) }
        fun saved() = runBlocking { (fixture.api.observe(PlannerQuery.Settings).first() as PolicyView).appearance }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        try {
            set(exported)
            ui.setContent {
                val settings by remember { fixture.api.observe(PlannerQuery.Settings) }.collectAsState(initial = PolicyView(RestorePolicy.PREPEND, exported))
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalBackgroundStore provides store) {
                    SettingsHelpHost { AppearanceEditor((settings as PolicyView).appearance, AppearanceGroup.MATERIAL,
                        viewModel<WorkspaceViewModel> { WorkspaceViewModel(fixture.api) }, planner = fixture.api) { closed.set(true) } }
                }
            }
            action(R.string.transfer_export); waitFor(hasText(label(R.string.transfer_exported))); assertTrue(file.length() > 0)
            set(other); val before = runBlocking { fixture.state().revision }
            action(R.string.transfer_import); waitFor(dialog()); assertEquals(other, saved())
            ui.onNode(hasText(label(R.string.ui_cancel)) and hasAnyAncestor(isDialog())).performClick()
            ui.waitUntil(7_000) { ui.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
            assertEquals(before, runBlocking { fixture.state().revision }); assertFalse(closed.get())
            action(R.string.transfer_import); waitFor(dialog()); ui.onNodeWithText(label(R.string.transfer_replace)).performClick()
            ui.waitUntil(7_000) { closed.get() }; assertEquals(exported, saved())
            assertEquals(before + 1, runBlocking { fixture.state().revision })
            assertEquals(listOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT), launches)
        } finally { folder.deleteRecursively() }
    }
}
