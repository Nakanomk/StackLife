package io.github.nakanomk.stacklife.archive

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.nakanomk.stacklife.assistant.withChatDocument
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BackupScreenTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun readableKeyNeedsASecondChoiceAndOpeningTheBackupDoesNotReplaceUntilConfirmation() {
        BackupDeviceTest.Device().use { source -> BackupDeviceTest.Device().use { target ->
            val donor = ArchiveFixture(source); val targetStore = ArchiveFixture(target)
            val data = runBlocking {
                donor.apply(PlannerCommand.TaskAdd(TaskInput("incoming"))); targetStore.apply(PlannerCommand.TaskAdd(TaskInput("target")))
                donor.pack(setOf(BackupPart.QUEUE, BackupPart.STACK))
            }
            withChatDocument(target.target, "zip", "application/zip", data) { uri ->
                val launches = mutableListOf<String>()
                val owner = object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                            require(contract is ActivityResultContracts.OpenDocument); launches.add(contract.createIntent(target.target, input).action!!)
                            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                        }
                    }
                }
                ui.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { BackupScreen(targetStore.archive) {} } }
                fun action(label: String) {
                    val row = hasText(label) and hasClickAction()
                    ui.onNodeWithTag("backup-page").performScrollToNode(row); ui.onNode(row).performScrollTo().performClick()
                }
                fun dialog(label: String): SemanticsNodeInteraction {
                    val match = hasText(label) and hasAnyAncestor(isDialog())
                    ui.waitUntil(8_000) { ui.onAllNodes(match).fetchSemanticsNodes().isNotEmpty() }; return ui.onNode(match)
                }
                action(uiText("Export selected…", "导出所选分类…"))
                dialog(uiText("Save a readable backup?", "保存可读取的备份？")); assertTrue(launches.isEmpty())
                dialog(target.target.getString(io.github.nakanomk.stacklife.R.string.ui_cancel)).performClick()
                action(uiText("Open backup…", "打开备份文件…"))
                dialog(BackupPart.STACK.label()).performClick(); dialog(uiText("Review changes", "核对差异")).performClick()
                dialog(uiText("Replace selected categories?", "替换所选分类？"))
                assertEquals(listOf("target"), runBlocking { targetStore.workspace().queue.map { it.title } })
                assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT), launches)
                dialog(target.target.getString(io.github.nakanomk.stacklife.R.string.transfer_replace)).performClick()
                ui.waitUntil(8_000) { targetStore.archive.lastRestore.value != null }
                assertEquals(listOf("incoming"), runBlocking { targetStore.workspace().queue.map { it.title } })
            }
        } }
    }
}
