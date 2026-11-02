package com.zibi.app

import com.zibi.app.data.ListDao
import com.zibi.app.data.ListEntity
import com.zibi.app.data.ListItem
import com.zibi.app.tools.AddToListTool
import com.zibi.app.tools.listKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeListDao : ListDao {
    val lists = mutableListOf<ListEntity>()
    val items = mutableListOf<ListItem>()
    private var nextId = 1L

    override suspend fun insertList(list: ListEntity): Long {
        val id = nextId++
        lists += list.copy(id = id)
        return id
    }
    override suspend fun insertItems(items: List<ListItem>): List<Long> =
        items.map { i -> val id = nextId++; this.items += i.copy(id = id); id }
    override suspend fun findByKey(key: String) = lists.firstOrNull { it.nameKey == key }
    override suspend fun itemsOf(listId: Long) = items.filter { it.listId == listId }
    override suspend fun countItems(listId: Long) = items.count { it.listId == listId }
    override fun observeLists(): Flow<List<ListEntity>> = flowOf(lists)
    override fun observeItems(): Flow<List<ListItem>> = flowOf(items)
    override suspend fun setItemDone(id: Long, done: Boolean) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = items[i].copy(done = done)
    }
    override suspend fun deleteItems(ids: List<Long>): Int {
        val before = items.size
        items.removeAll { it.id in ids }
        return before - items.size
    }
    override suspend fun clearDone(listId: Long) { items.removeAll { it.listId == listId && it.done } }
    override suspend fun deleteList(id: Long) { lists.removeAll { it.id == id }; items.removeAll { it.listId == id } }
}

private fun args(list: String, vararg items: String) = buildJsonObject {
    put("list_name", list)
    put("items", JsonArray(items.map { JsonPrimitive(it) }))
}

class CaptureToolsTest {
    @Test
    fun differentWaysOfNamingTheSameListMatch() {
        val k = listKey("Grocery list")
        assertEquals(k, listKey("groceries"))
        assertEquals(k, listKey("my grocery list"))
        assertEquals(k, listKey("Groceries list"))
        assertEquals("packing", listKey("Packing"))
    }

    @Test
    fun createsTheListAndAddsItems() = runTest {
        val dao = FakeListDao()
        val r = AddToListTool(dao).execute(args("grocery list", "milk", "eggs"))
        assertTrue(r.ok)
        assertEquals(listOf("milk", "eggs"), dao.items.map { it.text })
        assertEquals("Grocery list", dao.lists.single().name)
    }

    @Test
    fun addingLaterReusesTheSameListAndSkipsDuplicates() = runTest {
        val dao = FakeListDao()
        val tool = AddToListTool(dao)
        tool.execute(args("Grocery list", "milk", "eggs"))
        val r = tool.execute(args("groceries", "Cheese", "MILK"))
        assertTrue(r.ok)
        assertEquals(1, dao.lists.size)
        assertEquals(listOf("milk", "eggs", "Cheese"), dao.items.map { it.text })
    }

    @Test
    fun nothingNewMeansNoUndoToken() = runTest {
        val dao = FakeListDao()
        val tool = AddToListTool(dao)
        tool.execute(args("Grocery list", "milk"))
        assertNull(tool.execute(args("Grocery list", "milk")).undoToken)
    }

    @Test
    fun undoRemovesJustThoseItemsAndDeletesAListItCreated() = runTest {
        val dao = FakeListDao()
        val tool = AddToListTool(dao)
        val first = tool.execute(args("Grocery list", "milk"))
        val second = tool.execute(args("Grocery list", "cheese"))

        assertTrue(tool.undo(second.undoToken!!))
        assertEquals(listOf("milk"), dao.items.map { it.text })
        assertEquals(1, dao.lists.size)

        assertTrue(tool.undo(first.undoToken!!))
        assertTrue(dao.items.isEmpty())
        assertTrue(dao.lists.isEmpty())
    }

    @Test
    fun rejectsEmptyItems() = runTest {
        val r = AddToListTool(FakeListDao()).execute(args("Grocery list"))
        assertFalse(r.ok)
        assertNotNull(r.error)
    }
}
