package com.zibi.app.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zibi.app.data.LibraryDao
import com.zibi.app.data.LibraryDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val dao: LibraryDao,
    private val api: LibraryApi,
) {
    val docs = dao.observeAll()

    /** Copies the picked PDF into app storage (so it can be attached later), then uploads and indexes it. */
    suspend fun add(uri: Uri) {
        val name = displayName(uri) ?: "document.pdf"
        val dir = File(context.filesDir, "library").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.pdf")
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't read that file.")
            input.use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
        }
        if (file.length() > MAX_BYTES) {
            file.delete()
            throw IOException("That file is too large (50 MB limit).")
        }
        val id = dao.insert(
            LibraryDoc(
                name = name, localPath = file.absolutePath, serverDocId = null,
                pages = 0, chunks = 0, status = "uploading", error = null, addedAt = System.currentTimeMillis(),
            )
        )
        upload(id)
    }

    suspend fun retry(doc: LibraryDoc) {
        dao.setResult(doc.id, "uploading", null, null, 0, 0)
        upload(doc.id)
    }

    suspend fun delete(doc: LibraryDoc) {
        doc.serverDocId?.let { runCatching { api.delete(it) } } // best effort
        File(doc.localPath).delete()
        dao.delete(doc.id)
    }

    private suspend fun upload(id: Long) {
        val doc = dao.get(id) ?: return
        try {
            val bytes = withContext(Dispatchers.IO) { File(doc.localPath).readBytes() }
            val r = api.upload(doc.name, bytes)
            dao.setResult(id, "ready", null, r.docId, r.pages, r.chunks)
        } catch (e: Exception) {
            dao.setResult(id, "failed", e.message ?: "Upload failed.", null, 0, 0)
        }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
    }
}
