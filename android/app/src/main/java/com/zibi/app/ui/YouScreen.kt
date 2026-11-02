package com.zibi.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Everything about you and the connection: what Zibi knows (Profile) and the server settings. */
@Composable
fun YouScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = selected) {
            Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text("Profile") })
            Tab(selected = selected == 1, onClick = { selected = 1 }, text = { Text("Server") })
        }
        if (selected == 0) ProfileScreen() else SettingsScreen()
    }
}
