package com.zibi.app.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.zibi.app.PermissionBroker
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Prepares a message in SMS or WhatsApp. It opens the real app with recipient and text filled in; the USER
 * presses Send. Zibi never sends anything on its own, so this needs no SMS permission.
 * Contact lookup happens on the phone: the contact list is never sent to the server.
 */
class SendMessageTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "send_message",
        description = "Prepare a message to a person in SMS or WhatsApp. This opens the messaging app with the " +
            "recipient and text filled in; the USER must press Send there. Never say it was sent, only that it is " +
            "ready to send. 'to' is a contact name or a phone number.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "to":{"type":"string","description":"contact name or phone number"},
               "text":{"type":"string"},
               "app":{"type":"string","enum":["sms","whatsapp"]}},
             "required":["to","text","app"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE, // opening a draft is safe; the human sends. A future auto-send tool would be HIGH_STAKES.
    )

    private sealed interface Lookup {
        data class Found(val name: String, val number: String, val normalized: String?) : Lookup
        data class Problem(val message: String) : Lookup
        data object Skipped : Lookup // no contacts permission: open the app without a recipient
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() } ?: return ToolResult.fail("'to' is required")
        val text = args.string("text")?.takeIf { it.isNotBlank() } ?: return ToolResult.fail("'text' is required")
        val app = args.string("app")
        if (app != "sms" && app != "whatsapp") return ToolResult.fail("app must be 'sms' or 'whatsapp'")

        val lookup = if (NUMBER.matches(to)) Lookup.Found(to, to, null) else lookupContact(to)
        val found = when (lookup) {
            is Lookup.Problem -> return ToolResult.fail(lookup.message)
            is Lookup.Found -> lookup
            Lookup.Skipped -> null
        }

        val opened = if (app == "sms") openSms(found?.number, text) else openWhatsApp(found, text)
        if (!opened) {
            return ToolResult.fail(if (app == "sms") "no messaging app available" else "WhatsApp is not installed")
        }
        val appName = if (app == "sms") "your messaging app" else "WhatsApp"
        val who = found?.name ?: "the recipient (choose them in the app)"
        return ToolResult(
            ok = true,
            summary = "Message to $who is ready in $appName, press Send there",
            result = buildJsonObject {
                put("status", "composer_opened_user_must_press_send")
                put("recipient_filled", found != null)
            },
        )
    }

    private suspend fun lookupContact(name: String): Lookup {
        if (!permissions.ensure(Manifest.permission.READ_CONTACTS)) return Lookup.Skipped
        val rows = try {
            withContext(Dispatchers.IO) { queryContacts(name) }
        } catch (e: SecurityException) {
            return Lookup.Skipped
        }
        if (rows.isEmpty()) return Lookup.Problem("no contact matching \"$name\". Ask the user for the phone number.")
        val names = rows.map { it.name }.distinct()
        val chosen = if (names.size == 1) names[0]
        else names.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: return Lookup.Problem("several contacts match: ${names.take(5).joinToString()}. Ask the user which one.")
        return rows.first { it.name == chosen }
    }

    private fun queryContacts(name: String): List<Lookup.Found> {
        val out = mutableListOf<Lookup.Found>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.NORMALIZED_NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1) ?: continue
                out += Lookup.Found(c.getString(0) ?: name, number, c.getString(2))
            }
        }
        return out
    }

    private fun openSms(number: String?, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number ?: ""))).apply {
            putExtra("sms_body", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(intent)
    }

    private fun openWhatsApp(found: Lookup.Found?, text: String): Boolean {
        // wa.me needs the number in international format, digits only. Contacts saved without a country code
        // may not open; SMS doesn't have that limitation.
        val digits = found?.let { (it.normalized ?: it.number).filter(Char::isDigit) }
        for (pkg in WHATSAPP_PACKAGES) {
            val intent = if (digits != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(text)}"))
            } else {
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            }
            intent.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(intent)) return true
        }
        return false
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private companion object {
        val NUMBER = Regex("^\\+?[0-9 ()\\-]{5,}$")
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
