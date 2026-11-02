package com.zibi.app.privacy

import com.zibi.app.data.VaultDao
import com.zibi.app.data.VaultEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Redacted(val text: String, val count: Int)

/**
 * The privacy boundary. Sensitive values are replaced by placeholders such as [PHONE_1] BEFORE anything leaves the
 * phone, so neither our server nor the AI model ever sees them. The real values are stored here, encrypted. When the
 * AI asks a tool to act on a placeholder, the phone swaps the real value back in locally, so the request still works.
 */
class PrivacyVault(private val dao: VaultDao, private val cipher: SecretCipher) {
    private val mutex = Mutex()
    private var loaded = false
    private val placeholderByKey = HashMap<String, String>()
    private val valueByPlaceholder = HashMap<String, String>()

    /** How many different values are hidden from the AI. */
    val hiddenCount: Flow<Int> = dao.observeCount()

    private suspend fun ensureLoaded() {
        if (loaded) return
        for (e in dao.all()) {
            val value = runCatching { cipher.decrypt(e.valueEnc) }.getOrNull() ?: continue
            valueByPlaceholder[e.placeholder] = value
            placeholderByKey[keyOf(e.kind, value)] = e.placeholder
        }
        loaded = true
    }

    // the same phone number written two ways gets one placeholder
    private fun keyOf(kind: String, value: String) =
        kind + ":" + if (kind == "PHONE") value.filter { it.isDigit() || it == '+' } else value.trim()

    private suspend fun placeholderFor(kind: String, value: String): String {
        val key = keyOf(kind, value)
        placeholderByKey[key]?.let { return it }
        val next = valueByPlaceholder.keys
            .filter { it.startsWith("[${kind}_") }
            .mapNotNull { NUMBER_IN_PLACEHOLDER.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1
        val placeholder = "[${kind}_$next]"
        dao.insert(VaultEntry(placeholder = placeholder, kind = kind, valueEnc = cipher.encrypt(value), createdAt = System.currentTimeMillis()))
        placeholderByKey[key] = placeholder
        valueByPlaceholder[placeholder] = value
        return placeholder
    }

    /** Hides a whole value (a fact the user marked private). */
    suspend fun protect(value: String, kind: String = "PRIVATE"): String =
        mutex.withLock { ensureLoaded(); placeholderFor(kind, value) }

    /** Replaces every sensitive value found in [text] with a placeholder. */
    suspend fun redact(text: String): Redacted = mutex.withLock {
        ensureLoaded()
        val spans = findSensitive(text)
        if (spans.isEmpty()) return@withLock Redacted(text, 0)
        val out = StringBuilder()
        var pos = 0
        for (s in spans) {
            out.append(text, pos, s.start)
            out.append(placeholderFor(s.kind, text.substring(s.start, s.end)))
            pos = s.end
        }
        out.append(text, pos, text.length)
        Redacted(out.toString(), spans.size)
    }

    /** Placeholders back to real values. Used locally only: for tools, and for showing text to the user. */
    suspend fun resolve(text: String): String = mutex.withLock {
        ensureLoaded()
        if (!text.contains('[')) text else PLACEHOLDER.replace(text) { valueByPlaceholder[it.value] ?: it.value }
    }

    suspend fun redactJson(el: JsonElement): JsonElement = mapStrings(el) { redact(it).text }
    suspend fun resolveJson(el: JsonElement): JsonElement = mapStrings(el) { resolve(it) }

    private suspend fun mapStrings(el: JsonElement, f: suspend (String) -> String): JsonElement = when {
        el is JsonObject -> JsonObject(el.mapValues { mapStrings(it.value, f) })
        el is JsonArray -> JsonArray(el.map { mapStrings(it, f) })
        el is JsonPrimitive && el.isString -> JsonPrimitive(f(el.content))
        else -> el
    }

    private companion object {
        val PLACEHOLDER = Regex("\\[(?:PHONE|EMAIL|CARD|ID|SECRET|PRIVATE)_\\d+]")
        val NUMBER_IN_PLACEHOLDER = Regex("_(\\d+)]")
    }
}
