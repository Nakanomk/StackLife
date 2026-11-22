package io.github.nakanomk.stacklife.storage

import android.content.Context
import androidx.room.Room
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.nakanomk.stacklife.assistant.ConversationImageRow
import io.github.nakanomk.stacklife.planner.Owner
import io.github.nakanomk.stacklife.assistant.ConversationHistory
import io.github.nakanomk.stacklife.assistant.ConversationMessageRow
import io.github.nakanomk.stacklife.assistant.ConversationRow
import io.github.nakanomk.stacklife.planner.SavedSchedule
import io.github.nakanomk.stacklife.planner.ScheduleSummary
import kotlinx.serialization.json.Json
import io.github.nakanomk.stacklife.planner.FrameSummary
import io.github.nakanomk.stacklife.planner.FrameSource
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.RestorePolicy

@Database(
    entities = [TaskRow::class, FrameRow::class, FrameItemRow::class, SettingsRow::class,
        TermRow::class, CourseRow::class, GraphRow::class, GraphNodeRow::class, GraphEdgeRow::class,
        ConversationRow::class, ConversationMessageRow::class, ConversationImageRow::class, ScheduleRow::class,
        SegmentRow::class, ExceptionRow::class, CompletionRow::class, ReminderSettingRow::class, ReminderRuleRow::class,
        ReminderReceiptRow::class, CountdownRow::class],
    version = 12,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9, spec = ExistingInstall::class), AutoMigration(from = 11, to = 12)],
    exportSchema = true,
)
abstract class PlannerDatabase : RoomDatabase() {
    internal abstract fun plannerDao(): PlannerDao
    internal abstract fun courseDao(): CourseDao
    internal abstract fun scheduleDao(): ScheduleDao
    internal abstract fun alarmDao(): AlarmDao
    internal abstract fun graphDao(): GraphDao
    internal abstract fun countdownDao(): CountdownDao
    internal abstract fun conversationHistory(): ConversationHistory
    companion object {
        internal fun open(context: Context, name: String): PlannerDatabase =
            Room.databaseBuilder(context.applicationContext, PlannerDatabase::class.java, name)
                .addMigrations(AgendaMigration, GraphLinkMigration).addCallback(AgendaDefaults).build()
    }
}

/** An upgrade is not a fresh installation, even if the user never wrote a planner setting. */
internal class ExistingInstall : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("INSERT OR IGNORE INTO planner_settings(id,revision,restorePolicy,appearance,onboardingVersion) VALUES (1,0,'PREPEND','{}',1)")
        connection.execSQL("UPDATE planner_settings SET onboardingVersion=1 WHERE id=1")
    }
}

@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(entity = FrameRow::class, parentColumns = ["id"], childColumns = ["frameId"])],
    indices = [Index(value = ["owner", "queuePosition"]), Index(value = ["frameId"])],
)
internal data class TaskRow(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String,
    val minutes: Int,
    val owner: Owner,
    val frameId: String?,
    val queuePosition: Int?,
    val completedFromFrame: String? = null,
    val completedFromPosition: Int? = null,
)

@Entity(tableName = "frames", indices = [Index(value = ["savedAtMillis"])])
internal data class FrameRow(
    @PrimaryKey val id: String,
    val reason: String,
    val savedAtMillis: Long,
    val source: FrameSource,
)

@Entity(
    tableName = "frame_items",
    primaryKeys = ["frameId", "originalPosition"],
    foreignKeys = [
        ForeignKey(entity = FrameRow::class, parentColumns = ["id"], childColumns = ["frameId"]),
        ForeignKey(entity = TaskRow::class, parentColumns = ["id"], childColumns = ["ownedTaskId"]),
    ],
    indices = [Index(value = ["frameId", "taskId"]), Index(value = ["ownedTaskId"])],
)
internal data class FrameItemRow(
    val frameId: String,
    val originalPosition: Int,
    val taskId: String,
    val title: String,
    val notes: String,
    val minutes: Int,
    val scheduleJson: String?,
    @ColumnInfo(defaultValue = "0") val referenceOnly: Boolean = false,
    val occurrenceDay: Long? = null,
    val ownedTaskId: String? = if (referenceOnly) null else taskId,
) {
    fun savedSchedule() = scheduleJson?.let { snapshotJson.decodeFromString<SavedSchedule>(it).also { p -> check(p.payloadVersion == 1) } }
}
internal val snapshotJson = Json { encodeDefaults = true }

// Until the first successful commit an absent singleton represents these same defaults.
@Entity(tableName = "planner_settings")
internal data class SettingsRow(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0,
    val restorePolicy: RestorePolicy = RestorePolicy.PREPEND,
    @ColumnInfo(defaultValue = "'{}'") val appearance: String = "{}",
    @ColumnInfo(defaultValue = "0") val receiptsImported: Boolean = false,
    @ColumnInfo(defaultValue = "0") val onboardingVersion: Int = 0,
)

