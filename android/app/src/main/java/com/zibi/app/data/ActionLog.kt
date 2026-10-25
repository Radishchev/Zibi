package com.zibi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "action_log")
data class ActionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val tool: String,
    val argsJson: String,
    val ok: Boolean,
    val summary: String,
    val undoToken: String? = null,
    val undone: Boolean = false,
)

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActionLogEntry): Long

    @Query("SELECT * FROM action_log ORDER BY id DESC")
    fun observeAll(): Flow<List<ActionLogEntry>>

    @Query("UPDATE action_log SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)
}
