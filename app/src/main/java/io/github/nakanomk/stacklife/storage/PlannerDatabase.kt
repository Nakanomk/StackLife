package io.github.nakanomk.stacklife.storage

import androidx.room.AutoMigration
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
import io.github.nakanomk.stacklife.planner.DayBlock
import io.github.nakanomk.stacklife.planner.FrameSource
import io.github.nakanomk.stacklife.planner.QueueTask
import io.github.nakanomk.stacklife.planner.RestorePolicy

@Database(
    entities = [TaskRow::class, FrameRow::class, FrameItemRow::class, SettingsRow::class, TimeBlockRow::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class PlannerDatabase : RoomDatabase() {
    internal abstract fun plannerDao(): PlannerDao
}

internal enum class Owner { QUEUE, FRAME, DONE, REMOVED }

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
)

@Entity(
    tableName = "time_blocks",
    foreignKeys = [ForeignKey(entity = TaskRow::class, parentColumns = ["id"], childColumns = ["taskId"])],
    indices = [Index(value = ["endMillis"])],
)
internal data class TimeBlockRow(@PrimaryKey val taskId: String, val startMillis: Long, val endMillis: Long)

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
        ForeignKey(entity = TaskRow::class, parentColumns = ["id"], childColumns = ["taskId"]),
    ],
    indices = [Index(value = ["frameId", "taskId"], unique = true), Index(value = ["taskId"])],
)
internal data class FrameItemRow(
    val frameId: String,
    val originalPosition: Int,
    val taskId: String,
    val title: String,
    val notes: String,
    val minutes: Int,
    val scheduledStartMillis: Long?,
    val scheduledEndMillis: Long?,
)

// Until the first successful commit an absent singleton represents these same defaults.
@Entity(tableName = "planner_settings")
internal data class SettingsRow(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0,
    val restorePolicy: RestorePolicy = RestorePolicy.PREPEND,
)

internal data class ItemState(@Embedded val item: FrameItemRow, val restorable: Boolean)
internal data class PositionUpdate(val id: String, val queuePosition: Int)

@Dao
internal interface PlannerDao {
    @Query("SELECT * FROM planner_settings WHERE id = 1")
    fun settings(): SettingsRow?

    @Query("""SELECT t.id, t.title, t.notes, t.minutes, b.startMillis AS scheduledStartMillis, b.endMillis AS scheduledEndMillis
        FROM tasks t LEFT JOIN time_blocks b ON b.taskId = t.id WHERE t.owner = 'QUEUE' ORDER BY t.queuePosition""")
    fun visibleQueue(): List<QueueTask>

    @Query("""SELECT t.id, t.title, t.notes, t.minutes, NULL AS scheduledStartMillis, NULL AS scheduledEndMillis
        FROM tasks t WHERE t.owner = 'QUEUE' AND NOT EXISTS (SELECT 1 FROM time_blocks b WHERE b.taskId = t.id)
        ORDER BY t.queuePosition""")
    fun dayTray(): List<QueueTask>

    @Query("""SELECT b.taskId, t.title, b.startMillis, b.endMillis, t.owner AS state
        FROM time_blocks b JOIN tasks t ON t.id = b.taskId
        WHERE b.startMillis < :endMillis AND b.endMillis > :startMillis ORDER BY b.startMillis, b.taskId""")
    fun dayBlocks(startMillis: Long, endMillis: Long): List<DayBlock>

    @Query("SELECT * FROM time_blocks WHERE taskId IN (:ids)")
    fun slots(ids: List<String>): List<TimeBlockRow>

    @Upsert fun saveSlots(slots: List<TimeBlockRow>)
    @Query("DELETE FROM time_blocks WHERE taskId IN (:ids)") fun deleteSlots(ids: List<String>)

    @Query("""SELECT * FROM frames AS f ORDER BY
        EXISTS (SELECT 1 FROM tasks AS t WHERE t.frameId = f.id) DESC,
        f.savedAtMillis DESC, f.rowid DESC""")
    fun frames(): List<FrameRow>

    @Query("""SELECT i.*, CASE WHEN t.frameId = i.frameId AND t.owner = 'FRAME' THEN 1 ELSE 0 END AS restorable
        FROM frame_items AS i JOIN tasks AS t ON t.id = i.taskId
        ORDER BY i.frameId, i.originalPosition""")
    fun itemStates(): List<ItemState>

    @Query("SELECT COUNT(*) FROM tasks WHERE owner = 'QUEUE'")
    fun queueSize(): Int

    @Query("SELECT id FROM tasks WHERE owner = 'QUEUE' ORDER BY queuePosition")
    fun queueIds(): List<String>

    @Query("SELECT * FROM tasks WHERE id IN (:ids)")
    fun tasks(ids: List<String>): List<TaskRow>

    @Query("""SELECT i.taskId FROM frame_items AS i JOIN tasks AS t ON t.id = i.taskId
        WHERE i.frameId = :frameId AND t.frameId = :frameId AND t.owner = 'FRAME'
        ORDER BY i.originalPosition""")
    fun restorableIds(frameId: String): List<String>

    @Insert fun insertFrames(frames: List<FrameRow>)
    @Insert fun insertTasks(tasks: List<TaskRow>)
    @Update fun updateTasks(tasks: List<TaskRow>)
    @Update(entity = TaskRow::class) fun updatePositions(positions: List<PositionUpdate>)
    @Insert fun insertItems(items: List<FrameItemRow>)
    @Upsert fun saveSettings(settings: SettingsRow)
}
