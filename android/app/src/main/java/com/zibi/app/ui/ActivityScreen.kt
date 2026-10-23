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

/** Reminders and History share one tab to keep the bottom bar at five items. */
@Composable
fun ActivityScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = selected) {
            Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text("Reminders") })
            Tab(selected = selected == 1, onClick = { selected = 1 }, text = { Text("History") })
        }
        if (selected == 0) RemindersScreen() else HistoryScreen()
    }
}
