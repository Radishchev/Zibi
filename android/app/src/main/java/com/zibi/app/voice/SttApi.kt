package com.zibi.app.voice

import com.zibi.app.data.SettingsRepository
import com.zibi.app.library.httpBase
import com.zibi.app.protocol.ZibiJson
import com.zibi.app.protocol.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** REST client for speech-to-text on the Zibi server (POST /transcribe). Audio never goes over the WebSocket. */
class SttApi(private val settings: SettingsRepository, private val deviceId: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // transcribing a long recording takes a while
        .build()

    /** Uploads one recording and returns the transcript (empty string if nothing was said). */
    suspend fun transcribe(name: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val url = try {
            httpBase(settings.serverUrl.first()).toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw IOException("The server URL in Settings isn't valid.")
        }.newBuilder().addPathSegment("transcribe").addQueryParameter("name", name).build()
        val request = Request.Builder().url(url)
            .header("X-Zibi-Device", deviceId)
            .apply { if (settings.token.first().isNotBlank()) header("X-Zibi-Token", settings.token.first()) }
            .post(bytes.toRequestBody("audio/mp4".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException(errorDetail(body) ?: "Transcription failed (HTTP ${resp.code}).")
            ZibiJson.parseToJsonElement(body).jsonObject.string("text") ?: ""
        }
    }

    private fun errorDetail(body: String): String? = try {
        (ZibiJson.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.string("detail")
    } catch (e: Exception) {
        null
    }
}
