package com.zibi.app.bubble

import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot
import android.widget.Toast
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zibi.app.MainActivity
import com.zibi.app.appContainer

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BubbleOverlay(
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val chatVm = appContainer().chatVm
    val recording by chatVm.recording.collectAsState()
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var downRawX by remember { mutableStateOf(0f) }
    var downRawY by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
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
                                menuExpanded = true
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
                imageVector = Icons.Filled.Mic,
                contentDescription = "Open Zibi menu",
                tint = if (recording) Color.Red else MaterialTheme.colorScheme.onPrimary,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Chat with Zibi") },
                onClick = {
                    menuExpanded = false
                    openMainScreen(context, MainActivity.SCREEN_CHAT)
                },
            )
            DropdownMenuItem(
                text = { Text(if (recording) "Stop and send" else "Use mic") },
                onClick = {
                    menuExpanded = false
                    if (recording) {
                        chatVm.stopRecordingAndSend()
                    } else if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        chatVm.startRecording()
                    } else {
                        Toast.makeText(
                            context,
                            "Grant microphone permission in Zibi before using voice capture",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Quick actions") },
                onClick = {
                    menuExpanded = false
                    openMainScreen(context, MainActivity.SCREEN_QUICK)
                },
            )
        }
    }
}

private fun openMainScreen(context: android.content.Context, screen: String) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SCREEN, screen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        },
    )
}
