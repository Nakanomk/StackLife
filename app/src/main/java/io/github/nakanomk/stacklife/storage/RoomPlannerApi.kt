package io.github.nakanomk.stacklife.storage

import androidx.room.withTransaction
import io.github.nakanomk.stacklife.courses.*
import io.github.nakanomk.stacklife.graphs.goalOrder
import io.github.nakanomk.stacklife.planner.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPlannerApi(private val db: PlannerDatabase) : PlannerApi {
    private val dao = db.plannerDao()
    private val pending = ConcurrentHashMap<String, Proposal>()
    private val courses = db.courseDao()
    private val graphs = db.graphDao()
    private val reviewed = ConcurrentHashMap<String, ReviewedCourses>()

    // Every successful commit touches the singleton, including commits that only move slots.
    override fun observe(query: PlannerQuery): Flow<QueryResult> =
        db.invalidationTracker.createFlow("planner_settings").map {
            db.withTransaction {
                val settings = dao.settings() ?: SettingsRow()
                when (query) {
                    PlannerQuery.Workspace -> workspace(settings)
                    PlannerQuery.Queue -> QueueView(dao.visibleQueue())
                    is PlannerQuery.Task -> TaskView(dao.activeTask(query.taskId))
                    PlannerQuery.Frames -> FrameIndex(dao.frameIndex())
                    is PlannerQuery.Frame -> FrameDetailResult(dao.frame(query.frameId)?.let { f ->
                        FrameView(f.id, f.reason, f.savedAtMillis, f.source, dao.frameEntries(f.id).map { it.entry() })
                    })
                    PlannerQuery.Settings -> PolicyView(settings.restorePolicy)
                    is PlannerQuery.Day -> {
                        val tray = dao.dayTray()
                        if (query.startMillis >= query.endMillis) DayPlan(query, settings.revision, tray, emptyList())
                        else {
                            val slots = dao.dayBlocks(query.startMillis, query.endMillis)
                            val occupied = CourseOccupancy(courses.intersecting(query.startMillis, query.endMillis), slots)
                            DayPlan(query, settings.revision, tray, slots, occupied.courses, occupied.taskIds)
                        }
                    }
                    PlannerQuery.Terms -> Terms(courses.terms(HUST_SOURCE).map(TermRow::spec))
                    is PlannerQuery.Week -> week(query)
                    PlannerQuery.Graphs -> GraphList(graphs.list())
                    is PlannerQuery.Graph -> {
                        val graph = graphs.graph(query.graphId)
                        if (graph == null) GraphDetail(settings.revision, null, emptyList(), emptyList())
                        else GraphDetail(settings.revision, graph, graphs.nodes(graph.id), graphs.edges(graph.id))
                    }
                    is PlannerQuery.Goal -> {
                        try {
                            GoalOrder(settings.revision, goalOrder(graphs.nodes(query.graphId), graphs.edges(query.graphId), query.goalId))
                        } catch (e: IllegalArgumentException) {
                            GoalOrder(settings.revision, emptyList(), e.message ?: "无法生成目标顺序")
                        }
                    }
                }
            }
        }

    private fun week(query: PlannerQuery.Week): CourseWeek {
        val term = courses.term(query.termId, HUST_SOURCE)?.spec() ?: return CourseWeek(null, query.week, emptyList())
        if (query.week !in 1..term.weeks) return CourseWeek(term, query.week, emptyList())
        val monday = term.firstMonday.plusWeeks(query.week - 1L)
        val start = monday.atStartOfDay(campusZone).toInstant().toEpochMilli()
        val end = monday.plusWeeks(1).atStartOfDay(campusZone).toInstant().toEpochMilli()
        val occupancy = CourseOccupancy(courses.intersecting(start, end), dao.dayBlocks(start, end))
        return CourseWeek(term, query.week, occupancy.courses.filter { it.source == HUST_SOURCE && it.termId == term.id })
    }

    override fun stageCourseImport(term: TermSpec, meetings: List<CourseMeeting>): String {
        val id = UUID.randomUUID().toString()
        reviewed[id] = ReviewedCourses(term, meetings.toList())
        return id
    }
    override fun discardCourseImport(draftId: String) { reviewed.remove(draftId) }

    private fun ItemState.entry() = FrameEntry(item.taskId, item.originalPosition, item.title, item.notes, item.minutes,
        item.scheduledStartMillis, item.scheduledEndMillis, restorable)

    private fun workspace(settings: SettingsRow): Workspace {
        val entries = HashMap<String, MutableList<FrameEntry>>()
        for (state in dao.itemStates()) entries.getOrPut(state.item.frameId) { ArrayList() }.add(state.entry())
        return Workspace(settings.revision, dao.visibleQueue(), dao.frames().map {
            FrameView(it.id, it.reason, it.savedAtMillis, it.source, entries[it.id].orEmpty())
        }, settings.restorePolicy)
    }

    override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
        val proposal = try {
            db.withTransaction { BatchDraft(dao, courses, graphs, reviewed, dao.settings() ?: SettingsRow()).plan(commands) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidCommand) {
            return PreviewResult.Rejected(e.message.orEmpty())
        } catch (e: Exception) {
            return PreviewResult.Rejected("无法读取工作台：${e.localizedMessage ?: e.javaClass.simpleName}")
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
            ?: return CommitResult.Rejected(CommitFailure.UNKNOWN_PROPOSAL, "预览已失效或已使用，请重新预览。")
        return try {
            db.withTransaction {
                val revision = dao.settings()?.revision ?: 0L
                if (revision != proposal.baseRevision) {
                    CommitResult.Rejected(CommitFailure.STALE_PREVIEW, "工作台已有变更，请重新预览。")
                } else {
                    proposal.applyTo(dao, courses, graphs)
                    CommitResult.Applied(proposal.settings.revision)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommitResult.Rejected(CommitFailure.STORAGE, "未能保存更改：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }
}

internal class InvalidCommand(message: String) : IllegalArgumentException(message)

/** Owned solely by the pending map. No command or caller-owned collection survives preview. */
internal class Proposal(
    val baseRevision: Long,
    val settings: SettingsRow,
    val changes: List<PlannerChange>,
    private val frames: List<FrameRow>,
    private val inserts: List<TaskRow>,
    private val updates: List<TaskRow>,
    private val positions: List<PositionUpdate>,
    private val items: List<FrameItemRow>,
    private val slots: SlotDelta?,
    private val courses: List<CourseWrites>,
    private val graphs: List<GraphWrites>,
    val refs: Map<String, String>,
) {
    fun applyTo(dao: PlannerDao, courseDao: CourseDao, graphDao: GraphDao) {
        if (frames.isNotEmpty()) dao.insertFrames(frames)
        if (inserts.isNotEmpty()) dao.insertTasks(inserts)
        if (updates.isNotEmpty()) dao.updateTasks(updates)
        if (positions.isNotEmpty()) dao.updatePositions(positions)
        if (items.isNotEmpty()) dao.insertItems(items)
        slots?.applyTo(dao)
        for (write in courses) write.applyTo(courseDao)
        for (write in graphs) write.applyTo(graphDao)
        dao.saveSettings(settings)
    }
}
