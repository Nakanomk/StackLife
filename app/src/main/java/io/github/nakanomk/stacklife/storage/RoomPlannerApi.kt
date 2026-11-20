package io.github.nakanomk.stacklife.storage

import androidx.room.withTransaction
import io.github.nakanomk.stacklife.planner.CommitFailure
import io.github.nakanomk.stacklife.planner.CommitResult
import io.github.nakanomk.stacklife.planner.FrameEntry
import io.github.nakanomk.stacklife.planner.FrameView
import io.github.nakanomk.stacklife.planner.PlannerApi
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.PreviewResult
import io.github.nakanomk.stacklife.planner.QueryResult
import io.github.nakanomk.stacklife.planner.Workspace
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPlannerApi(private val db: PlannerDatabase) : PlannerApi {
    private val dao = db.plannerDao()
    private val pending = ConcurrentHashMap<String, Proposal>()

    override fun observe(query: PlannerQuery): Flow<QueryResult> = when (query) {
        // Every successful commit touches the singleton, including commits that only move tasks.
        PlannerQuery.Workspace -> db.invalidationTracker.createFlow("planner_settings").map {
            db.withTransaction {
                val settings = dao.settings() ?: SettingsRow()
                val entries = HashMap<String, MutableList<FrameEntry>>()
                for ((item, restorable) in dao.itemStates()) {
                    entries.getOrPut(item.frameId) { ArrayList() }.add(
                        FrameEntry(item.taskId, item.originalPosition, item.title, item.notes, item.minutes,
                            item.scheduledStartMillis, item.scheduledEndMillis, restorable),
                    )
                }
                Workspace(settings.revision, dao.visibleQueue(), dao.frames().map {
                    FrameView(it.id, it.reason, it.savedAtMillis, it.source, entries[it.id].orEmpty())
                }, settings.restorePolicy)
            }
        }
    }

    override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
        val proposal = try {
            db.withTransaction { BatchDraft(dao, dao.settings() ?: SettingsRow()).plan(commands) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidCommand) {
            return PreviewResult.Rejected(e.message.orEmpty())
        } catch (e: Exception) {
            return PreviewResult.Rejected("无法读取工作台：${e.localizedMessage ?: e.javaClass.simpleName}")
        }
        val id = UUID.randomUUID().toString()
        pending[id] = proposal
        return PreviewResult.Ready(id, proposal.baseRevision, proposal.changes)
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
                    proposal.applyTo(dao)
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
) {
    fun applyTo(dao: PlannerDao) {
        if (frames.isNotEmpty()) dao.insertFrames(frames)
        if (inserts.isNotEmpty()) dao.insertTasks(inserts)
        if (updates.isNotEmpty()) dao.updateTasks(updates)
        if (positions.isNotEmpty()) dao.updatePositions(positions)
        if (items.isNotEmpty()) dao.insertItems(items)
        dao.saveSettings(settings)
    }
}
