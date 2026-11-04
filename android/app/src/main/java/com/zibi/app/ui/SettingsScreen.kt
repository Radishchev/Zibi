package com.zibi.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.Divider
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.zibi.app.AppContainer
import com.zibi.app.appContainer
import com.zibi.app.bubble.BubbleService
import com.zibi.app.net.ConnState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var url by mutableStateOf("")
    var token by mutableStateOf("")
    var saved by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var bubbleEnabled by mutableStateOf(false)
    var canDrawOverlays by mutableStateOf(false)

    val connection = c.client.state

    init {
        viewModelScope.launch {
            url = c.settings.serverUrl.first()
            token = c.settings.token.first()
            c.settings.bubbleEnabled.collect { bubbleEnabled = it }
        }
    }

    fun reconnect() {
        c.client.reconnect()
    }

    fun clearMemory() {
        viewModelScope.launch {
            status = try {
                c.api.clearConversation()
                "Zibi's memory was cleared."
            } catch (e: Exception) {
                e.message ?: "Couldn't reach the server."
            }
        }
        saved = false
    }

    fun save() {
        viewModelScope.launch {
            c.settings.save(url, token)
            c.client.reconnect()
            saved = true
            status = "Saved."
        }
    }

    fun setBubbleEnabled(enabled: Boolean, context: android.content.Context) {
        bubbleEnabled = enabled
        viewModelScope.launch {
            if (enabled && !c.permissions.ensure(Manifest.permission.RECORD_AUDIO)) {
                bubbleEnabled = false
                return@launch
            }
            c.settings.setBubbleEnabled(enabled)
            if (enabled) {
                if (!canDrawOverlays) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } else {
                    BubbleService.start(context)
                }
            } else {
                BubbleService.stop(context)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel = appContainer().settingsVm) {
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val updateOverlayPermission = {
            vm.canDrawOverlays = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updateOverlayPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateOverlayPermission()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        ConnectionStatus(vm)
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
        if (vm.saved) Text("Saved.", modifier = Modifier.padding(top = 8.dp))
        vm.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Text("Voice bubble", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable hold-to-talk voice bubble", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = vm.bubbleEnabled,
                onCheckedChange = { vm.setBubbleEnabled(it, context) }
            )
        }
        if (vm.bubbleEnabled && !vm.canDrawOverlays) {
            OutlinedButton(onClick = { vm.setBubbleEnabled(true, context) }) {
                Text("Grant overlay permission")
            }
            Text(
                "Requires overlay permission (tap button above to grant in Settings).",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 24.dp))

        Text("Conversation", style = MaterialTheme.typography.titleMedium)
        Text(
            "Zibi remembers your recent conversation. If it gets confused, start fresh.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Button(onClick = vm::clearMemory) { Text("Clear conversation memory") }
        vm.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Live backend connection status. Lives here rather than in the chat UI. */
@Composable
private fun ConnectionStatus(vm: SettingsViewModel) {
    val conn by vm.connection.collectAsStateWithLifecycle()
    val state = conn
    val label = when (state) {
        ConnState.Connected -> "Connected"
        ConnState.Connecting -> "Connecting…"
        ConnState.Disconnected -> "Offline"
        is ConnState.Failed -> "Offline: ${state.message}"
    }
    val tint = when (state) {
        ConnState.Connected -> MaterialTheme.colorScheme.primary
        ConnState.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (state !is ConnState.Connected && state !is ConnState.Connecting) {
            TextButton(onClick = vm::reconnect) { Text("Reconnect") }
        }
    }
}