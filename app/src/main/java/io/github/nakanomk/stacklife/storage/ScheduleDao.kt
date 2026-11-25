package io.github.nakanomk.stacklife.storage

import androidx.room.*
import io.github.nakanomk.stacklife.planner.*

internal data class AgendaHeader(@Embedded val root: ScheduleRow, val title: String?, val notes: String?, val owner: Owner?, val icon: TaskIcon?)
private const val HEAD = "SELECT s.*,t.title,t.notes,t.owner,t.icon FROM agenda_schedules s LEFT JOIN tasks t ON t.id=s.taskId "
private const val WINDOW = "startMillis < :until AND (endMillis > :from OR startMillis=endMillis AND startMillis=:from)"

@Dao
internal interface ScheduleDao {
    @Query(HEAD + "WHERE s.id=:id") fun header(id: String): AgendaHeader?
    @Query(HEAD + "WHERE s.id IN (:ids)") fun headers(ids: List<String>): List<AgendaHeader>
    @Query(HEAD + "WHERE s.enabled AND s.startMillis IS NOT NULL AND " + WINDOW) fun once(from: Long, until: Long): List<AgendaHeader>
    @Query(HEAD + "WHERE s.enabled AND s.startMillis IS NULL AND (t.owner IN ('FRAME','QUEUE') OR s.role IS NOT NULL)") fun series(): List<AgendaHeader>
    @Query("SELECT * FROM agenda_schedule_segments WHERE scheduleId IN (:ids) ORDER BY scheduleId,fromDay") fun segments(ids: List<String>): List<SegmentRow>
    @Query("SELECT * FROM agenda_exceptions WHERE scheduleId=:id AND originalDay=:day") fun exception(id: String, day: Long): ExceptionRow?
    @Query("SELECT * FROM agenda_exceptions WHERE scheduleId IN (:ids) AND originalDay BETWEEN :first AND :last")
    fun originals(ids: List<String>, first: Long, last: Long): List<ExceptionRow>
    @Query("SELECT * FROM agenda_exceptions WHERE " + WINDOW) fun moved(from: Long, until: Long): List<ExceptionRow>
    @Query("SELECT * FROM agenda_exceptions WHERE scheduleId=:id ORDER BY originalDay") fun exceptions(id: String): List<ExceptionRow>
    @Query("SELECT occurrenceKey FROM agenda_completions WHERE occurrenceKey IN (:keys)") fun completed(keys: List<String>): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM agenda_completions WHERE occurrenceKey=:key)") fun completed(key: String): Boolean
    @Query("SELECT * FROM reminder_settings WHERE subject=:subject") fun setting(subject: String): ReminderSettingRow?
    @Query("SELECT * FROM reminder_rules WHERE subject=:subject ORDER BY rowid") fun rules(subject: String): List<ReminderRuleRow>
    @Query("SELECT * FROM reminder_settings WHERE rootId=:id ORDER BY subject") fun scheduleSettings(id: String): List<ReminderSettingRow>
    @Query("SELECT r.* FROM reminder_rules r JOIN reminder_settings s ON s.subject=r.subject WHERE s.rootId=:id ORDER BY r.subject,r.rowid")
    fun scheduleRules(id: String): List<ReminderRuleRow>
    @Upsert fun write(root: ScheduleRow)
    @Upsert fun writeSegments(rows: List<SegmentRow>)
    @Upsert fun write(exception: ExceptionRow)
    @Query("DELETE FROM agenda_exceptions WHERE scheduleId=:id AND originalDay=:day") fun deleteException(id: String, day: Long)
    @Query("DELETE FROM agenda_schedule_segments WHERE scheduleId=:id") fun clearSegments(id: String)
    @Query("DELETE FROM agenda_schedules WHERE id=:id") fun deleteRoot(id: String)
    @Upsert fun complete(completion: CompletionRow)
    @Query("DELETE FROM agenda_completions WHERE occurrenceKey=:key") fun reopen(key: String)
    @Upsert fun write(setting: ReminderSettingRow)
    @Insert fun insertRules(rows: List<ReminderRuleRow>)
    @Query("DELETE FROM reminder_settings WHERE subject=:subject") fun deletePolicy(subject: String)
    @Query("INSERT OR IGNORE INTO reminder_receipts(occurrenceKey,ruleId,triggerMillis,status) SELECT :newKey,ruleId,triggerMillis,status FROM reminder_receipts WHERE occurrenceKey=:oldKey")
    fun copyReceipts(oldKey: String, newKey: String)
}

internal fun ScheduleDao.policy(subject: ReminderSubject): ReminderPolicy {
    val key = subject.storageKey()
    val setting = setting(key) ?: return if (subject is ReminderSubject.Occurrence) ReminderPolicy(true, emptyList()) else initialReminderPolicy
    return ReminderPolicy(setting.inherit, rules(key).map(ReminderRuleRow::rule))
}
internal fun ScheduleDao.putPolicy(subject: ReminderSubject, policy: ReminderPolicy?) {
    val key = subject.storageKey()
    deletePolicy(key)
    if (policy == null) return
    write(ReminderSettingRow(key, policy.inherit, subject.rootId()))
    if (policy.rules.isNotEmpty()) insertRules(policy.rules.map { rule -> when (val t = rule.time) {
        is ReminderWhen.BeforeStart -> ReminderRuleRow(key, rule.id, t.leadMillis, null)
        is ReminderWhen.AtInstant -> ReminderRuleRow(key, rule.id, null, t.instantMillis)
    } })
}

internal fun ReminderSubject.rootId(): String? = when (this) {
    is ReminderSubject.Schedule -> id
    is ReminderSubject.Occurrence -> (key as? OccurrenceKey.Schedule)?.scheduleId
    ReminderSubject.CourseDefault -> null
}

internal fun OccurrenceKey.reminderParent(): ReminderSubject = when (this) {
    is OccurrenceKey.Schedule -> ReminderSubject.Schedule(scheduleId)
    is OccurrenceKey.Course -> ReminderSubject.CourseDefault
}
internal fun ScheduleDao.effectiveRules(subject: ReminderSubject, policy: ReminderPolicy): List<ReminderRule> =
    if (policy.inherit && subject is ReminderSubject.Occurrence) this.policy(subject.key.reminderParent()).rules else policy.rules
