package io.github.nakanomk.stacklife.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** One local activation point. Neither a provider credential nor an imported revision lives here. */
@Entity(tableName = "backup_device")
internal data class BackupDeviceRow(
    @PrimaryKey val id: Int = 1,
    val connectionGeneration: String? = null, // null = pre-v17 AtomicFile; empty = explicitly cleared
    val connectionEpoch: Long = 0,
    val pendingPreferences: String? = null,
)

@Dao
internal interface BackupDeviceDao {
    @Query("SELECT * FROM backup_device WHERE id = 1") suspend fun active(): BackupDeviceRow?
    @Upsert suspend fun write(row: BackupDeviceRow)
}
