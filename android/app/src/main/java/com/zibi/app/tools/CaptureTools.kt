package com.zibi.app.tools

import com.zibi.app.data.DraftDao
import com.zibi.app.data.ListDao
import com.zibi.app.data.ListEntity
import com.zibi.app.data.ListItem
import com.zibi.app.data.MessageDraft
import com.zibi.app.data.Task
import com.zibi.app.data.TaskDao
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Tools for "just put my thoughts in": lists, tasks and messages to send later.
 * Everything here only touches Zibi's own local database, so it is instant and fully undoable.
 */

/** "Grocery list", "groceries" and "my grocery list" should all be the same list. */
fun listKey(name: String): String {
    val noise = setOf("list", "my", "the", "a")
    val words = name.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(" ")
        .filter { it.isNotBlank() && it !in noise }
    val joined = words.joinToString(" ").ifEmpty { name.lowercase().trim() }
    return when {
        joined.endsWith("ies") -> joined.dropLast(3) + "y"
        joined.endsWith("s") && !joined.endsWith("ss") -> joined.dropLast(1)
        else -> joined
    }
}

class AddToListTool(private val dao: ListDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "add_to_list",
        description = "Add items to a named list such as a grocery or packing list. Creates the list if it doesn't " +
            "exist. Always use the same list name for the same list (for example \"Grocery list\"). " +
            "Items already on the list are skipped.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "list_name":{"type":"string"},
               "items":{"type":"array","items":{"type":"string"},"minItems":1}},
             "required":["list_name","items"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.string("list_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("list_name is required")
        val requested = (args["items"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        if (requested.isEmpty()) return ToolResult.fail("items must be a non-empty list of strings")

        val now = System.currentTimeMillis()
        val key = listKey(name)
        val existingList = dao.findByKey(key)
        val display = name.replaceFirstChar { it.uppercase() }
        val list = existingList ?: ListEntity(
            id = dao.insertList(ListEntity(name = display, nameKey = key, createdAt = now)),
            name = display,
            nameKey = key,
            createdAt = now,
        )
        val created = existingList == null

        val alreadyThere = dao.itemsOf(list.id).filter { !it.done }.map { it.text.lowercase() }.toSet()
        val fresh = requested.distinctBy { it.lowercase() }.filter { it.lowercase() !in alreadyThere }
        val ids = if (fresh.isEmpty()) emptyList()
        else dao.insertItems(fresh.map { ListItem(listId = list.id, text = it, createdAt = now) })

        val summary = if (fresh.isEmpty()) "Already on ${list.name}: ${requested.joinToString()}"
        else "Added ${fresh.joinToString()} to ${list.name}"
        return ToolResult(
            ok = true,
            summary = summary,
            result = buildJsonObject {
                put("list", list.name)
                put("added", buildJsonArray { fresh.forEach { add(JsonPrimitive(it)) } })
                put("already_on_list", buildJsonArray {
                    requested.filter { it !in fresh }.forEach { add(JsonPrimitive(it)) }
                })
            },
            undoToken = if (ids.isEmpty() && !created) null
            else "${list.id}|${if (created) 1 else 0}|${ids.joinToString(",")}",
        )
    }

    override suspend fun undo(token: String): Boolean {
        val parts = token.split("|")
        if (parts.size != 3) return false
        val listId = parts[0].toLongOrNull() ?: return false
        val created = parts[1] == "1"
        val ids = parts[2].split(",").mapNotNull { it.toLongOrNull() }
        if (ids.isNotEmpty()) dao.deleteItems(ids)
        if (created && dao.countItems(listId) == 0) dao.deleteList(listId)
        return true
    }
}

class AddTaskTool(private val dao: TaskDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "add_task",
        description = "Save something the user needs to do that has no specific time. If a project is mentioned, " +
            "put its name in 'project'. For things with a date or time use create_reminder instead.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "title":{"type":"string"},
               "project":{"type":"string"},
               "notes":{"type":"string"}},
             "required":["title"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.string("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.fail("title is required")
        val project = args.string("project")?.trim()?.takeIf { it.isNotEmpty() }
        val id = dao.insert(
            Task(
                title = title,
                project = project,
                notes = args.string("notes")?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = System.currentTimeMillis(),
            )
        )
        return ToolResult(
            ok = true,
            summary = "Task saved: $title" + (project?.let { " ($it)" } ?: ""),
            result = buildJsonObject { put("task_id", id) },
            undoToken = id.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        return dao.deleteById(id) > 0
    }
}

class SaveMessageDraftTool(private val dao: DraftDao) : ZibiTool {
    override val spec = ToolSpec(
        name = "save_message_draft",
        description = "Save a message the user wants to send someone LATER (\"I have to tell Sam about X\"). It " +
            "appears as a button; when tapped, the message opens in SMS or WhatsApp ready to send. Write the text in " +
            "the user's voice. Leave 'app' out unless the user said which app. If the user wants to message right " +
            "now, use send_message instead.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "to":{"type":"string","description":"contact name or phone number"},
               "text":{"type":"string"},
               "app":{"type":"string","enum":["sms","whatsapp"]}},
             "required":["to","text"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("'to' is required")
        val text = args.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("'text' is required")
        val app = args.string("app")
        if (app != null && app != "sms" && app != "whatsapp") return ToolResult.fail("app must be 'sms' or 'whatsapp'")
        val id = dao.insert(
            MessageDraft(recipient = to, text = text, app = app, createdAt = System.currentTimeMillis())
        )
        return ToolResult(
            ok = true,
            summary = "Message to $to saved. Find it in Quick",
            result = buildJsonObject { put("draft_id", id) },
            undoToken = id.toString(),
        )
    }

    override suspend fun undo(token: String): Boolean {
        val id = token.toLongOrNull() ?: return false
        return dao.deleteById(id) > 0
    }
}
