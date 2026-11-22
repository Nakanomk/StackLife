package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.AppLanguage
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.appearanceDifferences
import io.github.nakanomk.stacklife.planner.FrameSource
import io.github.nakanomk.stacklife.planner.frameLabel
import io.github.nakanomk.stacklife.planner.Destination
import io.github.nakanomk.stacklife.planner.appearanceLabel
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantLocaleTest {
    @Test fun keyedLocationsAndLabelsFollowLanguageWithoutChangingStoredTextOrCapabilities() {
        val previous = AppLanguage.locale
        try {
            AppLanguage.update(listOf(Locale.SIMPLIFIED_CHINESE))
            val page = AssistantPage.Week("Week 2 · 秋季", 3)
            val row = ConversationRow(scope = page.scopeName(), scopeKey = page.historyKey(), title = "保留 · Week 2")
            val graph = Destination.GRAPHS
            assertEquals("流程图", graph.label)
            assertEquals("警告", NoticeLevel.WARNING.label)
            val before = Appearance()
            val change = appearanceDifferences(before, before.copy(compact = true)).single()
            assertEquals("紧凑留白", change.label())

            AppLanguage.update(listOf(Locale.ENGLISH))
            assertEquals("Graphs", graph.label)
            assertEquals("Warning", NoticeLevel.WARNING.label)
            assertEquals("Compact spacing", change.label())
            assertEquals("compact", change.key)
            assertEquals("On", appearanceLabel(change.after))
            assertEquals("Queue saved before switching frames", frameLabel("切换栈帧前自动保存", FrameSource.SWAP))
            assertEquals("切换栈帧前自动保存", frameLabel("切换栈帧前自动保存", FrameSource.MANUAL))
            assertEquals(page, row.resumePage(AssistantPage.Queue))
            assertEquals("Week 2 · 秋季 · Week 3", row.displayScope())
            assertEquals("Week 2 · 秋季 · 第 3 周", row.scope)
            assertEquals("保留 · Week 2", row.title)
            assertEquals(row.scopeKey, page.historyKey())
            assertEquals(AssistantPage.Terms, historyPage(AssistantPage.CourseDraft("private-draft", "term").historyKey()))
        } finally {
            AppLanguage.update(listOf(previous))
        }
    }

    @Test fun labelOnlyLocationsReadEitherLanguageButShortIdsCannotAuthorizeEntities() {
        val week = AssistantPage.Week("term · 2026", 9)
        for ((label, expected) in listOf("当前队列" to AssistantPage.Queue, "Current queue" to AssistantPage.Queue,
            "term · 2026 · 第 9 周" to week, "term · 2026 · Week 9" to week,
            "当前任务 · 12345678" to AssistantPage.Terms, "Current task · 12345678" to AssistantPage.Terms)) {
            assertEquals(expected, ConversationRow(scope = label, title = "").resumePage(AssistantPage.Terms))
        }
    }
}
