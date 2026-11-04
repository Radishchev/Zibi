package com.zibi.app

import com.zibi.app.bubble.BubblePosition
import com.zibi.app.bubble.BubblePositioning
import org.junit.Assert.assertEquals
import org.junit.Test

class BubblePositioningTest {
    @Test
    fun dragUsesScreenCoordinatesRelativeToTheOriginalTouch() {
        assertEquals(
            BubblePosition(140, 230),
            BubblePositioning.dragFrom(100, 200, 500f, 600f, 540f, 630f),
        )
    }

    @Test
    fun releasedBubbleDockingChoosesTheNearestHorizontalEdge() {
        assertEquals(BubblePosition(0, 240), BubblePositioning.dock(BubblePosition(15, 240), 500, 56, 600))
        assertEquals(BubblePosition(444, 240), BubblePositioning.dock(BubblePosition(400, 240), 500, 56, 600))
    }

    @Test
    fun positionsAreClampedToTheAvailableScreenArea() {
        assertEquals(BubblePosition(0, 544), BubblePositioning.clamp(BubblePosition(-20, 700), 500, 56, 600))
    }

    @Test
    fun panelPositionFromRightEdge() {
        val pos = BubblePositioning.panelPosition(
            dockedPosition = BubblePosition(444, 300),
            screenWidth = 500,
            screenHeight = 800,
            panelWidth = 320,
            panelHeight = 480,
            margin = 16,
        )
        assertEquals(500 - 320 - 16, pos.x)
        assertEquals(300.coerceIn(0, 800 - 480 - 16).coerceAtLeast(0), pos.y)
    }

    @Test
    fun panelPositionFromLeftEdge() {
        val pos = BubblePositioning.panelPosition(
            dockedPosition = BubblePosition(0, 300),
            screenWidth = 500,
            screenHeight = 800,
            panelWidth = 320,
            panelHeight = 480,
            margin = 16,
        )
        assertEquals(16, pos.x)
        assertEquals(300.coerceIn(0, 800 - 480 - 16).coerceAtLeast(0), pos.y)
    }

    @Test
    fun panelPositionClampsYWhenNearBottom() {
        val pos = BubblePositioning.panelPosition(
            dockedPosition = BubblePosition(0, 700),
            screenWidth = 500,
            screenHeight = 800,
            panelWidth = 320,
            panelHeight = 480,
            margin = 16,
        )
        assertEquals(800 - 480 - 16, pos.y)
    }

    @Test
    fun panelPositionClampsYWhenNearTop() {
        val pos = BubblePositioning.panelPosition(
            dockedPosition = BubblePosition(0, -50),
            screenWidth = 500,
            screenHeight = 800,
            panelWidth = 320,
            panelHeight = 480,
            margin = 16,
        )
        assertEquals(16, pos.y)
    }
}
