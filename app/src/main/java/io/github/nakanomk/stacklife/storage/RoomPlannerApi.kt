package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.about.AppDiagnostics
import androidx.room.withTransaction
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.graphs.goalOrder
import io.github.nakanomk.stacklife.planner.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPlannerApi(private val db: PlannerDatabase, private val importCalendars: Map<String, CourseCalendar> = SchoolImports.calendars,
    private val imageExists: (String) -> Boolean = { false }, private val collectBackgrounds: (suspend () -> Unit)? = null,
    private val afterCommit: suspend () -> Unit = {}) : PlannerApi {
    private val dao = db.plannerDao()
    private val agenda = db.scheduleDao()
    private val reader = ScheduleReader(agenda)
    private val pending = ConcurrentHashMap<String, Proposal>()
    private val courses = db.courseDao()
    private val graphs = db.graphDao()
    private val graphLinks = GraphLinkReader(db)
    private val countdowns = db.countdownDao()
    private val reviewed = ConcurrentHashMap<String, ReviewedCourses>()

    // Every successful commit touches the singleton, including commits that only move slots.
    override fun observe(query: PlannerQuery): Flow<QueryResult> =
        db.invalidationTracker.createFlow("planner_settings").map {
            db.withTransaction {
                val settings = dao.settings() ?: SettingsRow()
                when (query) {
                    PlannerQuery.Workspace -> workspace(settings)
                    PlannerQuery.Queue -> QueueView(taskViews(dao.visibleQueue()))
                    is PlannerQuery.Task -> TaskView(dao.currentTask(query.taskId)?.view(reader.load(query.taskId)?.summary()))
                    PlannerQuery.Frames -> FrameIndex(dao.frameIndex())
                    is PlannerQuery.Frame -> FrameDetailResult(dao.frame(query.frameId)?.let { f ->
                        FrameView(f.id, f.reason, f.savedAtMillis, f.source, frameEntries(dao.frameEntries(f.id)))
                    })
                    PlannerQuery.Settings -> PolicyView(settings.restorePolicy, decodeAppearance(settings.appearance), settings.onboardingVersion)
                    is PlannerQuery.Schedule -> ScheduleView(settings.revision, reader.load(query.id)?.summary())
                    is PlannerQuery.Occurrence -> OccurrenceView(settings.revision, db.readOccurrence(query.key, query.zone))
                    is PlannerQuery.Reminders -> {
                        val p = agenda.policy(query.subject)
                        ReminderView(settings.revision, p, agenda.effectiveRules(query.subject, p))
                    }
                    is PlannerQuery.Day -> {
                        val start = query.startMillis; val end = query.endMillis
                        val tasks = reader.between(start, end, query.zone)
                        val rows = if (start < end) courses.intersecting(start,end) else emptyList()
                        val occupied = CourseOccupancy(rows,tasks).courses
                        val fixed = rows.mapIndexedTo(ArrayList(rows.size)) { i,row -> row.agenda(false, occupied[i]) }
                        reader.markCompleted(fixed)
                        val all = ArrayList<AgendaOccurrence>(fixed.size + tasks.size)
                        all.addAll(tasks)
                        for (o in fixed) all.add(if (o.completed) o.copy(course = requireNotNull(o.course).copy(completed = true)) else o)
                        all.sortWith(agendaOrder)
                        markScheduleConflicts(all)
                        DayPlan(query, settings.revision, dao.dayTray().map { it.view() }, all)
                    }
                    PlannerQuery.Terms -> {
                        val rows = courses.terms()
                        Terms(rows.distinctBy { it.termId }.map(TermRow::spec),
                            rows.filter { it.source != MANUAL_SOURCE }.groupBy({ it.termId }, { it.source }))
                    }
                    is PlannerQuery.Week -> week(query)
                    is PlannerQuery.CourseWeeks -> with(query.pattern) {
                        CourseWeeksView(courses.patternWeeks(query.termId, query.source, title, room, day, firstPeriod, lastPeriod).toSet())
                    }
                    PlannerQuery.Countdowns -> CountdownsView(settings.revision, countdowns.list())
                    is PlannerQuery.Countdown -> CountdownView(settings.revision, countdowns.one(query.id))
                    PlannerQuery.Graphs -> GraphList(graphs.list())
                    is PlannerQuery.Source -> SourceView(settings.revision, graphLinks.source(query.link))
                    is PlannerQuery.LinkableEvents -> graphLinks.page(query, settings.revision)
                    is PlannerQuery.Graph -> {
                        val graph = graphs.graph(query.graphId)
                        if (graph == null) GraphDetail(settings.revision, null, emptyList(), emptyList())
                        else GraphDetail(settings.revision, graph, graphs.nodes(graph.id).map { it.resolved(graphLinks::source) }, graphs.edges(graph.id))
                    }
                    is PlannerQuery.Goal -> {
                        try {
                            val ordered = goalOrder(graphs.nodes(query.graphId), graphs.edges(query.graphId), query.goalId)
                            GoalOrder(settings.revision, ordered.map { it.resolved(graphLinks::source) })
                        } catch (e: IllegalArgumentException) {
                            GoalOrder(settings.revision, emptyList(), e.message ?: uiText("Could not order tasks for this goal", "无法生成目标顺序"))
                        }
                    }
                }
            }
        }

    private fun week(query: PlannerQuery.Week): CourseWeek {
        val term = courses.terms(query.termId).firstOrNull()?.spec()
            ?: return CourseWeek(null, query.week, emptyList())
        if (query.week !in 1..term.weeks) return CourseWeek(term, query.week, emptyList())
        val range = courses.weekBounds(term.id, query.week)
        val start = range.firstStart ?: return CourseWeek(term, query.week, emptyList())
        val end = range.lastEnd ?: return CourseWeek(term, query.week, emptyList())
        val occupancy = CourseOccupancy(courses.intersecting(start, end), reader.between(start, end, java.time.ZoneId.systemDefault()))
        val inWeek = occupancy.courses.filter { it.termId == term.id && it.meeting.week == query.week }
        val visible = ArrayList<ScheduledCourse>(inWeek.size)
        for (batch in inWeek.chunked(900)) {
            val keys = batch.map { OccurrenceKey.Course(CourseOccurrenceId.of(it.termId, it.source, it.meeting)).storageKey() }
            val done = agenda.completed(keys).toHashSet()
            batch.forEachIndexed { i,c -> visible.add(if (keys[i] in done) c.copy(completed = true) else c) }
        }
        if (visible.isEmpty()) return CourseWeek(term, query.week, visible)
        val titles = visible.mapTo(HashSet()) { it.meeting.title }
        val indices = buildMap {
            courses.orderedTitles(term.id).forEachIndexed { index, title ->
                if (title in titles) put(title, index)
            }
        }
        return CourseWeek(term, query.week, visible, indices)
    }

    override fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>, source: String): String {
        val id = UUID.randomUUID().toString()
        reviewed[id] = ReviewedCourses(term, meetings.toList(), source, importCalendars[source])
        return id
    }
    override fun discardCourseImport(draftId: String) { reviewed.remove(draftId) }

    private fun taskViews(fields: List<TaskFields>): List<QueueTask> {
        val loaded = reader.loadMany(fields.map { it.id })
        return fields.map { it.view(loaded[it.id]?.summary()) }
    }
    private fun frameEntries(items: List<ItemState>): List<FrameEntry> {
        val loaded = reader.loadMany(items.mapNotNull { it.current?.id })
        return items.map { (item,current) ->
            val saved = item.savedSchedule()
            FrameEntry(item.taskId,item.originalPosition,item.title,item.notes,item.minutes,saved?.schedule,
                current?.view(loaded[current.id]?.summary()), item.referenceOnly, item.occurrenceDay, saved?.occurrenceInterval)
        }
    }

    private fun workspace(settings: SettingsRow): Workspace {
        val items = dao.itemStates()
        val entries = HashMap<String, MutableList<FrameEntry>>()
        frameEntries(items).forEachIndexed { i,entry -> entries.getOrPut(items[i].item.frameId) { ArrayList() }.add(entry) }
        return Workspace(settings.revision, taskViews(dao.visibleQueue()), dao.frames().map {
            FrameView(it.id, it.reason, it.savedAtMillis, it.source, entries[it.id].orEmpty())
        }, settings.restorePolicy)
    }

    override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
        val proposal = try {
            db.withTransaction { BatchDraft(dao, courses, graphs, agenda, reviewed, dao.settings() ?: SettingsRow(), graphLinks::source, countdowns, imageExists).plan(commands) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidCommand) {
            return PreviewResult.Rejected(e.message.orEmpty())
        } catch (e: Exception) {
            return PreviewResult.Rejected(uiText({ "Could not read the workspace: ${e.localizedMessage ?: e.javaClass.simpleName}" }, { "无法读取工作台：${e.localizedMessage ?: e.javaClass.simpleName}" }))
        }
        val id = UUID.randomUUID().toString()
        pending[id] = proposal
        return PreviewResult.Ready(id, proposal.baseRevision, proposal.changes, proposal.refs)
    }

    override fun discard(proposalId: String) {
        pending.remove(proposalId)
    }

    override suspend fun commit(proposalId: String): CommitResult {
        val proposal = pending.remove(proposalId)
            ?: return CommitResult.Rejected(CommitFailure.UNKNOWN_PROPOSAL, uiText("This preview expired or was already used. Preview again.", "预览已失效或已使用，请重新预览。"))
                .also(AppDiagnostics::recordCommit)
        return try {
            db.withTransaction {
                val revision = dao.settings()?.revision ?: 0L
                if (revision != proposal.baseRevision) {
                    CommitResult.Rejected(CommitFailure.STALE_PREVIEW, uiText("The workspace has changed. Preview again.", "工作台已有变更，请重新预览。"))
                } else {
                    proposal.applyTo(dao, courses, graphs, agenda, countdowns, imageExists)
                    CommitResult.Applied(proposal.settings.revision, proposalId, proposal.effects, proposal.refs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommitResult.Rejected(CommitFailure.STORAGE, uiText({ "Could not save changes: ${e.localizedMessage ?: e.javaClass.simpleName}" }, { "未能保存更改：${e.localizedMessage ?: e.javaClass.simpleName}" }))
        }.also { result ->
            AppDiagnostics.recordCommit(result)
            if (result is CommitResult.Applied && proposal.backgroundsChanged) try { collectBackgrounds?.invoke() }
            catch (e: Exception) { android.util.Log.w("StackLife", "Saved backgrounds; file cleanup deferred", e) }
            if (result is CommitResult.Applied && proposal.agendaChanged) {
                try { afterCommit() } catch (e: Exception) {
                    android.util.Log.w("StackLife", "Agenda saved; reminder reconciliation failed", e)
                }
            }
        }
    }
}

internal fun CourseRow.agenda(done: Boolean, course: ScheduledCourse = ScheduledCourse(termId, source, meeting, completed = done)) =
    AgendaOccurrence(OccurrenceKey.Course(key()),meeting.title,"",meeting.startMillis,meeting.endMillis,done,occurrenceVersion,
        course = course, conflicts = course.conflicts)

/** Caller owns the transaction; notification validation and detail navigation use the same lookup. */
internal fun PlannerDatabase.readOccurrence(key: OccurrenceKey, zone: java.time.ZoneId): AgendaOccurrence? = when (key) {
    is OccurrenceKey.Schedule -> ScheduleReader(scheduleDao()).resolve(key, zone)
    is OccurrenceKey.Course -> with(key.occurrence) {
        courseDao().occurrence(termId,source,week,day,firstPeriod,lastPeriod,title,room)?.agenda(scheduleDao().completed(key.storageKey()))
    }
}

internal class InvalidCommand(message: String) : IllegalArgumentException(message)

/** Owned solely by the pending map. No command or caller-owned collection survives preview. */
internal class Proposal(
    val baseRevision: Long,
    val settings: SettingsRow,
    val changes: List<PlannerChange>,
    private val frames: List<FrameRow>,
    private val amendedFrames: List<FrameRow>,
    private val inserts: List<TaskRow>,
    private val updates: List<TaskRow>,
    private val positions: List<PositionUpdate>,
    private val items: List<FrameItemRow>,
    private val scheduleWrites: AgendaWrites,
    private val courses: List<CourseWrites>,
    private val graphs: List<GraphWrites>,
    private val countdowns: CountdownWrites?,
    val refs: Map<String, String>,
    val effects: List<WorkspaceEffect>,
    private val deletedFrames: Set<String>,
    val agendaChanged: Boolean,
    val backgroundsChanged: Boolean,
) {
    fun applyTo(dao: PlannerDao, courseDao: CourseDao, graphDao: GraphDao, agendaDao: ScheduleDao, countdownDao: CountdownDao, imageExists: (String)->Boolean) {
        countdowns?.requireImages(imageExists)
        if (backgroundsChanged) changes.firstOrNull { it.entityId == "appearance" }?.let { change ->
            val old = (change.before as AppearanceSnapshot).value.backgroundIds()
            for (id in (change.after as AppearanceSnapshot).value.backgroundIds() - old) if (!imageExists(id))
                throw InvalidCommand(uiText("The selected background is no longer available.", "所选背景已不可用。"))
        }
        if (frames.isNotEmpty()) dao.insertFrames(frames)
        if (amendedFrames.isNotEmpty()) dao.updateFrames(amendedFrames)
        if (inserts.isNotEmpty()) dao.insertTasks(inserts)
        if (updates.isNotEmpty()) dao.updateTasks(updates)
        if (positions.isNotEmpty()) dao.updatePositions(positions)
        if (items.isNotEmpty()) dao.insertItems(items)
        for (write in courses) write.applyTo(courseDao)
        scheduleWrites.apply(agendaDao)
        for (id in deletedFrames) {
            dao.deleteFrameItems(id)
            check(dao.deleteEmptyFrame(id) == 1)
        }
        for (write in graphs) write.applyTo(graphDao)
        countdowns?.applyTo(countdownDao)
        dao.saveSettings(settings.copy(receiptsImported = dao.settings()?.receiptsImported == true || settings.receiptsImported))
    }
}
