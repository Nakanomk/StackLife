package io.github.nakanomk.stacklife.gallery

import android.app.Application
import androidx.room.Room
import io.github.nakanomk.stacklife.appearance.BackgroundStore
import io.github.nakanomk.stacklife.assistant.*
import io.github.nakanomk.stacklife.courses.campusZone
import io.github.nakanomk.stacklife.fixtures.*
import io.github.nakanomk.stacklife.i18n.AppLocales
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.storage.AgendaDefaults
import io.github.nakanomk.stacklife.storage.PlannerDatabase
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Separate UID and process. No production Application, alarm scheduler, updater or fixture provider. */
class GalleryApplication : Application() {
    internal val session = java.util.UUID.randomUUID().toString()
    private val db by lazy { Room.inMemoryDatabaseBuilder(this, PlannerDatabase::class.java).addCallback(AgendaDefaults).build() }
    private val history by lazy { db.conversationHistory() }
    // The catalog offers built-in wallpapers only; Gallery history is the only possible private donor.
    internal val backgrounds by lazy { BackgroundStore(this, history::backgroundIds) }
    private val attachments by lazy { AttachmentStore(this, { history.imageIds().toSet() }) }
    internal val planner by lazy { RoomPlannerApi(db, imageExists = backgrounds::exists) }
    private val connections by lazy { ConnectionStore(this, "gallery-connection") }

    override fun onCreate() {
        super.onCreate()
        AppLocales.initialize(this)
    }

    internal fun newAssistant() = AssistantModel(planner, connections, OpenAiTransport(), history, attachments)

    // A configuration change can replace the Activity while seeding. All hosts await the same transaction.
    internal val ready by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        check(connections.profile() == null) { "Gallery must not have a configured model service" }
        attachments.recover()
        backgrounds.recover()
        val today = LocalDate.now(campusZone)
        val term = sampleTerm("gallery-week", today, 2)
        val courses = (1..term.weeks).flatMap { sampleCourses(term, today.dayOfWeek.value, it) }
        val draft = planner.stageCourseImport(term, courses)
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()
        try {
            val commands = buildList {
                for (n in 1..4) add(PlannerCommand.TaskAdd(sampleTask(n).copy(ref = "sample$n")))
                add(PlannerCommand.SetSchedule("@sample4", SchedulePattern.Once(ExactInterval(start, start + 1_200_000L))))
                add(PlannerCommand.FrameCreate(uiText("Gallery frame", "示例栈帧"), listOf(sampleTask(5), sampleTask(6))))
                add(PlannerCommand.CourseImport(draft))
            }
            val preview = planner.preview(commands)
            check(preview is PreviewResult.Ready) { (preview as PreviewResult.Rejected).message }
            val result = planner.commit(preview.proposalId)
            check(result is CommitResult.Applied) { (result as CommitResult.Rejected).message }
        } finally { planner.discardCourseImport(draft) }
        val page = AssistantPage.Queue
        history.sendUser(null, page.label, uiText("Gallery · local sample message", "示例 · 本机消息"), page.historyKey(), emptyList())
        Unit
    } }
}
