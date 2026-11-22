package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.planner.AgendaOccurrence
import io.github.nakanomk.stacklife.planner.agendaOrder
import io.github.nakanomk.stacklife.planner.intersectsWindow

/** FullCalendar Standard timegrid/core 6.1.22, buildPositioning/SegHierarchy/stretchWeb/
 * groupIntersectingEntries (MIT). Two unit-thickness levels, sorted starts, no reslicing.
 * Additional StackLife rule: an instant intersects another instant at the same time or a
 * positive half-open interval that contains it. Its tap target is never occupancy. */
internal class TimeGridPlacement(chronological: List<AgendaOccurrence>, from: Long, until: Long) {
    data class Rect(val item: AgendaOccurrence, val start: Long, val end: Long, val column: Int, var columns: Int)
    data class Overflow(val start: Long, val end: Long, val hidden: List<AgendaOccurrence>)

    private val levels = arrayOf(ArrayList<Rect>(), ArrayList<Rect>())
    val visible: List<Rect>
    val more: List<Overflow>

    init {
        val placed = ArrayList<Rect>()
        var hidden = ArrayList<AgendaOccurrence>()
        val groups = ArrayList<Overflow>()
        var groupStart = 0L; var groupEnd = 0L
        for (item in chronological) {
            if (!intersectsWindow(item.startMillis, item.endMillis, from, until)) continue
            val start = maxOf(item.startMillis, from); val end = minOf(item.endMillis, until)
            fun touching(level: Int) = levels[level].lastOrNull()?.takeIf { touches(start, end, it.start, it.end) }
            val left = touching(0); val right = touching(1)
            val column = if (left == null) 0 else if (right == null) 1 else -1
            if (column >= 0) {
                val neighbor = left ?: right
                val rect = Rect(item, start, end, column, if (neighbor == null) 1 else 2)
                if (neighbor != null) neighbor.columns = 2
                levels[column].add(rect); placed.add(rect)
            } else {
                if (hidden.isEmpty() || !touches(groupStart, groupEnd, start, end)) {
                    if (hidden.isNotEmpty()) {
                        groups += Overflow(groupStart, groupEnd, hidden)
                        hidden = ArrayList()
                    }
                    groupStart = start; groupEnd = end
                } else groupEnd = maxOf(groupEnd, end)
                hidden.add(item)
            }
        }
        if (hidden.isNotEmpty()) groups += Overflow(groupStart, groupEnd, hidden)
        visible = placed; more = groups
    }

    /** Resolve only the drawer the user opens. Includes visible neighbors in either level. */
    fun members(group: Overflow): List<AgendaOccurrence> {
        val matches = ArrayList<AgendaOccurrence>(group.hidden.size + 2)
        matches.addAll(group.hidden)
        for (level in levels) {
            var low = 0; var high = level.size
            while (low < high) {
                val i = (low + high).ushr(1); val rect = level[i]
                if (rect.end < group.start || rect.end == group.start && rect.start < rect.end) low = i + 1 else high = i
            }
            while (low < level.size) {
                val rect = level[low++]
                if (!touches(group.start, group.end, rect.start, rect.end)) break
                matches.add(rect.item)
            }
        }
        return matches.apply { sortWith(agendaOrder) }
    }
}

private fun touches(a: Long, b: Long, c: Long, d: Long): Boolean = when {
    a == b -> if (c == d) a == c else a >= c && a < d
    c == d -> c >= a && c < b
    else -> a < d && c < b
}
