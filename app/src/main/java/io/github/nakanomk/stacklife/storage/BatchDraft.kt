package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import java.util.Collections
import java.util.UUID

/** Single preview transaction. The overlay contains only addressed tasks; old frame snapshots never change. */
internal class BatchDraft(private val dao: PlannerDao, courseDao: CourseDao, private val graphDao: GraphDao,
    scheduleDao: ScheduleDao, private val reviewed: Map<String, ReviewedCourses>, private val base: SettingsRow,
    private val originalSource: (GraphLink) -> LinkedSource?, countdownDao: CountdownDao, private val imageExists: (String) -> Boolean) {
    private val originals = HashMap<String, TaskRow>()
    private val edited = LinkedHashMap<String, TaskRow>()
    private val courses = CourseDraft(courseDao)
    private val agenda = AgendaDraft(scheduleDao, courses, base.revision + 1, edited, ::task)
    private val graphs = LinkedHashMap<String, GraphDraft>()
    private val countdowns = CountdownDraft(countdownDao, imageExists)
    private val frames = ArrayList<FrameRow>()
    private var amendedFrames: MutableList<FrameRow>? = null
    private var deletedFrames: MutableSet<String>? = null
    private val restores = mutableMapOf<String, MutableList<FrameRestoreAction>>()
    private val items = ArrayList<FrameItemRow>()
    private val members = HashMap<String, List<String>>()
    private val changes = ArrayList<PlannerChange>()
    private var effects: MutableList<WorkspaceEffect>? = null
    private var policy = base.restorePolicy
    private var keepRestoredFrames = base.keepRestoredFrames
    private var onboardingVersion = base.onboardingVersion
    private val originalAppearance by lazy(LazyThreadSafetyMode.NONE) { decodeAppearance(base.appearance) }
    private var appearance: Appearance? = null

    private enum class Kind { TASK, FRAME, GRAPH, NODE, COUNTDOWN }
    private data class Binding(val id: String, val kind: Kind)
    private val bindings = HashMap<String, Binding>()

    private var order: ArrayList<String>? = null
    private val initialPositions = LinkedHashMap<String, QueuePositionLabel>()

    fun plan(commands: List<PlannerCommand>): Proposal {
        if (commands.isEmpty()) invalid(uiText("Select at least one change.", "请至少选择一项更改。"))
        applyCommands(commands)
        val single = commands.singleOrNull()
        return finish(single is PlannerCommand.TaskRemove || single is PlannerCommand.DeleteFrameHistory ||
            single is PlannerCommand.FramePush || single is PlannerCommand.FrameRestore)
    }

    private fun applyCommands(commands: List<PlannerCommand>) {
        for (cmd in commands) when (cmd) {
            is PlannerCommand.TaskAdd -> {
                val row = newTask(cmd.task.title, cmd.task.notes, cmd.task.minutes, cmd.task.ref, frameId = null, cmd.task.icon)
                edited[row.id] = row
            }
            is PlannerCommand.TaskEdit -> {
                checkRevision(cmd.expectedRevision)
                if (cmd.title == null && cmd.notes == null && cmd.minutes == null && cmd.icon == null) invalid(uiText("Select a task field to edit.", "请选择需要修改的任务字段。"))
                val row = task(resolve(cmd.taskId, Kind.TASK))
                if (cmd.expectedFrameId != null && row.frameId != cmd.expectedFrameId)
                    invalid(uiText("This task is no longer in this frame.", "这项任务已不在此栈帧中。"))
                editTask(row, cmd.title, cmd.notes, cmd.minutes, cmd.icon)
            }
            is PlannerCommand.TaskFinish -> retire(cmd.taskId, Owner.DONE)
            is PlannerCommand.SetSchedule -> {
                checkRevision(cmd.expectedRevision)
                agenda.set(resolve(cmd.id, Kind.TASK), cmd.pattern, cmd.enabled)
            }
            is PlannerCommand.SetOccurrenceOverride -> {
                checkRevision(cmd.expectedRevision)
                agenda.override(cmd.copy(key = cmd.key.copy(scheduleId = resolve(cmd.key.scheduleId, Kind.TASK))))
            }
            is PlannerCommand.SetOccurrenceCompletion -> {
                checkRevision(cmd.expectedRevision)
                completeOccurrence(cmd.copy(key = resolveKey(cmd.key)))
            }
            is PlannerCommand.SetReminders -> {
                checkRevision(cmd.expectedRevision)
                agenda.reminders(when (val s = cmd.subject) {
                    ReminderSubject.CourseDefault -> s
                    is ReminderSubject.Schedule -> s.copy(id = resolve(s.id, Kind.TASK))
                    is ReminderSubject.Occurrence -> s.copy(key = resolveKey(s.key))
                }, cmd.policy)
            }
            is PlannerCommand.TaskRemove -> retire(cmd.taskId, Owner.REMOVED)
            is PlannerCommand.CourseImport -> courses.replace(reviewed[cmd.draftId] ?: invalid(uiText("The import draft expired. Review it again.", "导入草稿已失效，请重新核对。")))
            is PlannerCommand.TermRemove -> courses.remove(cmd.termId, cmd.source)
            is PlannerCommand.ManualCourse -> courses.manual(cmd)
            is PlannerCommand.QueueOrder -> reorder(cmd.taskIds)
            is PlannerCommand.FrameCreate -> {
                if (cmd.tasks.isEmpty()) invalid(uiText("A frame needs at least one task.", "栈帧至少需要一项任务。"))
                val frame = newFrame(cmd.reason, FrameSource.MANUAL, cmd.ref)
                populateFrame(frame, cmd.tasks.size) { index ->
                    val input = cmd.tasks[index]
                    newTask(input.title, input.notes, input.minutes, input.ref, frame.id, input.icon)
                }
            }
            is PlannerCommand.FramePush -> {
                val ids = taskIds(cmd.taskIds)
                if (ids.isEmpty()) invalid(uiText("Select the tasks to save in a frame.", "请选择需要压栈的任务。"))
                val frame = newFrame(cmd.reason, FrameSource.PUSH, cmd.ref)
                record(WorkspaceEffect.Pushed(frame.id, saveQueue(frame, ids)))
            }
            is PlannerCommand.FrameAddReason -> addReason(cmd)
            is PlannerCommand.FrameRestore -> restore(cmd)
            is PlannerCommand.DeleteFrameHistory -> {
                if (cmd.frameIds.isEmpty()) invalid(uiText("Select a historical frame.", "请选择要删除的历史栈帧。"))
                record(WorkspaceEffect.DeletedHistory(Collections.unmodifiableList(cmd.frameIds.map(::deleteHistory))))
            }
            is PlannerCommand.CompleteOnboarding -> {
                cmd.visible?.let { chosen ->
                    val old = appearance ?: originalAppearance
                    if (chosen.isEmpty()) invalid(uiText("Keep at least one destination.", "请至少保留一个功能。"))
                    if (cmd.baseline != null && (cmd.baseline.visible != old.visible || cmd.baseline.start != old.start || cmd.baseline.order != old.order))
                        invalid(uiText("Navigation changed. Open the guide again to review it.", "导航设置已变更，请重新打开引导核对。"))
                    if (old.visible != chosen) appearance = old.withVisible(chosen)
                }
                if (onboardingVersion == 0 && cmd.starterDay != null) applyCommands(starterPlan(cmd.starterDay))
                onboardingVersion = maxOf(onboardingVersion, 1)
            }
            is PlannerCommand.SetRestorePolicy -> policy = cmd.policy
            is PlannerCommand.KeepRestoredFrames -> keepRestoredFrames = cmd.keep
            is PlannerCommand.EditAppearance -> {
                val expected = cmd.expected
                if (expected != null && (if (cmd.patch is AppearanceFields) !cmd.patch.matchesBaseline(expected, originalAppearance)
                    else expected != originalAppearance)) invalid(uiText("Appearance settings have changed. Edit the latest settings instead.", "外观设置已变更，请按最新设置重新编辑。"))
                appearance = cmd.patch.applyTo(appearance ?: originalAppearance)
            }
            is PlannerCommand.CountdownAdd -> { checkRevision(cmd.expectedRevision); countdowns.add(newId(cmd.ref, Kind.COUNTDOWN), cmd) }
            is PlannerCommand.CountdownEdit -> { checkRevision(cmd.expectedRevision); countdowns.edit(cmd.copy(id = resolve(cmd.id, Kind.COUNTDOWN))) }
            is PlannerCommand.CountdownRemove -> { checkRevision(cmd.expectedRevision); countdowns.remove(resolve(cmd.id, Kind.COUNTDOWN)) }
            is PlannerCommand.CountdownSetPinned -> { checkRevision(cmd.expectedRevision); countdowns.pin(resolve(cmd.id, Kind.COUNTDOWN), cmd.pinned) }
            is PlannerCommand.CountdownOrder -> { checkRevision(cmd.expectedRevision); countdowns.order(cmd.copy(ids = cmd.ids.map { resolve(it, Kind.COUNTDOWN) })) }
            is PlannerCommand.GraphCreate -> {
                val id = newId(cmd.ref, Kind.GRAPH)
                graphs[id] = graphDraft(id, null, cmd.title)
            }
            is PlannerCommand.GraphPatch -> {
                checkRevision(cmd.expectedRevision)
                if (cmd.edits.isEmpty()) invalid(uiText("Select at least one graph change.", "请至少选择一项流程图更改。"))
                val graph = graph(cmd.graphId)
                for (edit in cmd.edits) {
                    val next = if (edit is GraphEdit.LinkExisting) edit.copy(link = resolveLink(edit.link)) else edit
                    graph.edit(next, newNodeRef, resolveNodeRef)
                }
            }
            is PlannerCommand.GraphRemove -> {
                checkRevision(cmd.expectedRevision)
                graph(cmd.graphId).remove()
            }
            is PlannerCommand.GraphFrame -> generateFrame(cmd)
            is PlannerCommand.GraphQueue -> generateQueue(cmd)
        }
    }

    private fun record(effect: WorkspaceEffect) {
        (effects ?: ArrayList<WorkspaceEffect>().also { effects = it }).add(effect)
    }

    private fun invalid(message: String): Nothing = throw InvalidCommand(message)

    private fun resolveKey(key: OccurrenceKey): OccurrenceKey = when (key) {
        is OccurrenceKey.Schedule -> key.copy(scheduleId = resolve(key.scheduleId, Kind.TASK))
        is OccurrenceKey.Course -> key
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
            graphDraft(id, stored, stored.title)
        }
        if (draft.title == null) invalid(uiText({ "Graph “$raw” has been removed." }, { "流程图「$raw」已移除。" }))
        return draft
    }

    private fun graphDraft(id: String, original: GraphSummary?, title: String) = GraphDraft(graphDao, id, original, title,
        ::linkedSource, ::editLinkedSource, ::completeLinkedSource, originalSource)

    private fun resolveLink(link: GraphLink): GraphLink = when (link) {
        is GraphLink.Task -> GraphLink.Task(resolve(link.taskId, Kind.TASK))
        is GraphLink.Occurrence -> link.copy(scheduleId = resolve(link.scheduleId, Kind.TASK))
    }

    private fun linkedSource(link: GraphLink): LinkedSource? = when (link) {
        is GraphLink.Task -> findTask(link.taskId)?.takeUnless { it.owner == Owner.REMOVED }?.let { t ->
            LinkedSource(link, t.title, t.notes, t.minutes, t.owner, t.id, agenda.current(t.id), null, t.frameId, t.queuePosition, t.icon)
        }
        is GraphLink.Occurrence -> agenda.resolve(link.key())?.let { o ->
            val t = o.taskId?.let(::findTask)
            LinkedSource(link, o.title, o.notes, t?.minutes ?: o.elapsedMinutes(),
                o.owner, o.taskId, agenda.current(link.scheduleId), o, t?.frameId, t?.queuePosition, o.icon)
        }
    }

    private fun editTask(row: TaskRow, name: String?, notes: String?, minutes: Int?, icon: TaskIcon?) {
        if (row.owner == Owner.REMOVED) invalid(uiText("Removed tasks cannot be edited.", "来源已移除，无法编辑。"))
        val duration = minutes ?: row.minutes
        edited[row.id] = row.copy(title = title(name ?: row.title, duration), notes = notes ?: row.notes, minutes = duration, icon = icon ?: row.icon)
    }

    private fun editLinkedSource(link: GraphLink, cmd: GraphEdit.EditNode) {
        val source = linkedSource(link) ?: invalid(uiText("The source was removed.", "来源已移除。"))
        val taskId = source.taskId ?: invalid(uiText("Edit a system routine in its schedule.", "请在日程中编辑系统作息。"))
        editTask(task(taskId), cmd.title, cmd.notes, cmd.minutes, cmd.icon)
    }

    private fun completeOccurrence(cmd: PlannerCommand.SetOccurrenceCompletion) {
        val actual = agenda.complete(cmd)
        if (!actual.daily && actual.taskId != null) setTaskCompletion(task(actual.taskId), cmd.completed)
    }

    private fun setTaskCompletion(row: TaskRow, completed: Boolean) {
        if (completed && row.owner != Owner.DONE) retire(row.id, Owner.DONE)
        else if (!completed && row.owner == Owner.DONE) reopen(row)
    }

    private fun completeLinkedSource(link: GraphLink, completed: Boolean) {
        when (link) {
            is GraphLink.Occurrence -> completeOccurrence(PlannerCommand.SetOccurrenceCompletion(link.key(), completed))
            is GraphLink.Task -> {
                val row = task(link.taskId)
                if (row.owner == Owner.REMOVED) invalid(uiText("The source was removed.", "来源已移除。"))
                val schedule = agenda.current(row.id)
                if (schedule?.enabled == true && schedule.pattern is SchedulePattern.Once)
                    completeOccurrence(PlannerCommand.SetOccurrenceCompletion(OccurrenceKey.Schedule(row.id), completed))
                else setTaskCompletion(row, completed)
            }
        }
    }

    private fun checkedGoal(graph: GraphDraft, goal: String): List<GraphNode> {
        val ordered = graph.order(resolveNodeRef(goal))
        ordered.firstOrNull { it.linkState?.missing == true }?.let { node ->
            invalid(uiText({ "Source removed: "+node.title }, { "来源已移除："+node.title }))
        }
        return ordered
    }

    private fun generateQueue(cmd: PlannerCommand.GraphQueue) {
        checkRevision(cmd.expectedRevision)
        val ordered = checkedGoal(graph(cmd.graphId), cmd.goalId)
        if (ordered.none { it.link == null }) invalid(uiText("All sources already exist. No tasks to append.", "全部为已有事件，没有可追加的新任务。"))
        for (node in ordered) if (node.link == null) {
            val t = newTask(node.title, node.notes, node.minutes, null, null, node.icon)
            edited[t.id] = t
        }
        changes.add(PlannerChange("goal:" + cmd.goalId + ":" + changes.size, null, GoalGenerationSnapshot(false, ordered)))
    }

    private fun generateFrame(cmd: PlannerCommand.GraphFrame) {
        checkRevision(cmd.expectedRevision)
        val graph = graph(cmd.graphId)
        val ordered = checkedGoal(graph, cmd.goalId)
        // In the ancestor closure every other vertex precedes the goal.
        val reason = cmd.reason ?: uiText({ "Generated for goal “${ordered.last().title}”" }, { "为目标〈${ordered.last().title}〉生成" })
        val refs = if (cmd.taskRefs.isEmpty()) null else HashMap<String, String>(cmd.taskRefs.size).apply {
            for ((node, ref) in cmd.taskRefs) if (put(resolveNodeRef(node), ref) != null)
                invalid(uiText({ "Node “$node” cannot declare more than one reference to its generated task." }, { "同一个节点「$node」不能重复声明生成任务的引用。" }))
        }
        val frame = newFrame(reason, FrameSource.GRAPH, cmd.ref)
        val owned = ArrayList<String>()
        for ((index, node) in ordered.withIndex()) {
            val link = node.link
            if (link == null) {
                val t = newTask(node.title, node.notes, node.minutes, refs?.remove(node.id), frame.id, node.icon)
                edited[t.id] = t; owned.add(t.id); snapshot(frame.id, index, t, null)
            } else {
                if (refs?.containsKey(node.id) == true) invalid(uiText("Only templates create a task alias.", "只有新建模板任务可以声明别名。"))
                val source = requireNotNull(linkedSource(link))
                val day = (link as? GraphLink.Occurrence)?.originalDate
                val id = when (link) { is GraphLink.Task -> link.taskId; is GraphLink.Occurrence -> link.scheduleId }
                val saved = source.schedule?.let { s -> SavedSchedule(schedule = s,
                    occurrenceInterval = source.occurrence?.let { ExactInterval(it.startMillis, it.endMillis) }) }
                items.add(FrameItemRow(frame.id, index, id, source.title, source.notes, source.minutes,
                    saved?.let { snapshotJson.encodeToString(it) }, referenceOnly = true, occurrenceDay = day, icon = source.icon))
            }
        }
        members[frame.id] = owned
        changes.add(PlannerChange("goal:" + frame.id, null, GoalGenerationSnapshot(true, ordered)))
        if (!refs.isNullOrEmpty()) invalid(uiText({ "Node “${refs.keys.first()}” is not this goal or one of its prerequisites. It cannot declare a task reference." }, { "节点「${refs.keys.first()}」不属于该目标及其前置清单，不能为它声明任务引用。" }))
    }

    private fun taskIds(raw: List<String>): Set<String> {
        val result = LinkedHashSet<String>(raw.size)
        for (id in raw) if (!result.add(resolve(id, Kind.TASK))) invalid(uiText("Select each task only once.", "任务不能重复选择。"))
        return result
    }

    private fun title(raw: String, minutes: Int): String {
        if (minutes < 0) invalid(uiText("Estimated duration cannot be negative.", "预计时长不能为负。"))
        return raw.trim().ifEmpty { invalid(uiText("Enter a task title.", "任务标题不能为空。")) }
    }

    private fun newTask(raw: String, notes: String, minutes: Int, ref: String?, frameId: String?, icon: TaskIcon): TaskRow {
        val id = newId(ref, Kind.TASK)
        return TaskRow(id, title(raw, minutes), notes, minutes,
            if (frameId == null) Owner.QUEUE else Owner.FRAME, frameId,
            if (frameId == null) append(id) else null, icon = icon)
    }

    private fun newFrame(reason: String, source: FrameSource, ref: String?): FrameRow {
        val label = reason.trim()
        if (label.isEmpty() && source != FrameSource.PUSH) invalid(uiText("Enter a reason for saving this frame.", "请填写保存栈帧的原因。"))
        return FrameRow(newId(ref, Kind.FRAME), label, System.currentTimeMillis(), source).also(frames::add)
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
        }
    }

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

    private fun snapshot(frameId: String, position: Int, row: TaskRow, slot: ScheduleSummary?) {
        items.add(FrameItemRow(frameId, position, row.id, row.title, row.notes, row.minutes,
            slot?.let { snapshotJson.encodeToString(SavedSchedule(schedule = it)) }, icon = row.icon))
    }

    private fun append(id: String): Int = queue().let { it.add(id); it.lastIndex }

    private fun queue(): ArrayList<String> {
        order?.let { return it }
        val initial = dao.queuePositions()
        val queue = ArrayList<String>(initial.size)
        for (row in initial) { initialPositions[row.id] = row; queue.add(row.id) }
        order = queue
        return queue
    }

    private fun findTask(id: String): TaskRow? = edited[id] ?: originals[id] ?: dao.tasks(listOf(id)).singleOrNull()?.also { originals[id] = it }
    private fun task(id: String): TaskRow = findTask(id) ?: invalid(uiText("The selected task does not exist.", "所选任务不存在。"))

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
        if (owner == Owner.REMOVED) {
            agenda.clear(row.id)
            record(WorkspaceEffect.Removed(row.id))
        }
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

    private fun deleteHistory(raw: String): String {
        val id = resolve(raw, Kind.FRAME)
        val deleted = deletedFrames ?: LinkedHashSet<String>().also { deletedFrames = it }
        val frame = frames.find { it.id == id } ?: dao.frame(id)
        if (frame == null || id in deleted) invalid(uiText("This frame has already been deleted.", "此栈帧已删除。"))
        val ids = members.getOrPut(id) { dao.restorableIds(id) }
        if (ids.any { edited[it]?.let { row -> row.owner == Owner.FRAME && row.frameId == id } ?: true })
            invalid(uiText("Restore or finish the remaining tasks before deleting this history.", "请先恢复或完成帧内剩余任务，再删除这条历史。"))
        deleted.add(id)
        return id
    }

    private fun reorder(raw: List<String>) {
        val ids = taskIds(raw)
        val queue = queue()
        if (ids.size != queue.size || queue.any { it !in ids }) invalid(uiText("Include every task in the current queue exactly once.", "排序必须包含当前队列的全部任务，且不能重复。"))
        queue.clear()
        queue.addAll(ids)
    }

    /** null selects the entire current queue (only the automatic SWAP snapshot uses this). */
    private fun saveQueue(frame: FrameRow, selection: Set<String>?): List<String> {
        val queue = queue()
        val expected = selection?.size ?: queue.size
        loadTasks(selection ?: queue)
        agenda.preload(selection ?: queue)
        val saved = ArrayList<String>(expected)
        var kept = 0
        for (position in queue.indices) {
            val id = queue[position]
            if (selection == null || id in selection) {
                val row = task(id)
                snapshot(frame.id, position, row, agenda.current(id))
                edited[id] = row.copy(owner = Owner.FRAME, frameId = frame.id, queuePosition = null)
                saved.add(id)
            } else {
                queue[kept++] = id
            }
        }
        if (saved.size != expected) invalid(uiText("Some selected tasks are no longer in the queue.", "部分所选任务已不在队列中。"))
        queue.subList(kept, queue.size).clear()
        val ordered = Collections.unmodifiableList(saved)
        members[frame.id] = ordered
        return ordered
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

        val effective = if (policy == RestorePolicy.SWAP && cmd.mode == RestoreMode.SELECTED) RestorePolicy.PREPEND else policy
        val saved = if (effective == RestorePolicy.SWAP && queue().isNotEmpty()) {
            val frame = newFrame(swapReason(), FrameSource.SWAP, null)
            WorkspaceEffect.Pushed(frame.id, saveQueue(frame, null))
        } else null
        if (policy != RestorePolicy.APPEND) queue().addAll(0, restoring)
        for ((index, id) in restoring.withIndex()) {
            val pos = if (policy == RestorePolicy.APPEND) append(id) else index
            edited[id] = task(id).copy(owner = Owner.QUEUE, frameId = null, queuePosition = pos)
        }
        val ordered = Collections.unmodifiableList(restoring)
        restores.getOrPut(frameId) { ArrayList() }.add(FrameRestoreAction(cmd.mode, policy, ordered, saved?.frameId))
        val emptied = !keepRestoredFrames && source.none { id ->
            edited[id]?.let { it.owner == Owner.FRAME && it.frameId == frameId } ?: true
        }
        if (emptied) deleteHistory(frameId)
        record(WorkspaceEffect.Restored(frameId, ordered, cmd.mode, effective, saved, sourceDeleted = emptied))
    }

    private fun finish(reversible: Boolean): Proposal {
        val positions = ArrayList<PositionUpdate>()
        order?.forEachIndexed { position, id ->
            val row = edited[id]
            if (row != null) {
                if (row.queuePosition != position) edited[id] = row.copy(queuePosition = position)
            } else {
                val original = initialPositions.getValue(id)
                if (original.queuePosition != position) {
                    positions.add(PositionUpdate(id, position))
                }
            }
        }
        val inserts = ArrayList<TaskRow>()
        val updates = ArrayList<TaskRow>()
        for ((id, row) in edited) {
            val before = originals[id]
            if (before == row) continue
            if (before == null) inserts.add(row) else updates.add(row)
            val original = before?.snapshot()
            val final = row.snapshot()
            if (original != final) changes.add(PlannerChange(id, original, final))
        }
        order?.let { queue ->
            if (initialPositions.size != queue.size || initialPositions.values.any { queue[it.queuePosition] != it.id }) {
                val previous = initialPositions.values.map { NamedId(it.id, it.title) }
                val next = queue.map { id -> NamedId(id, edited[id]?.title ?: initialPositions.getValue(id).title) }
                changes.add(PlannerChange("queue", QueueSnapshot(previous), QueueSnapshot(next)))
            }
        }
        snapshotFrames()
        if (base.restorePolicy != policy)
            changes.add(PlannerChange("restorePolicy", RestorePolicySnapshot(base.restorePolicy), RestorePolicySnapshot(policy)))
        if (base.keepRestoredFrames != keepRestoredFrames)
            changes.add(PlannerChange("keepRestoredFrames", KeepRestoredFramesSnapshot(base.keepRestoredFrames), KeepRestoredFramesSnapshot(keepRestoredFrames)))
        val settingsJson = appearance?.let { next ->
            for (id in next.backgroundIds() - originalAppearance.backgroundIds()) if (!imageExists(id))
                invalid(uiText("The selected background is no longer available.", "所选背景已不可用。"))
            if (next != originalAppearance)
                changes.add(PlannerChange("appearance", AppearanceSnapshot(originalAppearance), AppearanceSnapshot(next)))
            encodeAppearance(next)
        } ?: base.appearance
        val courseWrites = courses.finish(changes, base.revision + 1, agenda::storedBetween, agenda::between)
        agenda.migrateCourses(courseWrites)
        val agendaWrites = agenda.finish(changes)
        val graphWrites = graphs.values.mapNotNull { it.finish(changes) }
        val countdownWrites = countdowns.finish(changes)
        val refs = if (bindings.isEmpty()) emptyMap() else Collections.unmodifiableMap(bindings.mapValues { it.value.id })
        val agendaChanged = agendaWrites.changed || courseWrites.isNotEmpty() || updates.any { row ->
            val old = originals.getValue(row.id)
            (row.owner == Owner.DONE) != (old.owner == Owner.DONE) && agenda.current(row.id) != null
        }
        return Proposal(base.revision, base.copy(revision = base.revision + 1, restorePolicy = policy, appearance = settingsJson,
            onboardingVersion = onboardingVersion, keepRestoredFrames = keepRestoredFrames),
            Collections.unmodifiableList(changes), frames, amendedFrames.orEmpty(), inserts, updates, positions, items,
            agendaWrites, courseWrites, graphWrites, countdownWrites, refs, effects?.let(Collections::unmodifiableList).orEmpty(),
            deletedFrames.orEmpty(), reversible, agendaChanged,
            countdownWrites?.imagesChanged == true || appearance?.let { it.backgroundIds() != originalAppearance.backgroundIds() } == true)
    }

    private fun TaskRow.snapshot() = if (owner == Owner.REMOVED) null else TaskSnapshot(title, notes, minutes, owner, frameId, queuePosition, icon)

    private fun snapshotFrames() {
        val addressed = buildSet {
            frames.forEach { add(it.id) }; amendedFrames?.forEach { add(it.id) }; addAll(deletedFrames.orEmpty())
            for ((id, row) in edited) {
                val prior = originals[id]?.frameId
                if (row.frameId != prior) { prior?.let(::add); row.frameId?.let(::add) }
            }
        }
        if (addressed.isEmpty()) return
        val newFrames = frames.associateBy { it.id }
        val amended = amendedFrames?.associateBy { it.id }.orEmpty()
        val newItems = items.groupBy { it.frameId }
        fun FrameItemRow.entry(available: Boolean): FrameItemSnapshot {
            val saved = savedSchedule()
            val interval = saved?.occurrenceInterval ?: (saved?.schedule?.pattern as? SchedulePattern.Once)?.interval
            return FrameItemSnapshot(taskId, originalPosition, title, notes, minutes, interval?.startMillis, interval?.endMillis,
                !referenceOnly && available, saved?.schedule, referenceOnly, occurrenceDay, icon)
        }
        for (id in addressed) {
            val created = newFrames[id]
            val original = if (created != null) null else dao.frame(id)
            val oldItems = if (original == null) emptyList() else dao.frameEntries(id).map { it.item.entry(it.current != null) }
            // For a deletion the retained before-side is the only frame shown in the review.
            val before = original?.let { FrameSnapshot(it.reason, it.savedAtMillis, it.source, oldItems,
                if (id in deletedFrames.orEmpty()) restores[id].orEmpty() else emptyList()) }
            val row = created ?: amended[id] ?: original
            val after = if (row == null || id in deletedFrames.orEmpty()) null else {
                val finalItems = if (created != null) newItems[id].orEmpty().map { it.entry(edited[it.taskId]?.frameId == id) }
                    else oldItems.map { item -> if (item.referenceOnly) item else edited[item.taskId]?.let { item.copy(restorable = it.frameId == id) } ?: item }
                FrameSnapshot(row.reason, row.savedAtMillis, row.source, finalItems, restores[id].orEmpty())
            }
            if (before != after) changes.add(PlannerChange(id, before, after))
        }
    }

}
