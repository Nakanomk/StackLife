package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.ScheduledState
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.PlannerCommand
import java.time.Instant
import java.time.ZoneId
import java.util.TreeMap

/** Effective occupancy during a single read transaction. A null replacement releases the DB interval. */
internal class SlotDraft(private val dao: PlannerDao, private val originalTasks: Map<String, TaskRow>) {
    private val originals = HashMap<String, TimeBlockRow?>()
    private val replacements = LinkedHashMap<String, TimeBlockRow?>()
    // Effective intervals cannot overlap, so at most one earlier start can cross a proposed start.
    private val movedByStart = TreeMap<Long, TimeBlockRow>()

    fun current(id: String): TimeBlockRow? {
        if (id in replacements) return replacements[id]
        if (id !in originals && id in originalTasks) originals[id] = dao.slots(listOf(id)).singleOrNull()
        return originals[id]
    }

    /** Called after loading the tasks, before capturing many frame items. Cache misses as well as hits. */
    fun preload(ids: Collection<String>) {
        var batch: ArrayList<String>? = null
        fun fetch(ids: ArrayList<String>) {
            for (id in ids) originals[id] = null
            for (slot in dao.slots(ids)) originals[slot.taskId] = slot
            ids.clear()
        }
        for (id in ids) {
            if (id !in originalTasks || id in originals) continue
            val pending = batch ?: ArrayList<String>(minOf(ids.size, 900)).also { batch = it }
            pending.add(id)
            if (pending.size == 900) fetch(pending)
        }
        batch?.let { if (it.isNotEmpty()) fetch(it) }
    }

    fun set(row: TaskRow, cmd: PlannerCommand.SlotSet, editedTasks: Map<String, TaskRow>, courses: CourseDraft) {
        if (current(row.id) == null && row.owner != Owner.QUEUE) throw InvalidCommand(uiText("Only queued tasks can be scheduled for the first time.", "只能为在队任务新增时段。"))
        if (cmd.durationMinutes <= 0) throw InvalidCommand(uiText("Scheduled duration must be greater than zero.", "排程时长必须大于零。"))
        val instant = cmd.start.toInstant()
        if (instant.nano % 1_000_000 != 0) throw InvalidCommand(uiText("Start time cannot be more precise than a millisecond.", "开始时刻的精度不能小于一毫秒。"))
        val slot = try {
            val start = instant.toEpochMilli()
            TimeBlockRow(row.id, start, Math.addExact(start, cmd.durationMinutes.toLong() * 60_000L))
        } catch (_: ArithmeticException) {
            throw InvalidCommand(uiText("This time range is outside the supported dates.", "安排的起止时间超出可保存范围。"))
        }

        var conflicts: StringBuilder? = null
        var count = 0L
        fun showExample(): Boolean = ++count <= MAX_PARTNER_NAMES
        fun conflict(title: String, start: Long, end: Long) {
            val message = conflicts?.append(uiText("; ", "；")) ?: StringBuilder(uiText("Scheduling conflict: ", "时段冲突：")).also { conflicts = it }
            message.append(uiText("“", "「")).append(title).append(uiText("” ", "」 ")).append(intervalLabel(start, end, cmd.start.offset))
        }
        for (block in dao.dayBlocks(slot.startMillis, slot.endMillis)) {
            if (block.taskId != row.id && block.taskId !in replacements && showExample()) {
                conflict((editedTasks[block.taskId]?.title ?: block.title).diagnosticText(), block.startMillis, block.endMillis)
            }
        }
        for (course in courses.intersecting(slot.startMillis, slot.endMillis)) {
            if (showExample()) conflict(uiText({ "Fixed class · ${course.label()}" }, { "固定课程 · ${course.label()}" }), course.meeting.startMillis, course.meeting.endMillis)
        }
        fun checkMoved(moved: TimeBlockRow) {
            if (moved.taskId != row.id && showExample()) {
                val other = editedTasks[moved.taskId] ?: originalTasks.getValue(moved.taskId)
                conflict(other.title.diagnosticText(), moved.startMillis, moved.endMillis)
            }
        }
        movedByStart.lowerEntry(slot.startMillis)?.value?.let { if (it.endMillis > slot.startMillis) checkMoved(it) }
        for (moved in movedByStart.subMap(slot.startMillis, true, slot.endMillis, false).values) checkMoved(moved)
        conflicts?.let {
            if (count > MAX_PARTNER_NAMES) it.append(uiText({ "; ${count - MAX_PARTNER_NAMES} more conflicts not listed" }, { "；另有 ${count - MAX_PARTNER_NAMES} 个冲突未列出" }))
            throw InvalidCommand(it.toString())
        }
        replace(row.id, slot)
    }

