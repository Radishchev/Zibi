package com.zibi.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "lists")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "list_items")
data class ListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface ListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: ListEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ListItem>): List<Long>

    @Query("SELECT * FROM lists WHERE nameKey = :key LIMIT 1")
    suspend fun findByKey(key: String): ListEntity?

    @Query("SELECT * FROM list_items WHERE listId = :listId ORDER BY id ASC")
    suspend fun itemsOf(listId: Long): List<ListItem>

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId")
    suspend fun countItems(listId: Long): Int

    @Query("SELECT * FROM lists ORDER BY id DESC")
    fun observeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM list_items ORDER BY id ASC")
    fun observeItems(): Flow<List<ListItem>>

    @Query("UPDATE list_items SET done = :done WHERE id = :id")
    suspend fun setItemDone(id: Long, done: Boolean)

    @Query("DELETE FROM list_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>): Int

    @Query("DELETE FROM list_items WHERE listId = :listId AND done = 1")
    suspend fun clearDone(listId: Long)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun deleteList(id: Long)
}
