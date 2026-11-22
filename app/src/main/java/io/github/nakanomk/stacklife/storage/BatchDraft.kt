package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.Appearance
import io.github.nakanomk.stacklife.planner.AppearanceFields
import io.github.nakanomk.stacklife.planner.AppearancePatch
import io.github.nakanomk.stacklife.planner.decodeAppearance
import io.github.nakanomk.stacklife.planner.encodeAppearance
import io.github.nakanomk.stacklife.planner.FrameSource
import io.github.nakanomk.stacklife.planner.frameLabel
import io.github.nakanomk.stacklife.planner.swapReason
import io.github.nakanomk.stacklife.planner.PlannerChange
import io.github.nakanomk.stacklife.planner.PlannerCommand
import io.github.nakanomk.stacklife.planner.RestoreMode
import io.github.nakanomk.stacklife.planner.RestorePolicy
import io.github.nakanomk.stacklife.planner.RoutineKind
import io.github.nakanomk.stacklife.planner.label
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

/** Single preview transaction. The overlay contains only addressed tasks; old frame snapshots never change. */
internal class BatchDraft(private val dao: PlannerDao, courseDao: CourseDao, private val graphDao: GraphDao,
    private val agendaDao: AgendaDao, private val reviewed: Map<String, ReviewedCourses>, private val base: SettingsRow) {
    private val originals = HashMap<String, TaskRow>()
    private val edited = LinkedHashMap<String, TaskRow>()
    private val slots = SlotDraft(dao, originals)
    private val courses = CourseDraft(courseDao)
    private val graphs = LinkedHashMap<String, GraphDraft>()
    private val frames = ArrayList<FrameRow>()
    private var amendedFrames: MutableList<FrameRow>? = null
    private var deletedFrames: MutableSet<String>? = null
    private var ticks: AgendaTicks? = null
    private var routine = base.routine()
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
        if (commands.isEmpty()) invalid(uiText("Select at least one change.", "请至少选择一项更改。"))
        for (cmd in commands) when (cmd) {
            is PlannerCommand.TaskAdd -> {
                val row = newTask(cmd.task.title, cmd.task.notes, cmd.task.minutes, cmd.task.ref, frameId = null)
                edited[row.id] = row
            }
            is PlannerCommand.TaskEdit -> {
                if (cmd.title == null && cmd.notes == null && cmd.minutes == null) invalid(uiText("Select a task field to edit.", "请选择需要修改的任务字段。"))
                val row = activeTask(cmd.taskId)
                if (cmd.expectedFrameId != null && row.frameId != cmd.expectedFrameId)
                    invalid(uiText("This task is no longer in this frame.", "这项任务已不在此栈帧中。"))
                val minutes = cmd.minutes ?: row.minutes
                edited[row.id] = row.copy(title = title(cmd.title ?: row.title, minutes), notes = cmd.notes ?: row.notes, minutes = minutes)
            }
            is PlannerCommand.TaskFinish -> retire(cmd.taskId, Owner.DONE)
            is PlannerCommand.SetScheduledCompletion -> {
                checkRevision(cmd.expectedRevision)
                val row = task(resolve(cmd.taskId, Kind.TASK))
                if (slots.current(row.id) == null || row.owner == Owner.REMOVED)
                    invalid(uiText("This task is no longer scheduled.", "这项任务已不在日程中。"))
                if (cmd.completed && row.owner != Owner.DONE) retire(row.id, Owner.DONE)
                if (!cmd.completed && row.owner == Owner.DONE) reopen(row)
            }
            is PlannerCommand.SetCourseCompletion -> {
                checkRevision(cmd.expectedRevision)
                if (!courses.contains(cmd.occurrence) { agendaDao.hasMeeting(cmd.occurrence) })
                    invalid(uiText("This class is no longer scheduled.", "这一课次已不在日程中。"))
                agendaTicks().courses[CourseTick(cmd.occurrence)] = cmd.completed
                completionChange(cmd.occurrence.termId, cmd.occurrence.title, cmd.completed)
            }
            is PlannerCommand.SetRoutineCompletion -> {
                checkRevision(cmd.expectedRevision)
                if (routine[cmd.kind] == null) invalid(uiText("This routine is no longer scheduled.", "这项作息已不在日程中。"))
                agendaTicks().routines[RoutineTick(cmd.date.toEpochDay(), cmd.kind)] = cmd.completed
                completionChange(cmd.date.toString(), cmd.kind.label(), cmd.completed)
            }
            is PlannerCommand.SetRoutineTime -> {
                if (cmd.minute != null && cmd.minute !in 0 until 1440) invalid(uiText("Choose a time within the day.", "请选择一天之内的时间。"))
                change("routine:${cmd.kind}", cmd.kind.label(), routine[cmd.kind]?.let(::clockMinute), cmd.minute?.let(::clockMinute), cmd.kind.label())
                routine = if (cmd.kind == RoutineKind.WAKE) routine.copy(wakeMinute = cmd.minute) else routine.copy(sleepMinute = cmd.minute)
            }
            is PlannerCommand.TaskRemove -> retire(cmd.taskId, Owner.REMOVED)
            is PlannerCommand.SlotSet -> {
                checkRevision(cmd.expectedRevision)
                slots.set(activeTask(cmd.taskId), cmd, edited, courses)
            }
            is PlannerCommand.SlotClear -> {
                checkRevision(cmd.expectedRevision)
                slots.clear(activeTask(cmd.taskId).id, required = true)
            }
            is PlannerCommand.CourseImport -> courses.replace(reviewed[cmd.draftId] ?: invalid(uiText("The import draft expired. Review it again.", "导入草稿已失效，请重新核对。")))
            is PlannerCommand.TermRemove -> courses.remove(cmd.termId, cmd.source)
            is PlannerCommand.ManualCourse -> courses.manual(cmd)
            is PlannerCommand.QueueOrder -> reorder(cmd.taskIds)
            is PlannerCommand.FrameCreate -> {
                if (cmd.tasks.isEmpty()) invalid(uiText("A frame needs at least one task.", "栈帧至少需要一项任务。"))
                val frame = newFrame(cmd.reason, FrameSource.MANUAL, cmd.ref)
                populateFrame(frame, cmd.tasks.size) { index ->
                    val input = cmd.tasks[index]
                    newTask(input.title, input.notes, input.minutes, input.ref, frame.id)
                }
            }
            is PlannerCommand.FramePush -> {
                val ids = taskIds(cmd.taskIds)
                if (ids.isEmpty()) invalid(uiText("Select the tasks to save in a frame.", "请选择需要压栈的任务。"))
                saveQueue(newFrame(cmd.reason, FrameSource.PUSH, cmd.ref), ids)
            }
            is PlannerCommand.FrameAddReason -> addReason(cmd)
            is PlannerCommand.FrameRestore -> restore(cmd)
            is PlannerCommand.DeleteFrameHistory -> {
                if (cmd.frameIds.isEmpty()) invalid(uiText("Select a historical frame.", "请选择要删除的历史栈帧。"))
                cmd.frameIds.forEach(::deleteHistory)
            }
            is PlannerCommand.SetRestorePolicy -> policy = cmd.policy
            is PlannerCommand.EditAppearance -> {
                val expected = cmd.expected
                if (expected != null && (if (cmd.patch is AppearanceFields) !cmd.patch.matchesBaseline(expected, originalAppearance)
                    else expected != originalAppearance)) invalid(uiText("Appearance settings have changed. Edit the latest settings instead.", "外观设置已变更，请按最新设置重新编辑。"))
                appearance = cmd.patch.applyTo(appearance ?: originalAppearance)
            }
            is PlannerCommand.GraphCreate -> {
                val id = newId(cmd.ref, Kind.GRAPH)
                graphs[id] = GraphDraft(graphDao, id, null, cmd.title)
            }
            is PlannerCommand.GraphPatch -> {
                checkRevision(cmd.expectedRevision)
                if (cmd.edits.isEmpty()) invalid(uiText("Select at least one graph change.", "请至少选择一项流程图更改。"))
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

    private fun agendaTicks() = ticks ?: AgendaTicks().also { ticks = it }
    private fun clockMinute(value: Int) = java.time.LocalTime.ofSecondOfDay(value * 60L).toString()
    private fun completionChange(id: String, title: String, done: Boolean) {
        val checked = uiText("Completed", "已完成"); val open = uiText("Not completed", "未完成")
        change(id, title, if (done) open else checked, if (done) checked else open, title)
    }

    private fun checkRevision(expected: Long?) {
        if (expected != null && expected != base.revision) invalid(uiText("The schedule has changed. Preview the latest version.", "日程已有变更，请按最新内容重新预览。"))
    }

    private fun newId(ref: String?, kind: Kind): String {
        val id = UUID.randomUUID().toString()
        if (ref != null) {
            if (ref.isBlank() || ref.startsWith('@') || ref != ref.trim()) invalid(uiText("A new reference must not be blank, start with @, or have leading or trailing spaces.", "新建引用名不能为空、以 @ 开头或含有首尾空格。"))
            if (bindings.put(ref, Binding(id, kind)) != null) invalid(uiText({ "Reference @$ref is declared twice in this batch." }, { "本批次重复声明了引用：@$ref。" }))
        }
        return id
    }

    private fun resolve(raw: String, kind: Kind): String {
        if (!raw.startsWith('@')) return raw
        val binding = bindings[raw.substring(1)] ?: invalid(uiText({ "Reference $raw has not been declared yet." }, { "引用 $raw 尚未声明。" }))
        if (binding.kind != kind) invalid(uiText({ "Reference $raw has the wrong type for this operation." }, { "引用 $raw 的类型与此操作不符。" }))
        return binding.id
    }

    private val newNodeRef: (String?) -> String = { newId(it, Kind.NODE) }
    private val resolveNodeRef: (String) -> String = { resolve(it, Kind.NODE) }

    private fun graph(raw: String): GraphDraft {
        val id = resolve(raw, Kind.GRAPH)
        val draft = graphs.getOrPut(id) {
            val stored = graphDao.graph(id) ?: invalid(uiText({ "Graph “$raw” does not exist." }, { "流程图「$raw」不存在。" }))
            GraphDraft(graphDao, id, stored, stored.title)
        }
        if (draft.title == null) invalid(uiText({ "Graph “$raw” has been removed." }, { "流程图「$raw」已移除。" }))
        return draft
    }

    private fun generateFrame(cmd: PlannerCommand.GraphFrame) {
        checkRevision(cmd.expectedRevision)
        val graph = graph(cmd.graphId)
        val ordered = graph.order(resolveNodeRef(cmd.goalId))
        // In the ancestor closure every other vertex precedes the goal.
        val reason = cmd.reason ?: uiText({ "Generated for goal “${ordered.last().title}”" }, { "为目标〈${ordered.last().title}〉生成" })
        val refs = if (cmd.taskRefs.isEmpty()) null else HashMap<String, String>(cmd.taskRefs.size).apply {
            for ((node, ref) in cmd.taskRefs) if (put(resolveNodeRef(node), ref) != null)
                invalid(uiText({ "Node “$node” cannot declare more than one reference to its generated task." }, { "同一个节点「$node」不能重复声明生成任务的引用。" }))
        }
        val frame = newFrame(reason, FrameSource.GRAPH, cmd.ref)
        populateFrame(frame, ordered.size) { index ->
            val node = ordered[index]
            newTask(node.title, node.notes, node.minutes, refs?.remove(node.id), frame.id)
        }
        if (!refs.isNullOrEmpty()) invalid(uiText({ "Node “${refs.keys.first()}” is not this goal or one of its prerequisites. It cannot declare a task reference." }, { "节点「${refs.keys.first()}」不属于该目标及其前置清单，不能为它声明任务引用。" }))
    }

    private fun taskIds(raw: List<String>): Set<String> {
        val result = LinkedHashSet<String>(raw.size)
        for (id in raw) if (!result.add(resolve(id, Kind.TASK))) invalid(uiText("Select each task only once.", "任务不能重复选择。"))
        return result
    }

    private fun title(raw: String, minutes: Int): String {
        if (minutes <= 0) invalid(uiText("Estimated duration must be greater than zero.", "预计时长必须大于零。"))
        return raw.trim().ifEmpty { invalid(uiText("Enter a task title.", "任务标题不能为空。")) }
    }

    private fun newTask(raw: String, notes: String, minutes: Int, ref: String?, frameId: String?): TaskRow {
        val id = newId(ref, Kind.TASK)
        return TaskRow(id, title(raw, minutes), notes, minutes,
            if (frameId == null) Owner.QUEUE else Owner.FRAME, frameId,
            if (frameId == null) append(id) else null)
    }

    private fun newFrame(reason: String, source: FrameSource, ref: String?): FrameRow {
        val label = reason.trim()
        if (label.isEmpty() && source != FrameSource.PUSH) invalid(uiText("Enter a reason for saving this frame.", "请填写保存栈帧的原因。"))
        return FrameRow(newId(ref, Kind.FRAME), label, System.currentTimeMillis(), source).also {
            frames.add(it); frameNames[it.id] = frameLabel(label, source)
        }
    }

    private fun addReason(cmd: PlannerCommand.FrameAddReason) {
        val id = resolve(cmd.frameId, Kind.FRAME)
        if (deletedFrames?.contains(id) == true) invalid(uiText("This frame has been deleted.", "此栈帧已删除。"))
        val reason = cmd.reason.trim().ifEmpty { invalid(uiText("Enter a reason.", "补充的原因不能为空。")) }
        val index = frames.indexOfFirst { it.id == id }
        val frame = if (index >= 0) frames[index] else amendedFrames?.find { it.id == id } ?: dao.frame(id)
        if (frame == null || frame.source != FrameSource.PUSH) invalid(uiText("You can add a reason only to a frame saved from the queue.", "只能给从队列压入的栈帧补充原因。"))
        if (frame.reason.isNotBlank()) invalid(uiText("This frame already has a reason. It was not overwritten.", "此栈帧已有原因，未覆盖原内容。"))
        val amended = frame.copy(reason = reason)
        if (index >= 0) {
            frames[index] = amended
        } else {
            (amendedFrames ?: ArrayList<FrameRow>().also { amendedFrames = it }).add(amended)
            change(id, uiText("Frame reason", "压栈原因"), uiText("Not specified", "未填写"), reason, frameLabel(frame.reason, frame.source))
        }
        frameNames[id] = reason
    }

    private fun frameName(id: String) = frameNames.getOrPut(id) { dao.frame(id)?.let { frameLabel(it.reason, it.source) } ?: id }

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
        val row = dao.tasks(listOf(id)).singleOrNull() ?: invalid(uiText("The selected task does not exist.", "所选任务不存在。"))
        originals[id] = row
        row
    }

    private fun loadTasks(ids: Collection<String>) {
        var missing: ArrayList<String>? = null
        fun fetch(batch: ArrayList<String>) {
            val rows = dao.tasks(batch)
            if (rows.size != batch.size) invalid(uiText("Some selected tasks no longer exist.", "部分所选任务已不存在。"))
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
        if (row.owner != Owner.QUEUE && row.owner != Owner.FRAME) invalid(uiText("Completed or removed tasks cannot be edited.", "任务已完成或已移出，不能再修改。"))
        return row
    }

    private fun retire(raw: String, owner: Owner) {
        val row = activeTask(raw)
        val position = if (row.owner == Owner.QUEUE) queue().indexOf(row.id).also { queue().removeAt(it) } else null
        if (owner == Owner.REMOVED) slots.clear(row.id, required = false)
        edited[row.id] = row.copy(owner = owner, frameId = null, queuePosition = null,
            completedFromFrame = row.frameId.takeIf { owner == Owner.DONE }, completedFromPosition = position.takeIf { owner == Owner.DONE })
    }

    private fun reopen(row: TaskRow) {
        val prior = row.completedFromFrame
        if (prior != null && deletedFrames?.contains(prior) != true && (frames.any { it.id == prior } || dao.frame(prior) != null)) {
            val original = members[prior] ?: dao.restorableIds(prior)
            if (row.id !in original) {
                val include = original.toHashSet().also { it.add(row.id) }
                members[prior] = dao.originalTaskIds(prior).filter { it in include }
            }
            edited[row.id] = row.copy(owner = Owner.FRAME, frameId = prior, completedFromFrame = null, completedFromPosition = null)
        } else {
            val queue = queue()
            val index = row.completedFromPosition?.coerceIn(0, queue.size) ?: queue.size
            queue.add(index, row.id)
            edited[row.id] = row.copy(owner = Owner.QUEUE, queuePosition = index, completedFromFrame = null, completedFromPosition = null)
        }
    }

    private fun deleteHistory(raw: String) {
        val id = resolve(raw, Kind.FRAME)
        val deleted = deletedFrames ?: LinkedHashSet<String>().also { deletedFrames = it }
        val frame = frames.find { it.id == id } ?: dao.frame(id)
        if (frame == null || id in deleted) invalid(uiText("This frame has already been deleted.", "此栈帧已删除。"))
        val ids = members.getOrPut(id) { dao.restorableIds(id) }
        if (ids.any { edited[it]?.let { row -> row.owner == Owner.FRAME && row.frameId == id } ?: true })
            invalid(uiText("Restore or finish the remaining tasks before deleting this history.", "请先恢复或完成帧内剩余任务，再删除这条历史。"))
        deleted.add(id)
        change(id, uiText("Delete frame history", "删除栈帧历史"), frameName(id), null, frameName(id))
    }

    private fun reorder(raw: List<String>) {
        val ids = taskIds(raw)
        val queue = queue()
        if (ids.size != queue.size || queue.any { it !in ids }) invalid(uiText("Include every task in the current queue exactly once.", "排序必须包含当前队列的全部任务，且不能重复。"))
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
        if (saved.size != expected) invalid(uiText("Some selected tasks are no longer in the queue.", "部分所选任务已不在队列中。"))
        queue.subList(kept, queue.size).clear()
        members[frame.id] = saved
    }

    private fun restore(cmd: PlannerCommand.FrameRestore) {
        val frameId = resolve(cmd.frameId, Kind.FRAME)
        if (deletedFrames?.contains(frameId) == true) invalid(uiText("This frame has been deleted.", "此栈帧已删除。"))
        val selected = when (cmd.mode) {
            RestoreMode.ALL -> {
                if (cmd.taskIds.isNotEmpty()) invalid(uiText("Do not select individual tasks when restoring a whole frame.", "全部恢复时不应指定部分任务。"))
                null
            }
            RestoreMode.SELECTED -> taskIds(cmd.taskIds).also {
                if (it.isEmpty()) invalid(uiText("Select the tasks to restore.", "请选择需要恢复的任务。"))
            }
        }
        val source = members.getOrPut(frameId) { dao.restorableIds(frameId) }
        val restoring = source.filter { id ->
            (selected == null || id in selected) && (edited[id]?.let { it.frameId == frameId && it.owner == Owner.FRAME } ?: true)
        }
        if (selected != null && restoring.size != selected.size) invalid(uiText("These tasks no longer belong to this frame and cannot be restored.", "所选任务已不属于此栈帧，无法恢复。"))
        if (restoring.isEmpty()) invalid(uiText("This frame does not exist or has no tasks left to restore.", "此栈帧不存在或已无可恢复的任务。"))
        loadTasks(restoring)

        val explanation = when (policy) {
            RestorePolicy.APPEND -> uiText("Append to the queue in frame order", "按帧内原序追加到队尾")
            RestorePolicy.PREPEND -> uiText("Prepend to the queue in frame order", "按帧内原序插入队首")
            RestorePolicy.SWAP -> when {
                cmd.mode == RestoreMode.SELECTED -> uiText("Prepend selected tasks in frame order without saving the current queue", "部分恢复按帧内原序插入队首，不保存当前队列")
                queue().isEmpty() -> uiText("The queue is empty; restore in frame order", "当前队列为空，直接按帧内原序恢复")
                else -> {
                    saveQueue(newFrame(swapReason(), FrameSource.SWAP, null), null)
                    uiText("Switch frames: save the current queue, then restore in frame order", "整帧切换：先另存当前队列，再按帧内原序恢复")
                }
            }
        }
        if (policy != RestorePolicy.APPEND) queue().addAll(0, restoring)
        for ((index, id) in restoring.withIndex()) {
            val pos = if (policy == RestorePolicy.APPEND) append(id) else index
            edited[id] = task(id).copy(owner = Owner.QUEUE, frameId = null, queuePosition = pos)
        }
        changes.add(PlannerChange(frameId, uiText("Restore method", "恢复方式"), null, explanation, frameName(frameId)))
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
                    change(id, uiText("Queue position", "队列位置"), original.queuePosition + 1, position + 1, original.title)
                }
            }
        }
        val inserts = ArrayList<TaskRow>()
        val updates = ArrayList<TaskRow>()
        for ((id, row) in edited) {
            val before = originals[id]
            if (before == row) continue
            if (before == null) inserts.add(row) else updates.add(row)
            change(id, uiText("Title", "标题"), before?.title, row.title)
            change(id, uiText("Notes", "备注"), before?.notes?.ifEmpty { null }, row.notes.ifEmpty { null })
            change(id, uiText("Estimated minutes", "预计分钟"), before?.minutes, row.minutes)
            change(id, uiText("Status", "归属"), before?.owner?.label(), row.owner.label())
            if (before?.frameId != row.frameId) change(id, uiText("Frame", "所属栈帧"), before?.frameId?.let(::frameName), row.frameId?.let(::frameName))
            change(id, uiText("Queue position", "队列位置"), before?.queuePosition?.plus(1), row.queuePosition?.plus(1))
        }
        for (frame in frames) {
            change(frame.id, uiText("Frame reason", "压栈原因"), null, frame.reason.ifBlank { uiText("Not specified", "未填写") }, frameLabel(frame.reason, frame.source))
            change(frame.id, uiText("Saved at", "保存时刻"), null, Instant.ofEpochMilli(frame.savedAtMillis).atZone(ZoneId.systemDefault()).format(savedTimeFormat))
            change(frame.id, uiText("Frame source", "栈帧来源"), null, when (frame.source) {
                FrameSource.PUSH -> uiText("Saved from the queue", "从队列保存")
                FrameSource.MANUAL -> uiText("Created manually", "手动新建")
                FrameSource.SWAP -> uiText("Saved before switching frames", "切换前自动保存")
                FrameSource.GRAPH -> uiText("Generated from a graph goal", "从流程图目标生成")
            })
        }
        for (item in items) {
            change(item.frameId, uiText({ "Original item ${item.originalPosition + 1}" }, { "原第 ${item.originalPosition + 1} 项" }), null,
                buildString {
                    append(item.title).append(" · ").append(item.minutes).append(uiText(" min", " 分钟"))
                    if (item.scheduledStartMillis != null && item.scheduledEndMillis != null) {
                        append('\n').append(intervalLabel(item.scheduledStartMillis, item.scheduledEndMillis, ZoneId.systemDefault()))
                    }
                    if (item.notes.isNotEmpty()) append('\n').append(item.notes)
                })
        }
        if (base.restorePolicy != policy) change("工作台", uiText("Restore policy", "恢复策略"), base.restorePolicy.label(), policy.label(), uiText("Workspace", "工作台"))
        val settingsJson = appearance?.let { next ->
            AppearancePatch.changes(originalAppearance, next, changes)
            encodeAppearance(next)
        } ?: base.appearance
        val slotDelta = slots.finish(changes, edited)
        val courseWrites = courses.finish(changes) { start, end -> slots.effective(start, end, edited) }
        val graphWrites = graphs.values.mapNotNull { it.finish(changes) }
        val refs = if (bindings.isEmpty()) emptyMap() else Collections.unmodifiableMap(bindings.mapValues { it.value.id })
        ticks?.courses?.keys?.removeAll { !courses.contains(it.occurrence) { true } }
        val routineChanged = base.wakeMinute != routine.wakeMinute || base.sleepMinute != routine.sleepMinute
        val agendaChanged = routineChanged || slotDelta != null || courseWrites.isNotEmpty() || ticks != null ||
            updates.any { row ->
                val old = originals.getValue(row.id)
                (row.title != old.title || (row.owner == Owner.DONE) != (old.owner == Owner.DONE)) && slots.current(row.id) != null
            }
        return Proposal(base.revision, base.copy(revision = base.revision + 1, restorePolicy = policy, appearance = settingsJson,
            wakeMinute = routine.wakeMinute, sleepMinute = routine.sleepMinute), Collections.unmodifiableList(changes),
            frames, amendedFrames.orEmpty(), inserts, updates, positions, items, slotDelta, courseWrites, graphWrites, refs,
            deletedFrames.orEmpty(), ticks, agendaChanged)
    }

    private fun change(id: String, field: String, before: Any?, after: Any?, label: String? = edited[id]?.title) {
        if (before != after) changes.add(PlannerChange(id, field, before?.toString(), after?.toString(), label))
    }

    private fun Owner.label() = when (this) {
        Owner.QUEUE -> uiText("Queue", "队列")
        Owner.FRAME -> uiText("Frame", "栈帧")
        Owner.DONE -> uiText("Completed", "已完成")
        Owner.REMOVED -> uiText("Removed", "已移出")
    }

    private fun RestorePolicy.label() = when (this) {
        RestorePolicy.PREPEND -> uiText("Add to front", "插入队首")
        RestorePolicy.APPEND -> uiText("Add to end", "追加到队尾")
        RestorePolicy.SWAP -> uiText("Switch frames", "整帧切换")
    }
}

private val savedTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss xxx")
