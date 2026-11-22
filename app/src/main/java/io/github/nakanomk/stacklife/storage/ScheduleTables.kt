package io.github.nakanomk.stacklife.storage

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.nakanomk.stacklife.planner.*

@Entity(tableName = "agenda_schedules", foreignKeys = [ForeignKey(entity = TaskRow::class, parentColumns = ["id"],
    childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["taskId"], unique = true),
    Index(value = ["role"], unique = true), Index(value = ["startMillis"]), Index(value = ["endMillis"])])
internal data class ScheduleRow(@PrimaryKey val id: String, val taskId: String?, val startMillis: Long?, val endMillis: Long?,
    val enabled: Boolean, val role: RoutineKind?, val version: Long)

@Entity(tableName = "agenda_schedule_segments", primaryKeys = ["scheduleId", "fromDay"],
    foreignKeys = [ForeignKey(entity = ScheduleRow::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)])
internal data class SegmentRow(val scheduleId: String, @Embedded val segment: DailySegment)

/** Null start/end is an explicit cancellation; originalDay remains unchanged when moved. */
@Entity(tableName = "agenda_exceptions", primaryKeys = ["scheduleId", "originalDay"],
    foreignKeys = [ForeignKey(entity = ScheduleRow::class, parentColumns = ["id"], childColumns = ["scheduleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["startMillis"]), Index(value = ["endMillis"])])
internal data class ExceptionRow(val scheduleId: String, val originalDay: Long, val startMillis: Long?, val endMillis: Long?, val version: Long)

/** Explicit canonical key. Historical completion need not have an active schedule or course row. */
@Entity(tableName = "agenda_completions")
internal data class CompletionRow(@PrimaryKey val occurrenceKey: String)

@Entity(tableName = "reminder_settings",foreignKeys = [ForeignKey(entity = ScheduleRow::class,parentColumns = ["id"],
    childColumns = ["rootId"],onDelete = ForeignKey.CASCADE)],indices = [Index("rootId")])
internal data class ReminderSettingRow(@PrimaryKey val subject: String, val inherit: Boolean, val rootId: String?)

@Entity(tableName = "reminder_rules", primaryKeys = ["subject", "id"], foreignKeys = [ForeignKey(entity = ReminderSettingRow::class,
    parentColumns = ["subject"], childColumns = ["subject"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["instantMillis"])])
internal data class ReminderRuleRow(val subject: String, val id: String, val leadMillis: Long?, val instantMillis: Long?) {
    fun rule() = ReminderRule(id, if (instantMillis != null) ReminderWhen.AtInstant(instantMillis) else ReminderWhen.BeforeStart(requireNotNull(leadMillis)))
}

enum class DeliveryStatus { DELIVERED, DISMISSED }
@Entity(tableName = "reminder_receipts", primaryKeys = ["occurrenceKey", "ruleId", "triggerMillis"], indices = [Index(value = ["triggerMillis"])])
internal data class ReminderReceiptRow(val occurrenceKey: String, val ruleId: String, val triggerMillis: Long, val status: DeliveryStatus)
