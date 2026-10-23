package com.zibi.app.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.zibi.app.data.HandoffDao
import com.zibi.app.data.LibraryDao
import java.io.File

enum class HandoffTarget(val label: String, val packageName: String?) {
    Claude("Claude", "com.anthropic.claude"),
    ChatGpt("ChatGPT", "com.openai.chatgpt"),
    Other("another app", null),
}

/**
 * Sends a prepared prompt (and PDFs) to another AI app through Android's share mechanism.
 * The prompt is ALWAYS copied to the clipboard too, because apps differ in what they accept from a share.
 */
class HandoffLauncher(
    private val context: Context,
    private val library: LibraryDao,
    private val handoffs: HandoffDao,
) {
    suspend fun open(handoffId: Long, target: HandoffTarget, prompt: String): String {
        val handoff = handoffs.get(handoffId) ?: return "That hand-off no longer exists."
        val files = handoff.docIds.split(",")
            .mapNotNull { it.toLongOrNull() }
            .mapNotNull { library.get(it) }
            .map { File(it.localPath) }
            .filter { it.exists() }
        val uris = files.map { FileProvider.getUriForFile(context, AUTHORITY, it) }

        copyToClipboard(prompt)

        val built = build(prompt, uris, target.packageName)
        // "Other" always shows the system chooser; Claude/ChatGPT go straight to the app
        val direct = if (target.packageName == null) Intent.createChooser(built, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else built
        val opened = tryStart(direct) ||
            (target.packageName != null && tryStart(Intent.createChooser(build(prompt, uris, null), "Open with")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        if (!opened) return "No app could take it. The prompt is copied: paste it into any AI app."

        handoffs.markOpened(handoffId, System.currentTimeMillis())
        val attached = if (files.isEmpty()) "" else " with ${files.size} file(s)"
        return "Opened ${target.label}$attached. The prompt is also copied to your clipboard. If it didn't " +
            "fill in, or a file is missing, paste it and attach the PDF yourself."
    }

    private fun build(prompt: String, uris: List<Uri>, pkg: String?): Intent {
        val intent = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = "application/pdf"
            }
            uris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris[0])
                type = "application/pdf"
            }
            else -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        }
        intent.putExtra(Intent.EXTRA_TEXT, prompt)
        if (uris.isNotEmpty()) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
        }
        if (pkg != null) intent.setPackage(pkg)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Zibi prompt", text))
    }

    companion object {
        const val AUTHORITY = "com.zibi.app.files"
    }
}
