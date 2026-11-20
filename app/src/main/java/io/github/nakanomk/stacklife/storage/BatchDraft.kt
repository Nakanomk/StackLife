package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.AppearancePatch
import io.github.nakanomk.stacklife.planner.decodeAppearance
import io.github.nakanomk.stacklife.planner.encodeAppearance
import io.github.nakanomk.stacklife.planner.FrameSource
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.RestoreMode
import io.github.nakanomk.stacklife.planner.RestorePolicy
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Single preview transaction. The overlay contains only addressed tasks; old frame snapshots never change. */
internal class BatchDraft(private val dao: PlannerDao, courseDao: CourseDao, private val graphDao: GraphDao,
    private val reviewed: Map<String, ReviewedCourses>, private val base: SettingsRow) {
    private val originals = HashMap<String, TaskRow>()
    private val edited = LinkedHashMap<String, TaskRow>()
    private val slots = SlotDraft(dao, originals)
    private val courses = CourseDraft(courseDao)
    private val graphs = LinkedHashMap<String, GraphDraft>()
    private val frames = ArrayList<FrameRow>()
    private val frameNames by lazy(LazyThreadSafetyMode.NONE) { HashMap<String, String>() }
    private val items = ArrayList<FrameItemRow>()
    private val members = HashMap<String, List<String>>()
    private val changes = ArrayList<PlannerChange>()
    private var policy = base.restorePolicy
    private val originalAppearance by lazy(LazyThreadSafetyMode.NONE) { decodeAppearance(base.appearance) }
    private var appearance: Appearance? = null

    private enum class Kind { TASK, FRAME, GRAPH, NODE }
    private data class Binding(val id: String, val kind: Kind)
    private val bindings = HashMap<String, Binding>()

    // Appending needs just COUNT(*); load positions and display labels only for a move/removal.
    private val initialSize by lazy(LazyThreadSafetyMode.NONE) { dao.queueSize() }
    private val pendingTail = ArrayList<String>()
    private var order: ArrayList<String>? = null
    private val initialPositions = HashMap<String, QueuePositionLabel>()

    fun plan(commands: List<PlannerCommand>): Proposal {
        if (commands.isEmpty()) invalid("请至少选择一项更改。")
        for (cmd in commands) when (cmd) {
            is PlannerCommand.TaskAdd -> {
                val row = newTask(cmd.task.title, cmd.task.notes, cmd.task.minutes, cmd.task.ref, frameId = null)
                edited[row.id] = row
            }
            is PlannerCommand.TaskEdit -> {
                if (cmd.title == null && cmd.notes == null && cmd.minutes == null) invalid("请选择需要修改的任务字段。")
                val row = activeTask(cmd.taskId)
                val minutes = cmd.minutes ?: row.minutes
                edited[row.id] = row.copy(title = title(cmd.title ?: row.title, minutes), notes = cmd.notes ?: row.notes, minutes = minutes)
            }
            is PlannerCommand.TaskFinish -> retire(cmd.taskId, Owner.DONE)
            is PlannerCommand.TaskRemove -> retire(cmd.taskId, Owner.REMOVED)
            is PlannerCommand.SlotSet -> {
                checkRevision(cmd.expectedRevision)
                slots.set(activeTask(cmd.taskId), cmd, edited, courses)
            }
            is PlannerCommand.SlotClear -> {
                checkRevision(cmd.expectedRevision)
                slots.clear(activeTask(cmd.taskId).id, required = true)
            }
            is PlannerCommand.CourseImport -> courses.replace(reviewed[cmd.draftId] ?: invalid("导入草稿已失效，请重新核对。"))
            is PlannerCommand.TermRemove -> courses.remove(cmd.termId)
            is PlannerCommand.QueueOrder -> reorder(cmd.taskIds)
            is PlannerCommand.FrameCreate -> {
                if (cmd.tasks.isEmpty()) invalid("栈帧至少需要一项任务。")
                val frame = newFrame(cmd.reason, FrameSource.MANUAL, cmd.ref)
                populateFrame(frame, cmd.tasks.size) { index ->
                    val input = cmd.tasks[index]
                    newTask(input.title, input.notes, input.minutes, input.ref, frame.id)
                }
            }
            is PlannerCommand.FramePush -> {
                val ids = taskIds(cmd.taskIds)
                if (ids.isEmpty()) invalid("请选择需要压栈的任务。")
                saveQueue(newFrame(cmd.reason, FrameSource.PUSH, cmd.ref), ids)
            }
            is PlannerCommand.FrameRestore -> restore(cmd)
            is PlannerCommand.SetRestorePolicy -> policy = cmd.policy
            is PlannerCommand.EditAppearance -> {
                if (cmd.expected != null && cmd.expected != originalAppearance) invalid("外观设置已变更，请按最新设置重新编辑。")
                appearance = cmd.patch.applyTo(appearance ?: originalAppearance)
            }
            is PlannerCommand.GraphCreate -> {
                val id = newId(cmd.ref, Kind.GRAPH)
                graphs[id] = GraphDraft(graphDao, id, null, cmd.title)
            }
            is PlannerCommand.GraphPatch -> {
                checkRevision(cmd.expectedRevision)
                if (cmd.edits.isEmpty()) invalid("请至少选择一项流程图更改。")
                val graph = graph(cmd.graphId)
                for (edit in cmd.edits) graph.edit(edit, newNodeRef, resolveNodeRef)
            }
            is PlannerCommand.GraphRemove -> {
                checkRevision(cmd.expectedRevision)
                graph(cmd.graphId).remove()
            }
            is PlannerCommand.GraphFrame -> generateFrame(cmd)
        }
        return finish()
    }

    private fun invalid(message: String): Nothing = throw InvalidCommand(message)

    private fun checkRevision(expected: Long?) {
        if (expected != null && expected != base.revision) invalid("日程已有变更，请按最新内容重新预览。")
    }

    private fun newId(ref: String?, kind: Kind): String {
        val id = UUID.randomUUID().toString()
        if (ref != null) {
            if (ref.isBlank() || ref.startsWith('@') || ref != ref.trim()) invalid("新建引用名不能为空、以 @ 开头或含有首尾空格。")
            if (bindings.put(ref, Binding(id, kind)) != null) invalid("本批次重复声明了引用：@$ref。")
        }
        return id
    }

    private fun resolve(raw: String, kind: Kind): String {
        if (!raw.startsWith('@')) return raw
        val binding = bindings[raw.substring(1)] ?: invalid("引用 $raw 尚未声明。")
        if (binding.kind != kind) invalid("引用 $raw 的类型与此操作不符。")
        return binding.id
    }

    private val newNodeRef: (String?) -> String = { newId(it, Kind.NODE) }
    private val resolveNodeRef: (String) -> String = { resolve(it, Kind.NODE) }

    private fun graph(raw: String): GraphDraft {
        val id = resolve(raw, Kind.GRAPH)
        val draft = graphs.getOrPut(id) {
            val stored = graphDao.graph(id) ?: invalid("流程图「$raw」不存在。")
            GraphDraft(graphDao, id, stored, stored.title)
        }
        if (draft.title == null) invalid("流程图「$raw」已移除。")
        return draft
    }

    private fun generateFrame(cmd: PlannerCommand.GraphFrame) {
        checkRevision(cmd.expectedRevision)
        val graph = graph(cmd.graphId)
        val ordered = graph.order(resolveNodeRef(cmd.goalId))
        // In the ancestor closure every other vertex precedes the goal.
        val reason = cmd.reason ?: "为目标〈${ordered.last().title}〉生成"
        val refs = if (cmd.taskRefs.isEmpty()) null else HashMap<String, String>(cmd.taskRefs.size).apply {
            for ((node, ref) in cmd.taskRefs) if (put(resolveNodeRef(node), ref) != null)
                invalid("同一个节点「$node」不能重复声明生成任务的引用。")
        }
        val frame = newFrame(reason, FrameSource.GRAPH, cmd.ref)
        populateFrame(frame, ordered.size) { index ->
            val node = ordered[index]
            newTask(node.title, node.notes, node.minutes, refs?.remove(node.id), frame.id)
        }
        if (!refs.isNullOrEmpty()) invalid("节点「${refs.keys.first()}」不属于该目标及其前置清单，不能为它声明任务引用。")
    }

    private fun taskIds(raw: List<String>): Set<String> {
        val result = LinkedHashSet<String>(raw.size)
        for (id in raw) if (!result.add(resolve(id, Kind.TASK))) invalid("任务不能重复选择。")
        return result
    }

    private fun title(raw: String, minutes: Int): String {
        if (minutes <= 0) invalid("预计时长必须大于零。")
        return raw.trim().ifEmpty { invalid("任务标题不能为空。") }
    }

    private fun newTask(raw: String, notes: String, minutes: Int, ref: String?, frameId: String?): TaskRow {
        val id = newId(ref, Kind.TASK)
        return TaskRow(id, title(raw, minutes), notes, minutes,
            if (frameId == null) Owner.QUEUE else Owner.FRAME, frameId,
            if (frameId == null) append(id) else null)
    }

    private fun newFrame(reason: String, source: FrameSource, ref: String?): FrameRow {
        val label = reason.trim().ifEmpty { invalid("请填写保存栈帧的原因。") }
        return FrameRow(newId(ref, Kind.FRAME), label, System.currentTimeMillis(), source).also { frames.add(it); frameNames[it.id] = label }
    }

    private fun frameName(id: String) = frameNames.getOrPut(id) { dao.frame(id)?.reason ?: id }

    private inline fun populateFrame(frame: FrameRow, count: Int, makeTask: (Int) -> TaskRow) {
        val ids = ArrayList<String>(count)
        for (position in 0 until count) {
            val row = makeTask(position)
            edited[row.id] = row
            ids.add(row.id)
            snapshot(frame.id, position, row, slot = null)
        }
        members[frame.id] = ids
    }

    private fun snapshot(frameId: String, position: Int, row: TaskRow, slot: TimeBlockRow?) {
        items.add(FrameItemRow(frameId, position, row.id, row.title, row.notes, row.minutes,
            slot?.startMillis, slot?.endMillis))
    }

    private fun append(id: String): Int {
        val queue = order
        if (queue != null) {
            queue.add(id)
            return queue.lastIndex
        }
        pendingTail.add(id)
        return initialSize + pendingTail.lastIndex
    }

    private fun queue(): ArrayList<String> {
        order?.let { return it }
        val initial = dao.queuePositions()
        val queue = ArrayList<String>(initial.size + pendingTail.size)
        for (row in initial) { initialPositions[row.id] = row; queue.add(row.id) }
        queue.addAll(pendingTail)
        pendingTail.clear()
        order = queue
        return queue
    }

    private fun task(id: String): TaskRow = edited[id] ?: originals[id] ?: run {
        val row = dao.tasks(listOf(id)).singleOrNull() ?: invalid("所选任务不存在。")
        originals[id] = row
        row
    }

    private fun loadTasks(ids: Collection<String>) {
        var missing: ArrayList<String>? = null
        fun fetch(batch: ArrayList<String>) {
            val rows = dao.tasks(batch)
            if (rows.size != batch.size) invalid("部分所选任务已不存在。")
            for (row in rows) originals[row.id] = row
            batch.clear()
        }
        for (id in ids) {
            if (id in edited || id in originals) continue
            val batch = missing ?: ArrayList<String>(minOf(ids.size, 900)).also { missing = it }
            batch.add(id)
            // Stay below older Android SQLite builds' 999 bound-parameter limit.
            if (batch.size == 900) fetch(batch)
        }
        missing?.let { if (it.isNotEmpty()) fetch(it) }
    }

    private fun activeTask(raw: String): TaskRow {
        val row = task(resolve(raw, Kind.TASK))
        if (row.owner != Owner.QUEUE && row.owner != Owner.FRAME) invalid("任务已完成或已移出，不能再修改。")
        return row
    }

    private fun retire(raw: String, owner: Owner) {
        val row = activeTask(raw)
        if (row.owner == Owner.QUEUE) queue().remove(row.id)
        if (owner == Owner.REMOVED) slots.clear(row.id, required = false)
        edited[row.id] = row.copy(owner = owner, frameId = null, queuePosition = null)
    }

    private fun reorder(raw: List<String>) {
        val ids = taskIds(raw)
        val queue = queue()
        if (ids.size != queue.size || queue.any { it !in ids }) invalid("排序必须包含当前队列的全部任务，且不能重复。")
        queue.clear()
        queue.addAll(ids)
    }

    /** null selects the entire current queue (only the automatic SWAP snapshot uses this). */
    private fun saveQueue(frame: FrameRow, selection: Set<String>?) {
        val queue = queue()
        val expected = selection?.size ?: queue.size
        loadTasks(selection ?: queue)
        slots.preload(selection ?: queue)
        val saved = ArrayList<String>(expected)
        var kept = 0
        for (position in queue.indices) {
            val id = queue[position]
            if (selection == null || id in selection) {
                val row = task(id)
                snapshot(frame.id, position, row, slots.current(id))
                edited[id] = row.copy(owner = Owner.FRAME, frameId = frame.id, queuePosition = null)
                saved.add(id)
            } else {
                queue[kept++] = id
            }
        }
        if (saved.size != expected) invalid("部分所选任务已不在队列中。")
        queue.subList(kept, queue.size).clear()
        members[frame.id] = saved
    }

    private fun restore(cmd: PlannerCommand.FrameRestore) {
        val frameId = resolve(cmd.frameId, Kind.FRAME)
        val selected = when (cmd.mode) {
            RestoreMode.ALL -> {
                if (cmd.taskIds.isNotEmpty()) invalid("全部恢复时不应指定部分任务。")
                null
            }
            RestoreMode.SELECTED -> taskIds(cmd.taskIds).also {
                if (it.isEmpty()) invalid("请选择需要恢复的任务。")
            }
        }
        val source = members.getOrPut(frameId) { dao.restorableIds(frameId) }
        val restoring = source.filter { id ->
            (selected == null || id in selected) && (edited[id]?.let { it.frameId == frameId && it.owner == Owner.FRAME } ?: true)
        }
        if (selected != null && restoring.size != selected.size) invalid("所选任务已不属于此栈帧，无法恢复。")
        if (restoring.isEmpty()) invalid("此栈帧不存在或已无可恢复的任务。")
        loadTasks(restoring)

        val explanation = when (policy) {
            RestorePolicy.APPEND -> "按帧内原序追加到队尾"
            RestorePolicy.PREPEND -> "按帧内原序插入队首"
            RestorePolicy.SWAP -> when {
                cmd.mode == RestoreMode.SELECTED -> "部分恢复按帧内原序插入队首，不保存当前队列"
                queue().isEmpty() -> "当前队列为空，直接按帧内原序恢复"
                else -> {
                    saveQueue(newFrame("切换栈帧前自动保存", FrameSource.SWAP, null), null)
                    "整帧切换：先另存当前队列，再按帧内原序恢复"
                }
            }
        }
        if (policy != RestorePolicy.APPEND) queue().addAll(0, restoring)
        for ((index, id) in restoring.withIndex()) {
            val pos = if (policy == RestorePolicy.APPEND) append(id) else index
            edited[id] = task(id).copy(owner = Owner.QUEUE, frameId = null, queuePosition = pos)
        }
        changes.add(PlannerChange(frameId, "恢复方式", null, explanation, frameName(frameId)))
    }

    private fun finish(): Proposal {
        val positions = ArrayList<PositionUpdate>()
        order?.forEachIndexed { position, id ->
            val row = edited[id]
            if (row != null) {
                if (row.queuePosition != position) edited[id] = row.copy(queuePosition = position)
            } else {
                val original = initialPositions.getValue(id)
                if (original.queuePosition != position) {
                    positions.add(PositionUpdate(id, position))
                    change(id, "队列位置", original.queuePosition + 1, position + 1, original.title)
                }
            }
        }
        val inserts = ArrayList<TaskRow>()
        val updates = ArrayList<TaskRow>()
        for ((id, row) in edited) {
            val before = originals[id]
            if (before == row) continue
            if (before == null) inserts.add(row) else updates.add(row)
            change(id, "标题", before?.title, row.title)
            change(id, "备注", before?.notes?.ifEmpty { null }, row.notes.ifEmpty { null })
            change(id, "预计分钟", before?.minutes, row.minutes)
            change(id, "归属", before?.owner?.label(), row.owner.label())
            if (before?.frameId != row.frameId) change(id, "所属栈帧", before?.frameId?.let(::frameName), row.frameId?.let(::frameName))
            change(id, "队列位置", before?.queuePosition?.plus(1), row.queuePosition?.plus(1))
        }
        for (frame in frames) {
            change(frame.id, "压栈原因", null, frame.reason)
            change(frame.id, "保存时刻", null, Instant.ofEpochMilli(frame.savedAtMillis).atZone(ZoneId.systemDefault()).format(savedTimeFormat))
            change(frame.id, "栈帧来源", null, when (frame.source) {
                FrameSource.PUSH -> "从队列保存"
                FrameSource.MANUAL -> "手动新建"
                FrameSource.SWAP -> "切换前自动保存"
                FrameSource.GRAPH -> "从流程图目标生成"
            })
        }
        for (item in items) {
            change(item.frameId, "原第 ${item.originalPosition + 1} 项", null,
                buildString {
                    append(item.title).append(" · ").append(item.minutes).append(" 分钟")
                    if (item.scheduledStartMillis != null && item.scheduledEndMillis != null) {
                        append('\n').append(intervalLabel(item.scheduledStartMillis, item.scheduledEndMillis, ZoneId.systemDefault()))
                    }
                    if (item.notes.isNotEmpty()) append('\n').append(item.notes)
                })
        }
        if (base.restorePolicy != policy) change("工作台", "恢复策略", base.restorePolicy.label(), policy.label())
        val settingsJson = appearance?.let { next ->
            AppearancePatch.changes(originalAppearance, next, changes)
            encodeAppearance(next)
        } ?: base.appearance
        val slotDelta = slots.finish(changes, edited)
        val courseWrites = courses.finish(changes) { start, end -> slots.effective(start, end, edited) }
        val graphWrites = graphs.values.mapNotNull { it.finish(changes) }
        val refs = if (bindings.isEmpty()) emptyMap() else Collections.unmodifiableMap(bindings.mapValues { it.value.id })
        return Proposal(base.revision, base.copy(revision = base.revision + 1, restorePolicy = policy, appearance = settingsJson),
            Collections.unmodifiableList(changes), frames, inserts, updates, positions, items, slotDelta, courseWrites, graphWrites, refs)
    }

    private fun change(id: String, field: String, before: Any?, after: Any?, label: String? = edited[id]?.title) {
        if (before != after) changes.add(PlannerChange(id, field, before?.toString(), after?.toString(), label))
    }

    private fun Owner.label() = when (this) {
        Owner.QUEUE -> "队列"
        Owner.FRAME -> "栈帧"
        Owner.DONE -> "已完成"
        Owner.REMOVED -> "已移出"
    }

    private fun RestorePolicy.label() = when (this) {
        RestorePolicy.PREPEND -> "插入队首"
        RestorePolicy.APPEND -> "追加到队尾"
        RestorePolicy.SWAP -> "整帧切换"
    }
}

private val savedTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx")
