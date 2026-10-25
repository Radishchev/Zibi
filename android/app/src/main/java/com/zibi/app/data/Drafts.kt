package com.zibi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts")
data class MessageDraft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipient: String,
    val text: String,
    val app: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val openedAt: Long? = null,
    val done: Boolean = false,
)

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: MessageDraft): Long

    @Query("SELECT * FROM drafts WHERE done = 0 ORDER BY id DESC")
    fun observePending(): Flow<List<MessageDraft>>

    @Query("UPDATE drafts SET openedAt = :ts, app = :app WHERE id = :id")
    suspend fun markOpened(id: Long, app: String, ts: Long)

    @Query("UPDATE drafts SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
