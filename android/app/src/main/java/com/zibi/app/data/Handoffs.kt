package com.zibi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "handoffs")
data class Handoff(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val prompt: String,
    val docIds: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val openedAt: Long? = null,
    val done: Boolean = false,
)

@Dao
interface HandoffDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(handoff: Handoff): Long

    @Query("SELECT * FROM handoffs WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): Handoff?

    @Query("SELECT * FROM handoffs WHERE done = 0 ORDER BY id DESC")
    fun observePending(): Flow<List<Handoff>>

    @Query("UPDATE handoffs SET openedAt = :ts WHERE id = :id")
    suspend fun markOpened(id: Long, ts: Long)

    @Query("UPDATE handoffs SET done = 1 WHERE id = :id")
    suspend fun dismiss(id: Long)

    @Query("DELETE FROM handoffs WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
