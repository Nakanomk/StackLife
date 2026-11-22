package io.github.nakanomk.stacklife.today

import io.github.nakanomk.stacklife.planner.*
import org.junit.Assert.*
import org.junit.Test

class TimeGridPlacementTest {
    private fun item(id: String, start: Long, end: Long) = AgendaOccurrence(OccurrenceKey.Schedule(id), id, "", start, end,
        completed = false, version = 0, taskId = id, owner = Owner.QUEUE)

    @Test fun twoVisibleTwentySavedAndTheDrawerRetainsEveryStableIdentity() {
        val all = (1..20).map { item(it.toString(), 10, 20) }.sortedWith(agendaOrder)
        val layout = TimeGridPlacement(all, 0, 100)
        assertEquals(listOf(0, 1), layout.visible.map { it.column }); assertTrue(layout.visible.all { it.columns == 2 })
        val group = layout.more.single()
        assertEquals(18, group.hidden.size); assertEquals(all, layout.members(group))
    }

    @Test fun separatedHiddenSpansShareTheLongVisibleParentButNotUnrelatedNeighbors() {
        val a = item("A", -20, 90); val b = item("B", 1, 80)
        val c = item("C", 10, 20); val d = item("D", 19, 40); val e = item("E", 40, 60)
        val f = item("F", 90, 95)
        val layout = TimeGridPlacement(listOf(a,b,c,d,e,f), 0, 100)
        assertEquals(listOf(10L to 40L, 40L to 60L), layout.more.map { it.start to it.end })
        assertEquals(listOf(a,b,c,d), layout.members(layout.more.first()))
        assertEquals(listOf(a,b,e), layout.members(layout.more.last()))
        assertEquals(1, layout.visible.last().columns); assertEquals(0, layout.visible.first().start)
    }

    @Test fun pointsAtSameInstantFormAGroupWithoutClaimingTimeOrAnotherCivilDay() {
        val old = item("yesterday", -1, 0); val tomorrow = item("tomorrow", 100, 100)
        val points = listOf("a", "b", "c").map { item(it, 0, 0) }
        val startsNow = item("start", 0, 5); val alsoAtFive = item("five", 5, 5)
        val layout = TimeGridPlacement((listOf(old, tomorrow, startsNow, alsoAtFive) + points).sortedWith(agendaOrder), 0, 100)
        val group = layout.more.single()
        assertEquals(points + startsNow, layout.members(group)); assertEquals(0 to 5, group.start.toInt() to group.end.toInt())
        val atFive = layout.visible.single { it.item == alsoAtFive }
        assertEquals(atFive.start, atFive.end); assertEquals(1, atFive.columns)
        assertFalse(layout.visible.any { it.item == old || it.item == tomorrow })
    }
}
