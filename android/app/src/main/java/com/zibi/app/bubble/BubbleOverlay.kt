package com.zibi.app.bubble

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        // Collapsed: Sophisticated 3D metallic ring bubble
        MetallicRingBubble(
            recording = recording,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onTap = { panelState.value = BubblePanelState.open() },
            contentDesc = if (recording) "Recording, open Zibi to stop" else "Open Zibi",
        )
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

/**
 * The classic iPhone AssistiveTouch mark: a ring with a dot at its centre.
 * Stroke weight, dot size, and proportions match the iOS reference.
 * The dot swells and turns red while recording.
 */
@Composable
private fun AssistiveTouchGlyph(recording: Boolean, modifier: Modifier = Modifier) {
    val tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(28.dp)) {
        val stroke = 3.5.dp.toPx()
        val ringRadius = (size.minDimension - stroke) / 2f
        drawCircle(color = tint, radius = ringRadius, style = Stroke(width = stroke))
        val dotRadius = if (recording) size.minDimension * 0.22f else size.minDimension * 0.16f
        drawCircle(color = tint, radius = dotRadius)
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

/**
 * Sophisticated 3D metallic ring bubble with concentric layers, radial gradients,
 * and beveled edges for realistic depth. Monochrome charcoal/silver-gray palette.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MetallicRingBubble(
    recording: Boolean,
    onDragStart: (Float, Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    contentDesc: String,
) {
    val context = LocalContext.current
    val downRawX = remember { mutableStateOf(0f) }
    val downRawY = remember { mutableStateOf(0f) }
    val dragging: MutableState<Boolean> = remember { mutableStateOf(false) }
    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    Box(
        modifier = Modifier
            .size(56.dp)
            .shadow(12.dp, CircleShape)
            .semantics { contentDescription = contentDesc }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX.value = event.rawX
                        downRawY.value = event.rawY
                        dragging.value = false
                        onDragStart(downRawX.value, downRawY.value)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging.value && hypot(event.rawX - downRawX.value, event.rawY - downRawY.value) > touchSlop) {
                            dragging.value = true
                        }
                        if (dragging.value) onDrag(event.rawX, event.rawY)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging.value) {
                            onDrag(event.rawX, event.rawY)
                            onDragEnd()
                        } else {
                            onTap()
                        }
                        dragging.value = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (dragging.value) onDragEnd()
                        dragging.value = false
                    }
                }
                true
            },
    ) {
        Canvas(Modifier.size(56.dp)) {
            drawMetallicRing(this, recording = recording)
        }
    }
}

private fun drawMetallicRing(scope: DrawScope, recording: Boolean) {
    val size: Float = minOf(scope.size.width, scope.size.height)
    val cx: Float = scope.size.width / 2f
    val cy: Float = scope.size.height / 2f

    // Ring dimensions - creating a substantial 3D ring
    val outerRadius = size * 0.46f          // 46% of 56dp = ~25.8dp
    val innerRadius = size * 0.28f          // 28% of 56dp = ~15.7dp
    val ringThickness = outerRadius - innerRadius  // ~10dp thick ring

    // === Layer 1: Outer dark charcoal base ring (recessed) ===
    // Draw multiple circles to create gradient-like depth
    val layer1Outer = outerRadius
    val layer1Inner = outerRadius - ringThickness * 0.45f
    drawRingBands(scope, cx, cy, layer1Outer, layer1Inner, 8) { i, total ->
        val t = i / (total - 1f)
        // Dark charcoal gradient: black -> dark charcoal -> near black -> black
        when {
            t < 0.25f -> Color(0xFF0D0D10)
            t < 0.5f -> Color(0xFF1A1A20)
            t < 0.75f -> Color(0xFF121218)
            else -> Color(0xFF0D0D10)
        }
    }

    // === Layer 2: Medium charcoal inner bevel (catch light) ===
    val layer2Outer = outerRadius - ringThickness * 0.08f
    val layer2Inner = outerRadius - ringThickness * 0.52f
    drawRingBands(scope, cx, cy, layer2Outer, layer2Inner, 6) { i, total ->
        val t = i / (total - 1f)
        when {
            t < 0.33f -> Color(0xFF2A2A35)
            t < 0.66f -> Color(0xFF1E1E28)
            else -> Color(0xFF1A1A22)
        }
    }

    // === Layer 3: Silver-gray highlight ring (top catch light, offset for 3D) ===
    val highlightCx = cx - size * 0.03f
    val highlightCy = cy - size * 0.03f
    val layer3Outer = outerRadius - ringThickness * 0.12f
    val layer3Inner = outerRadius - ringThickness * 0.58f
    drawRingBands(scope, highlightCx, highlightCy, layer3Outer, layer3Inner, 5) { i, total ->
        val t = i / (total - 1f)
        when {
            t < 0.25f -> Color(0xFF6E6E80)
            t < 0.5f -> Color(0xFF4A4A5C)
            t < 0.75f -> Color(0xFF3A3A4A)
            else -> Color(0xFF2E2E3C)
        }
    }

    // === Layer 4: Inner bevel - raised inner edge (concave highlight) ===
    val layer4Outer = innerRadius + ringThickness * 0.22f
    val layer4Inner = innerRadius
    drawRingBands(scope, cx, cy, layer4Outer, layer4Inner, 6) { i, total ->
        val t = i / (total - 1f)
        when {
            t < 0.2f -> Color(0xFF5A5A6E)
            t < 0.4f -> Color(0xFF4A4A5E)
            t < 0.6f -> Color(0xFF3A3A4C)
            t < 0.8f -> Color(0xFF2E2E3A)
            else -> Color(0xFF24242E)
        }
    }

    // === Layer 5: Inner shadow/depth (dark recess inside the ring) ===
    val layer5Outer = innerRadius + ringThickness * 0.15f
    val layer5Inner = innerRadius + ringThickness * 0.02f
    drawRingBands(scope, cx, cy, layer5Outer, layer5Inner, 4) { i, total ->
        val t = i / (total - 1f)
        when {
            t < 0.33f -> Color(0xFF0A0A0D)
            t < 0.66f -> Color(0xFF0F0F14)
            else -> Color(0xFF0D0D12)
        }
    }

    // === Subtle outer edge highlight (thin bright line) ===
    scope.drawCircle(
        color = Color(0xFF3A3A4A).copy(alpha = 0.4f),
        radius = outerRadius,
        style = Stroke(width = maxOf(with(scope) { 1.dp.toPx() }, size * 0.006f)),
    )

    // === Subtle inner edge highlight (thin bright line) ===
    scope.drawCircle(
        color = Color(0xFF5A5A6E).copy(alpha = 0.5f),
        radius = innerRadius,
        style = Stroke(width = maxOf(with(scope) { 1.dp.toPx() }, size * 0.006f)),
    )

    // === Recording indicator: Inner pulse ring ===
    if (recording) {
        val pulseRadius = innerRadius * 0.85f
        val pulseAlpha = (0.4f + 0.3f * sin((System.currentTimeMillis() / 400.0).toDouble())).toFloat().coerceIn(0.3f, 0.7f)
        scope.drawCircle(
            color = Color(0xFFFF3B30).copy(alpha = pulseAlpha),
            radius = pulseRadius,
            style = Stroke(width = maxOf(with(scope) { 2.dp.toPx() }, size * 0.015f)),
        )
        // Inner glow dot
        scope.drawCircle(
            color = Color(0xFFFF3B30).copy(alpha = 0.6f),
            radius = pulseRadius * 0.35f,
        )
    }
}

private fun drawRingBands(
    scope: DrawScope,
    cx: Float,
    cy: Float,
    outerRadius: Float,
    innerRadius: Float,
    bandCount: Int,
    colorForBand: (Int, Int) -> Color,
) {
    val thickness = outerRadius - innerRadius
    for (i in 0 until bandCount) {
        val t = i / (bandCount - 1f)
        val radius = outerRadius - thickness * t
        val strokeWidth = thickness / bandCount * 1.2f
        scope.drawCircle(
            color = colorForBand(i, bandCount),
            radius = radius,
            style = Stroke(width = strokeWidth),
        )
    }
}
