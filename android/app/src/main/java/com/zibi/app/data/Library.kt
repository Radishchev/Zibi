package com.zibi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library")
data class LibraryDoc(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val localPath: String,
    val serverDocId: Long? = null,
    val pages: Int = 0,
    val chunks: Int = 0,
    val status: String = "uploading",
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(doc: LibraryDoc): Long

    @Query("SELECT * FROM library WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): LibraryDoc?

    @Query("SELECT * FROM library ORDER BY id DESC")
    suspend fun all(): List<LibraryDoc>

    @Query("SELECT * FROM library ORDER BY id DESC")
    fun observeAll(): Flow<List<LibraryDoc>>

    @Query("""
        UPDATE library 
        SET status = :status, error = :error, serverDocId = :serverDocId, pages = :pages, chunks = :chunks 
        WHERE id = :id
    """)
    suspend fun setResult(
        id: Long,
        status: String,
        error: String?,
        serverDocId: Long?,
        pages: Int,
        chunks: Int,
    )

    @Query("DELETE FROM library WHERE id = :id")
    suspend fun delete(id: Long)
}
