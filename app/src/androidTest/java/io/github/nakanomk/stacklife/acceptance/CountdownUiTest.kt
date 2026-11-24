package io.github.nakanomk.stacklife.acceptance

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.appearance.LocalBackgroundStore
import io.github.nakanomk.stacklife.appearance.redPixels
import io.github.nakanomk.stacklife.appearance.withSelectedPhoto
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.planner.*
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CountdownUiTest {
    private val store = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(store).around(ui)
    private fun list() = runBlocking { store.api.observe(PlannerQuery.Countdowns).first() as CountdownsView }
    private fun attach() = ui.activityRule.scenario.onActivity { activity ->
        activity.setContent { StackLifeApp(viewModel { WorkspaceViewModel(store.api) }) }
    }
    private fun await(m: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(10_000) { ui.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }; return ui.onNode(m)
    }
    private fun tag(s: String) = await(hasTestTag(s))
    private fun back() = await(hasContentDescription("返回")).performClick()
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureCountdown") != "yes") return
        ui.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation(); inst.uiAutomation.waitForIdle(450, 5000)
        val image = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { File(inst.targetContext.getExternalFilesDir(null), "u7-countdown-$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    @Test fun previewDoesNotWriteUntilSavedAndDetailSwipesEditTheVisibleIdentity() {
        val now = LocalDate.now()
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("不受影响的任务")),
            PlannerCommand.CountdownAdd("阅读开始", now.minusDays(30).toEpochDay(), CountdownMode.ACCUMULATE),
            PlannerCommand.CountdownAdd("展览开幕", now.plusDays(10).toEpochDay())) }
        val queue = runBlocking { store.state().queue }
        val before = list(); val (reading, exhibit) = before.items
        attach(); tag("nav:countdown").performClick(); tag("countdown-add").performClick()
        tag("countdown-name").performTextReplacement("未保存的日期"); tag("countdown-preview").assertTextContains("未保存的日期")
        assertEquals(before, list()); back(); tag("countdown-add").performClick()
        tag("countdown-name").performTextReplacement("研讨会开幕")
        tag("countdown-date").performScrollTo().performClick()
        tag("wheel-year").performSemanticsAction(SemanticsActions.SetProgress) { it(2028f) }
        tag("wheel-month").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        tag("wheel-day").performSemanticsAction(SemanticsActions.SetProgress) { it(29f) }
        tag("wheel-confirm").performClick()
        await(hasText("置顶") and hasClickAction()).performScrollTo().performClick()
        tag("countdown-preview").performScrollTo().assertTextContains("研讨会开幕").assertTextContains("置顶"); capture("editor")
        ui.activityRule.scenario.recreate(); attach()
        tag("countdown-name").assertTextContains("研讨会开幕"); assertEquals(before, list())
        tag("countdown-save").performClick(); tag("countdown-list")
        val added = list().items.first(); assertTrue(added.pinned); assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), added.targetDay)
        assertEquals(before.revision + 1, list().revision); capture("list")
        await(hasContentDescription("排序")).performClick()
        ui.tapAboveDock(tag("countdown-list"), hasTestTag("countdown-move:${reading.id}:1"))
        ui.waitUntil(10_000) { list().items.map { it.id } == listOf(added.id, exhibit.id, reading.id) }
        await(hasContentDescription("完成")).performClick()
        tag("countdown-list").performScrollToNode(hasTestTag("open-countdown:${added.id}")); tag("open-countdown:${added.id}").performClick()
        tag("countdown-detail:${added.id}").assertIsDisplayed(); capture("detail")
        tag("countdown-detail-pager").performTouchInput { swipeUp() }
        tag("countdown-detail:${exhibit.id}").assertIsDisplayed(); tag("countdown-edit").performClick()
        tag("countdown-name").assertTextContains(exhibit.title).performTextReplacement("展览新日期")
        await(hasText("置顶") and hasClickAction()).performScrollTo().performClick()
        val rev = list().revision
        tag("countdown-save").performClick(); tag("countdown-detail:${exhibit.id}").assertTextContains("展览新日期")
        assertEquals(rev + 1, list().revision); assertTrue(list().items.single { it.id == exhibit.id }.pinned)
        tag("countdown-edit").performClick()
        val delete = hasTestTag("countdown-delete")
        val form = ui.onNode(hasScrollAction() and hasAnyDescendant(delete))
        ui.tapAboveDock(form, delete)
        await(hasText("取消") and hasAnyAncestor(isDialog())).performClick(); assertEquals(3, list().items.size)
        ui.tapAboveDock(form, delete); tag("countdown-confirm-delete").performClick()
        await(hasText("倒数日已不存在。")); assertEquals(listOf(added.id, reading.id), list().items.map { it.id })
        assertEquals(queue, runBlocking { store.state().queue })
    }

    private fun bounds(tag: String) = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun controlledBack(block: (DirectNavigationEventInput) -> Unit) {
        val input = DirectNavigationEventInput()
        ui.runOnIdle { ui.activity.navigationEventDispatcher.addInput(input) }
        ui.mainClock.autoAdvance = false
        try { block(input) } finally {
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.navigationEventDispatcher.removeInput(input) }
        }
    }
    private fun start(input: DirectNavigationEventInput) {
        ui.runOnUiThread { input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0f)) }
        ui.mainClock.advanceTimeBy(64)
    }
    private fun seek(input: DirectNavigationEventInput, fraction: Float) {
        ui.runOnUiThread { input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = fraction)) }
        ui.mainClock.advanceTimeBy(64)
    }
    private fun expectWindow(full: Rect, landing: Rect, frame: Rect, progress: Float) {
        val expected = androidx.compose.ui.geometry.lerp(full, landing, progress)
        assertEquals(expected.left, frame.left, 3f); assertEquals(expected.right, frame.right, 3f)
        assertEquals(expected.top, frame.top, 3f); assertEquals(expected.bottom, frame.bottom, 3f)
    }

    @Test fun pagedDetailSeeksToBReversesAndReturnsByToolbar() {
        runBlocking { store.apply(PlannerCommand.CountdownAdd("A", 20_000), PlannerCommand.CountdownAdd("B", 20_001)) }
        val (a, b) = list().items
        attach(); tag("nav:countdown").performClick()
        val landing = tag("open-countdown:${b.id}").fetchSemanticsNode().boundsInRoot
        tag("open-countdown:${a.id}").performClick()
        tag("countdown-detail-pager").performTouchInput { swipeUp() }
        tag("countdown-detail:${b.id}").assertIsDisplayed()
        val route = "route:countdown:${a.id}"
        val full = bounds(route)
        val edit = bounds("countdown-edit")
        tag("countdown-edit").performClick()
        val editor = "route:countdown:edit:${b.id}"
        val form = tag(editor).fetchSemanticsNode().boundsInRoot
        controlledBack { input ->
            start(input); seek(input, .65f); expectWindow(form, edit, bounds(editor), .65f)
            ui.runOnUiThread { input.backCompleted() }; ui.mainClock.advanceTimeBy(600)
            start(input); seek(input, .72f); expectWindow(full, landing, bounds(route), .72f)
            seek(input, .35f); expectWindow(full, landing, bounds(route), .35f)
            ui.runOnUiThread { input.backCancelled() }; ui.mainClock.advanceTimeBy(600)
            assertEquals(full, bounds(route)); ui.onNodeWithTag("countdown-detail:${b.id}").assertIsDisplayed()
            val click = ui.onNode(hasContentDescription("返回") and hasAnyAncestor(hasTestTag(route)))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            ui.runOnUiThread { click() }; ui.mainClock.advanceTimeBy(160)
            val mid = bounds(route)
            val travel = (mid.height - full.height) / (landing.height - full.height)
            assertTrue(travel > .01f && travel < .99f); expectWindow(full, landing, mid, travel)
            ui.mainClock.advanceTimeBy(600)
        }
        assertEquals(landing, bounds("open-countdown:${b.id}"))
    }

    @Test fun hiddenCountdownFeatureReturnsNewDraftToItsOwnAddButton() {
        runBlocking { store.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(
            Appearance(start = Destination.WORKSPACE, visible = setOf(Destination.WORKSPACE), glass = false)))) }
        attach(); await(hasContentDescription("设置") and hasClickAction()).performClick()
        val jump = hasTestTag("open-feature:countdown")
        ui.onNode(hasScrollToIndexAction()).performScrollToNode(jump); await(jump).performClick()
        val button = tag("countdown-add").fetchSemanticsNode().boundsInRoot
        tag("countdown-add").performClick(); tag("countdown-name").performTextReplacement("本次草稿")
        val route = "route:countdown:new"
        val full = bounds(route)
        controlledBack { input ->
            start(input); seek(input, .5f); expectWindow(full, button, bounds(route), .5f)
            ui.runOnUiThread { input.backCancelled() }; ui.mainClock.advanceTimeBy(600)
            ui.onNodeWithTag("countdown-name").assertTextContains("本次草稿")
            start(input); seek(input, .6f)
            ui.runOnUiThread { input.backCompleted() }; ui.mainClock.advanceTimeBy(600)
        }
        assertEquals(button, bounds("countdown-add")); assertTrue(list().items.isEmpty())
        back(); await(jump).assertIsDisplayed()
    }

    @Test fun offscreenAndDeletedPagedItemLeaveAtEdgeWithoutUsingFirstCard() {
        runBlocking { store.apply(*(1..12).map { PlannerCommand.CountdownAdd("日期 $it", 20_000L + it) }.toTypedArray()) }
        val cards = list().items
        val a = cards[0]; val b = cards[1]
        attach(); tag("nav:countdown").performClick(); tag("open-countdown:${a.id}").performClick()
        tag("countdown-detail-pager").performTouchInput { swipeUp() }
        tag("countdown-detail:${b.id}").assertIsDisplayed()
        runBlocking { store.apply(PlannerCommand.CountdownOrder(false, cards.filterNot { it.id == b.id }.map { it.id } + b.id)) }
        ui.waitForIdle()
        val route = "route:countdown:${a.id}"
        val full = bounds(route)
        fun checkEdge(cancel: Boolean) = controlledBack { input ->
            start(input); seek(input, .5f)
            val frame = bounds(route)
            assertEquals(full.top, frame.top, 1f); assertEquals(full.bottom, frame.bottom, 1f)
            assertEquals(full.left + full.width * .5f, frame.left, 3f)
            ui.runOnUiThread { if (cancel) input.backCancelled() else input.backCompleted() }; ui.mainClock.advanceTimeBy(600)
        }
        checkEdge(true)
        runBlocking { store.apply(PlannerCommand.CountdownRemove(b.id)) }
        await(hasText("倒数日已不存在。")); checkEdge(false)
        tag("open-countdown:${a.id}").assertIsDisplayed()
    }

    @Test fun sameTitleCourseOpensSecondWeekAndRestoresThatSheetAfterCancelAndSave() {
        val term = TermSpec("十二月课程", LocalDate.of(2026, 11, 30), 2, BellSchedule.SUMMER)
        val series = ManualSeries(CoursePattern("实验", "不复制的教室", 4, 1, 2), setOf(1, 2))
        runBlocking { store.apply(PlannerCommand.ManualCourse(term.id, null, series, term)) }
        val query = PlannerQuery.Week(term.id, 2)
        fun occurrences() = runBlocking { (store.api.observe(query).first() as CourseWeek).classes }
        val original = occurrences()
        attach(); tag("nav:courses").performClick()
        tag("course-week")
        if (ui.onAllNodes(hasTestTag("course-next") and isEnabled()).fetchSemanticsNodes().isNotEmpty()) tag("course-next").performClick()
        tag("course-week").assertTextContains("第 2 周")
        tag("course-options").performClick(); tag("course-view-toggle").performClick()
        tag("week-course:0").performClick()
        fun openForm() {
            tag("occurrence-add-countdown").performScrollTo().performClick()
            tag("countdown-name").assertTextContains("实验")
            tag("countdown-date").assertTextContains("2026-12-10")
            ui.onAllNodes(isDialog()).assertCountEquals(0)
        }
        openForm(); ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
        tag("course-detail").assertIsDisplayed(); assertTrue(list().items.isEmpty())
        openForm(); tag("countdown-name").performTextReplacement("第二次实验")
        tag("countdown-save").performClick(); tag("course-detail").assertIsDisplayed()
        assertEquals(original, occurrences()); val saved = list().items.single()
        assertEquals("第二次实验", saved.title); assertEquals(LocalDate.of(2026, 12, 10).toEpochDay(), saved.targetDay)
        runBlocking { store.apply(PlannerCommand.ManualCourse(term.id, series, null)) }
        ui.activityRule.scenario.close(); store.reopen()
        assertEquals(saved, list().items.single())
    }

    @Test fun movedOvernightOccurrenceUsesActualStartAndSavesOnceWithoutChangingSchedule() {
        val zone = ZoneId.systemDefault(); val today = LocalDate.now(zone)
        val original = today.minusDays(2).toEpochDay(); val moved = today.minusDays(1)
        val start = moved.atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val interval = ExactInterval(start, start + 60 * 60_000L)
        runBlocking { store.apply(PlannerCommand.TaskAdd(TaskInput("夜间交接", "私密备注", 60, "task")), PlannerCommand.SetSchedule("@task",
            SchedulePattern.Daily(listOf(DailySegment(original, 600, zone.id, 60, original))))) }
        val id = runBlocking { store.state().queue.single().id }
        val occurrence = OccurrenceKey.Schedule(id, original)
        val subject = ReminderSubject.Occurrence(occurrence)
        val policy = ReminderPolicy(false, listOf(ReminderRule("private-alert", ReminderWhen.BeforeStart(30_000L))))
        runBlocking { store.apply(PlannerCommand.SetOccurrenceOverride(occurrence, EditScope.THIS, interval), PlannerCommand.SetReminders(subject, policy)) }
        fun actual() = runBlocking { (store.api.observe(PlannerQuery.Occurrence(occurrence, zone)).first() as OccurrenceView).occurrence }
        val before = actual(); val revision = list().revision
        attach(); tag("day-view").performClick(); tag("day-view-events").performClick()
        tag("block:${occurrence.storageKey()}").performScrollTo().performClick()
        // An unsubmitted change belongs to the schedule form, not to the countdown seed.
        val unsavedDate = today.plusDays(6)
        ui.chooseDate("slot:date", unsavedDate)
        tag("occurrence-add-countdown").performScrollTo().performClick()
        tag("countdown-name").assertTextContains("夜间交接"); tag("countdown-date").assertTextContains(moved.toString())
        ui.activityRule.scenario.recreate(); attach()
        tag("countdown-name").assertTextContains("夜间交接"); assertTrue(list().items.isEmpty())
        val save = tag("countdown-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        ui.runOnUiThread { save(); save() }
        tag("slot:date").assertTextContains(unsavedDate.toString(), substring = true)
        assertEquals(before, actual()); assertEquals(revision + 1, list().revision)
        val value = list().items.single(); assertEquals("夜间交接", value.title); assertEquals(moved.toEpochDay(), value.targetDay)
        assertEquals(policy, runBlocking { (store.api.observe(PlannerQuery.Reminders(subject)).first() as ReminderView).policy })
        ui.activityRule.scenario.close(); store.reopen()
        assertEquals(value, list().items.single()); assertEquals(before, actual())
    }

    @Test fun todayCoursePanelUsesTheCourseDisplayDateWithoutSwitchingTabs() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val weekday = today.dayOfWeek.value
        val term = TermSpec("本周实验", today.minusDays(weekday - 1L), 1, BellSchedule.SUMMER)
        val classTime = term.meeting(1, weekday, "早课", "不复制的教室", 1, 2)
        val localDay = Instant.ofEpochMilli(classTime.startMillis).atZone(zone).toLocalDate()
        runBlocking { store.apply(PlannerCommand.CourseImport(store.api.stageCourseImport(term, listOf(classTime)))) }
        attach(); tag("day-view").performClick(); tag("day-view-events").performClick()
        if (localDay != today) await(hasContentDescription(if (localDay < today) "前一天" else "后一天")).performClick()
        tag("day-course").performScrollTo().performClick()
        tag("occurrence-add-countdown").performScrollTo().performClick()
        tag("countdown-name").assertTextContains("早课")
        tag("countdown-date").assertTextContains(Instant.ofEpochMilli(classTime.startMillis).atZone(campusZone).toLocalDate().toString())
        back(); tag("course-detail").assertIsDisplayed(); assertTrue(list().items.isEmpty())
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun cancelledSeekKeepsImportedPrivatePhotoAndCommittedBackReleasesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "countdown-seek-").toFile()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) {
            store.db.countdownDao().backgroundIds().toSet()
        }
        try {
            withSelectedPhoto(context, redPixels(Bitmap.CompressFormat.PNG), "image/png") { uri ->
                val picker = object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = object : ActivityResultRegistry() {
                        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                            check(contract is PickVisualMedia)
                            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                        }
                    }
                }
                ui.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker, LocalBackgroundStore provides photos) {
                    StackLifeApp(viewModel { WorkspaceViewModel(store.api) })
                } }
                tag("nav:countdown").performClick()
                val button = tag("countdown-add").fetchSemanticsNode().boundsInRoot
                tag("countdown-add").performClick()
                await(hasText("选择背景图片") and hasClickAction()).performScrollTo().performClick()
                await(hasText("移除背景") and hasClickAction())
                val id = File(folder, "background-images").list()!!.single()
                val route = "route:countdown:new"; val full = bounds(route)
                controlledBack { input ->
                    start(input); seek(input, .6f); expectWindow(full, button, bounds(route), .6f)
                    ui.runOnUiThread { input.backCancelled() }; ui.mainClock.advanceTimeBy(600)
                    assertTrue(photos.exists(id)); assertEquals(full, bounds(route))
                    start(input); seek(input, .8f); ui.runOnUiThread { input.backCompleted() }
                }
                tag("countdown-add").assertIsDisplayed(); assertFalse(photos.exists(id)); assertTrue(list().items.isEmpty())
            }
        } finally { folder.deleteRecursively() }
    }
}
