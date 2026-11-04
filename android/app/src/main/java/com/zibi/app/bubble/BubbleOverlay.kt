package com.zibi.app.bubble

import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zibi.app.PermissionActivity
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

    // Notify service when panel expansion changes
    LaunchedEffect(isExpanded) {
        onPanelExpandedChanged(isExpanded)
    }

    Box(contentAlignment = Alignment.Center) {
        if (!isExpanded) {
            // Collapsed: iPhone 5-style AssistiveTouch bubble
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = Color(0xFF1A1A2E), // Dark charcoal
                        shape = CircleShape,
                    )
                    .graphicsLayer { shadowElevation = 8f }
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
                    tint = if (recording) Color.Red else Color.White,
                )
            }
        } else {
            // Expanded: Floating panel with Chat and Quick tabs
            val panelWidth = 320.dp
            val panelHeight = 480.dp

            // Panel - the overlay window is sized to exactly the panel, so there is
            // no backdrop to render. Only the close button dismisses the panel.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(panelWidth)
                        .height(panelHeight)
                        .background(
                            color = Color(0xFF1A1A2E), // Dark charcoal
                            shape = RoundedCornerShape(20.dp),
                        )
                        .graphicsLayer { shadowElevation = 20f },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        // Header with tab selector and close button - fixed height
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tab buttons - simple text
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TabText(
                                    label = "Chat",
                                    selected = currentTab == BubblePanelTab.Chat,
                                    onClick = { panelState.value = panelState.value.select(BubblePanelTab.Chat) },
                                )
                                TabText(
                                    label = "Quick",
                                    selected = currentTab == BubblePanelTab.Quick,
                                    onClick = { panelState.value = panelState.value.select(BubblePanelTab.Quick) },
                                )
                            }
                            // Close button - large prominent red circle with X
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0xFFFF3B30), CircleShape)
                                    .clickable { panelState.value = panelState.value.close() }
                                    .semantics { contentDescription = "Close panel" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Close, tint = Color.White, contentDescription = "", modifier = Modifier.size(24.dp))
                            }
                        }
                        // Content - fill remaining space, handles IME resize
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
    }
}

@Composable
private fun TabText(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
        fontSize = 16.sp,
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onClick() }
            .semantics { contentDescription = label },
    )
}