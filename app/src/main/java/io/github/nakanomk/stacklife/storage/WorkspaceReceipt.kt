package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import java.time.LocalDate
import java.time.ZoneId

/** B4 stable neighbour IDs; O5 inverse changeset: validate all after-images before changing any row. */
internal class WorkspaceReceipt private constructor(val id: String, val deadline: Long, val effect: WorkspaceEffect,
    private val before: Image, private val after: Image) {
    private class Frame(val row: FrameRow, val ordinal: Long, val items: List<FrameItemRow>, val members: List<String>) {
        fun insert(dao: PlannerDao) {
            // SQLite may recycle the highest ROWID after deletion. Preserve the explicit tie order of
            // this history without overwriting a subsequently created frame, even for equal timestamps.
            if (dao.hasFrameOrdinal(ordinal)) {
                var gap = ordinal + 1
                while (dao.hasFrameOrdinal(gap)) gap++
                while (gap > ordinal) { dao.moveFrameOrdinal(gap - 1, gap); gap-- }
            }
            dao.reinsertFrame(ordinal, row.id, row.reason, row.savedAtMillis, row.source)
            dao.insertItems(items)
        }
    }
    private class Schedule(val root: ScheduleRow, val segments: List<SegmentRow>, val exceptions: List<ExceptionRow>,
        val settings: List<ReminderSettingRow>, val rules: List<ReminderRuleRow>) {
        fun insert(dao: ScheduleDao, revision: Long) {
            // Completion keys and delivered notification receipts outlive schedule deletion. Do not replay either.
            dao.write(root.copy(version = revision)); dao.writeSegments(segments)
            for (e in exceptions) dao.write(e.copy(version = revision))
            for (s in settings) dao.write(s)
            dao.insertRules(rules)
        }
        fun overlaps(db: PlannerDatabase): Boolean {
            if (!root.enabled) return false
            val reader = ScheduleReader(db.scheduleDao()); val courses = db.courseDao(); val zone = ZoneId.systemDefault()
            fun conflicts(interval: ExactInterval): Boolean {
                val until = if (interval.point) interval.startMillis + 1 else interval.endMillis
                return reader.between(interval.startMillis, until, zone).any { it.taskId != root.taskId } ||
                    courses.intersecting(interval.startMillis, until).isNotEmpty()
            }
            root.startMillis?.let { return conflicts(ExactInterval(it, requireNotNull(root.endMillis))) }
            if (exceptions.any { it.actual()?.let(::conflicts) == true }) return true
            // Unbounded series have no last occurrence. Report concrete overlaps for the restored segments'
            // first active day and the current day. Other dates use the ordinary per-occurrence day projection.
            val today = LocalDate.now(zone).toEpochDay()
            for (s in segments) for (day in longArrayOf(s.segment.fromDay, maxOf(today, s.segment.fromDay))) {
                val occurrence = reader.resolve(OccurrenceKey.Schedule(root.id, day), zone) ?: continue
                if (conflicts(ExactInterval(occurrence.startMillis, occurrence.endMillis))) return true
            }
            return false
        }
    }
    private data class OwnerImage(val owner: Owner, val frame: String?, val completedFrame: String?, val completedPosition: Int?) {
        constructor(t: TaskRow) : this(t.owner, t.frameId, t.completedFromFrame, t.completedFromPosition)
    }
    private class Image(val queue: List<String>, val tasks: Map<String, OwnerImage>, val frames: Map<String, Frame?>, val schedule: Schedule?)

    /** Caller holds the same Room transaction as both images and all updates. Returns a concrete schedule warning. */
    fun reverse(db: PlannerDatabase, keepOnly: Boolean, kept: Boolean, revision: Long): Boolean {
        val dao = db.plannerDao(); val agenda = db.scheduleDao()
        val emptied = (effect as? WorkspaceEffect.Restored)?.takeIf { it.sourceDeleted }?.frameId
        if (keepOnly) {
            if (kept || emptied == null || dao.frame(emptied) != null) conflict()
            requireNotNull(before.frames[emptied]).insert(dao)
            return false
        }
        val changed = before.tasks.keys
        val currentTasks = rows(dao, changed)
        if (currentTasks.size != after.tasks.size || currentTasks.any { (id, row) -> OwnerImage(row) != after.tasks[id] }) conflict()
        for ((id, originalAfter) in after.frames) {
            val expected = if (kept && emptied == id) before.frames[id] else originalAfter
            val row = dao.frame(id)
            if (row == null || expected == null) { if (row != expected?.row) conflict(); continue }
            val willDelete = before.frames[id] == null
            if ((if (willDelete) row != expected.row else row.copy(reason = expected.row.reason) != expected.row) ||
                dao.savedItems(id) != expected.items || dao.restorableIds(id) != if (kept && emptied == id) emptyList() else expected.members) conflict()
        }
        if (effect is WorkspaceEffect.Removed && agenda.header(effect.taskId) != null) conflict()
        val currentOrder = if (changed.isEmpty()) emptyList() else dao.queueIds()
        val target = if (changed.isEmpty()) emptyList() else restoreQueue(before.queue, after.queue, currentOrder, changed)
        val targetIndices = target.withIndex().associate { (index, id) -> id to index }
        for ((id, frame) in before.frames) if (frame != null && after.frames[id] == null && !(kept && emptied == id)) frame.insert(dao)
        if (changed.isNotEmpty()) dao.updateTasks(currentTasks.map { (id, row) ->
            val was = before.tasks.getValue(id)
            row.copy(owner = was.owner, frameId = was.frame, queuePosition = targetIndices[id],
                completedFromFrame = was.completedFrame, completedFromPosition = was.completedPosition)
        })
        val positions = ArrayList<PositionUpdate>()
        currentOrder.forEachIndexed { index, id -> if (id !in changed) {
            val pos = targetIndices.getValue(id); if (pos != index) positions.add(PositionUpdate(id, pos))
        } }
        if (positions.isNotEmpty()) dao.updatePositions(positions)
        for ((id, frame) in before.frames) if (frame == null) {
            dao.deleteFrameItems(id); if (dao.deleteEmptyFrame(id) != 1) conflict()
        }
        before.schedule?.insert(agenda, revision)
        return before.schedule?.overlaps(db) == true
    }
    val restoresSchedule get() = before.schedule != null

    companion object {
        private fun rows(dao: PlannerDao, ids: Set<String>) = buildMap {
            for (batch in ids.chunked(900)) for (row in dao.tasks(batch)) put(row.id, row)
        }
        fun capture(db: PlannerDatabase, id: String, deadline: Long, effect: WorkspaceEffect, apply: () -> Unit): WorkspaceReceipt {
            val tasks = linkedSetOf<String>(); val frames = linkedSetOf<String>()
            when (effect) {
                is WorkspaceEffect.Pushed -> { tasks.addAll(effect.taskIds); frames.add(effect.frameId) }
                is WorkspaceEffect.Restored -> { tasks.addAll(effect.taskIds); frames.add(effect.frameId)
                    effect.swapped?.let { tasks.addAll(it.taskIds); frames.add(it.frameId) } }
                is WorkspaceEffect.Removed -> tasks.add(effect.taskId)
                is WorkspaceEffect.DeletedHistory -> frames.addAll(effect.frameIds)
            }
            val dao = db.plannerDao(); val agenda = db.scheduleDao()
            fun image(withSchedule: Boolean) = Image(if (tasks.isEmpty()) emptyList() else dao.queueIds(),
                rows(dao, tasks).mapValues { OwnerImage(it.value) }, frames.associateWith { key -> dao.frame(key)?.let {
                    Frame(it, requireNotNull(dao.savedRowId(key)), dao.savedItems(key), dao.restorableIds(key)) } },
                if (withSchedule && effect is WorkspaceEffect.Removed) agenda.header(effect.taskId)?.root?.let { root ->
                    Schedule(root, agenda.segments(listOf(root.id)), agenda.exceptions(root.id), agenda.scheduleSettings(root.id), agenda.scheduleRules(root.id))
                } else null)
            val before = image(true); apply(); return WorkspaceReceipt(id, deadline, effect, before, image(false))
        }
        private fun conflict(): Nothing = throw InvalidCommand(uiText("The affected tasks, queue order or frame changed. Nothing was undone.", "相关任务、队列顺序或栈帧已改变，未撤回任何更改。"))

        private fun restoreQueue(before: List<String>, after: List<String>, current: List<String>, moved: Set<String>): List<String> {
            val expectedMoving = after.filter { it in moved }; val boundaries = HashMap<String, Int>()
            var passed = 0
            for (id in after) if (id in moved) passed++ else boundaries[id] = passed
            passed = 0
            val remaining = ArrayList<String>(current.size)
            for (id in current) if (id in moved) {
                if (expectedMoving.getOrNull(passed++) != id) conflict()
            } else {
                if (boundaries[id]?.let { it != passed } == true) conflict()
                remaining.add(id)
            }
            if (passed != expectedMoving.size) conflict()
            val indices = remaining.withIndex().associate { (i, id) -> id to i }
            val next = IntArray(before.size); var anchor = -1
            for (i in before.indices.reversed()) { next[i] = anchor; indices[before[i]]?.let { anchor = it } }
            val restored = ArrayList<String>(remaining.size + moved.size); var cursor = 0; var previous = -1
            before.forEachIndexed { i, id -> if (id in moved) {
                val following = next[i]
                if (previous >= 0 && following >= 0 && previous >= following) conflict()
                val at = when { following >= 0 -> following; previous >= 0 -> previous + 1; else -> 0 }
                if (at < cursor) conflict()
                while (cursor < at) restored.add(remaining[cursor++]); restored.add(id)
            } else indices[id]?.let { previous = it } }
            while (cursor < remaining.size) restored.add(remaining[cursor++])
            return restored
        }
    }
}
