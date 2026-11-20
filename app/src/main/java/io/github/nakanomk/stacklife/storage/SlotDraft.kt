package io.github.nakanomk.stacklife.storage

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

    fun set(row: TaskRow, cmd: PlannerCommand.SlotSet, editedTasks: Map<String, TaskRow>) {
        if (current(row.id) == null && row.owner != Owner.QUEUE) throw InvalidCommand("只能为在队任务新增时段。")
        if (cmd.durationMinutes <= 0) throw InvalidCommand("排程时长必须大于零。")
        val instant = cmd.start.toInstant()
        if (instant.nano % 1_000_000 != 0) throw InvalidCommand("开始时刻的精度不能小于一毫秒。")
        val slot = try {
            val start = instant.toEpochMilli()
            TimeBlockRow(row.id, start, Math.addExact(start, cmd.durationMinutes.toLong() * 60_000L))
        } catch (_: ArithmeticException) {
            throw InvalidCommand("安排的起止时间超出可保存范围。")
        }

        var conflicts: StringBuilder? = null
        fun conflict(title: String, start: Long, end: Long) {
            val message = conflicts?.append("；") ?: StringBuilder("时段冲突：").also { conflicts = it }
            message.append('「').append(title).append("」 ").append(intervalLabel(start, end, cmd.start.offset))
        }
        for (block in dao.dayBlocks(slot.startMillis, slot.endMillis)) {
            if (block.taskId != row.id && block.taskId !in replacements) {
                conflict(editedTasks[block.taskId]?.title ?: block.title, block.startMillis, block.endMillis)
            }
        }
        fun checkMoved(moved: TimeBlockRow) {
            if (moved.taskId != row.id) {
                val other = editedTasks[moved.taskId] ?: originalTasks.getValue(moved.taskId)
                conflict(other.title, moved.startMillis, moved.endMillis)
            }
        }
        movedByStart.lowerEntry(slot.startMillis)?.value?.let { if (it.endMillis > slot.startMillis) checkMoved(it) }
        for (moved in movedByStart.subMap(slot.startMillis, true, slot.endMillis, false).values) checkMoved(moved)
        conflicts?.let { throw InvalidCommand(it.toString()) }
        replace(row.id, slot)
    }

    fun clear(id: String, required: Boolean) {
        if (current(id) != null) replace(id, null)
        else if (required) throw InvalidCommand("所选任务没有可取消的时段。")
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
            changes.add(PlannerChange(id, "排程 · $title",
                originals[id]?.let { intervalLabel(it.startMillis, it.endMillis, zone) },
                after?.let { intervalLabel(it.startMillis, it.endMillis, zone) }))
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