    fun effective(start: Long, end: Long, edits: Map<String, TaskRow>): List<DayBlock> {
        val stored = dao.dayBlocks(start, end)
        if (edits.isEmpty() && replacements.isEmpty()) return stored
        val result = ArrayList<DayBlock>(stored.size)
        for (block in stored) if (block.taskId !in replacements) {
            val updated = edits[block.taskId]
            result.add(if (updated == null) block else block.copy(title = updated.title, state = ScheduledState.valueOf(updated.owner.name)))
        }
        fun add(slot: TimeBlockRow) {
            val row = edits[slot.taskId] ?: originalTasks.getValue(slot.taskId)
            result.add(DayBlock(row.id, row.title, slot.startMillis, slot.endMillis, ScheduledState.valueOf(row.owner.name)))
        }
        movedByStart.lowerEntry(start)?.value?.let { if (it.endMillis > start) add(it) }
        for (slot in movedByStart.subMap(start, true, end, false).values) add(slot)
        result.sortBy { it.startMillis }
        return result
    }

    fun clear(id: String, required: Boolean) {
        if (current(id) != null) replace(id, null)
        else if (required) throw InvalidCommand(uiText("The selected task has no scheduled time to clear.", "所选任务没有可取消的时段。"))
    }

    private fun replace(id: String, slot: TimeBlockRow?) {
        replacements[id]?.let { movedByStart.remove(it.startMillis) }
        if (originals[id] == slot) {
            replacements.remove(id)
        } else {
            replacements[id] = slot
            if (slot != null) movedByStart[slot.startMillis] = slot
        }
    }

    fun finish(changes: MutableList<PlannerChange>, editedTasks: Map<String, TaskRow>): SlotDelta? {
        if (replacements.isEmpty()) return null
        val writes = ArrayList<TimeBlockRow>()
        val deletes = ArrayList<String>()
        val zone = ZoneId.systemDefault()
        for ((id, after) in replacements) {
            if (after == null) deletes.add(id) else writes.add(after)
            val title = (editedTasks[id] ?: originalTasks.getValue(id)).title
            changes.add(PlannerChange(id, uiText({ "Schedule · $title" }, { "排程 · $title" }),
                originals[id]?.let { intervalLabel(it.startMillis, it.endMillis, zone) },
                after?.let { intervalLabel(it.startMillis, it.endMillis, zone) }, title))
        }
        return SlotDelta(writes, deletes)
    }
}

internal fun intervalLabel(start: Long, end: Long, zone: ZoneId): String =
    "${Instant.ofEpochMilli(start).atZone(zone).toOffsetDateTime()} – ${Instant.ofEpochMilli(end).atZone(zone).toOffsetDateTime()}"

internal class SlotDelta(private val writes: List<TimeBlockRow>, private val deletes: List<String>) {
    fun applyTo(dao: PlannerDao) {
        for (i in deletes.indices step 900) dao.deleteSlots(deletes.subList(i, minOf(i + 900, deletes.size)))
        if (writes.isNotEmpty()) dao.saveSlots(writes)
    }
}
