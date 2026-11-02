package com.zibi.app.bubble

data class BubblePosition(val x: Int, val y: Int)

object BubblePositioning {
    fun dragFrom(
        startX: Int,
        startY: Int,
        downRawX: Float,
        downRawY: Float,
        rawX: Float,
        rawY: Float,
    ) = BubblePosition(
        (startX + rawX - downRawX).toInt(),
        (startY + rawY - downRawY).toInt(),
    )

    fun clamp(position: BubblePosition, screenWidth: Int, bubbleSize: Int, screenHeight: Int) = BubblePosition(
        position.x.coerceIn(0, (screenWidth - bubbleSize).coerceAtLeast(0)),
        position.y.coerceIn(0, (screenHeight - bubbleSize).coerceAtLeast(0)),
    )

    fun dock(position: BubblePosition, screenWidth: Int, bubbleSize: Int, screenHeight: Int): BubblePosition {
        val clamped = clamp(position, screenWidth, bubbleSize, screenHeight)
        val x = if (clamped.x + bubbleSize / 2 < screenWidth / 2) 0 else (screenWidth - bubbleSize).coerceAtLeast(0)
        return BubblePosition(x, clamped.y)
    }
}
