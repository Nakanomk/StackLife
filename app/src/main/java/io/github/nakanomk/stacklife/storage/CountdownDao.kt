package io.github.nakanomk.stacklife.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.nakanomk.stacklife.planner.Countdown
import io.github.nakanomk.stacklife.planner.CountdownMode

@Entity(tableName = "countdowns", indices = [Index(value = ["pinned", "position"])])
internal data class CountdownRow(@PrimaryKey val id: String, val title: String, val targetDay: Long,
    val mode: CountdownMode, val pinned: Boolean, val position: Int, val backgroundId: String?, val maskOpacity: Float)

@Dao
internal interface CountdownDao {
    @Query("SELECT * FROM countdowns ORDER BY pinned DESC, position, id") fun list(): List<Countdown>
    @Query("SELECT * FROM countdowns WHERE id=:id") fun one(id: String): Countdown?
    @Query("SELECT DISTINCT backgroundId FROM countdowns WHERE backgroundId IS NOT NULL") fun backgroundIds(): List<String>
    @Query("SELECT count(*) FROM countdowns WHERE pinned=:pinned") fun count(pinned: Boolean): Int
    @Query("SELECT * FROM countdowns WHERE pinned=:pinned AND position >= :start") fun suffix(pinned: Boolean, start: Int): List<Countdown>
    @Upsert fun put(rows: List<CountdownRow>)
    @Query("DELETE FROM countdowns WHERE id=:id") fun delete(id: String)
}
