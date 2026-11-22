package io.github.nakanomk.stacklife.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface AlarmDao {
    @Query("SELECT r.* FROM reminder_rules r JOIN reminder_settings s ON s.subject=r.subject WHERE NOT s.inherit AND (r.leadMillis IS NOT NULL OR r.instantMillis>=:from)")
    fun candidateRules(from: Long): List<ReminderRuleRow>

    @Query("SELECT MIN(startMillis) FROM course_meetings WHERE startMillis>=:from")
    fun nextCourseStart(from: Long): Long?
    @Query("SELECT * FROM course_meetings WHERE startMillis=:start")
    fun coursesAt(start: Long): List<CourseRow>
    @Query("SELECT * FROM agenda_exceptions WHERE scheduleId=:id AND startMillis>=:from AND startMillis<=:until ORDER BY startMillis,originalDay")
    fun moved(id: String, from: Long, until: Long): List<ExceptionRow>

    @Query("SELECT status FROM reminder_receipts WHERE occurrenceKey=:key AND ruleId=:id AND triggerMillis=:trigger")
    fun status(key: String, id: String, trigger: Long): DeliveryStatus?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun acknowledge(row: ReminderReceiptRow): Long
    @Query("UPDATE reminder_receipts SET status='DISMISSED' WHERE occurrenceKey=:key AND ruleId=:id AND triggerMillis=:trigger")
    fun dismiss(key: String, id: String, trigger: Long)
    @Query("SELECT EXISTS(SELECT 1 FROM planner_settings WHERE id=1 AND receiptsImported)") fun imported(): Boolean
    @Query("UPDATE planner_settings SET receiptsImported=1 WHERE id=1") fun finishImport(): Int
}
