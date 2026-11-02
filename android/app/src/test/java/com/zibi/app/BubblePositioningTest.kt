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
}
