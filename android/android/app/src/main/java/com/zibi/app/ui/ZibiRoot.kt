package com.zibi.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private enum class Screen(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Default.Face),
    Reminders("Reminders", Icons.Default.Notifications),
    History("History", Icons.Default.DateRange),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun ZibiRoot() {
    var current by rememberSaveable { mutableStateOf(Screen.Chat) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = current == s,
                        onClick = { current = s },
                        icon = { Icon(s.icon, contentDescription = s.label) },
                        label = { Text(s.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (current) {
                Screen.Chat -> ChatScreen()
                Screen.Reminders -> RemindersScreen()
                Screen.History -> HistoryScreen()
                Screen.Settings -> SettingsScreen()
            }
        }
    }
}