internal data class TaskFields(val id: String, val title: String, val notes: String, val minutes: Int) {
    fun view(schedule: ScheduleSummary? = null) = QueueTask(id, title, notes, minutes, schedule)
}
internal data class ItemState(@Embedded val item: FrameItemRow, @Embedded(prefix = "live_") val current: TaskFields?)
private const val FRAME_ITEMS = """SELECT i.*, t.id AS live_id, t.title AS live_title, t.notes AS live_notes, t.minutes AS live_minutes
    FROM frame_items i LEFT JOIN tasks t ON NOT i.referenceOnly AND t.id=i.ownedTaskId AND t.frameId=i.frameId AND t.owner='FRAME' """
internal data class PositionUpdate(val id: String, val queuePosition: Int)
internal data class QueuePositionLabel(val id: String, val queuePosition: Int, val title: String)

@Dao
internal interface PlannerDao {
    @Query("SELECT * FROM planner_settings WHERE id = 1")
    fun settings(): SettingsRow?

    @Query("SELECT id,title,notes,minutes FROM tasks WHERE owner='QUEUE' ORDER BY queuePosition")
    fun visibleQueue(): List<TaskFields>

    @Query("SELECT id,title,notes,minutes FROM tasks WHERE id=:id AND owner!='REMOVED'")
    fun currentTask(id: String): TaskFields?

    @Query("""SELECT t.id,t.title,t.notes,t.minutes FROM tasks t WHERE t.owner='QUEUE'
        AND NOT EXISTS (SELECT 1 FROM agenda_schedules s WHERE s.taskId=t.id AND s.enabled) ORDER BY t.queuePosition""")
    fun dayTray(): List<TaskFields>

    @Query("""SELECT * FROM frames AS f ORDER BY
        EXISTS (SELECT 1 FROM tasks AS t WHERE t.frameId = f.id) DESC,
        f.savedAtMillis DESC, f.rowid DESC""")
    fun frames(): List<FrameRow>

    @Query("""SELECT f.id, f.reason, f.savedAtMillis, f.source,
        (SELECT COUNT(*) FROM frame_items i WHERE i.frameId=f.id) AS originalCount,
        (SELECT COUNT(*) FROM tasks t WHERE t.frameId=f.id AND t.owner='FRAME') AS remaining
        FROM frames f ORDER BY (remaining > 0) DESC, f.savedAtMillis DESC, f.rowid DESC""")
    fun frameIndex(): List<FrameSummary>

    @Query("SELECT * FROM frames WHERE id=:id") fun frame(id: String): FrameRow?
    @Query(FRAME_ITEMS + "WHERE i.frameId=:id ORDER BY i.originalPosition")
    fun frameEntries(id: String): List<ItemState>

    @Query(FRAME_ITEMS + "ORDER BY i.frameId, i.originalPosition")
    fun itemStates(): List<ItemState>

    @Query("SELECT id,queuePosition,title FROM tasks WHERE owner = 'QUEUE' ORDER BY queuePosition")
    fun queuePositions(): List<QueuePositionLabel>

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    fun tasks(ids: List<String>): List<TaskRow>

    @Query("""SELECT i.taskId FROM frame_items AS i JOIN tasks AS t ON t.id = i.ownedTaskId
        WHERE NOT i.referenceOnly AND i.frameId = :frameId AND t.frameId = :frameId AND t.owner = 'FRAME'
        ORDER BY i.originalPosition""")
    fun restorableIds(frameId: String): List<String>

    @Insert fun insertFrames(frames: List<FrameRow>)
    @Update fun updateFrames(frames: List<FrameRow>)
    @Query("DELETE FROM frame_items WHERE frameId=:id") fun deleteFrameItems(id: String)
    @Query("SELECT taskId FROM frame_items WHERE frameId=:id AND NOT referenceOnly ORDER BY originalPosition") fun originalTaskIds(id: String): List<String>
    @Query("DELETE FROM frames WHERE id=:id AND NOT EXISTS (SELECT 1 FROM tasks WHERE frameId=:id)") fun deleteEmptyFrame(id: String): Int
    @Insert fun insertTasks(tasks: List<TaskRow>)
    @Update fun updateTasks(tasks: List<TaskRow>)
    @Update(entity = TaskRow::class) fun updatePositions(positions: List<PositionUpdate>)
    @Insert fun insertItems(items: List<FrameItemRow>)
    @Upsert fun saveSettings(settings: SettingsRow)
}
