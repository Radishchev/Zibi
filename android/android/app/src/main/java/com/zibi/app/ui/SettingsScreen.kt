package com.zibi.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zibi.app.AppContainer
import com.zibi.app.appViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var saved by mutableStateOf(false)

    init {
        viewModelScope.launch {
            url = c.settings.serverUrl.first()
            token = c.settings.token.first()
        }
    }

    fun save() {
        viewModelScope.launch {
            c.settings.save(url, token)
            c.client.reconnect()
            saved = true
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = appViewModel { SettingsViewModel(it) }) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it; vm.saved = false },
            label = { Text("WebSocket URL") },
            supportingText = { Text("Emulator: ws://10.0.2.2:8000/ws\nPhone: ws://<laptop-LAN-IP>:8000/ws") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = vm.token,
            onValueChange = { vm.token = it; vm.saved = false },
            label = { Text("Dev token (ZIBI_DEV_TOKEN)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Button(onClick = vm::save, modifier = Modifier.padding(top = 16.dp)) { Text("Save and reconnect") }
        if (vm.saved) Text("Saved. Check the Chat tab for connection status.", modifier = Modifier.padding(top = 8.dp))
    }
}
