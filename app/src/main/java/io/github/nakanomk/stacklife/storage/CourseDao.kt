package io.github.nakanomk.stacklife.storage

import androidx.room.*
import io.github.nakanomk.stacklife.courses.*
import java.time.LocalDate

@Entity(tableName = "course_terms", primaryKeys = ["termId", "source"])
internal data class TermRow(val termId: String, val source: String, val monday: Long, val weeks: Int,
    val initial: BellSchedule, val switchDay: Long?) {
    fun spec() = TermSpec(termId, LocalDate.ofEpochDay(monday), weeks, initial, switchDay?.let(LocalDate::ofEpochDay))
}

@Entity(tableName = "course_meetings", primaryKeys = ["termId", "source", "week", "day", "firstPeriod", "lastPeriod", "title", "room"],
    foreignKeys = [ForeignKey(entity = TermRow::class, parentColumns = ["termId", "source"], childColumns = ["termId", "source"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("endMillis")])
internal data class CourseRow(val termId: String, val source: String, @Embedded val meeting: CourseMeeting)

@Dao
internal interface CourseDao {
    @Query("SELECT * FROM course_terms WHERE source IN (:hub,:manual) ORDER BY monday DESC,termId,source")
    fun terms(hub: String, manual: String): List<TermRow>
    @Query("SELECT * FROM course_terms WHERE termId=:id AND source=:source") fun term(id: String, source: String): TermRow?
    @Query("SELECT * FROM course_meetings WHERE termId=:id AND source=:source ORDER BY startMillis,endMillis,title,room")
    fun meetings(id: String, source: String): List<CourseRow>
    @Query("SELECT DISTINCT title FROM course_meetings WHERE termId=:id AND source IN (:hub,:manual) ORDER BY title")
    fun orderedTitles(id: String, hub: String, manual: String): List<String>
    @Query("SELECT week FROM course_meetings WHERE termId=:id AND source=:source AND title=:title AND room=:room AND day=:day AND firstPeriod=:first AND lastPeriod=:last ORDER BY week")
    fun patternWeeks(id: String, source: String, title: String, room: String, day: Int, first: Int, last: Int): List<Int>
    @Query("SELECT * FROM course_meetings WHERE startMillis < :end AND endMillis > :start ORDER BY startMillis,endMillis,termId,title,room")
    fun intersecting(start: Long, end: Long): List<CourseRow>
    @Upsert fun put(term: TermRow)
    @Query("DELETE FROM course_terms WHERE termId=:id AND source=:source") fun remove(id: String, source: String)
    @Insert fun insert(meetings: List<CourseRow>)
    @Delete fun delete(meetings: List<CourseRow>)
}
