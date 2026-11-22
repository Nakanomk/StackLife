package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*
import java.time.ZoneId

/** Related-task selection by identity: go-vikunja/vikunja@2ba8cef799cf12f7d0cf1196a180d75a2890c873,
 * frontend/src/components/tasks/partials/RelatedTasks.vue and components/input/Multiselect.vue (AGPL-3.0).
 * Behavior only: StackLife's Room queries and dated occurrence identities use no upstream code. */
internal class GraphLinkReader(db: PlannerDatabase) {
    private val graphs = db.graphDao()
    private val schedules = db.scheduleDao()
    private val reader = ScheduleReader(schedules)

    /** Called inside the caller's read transaction, including for a graph diff's persistent baseline. */
    fun source(link: GraphLink): LinkedSource? {
        return when (link) {
            is GraphLink.Task -> {
                val task = graphs.linkableTask(link.taskId) ?: return null
                LinkedSource(link, task.title, task.notes, task.minutes, task.owner, task.id, reader.load(task.id)?.summary(), null,
                    frameId = task.frameId, queuePosition = task.queuePosition)
            }
            is GraphLink.Occurrence -> {
                val series = reader.load(link.scheduleId) ?: return null
                val occurrence = reader.resolve(link.key(), ZoneId.systemDefault(), series) ?: return null
                val task = occurrence.taskId?.let { graphs.linkableTask(it) ?: return null }
                LinkedSource(link, occurrence.title, occurrence.notes, task?.minutes ?: occurrence.elapsedMinutes(),
                    occurrence.owner, occurrence.taskId, series.summary(), occurrence,
                    frameId = task?.frameId, queuePosition = task?.queuePosition)
            }
        }
    }

    fun page(query: PlannerQuery.LinkableEvents, revision: Long): LinkablePage {
        requireNotNull(graphs.graph(query.graphId)) { uiText("This graph no longer exists.", "该流程图已不存在。") }
        val owner = when (query.sourceFilter) {
            LinkFilter.QUEUE -> Owner.QUEUE
            LinkFilter.FRAME -> Owner.FRAME
            LinkFilter.DONE -> Owner.DONE
            LinkFilter.ALL, LinkFilter.DAY -> null
        }
        val onlyDay = query.sourceFilter == LinkFilter.DAY
        val search = query.search.trim()
        val ordinary = if (onlyDay) 0 else graphs.taskCount(owner, search)
        val items = ArrayList<LinkCandidate>(query.limit)
        if (query.offset < ordinary) {
            for (row in graphs.taskPage(owner, search, query.offset, query.limit)) {
                items.add(LinkCandidate(GraphLink.Task(row.id), row.title, LinkState(row.owner, row.owner == Owner.DONE, row.scheduled),
                    linked = false, startMillis = row.startMillis))
            }
        }

        var total = ordinary
        val zone = ZoneId.systemDefault()
        if (onlyDay) {
            // A calendar day means actual presence, including moved-in and carry-in events.
            val day = PlannerQuery.Day(query.date, zone)
            for (event in reader.between(day.startMillis, day.endMillis, zone)) {
                val key = event.key as OccurrenceKey.Schedule // ScheduleReader never reads course rows.
                if (!event.title.contains(search, ignoreCase = true) && !key.scheduleId.contains(search)) continue
                if (total++ < query.offset || items.size == query.limit) continue
                val link = key.originalDay?.let { GraphLink.Occurrence(key.scheduleId, it) } ?: GraphLink.Task(requireNotNull(event.taskId))
                items.add(LinkCandidate(link, event.title, LinkState(event.owner, event.completed, scheduled = true, system = event.role != null),
                    linked = false, startMillis = event.startMillis))
            }
        } else {
            // Here the user picked an original recurrence date, even if this instance moved away.
            val date = query.date.toEpochDay()
            val dailyStart = items.size
            for (row in graphs.dailyOn(date, owner, search)) {
                val title = row.role?.label() ?: requireNotNull(row.title)
                if (row.role != null && !title.contains(search, ignoreCase = true) && !row.id.contains(search)) continue
                val start = row.movedStart ?: row.segment?.intervalOn(date, zone)?.startMillis ?: continue
                if (total++ < query.offset || items.size == query.limit) continue
                items.add(LinkCandidate(GraphLink.Occurrence(row.id, date), title, LinkState(row.owner, false, true, system = row.role != null),
                    linked = false, startMillis = start))
            }
            if (items.size > dailyStart) {
                val keys = items.subList(dailyStart, items.size).map { (it.link as GraphLink.Occurrence).key().storageKey() }
                val marked = schedules.completed(keys).toHashSet()
                for ((i, key) in keys.withIndex()) if (key in marked) {
                    val index = dailyStart + i
                    items[index] = items[index].copy(state = items[index].state.copy(completed = true))
                }
            }
        }
        if (items.isNotEmpty()) {
            val keys = items.map { it.link.storageKey() }
            val linked = graphs.linkedKeys(query.graphId, keys).toHashSet()
            for (i in items.indices) if (keys[i] in linked) items[i] = items[i].copy(linked = true)
        }
        return LinkablePage(revision, total, items)
    }
}

/** Only the template's Int estimate is bounded; Source still exposes the full exact interval. */
internal fun AgendaOccurrence.elapsedMinutes(): Int {
    val elapsed = endMillis - startMillis
    return if (elapsed < 0) Int.MAX_VALUE else (elapsed / 60_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
