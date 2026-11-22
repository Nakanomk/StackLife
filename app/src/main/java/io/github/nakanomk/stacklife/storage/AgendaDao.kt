package io.github.nakanomk.stacklife.storage

import androidx.room.*
import io.github.nakanomk.stacklife.planner.CourseOccurrenceId
import io.github.nakanomk.stacklife.planner.RoutineKind

@Entity(tableName = "course_ticks", primaryKeys = ["termId", "source", "week", "day", "firstPeriod", "lastPeriod", "title", "room"],
    foreignKeys = [ForeignKey(entity = CourseRow::class,
        parentColumns = ["termId", "source", "week", "day", "firstPeriod", "lastPeriod", "title", "room"],
        childColumns = ["termId", "source", "week", "day", "firstPeriod", "lastPeriod", "title", "room"], onDelete = ForeignKey.CASCADE)])
internal data class CourseTick(@Embedded val occurrence: CourseOccurrenceId)

@Entity(tableName = "routine_ticks", primaryKeys = ["epochDay", "kind"])
internal data class RoutineTick(val epochDay: Long, val kind: RoutineKind)

private const val KEY_COLUMNS = "termId,source,week,day,firstPeriod,lastPeriod,title,room"
private const val OPEN_COURSES = " FROM course_meetings c LEFT JOIN course_ticks d USING (" + KEY_COLUMNS + ") WHERE d.termId IS NULL AND "
private const val OPEN_TASKS = " FROM time_blocks b JOIN tasks t ON t.id=b.taskId WHERE t.owner IN ('QUEUE','FRAME') AND "
internal data class ReminderTaskRow(val id: String, val title: String)

@Dao
internal interface AgendaDao {
    @Query("SELECT d.* FROM course_ticks d JOIN course_meetings c USING (" + KEY_COLUMNS + ") WHERE c.startMillis < :end AND c.endMillis > :start")
    fun courseTicks(start: Long, end: Long): List<CourseTick>
    @Query("SELECT EXISTS(SELECT 1 FROM course_meetings WHERE termId=:termId AND source=:source AND week=:week AND day=:day AND firstPeriod=:first AND lastPeriod=:last AND title=:title AND room=:room)")
    fun hasMeeting(termId: String, source: String, week: Int, day: Int, first: Int, last: Int, title: String, room: String): Boolean
    @Upsert fun markCourse(tick: CourseTick)
    @Delete fun unmarkCourse(tick: CourseTick)
    @Query("SELECT kind FROM routine_ticks WHERE epochDay=:date") fun routineTicks(date: Long): List<RoutineKind>
    @Upsert fun markRoutine(tick: RoutineTick)
    @Delete fun unmarkRoutine(tick: RoutineTick)

    @Query("SELECT MIN(b.startMillis)" + OPEN_TASKS + "b.startMillis >= :start") fun nextTaskStart(start: Long): Long?
    @Query("SELECT t.id,t.title" + OPEN_TASKS + "b.startMillis = :start ORDER BY t.id") fun tasksAt(start: Long): List<ReminderTaskRow>
    @Query("SELECT MIN(c.startMillis)" + OPEN_COURSES + "c.startMillis >= :start") fun nextCourseStart(start: Long): Long?
    @Query("SELECT c.*" + OPEN_COURSES + "c.startMillis = :start ORDER BY c.termId,c.source,c.title,c.room,c.firstPeriod,c.lastPeriod")
    fun coursesAt(start: Long): List<CourseRow>
}

internal fun AgendaDao.hasMeeting(key: CourseOccurrenceId) = with(key) { hasMeeting(termId, source, week, day, firstPeriod, lastPeriod, title, room) }

internal class AgendaTicks {
    val courses = HashMap<CourseTick, Boolean>()
    val routines = HashMap<RoutineTick, Boolean>()
    fun applyTo(dao: AgendaDao) {
        for ((tick, done) in courses) if (done) dao.markCourse(tick) else dao.unmarkCourse(tick)
        for ((tick, done) in routines) if (done) dao.markRoutine(tick) else dao.unmarkRoutine(tick)
    }
}
