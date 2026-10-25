package com.zibi.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zibi_settings")

class SettingsRepository(private val context: Context) {
    private val keyUrl = stringPreferencesKey("server_url")
    private val keyToken = stringPreferencesKey("dev_token")

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyUrl] ?: DEFAULT_URL
    }

    val token: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyToken] ?: ""
    }

    suspend fun save(url: String, token: String) {
        context.dataStore.edit { prefs ->
            prefs[keyUrl] = url.trim()
            prefs[keyToken] = token.trim()
        }
    }

    companion object {
        const val DEFAULT_URL = "ws://10.0.2.2:8000/ws"
    }
}
