package com.zibi.app.library

import com.zibi.app.data.SettingsRepository
import com.zibi.app.protocol.ZibiJson
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** ws://host:8000/ws  ->  http://host:8000 */
fun httpBase(wsUrl: String): String {
    var u = wsUrl.trim().substringBefore("?").trimEnd('/')
    u = when {
        u.startsWith("wss://") -> "https://" + u.removePrefix("wss://")
        u.startsWith("ws://") -> "http://" + u.removePrefix("ws://")
        else -> u
    }
    return u.removeSuffix("/ws")
}

/** REST client for the document library on the Zibi server. Files never go over the WebSocket. */
class LibraryApi(private val settings: SettingsRepository, private val deviceId: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // indexing a big PDF takes a while
        .build()

    data class Uploaded(val docId: Long, val pages: Int, val chunks: Int)

    private suspend fun base(): HttpUrl = try {
        httpBase(settings.serverUrl.first()).toHttpUrl()
    } catch (e: IllegalArgumentException) {
        throw IOException("The server URL in Settings isn't valid.")
    }

    private suspend fun authed(builder: Request.Builder): Request.Builder {
        builder.header("X-Zibi-Device", deviceId)
        val token = settings.token.first()
        if (token.isNotBlank()) builder.header("X-Zibi-Token", token)
        return builder
    }

    suspend fun upload(name: String, bytes: ByteArray): Uploaded = withContext(Dispatchers.IO) {
        val url = base().newBuilder().addPathSegment("documents").addQueryParameter("name", name).build()
        val request = authed(Request.Builder().url(url))
            .post(bytes.toRequestBody("application/pdf".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorDetail(body) ?: "Upload failed (HTTP ${resp.code}).")
            val json = ZibiJson.parseToJsonElement(body).jsonObject
            Uploaded(
                docId = json.string("doc_id")?.toLongOrNull() ?: throw IOException("Unexpected server reply."),
                pages = json.string("pages")?.toIntOrNull() ?: 0,
                chunks = json.string("chunks")?.toIntOrNull() ?: 0,
            )
        }
    }

    suspend fun delete(serverDocId: Long) = withContext(Dispatchers.IO) {
        val url = base().newBuilder().addPathSegment("documents").addPathSegment(serverDocId.toString()).build()
        http.newCall(authed(Request.Builder().url(url)).delete().build()).execute().close()
    }

    private fun errorDetail(body: String): String? = try {
        (ZibiJson.parseToJsonElement(body) as? JsonObject)?.string("detail")
    } catch (e: Exception) {
        null
    }
}
