package com.zibi.app.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import com.zibi.app.PermissionBroker
import com.zibi.app.protocol.Risk
import com.zibi.app.protocol.ToolSpec
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A mail app installed on the phone: display label + package name. */
data class MailApp(val label: String, val packageName: String)

/** All installed apps that can compose a mailto: — found dynamically, never a hardcoded list. */
fun findMailApps(pm: PackageManager): List<MailApp> {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri: ResolveInfo ->
            runCatching { MailApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName) }.getOrNull()
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** Matches a free-form app name ("outlook", "com.microsoft...", "Yahoo Mail") against installed mail apps. */
fun matchMailApp(query: String, installed: List<MailApp>): MailApp? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    return installed.firstOrNull { it.label.lowercase() == q || it.packageName.lowercase() == q }
        ?: installed.firstOrNull {
            it.packageName.lowercase().contains(q) || it.label.lowercase().contains(q)
        }
}

internal fun emailComposerSummary(to: String?, recipientName: String?, appName: String): String = when {
    recipientName != null -> "Email to $recipientName is ready in $appName. Press Send there."
    !to.isNullOrBlank() -> "Email draft opened in $appName. I couldn't find an email address for $to; add it before sending."
    else -> "Email draft opened in $appName. Add a recipient before sending."
}

/**
 * Prepares an email in one of the user's installed mail apps. It opens the real app with recipient, subject
 * and body filled in; the USER presses Send. Zibi never sends anything on its own.
 *
 * - 'app' is any mail app by name (resolved against what's installed: gmail, outlook, yahoo, ...). The user's
 *   saved preference for the context decides which one (saved as a fact like "<context>_email_app"); omit 'app'
 *   for the phone's default mail app.
 * - 'to' is optional: a contact name (looked up on the phone — the address book never leaves it), an email
 *   address, or a [EMAIL_x] placeholder (the dispatcher puts the real value back). If omitted or not found,
 *   the composer opens WITHOUT a recipient and the user adds it in the app.
 */
class SendEmailTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : ZibiTool {
    override val spec = ToolSpec(
        name = "send_email",
        description = "Prepare an email. This opens a mail app with recipient, subject and body filled in; the USER " +
            "must press Send there. Never say it was sent, only that it is ready to send. 'to' is optional: a contact " +
            "name or an email address; if you leave it out, the composer opens without a recipient and the user adds it " +
            "in the app. 'app' is the NAME of any installed mail app (gmail, outlook, yahoo mail, ...), taken from " +
            "the user's saved preferences for this context (e.g. a fact like university_email_app); omit it to use " +
            "the phone's default mail app. If the named app isn't installed, the call fails and lists the installed " +
            "mail apps — retry with one of those or without 'app'.",
        parameters = schema(
            """
            {"type":"object",
             "properties":{
               "to":{"type":"string","description":"contact name or email address; omit to leave the recipient empty"},
               "subject":{"type":"string"},
               "body":{"type":"string"},
               "app":{"type":"string","description":"name of an installed mail app, e.g. gmail or outlook; omit for the default"}},
             "required":["body"],
             "additionalProperties":false}
            """.trimIndent()
        ),
        risk = Risk.WRITE, // opening a draft is safe; the human sends. A future auto-send tool would be HIGH_STAKES.
    )

    private sealed interface Lookup {
        data class Found(val name: String, val address: String) : Lookup
        data object NotFound : Lookup
        data object Empty : Lookup // no 'to' given: open the composer without a recipient
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = args.string("to")?.trim()?.takeIf { it.isNotEmpty() }
        val subject = args.string("subject")?.trim() ?: ""
        val body = args.string("body")?.takeIf { it.isNotBlank() } ?: return ToolResult.fail("'body' is required")
        val appQuery = args.string("app")?.trim()?.takeIf { it.isNotEmpty() && it != "default" }

        // Discovery can legally come back empty (package visibility); then we open without setPackage and
        // let the system resolve the intent — only a real ActivityNotFoundException means "no mail app".
        val mailApps = findMailApps(context.packageManager)
        val app = appQuery?.let { q ->
            matchMailApp(q, mailApps)
                ?: if (mailApps.isEmpty()) null // list hidden by visibility: fall through to the system chooser
                else return ToolResult.fail(
                    "no installed mail app matches \"$q\". Installed: ${mailApps.joinToString { it.label }}. " +
                        "Retry with one of those, or without 'app' for the default."
                )
        }

        val lookup = when {
            to == null -> Lookup.Empty
            EMAIL_ADDRESS.matches(to) -> Lookup.Found(to, to)
            else -> lookupContact(to)
        }
        val found = when (lookup) {
            is Lookup.Found -> lookup
            is Lookup.NotFound, Lookup.Empty -> null
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            found?.let {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(it.address))
                data = Uri.parse("mailto:${Uri.encode(it.address)}")
            }
            if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app?.let { setPackage(it.packageName) }
        }
        val opened = try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
        if (!opened) {
            return ToolResult.fail(
                if (mailApps.isEmpty()) "no mail app is installed on this phone"
                else "couldn't open ${app?.label ?: "a mail app"}. Installed: ${mailApps.joinToString { it.label }}"
            )
        }

        val appName = app?.label ?: "your mail app"
        return ToolResult(
            ok = true,
            summary = emailComposerSummary(to, found?.name, appName),
            result = buildJsonObject {
                put("status", "composer_opened_user_must_press_send")
                put("recipient_filled", found != null)
                put("app", app?.label ?: "default")
            },
        )
    }

    private suspend fun lookupContact(name: String): Lookup {
        if (!permissions.ensure(Manifest.permission.READ_CONTACTS)) {
            return Lookup.NotFound
        }
        val rows = try {
            withContext(Dispatchers.IO) { queryEmails(name) }
        } catch (e: SecurityException) {
            return Lookup.NotFound
        }
        if (rows.isEmpty()) {
            return Lookup.NotFound
        }
        val names = rows.map { it.name }.distinct()
        val chosen = if (names.size == 1) names[0]
        else names.firstOrNull { it.equals(name, ignoreCase = true) }
            ?: return Lookup.NotFound
        val row = rows.first { it.name == chosen }
        return Lookup.Found(row.name, row.address)
    }

    private fun queryEmails(name: String): List<Lookup.Found> {
        val out = mutableListOf<Lookup.Found>()
        context.contentResolver.query(
            Email.CONTENT_URI,
            arrayOf(Email.DISPLAY_NAME, Email.ADDRESS),
            "${Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                out += Lookup.Found(c.getString(0) ?: name, address)
            }
        }
        return out
    }

    private companion object {
        val EMAIL_ADDRESS = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
    }
}
