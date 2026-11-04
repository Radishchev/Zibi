package com.zibi.app.bubble

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zibi.app.appContainer
import com.zibi.app.ui.ChatScreen
import com.zibi.app.ui.QuickScreen
import kotlin.math.hypot

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BubbleOverlay(
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onPanelExpandedChanged: (Boolean) -> Unit,
) {
    val chatVm = appContainer().chatVm
    val quickVm = appContainer().quickVm
    val recording by chatVm.recording.collectAsState()
    val context = LocalContext.current
    val panelState = remember { mutableStateOf(BubblePanelState()) }
    var downRawX by remember { mutableStateOf(0f) }
    var downRawY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    val isExpanded = panelState.value.expanded
    val currentTab = panelState.value.tab

    LaunchedEffect(isExpanded) {
        onPanelExpandedChanged(isExpanded)
    }

    if (!isExpanded) {
        // Collapsed: AssistiveTouch-style floating bubble
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(8.dp, CircleShape)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            dragging = false
                            onDragStart(downRawX, downRawY)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!dragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) {
                                dragging = true
                            }
                            if (dragging) onDrag(event.rawX, event.rawY)
                        }
                        MotionEvent.ACTION_UP -> {
                            if (dragging) {
                                onDrag(event.rawX, event.rawY)
                                onDragEnd()
                            } else {
                                panelState.value = BubblePanelState.open()
                            }
                            dragging = false
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (dragging) onDragEnd()
                            dragging = false
                        }
                    }
                    true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (recording) Icons.Filled.Pause else Icons.Filled.Mic,
                contentDescription = if (recording) "Recording - tap to stop" else "Open Zibi",
                tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        // Expanded: the overlay window is sized to exactly the panel, so only the
        // close button dismisses it.
        val panelWidth = 320.dp
        val panelHeight = 480.dp

        Box(
            modifier = Modifier
                .width(panelWidth)
                .height(panelHeight)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TabPill(
                            label = "Chat",
                            selected = currentTab == BubblePanelTab.Chat,
                            onClick = { panelState.value = panelState.value.select(BubblePanelTab.Chat) },
                        )
                        TabPill(
                            label = "Quick",
                            selected = currentTab == BubblePanelTab.Quick,
                            onClick = { panelState.value = panelState.value.select(BubblePanelTab.Quick) },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                            .clickable { panelState.value = panelState.value.close() }
                            .semantics { contentDescription = "Close panel" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            tint = MaterialTheme.colorScheme.onError,
                            contentDescription = "",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (currentTab) {
                        BubblePanelTab.Chat -> ChatScreen(vm = chatVm)
                        BubblePanelTab.Quick -> QuickScreen(vm = quickVm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable { onClick() }
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
