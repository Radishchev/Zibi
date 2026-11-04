package com.zibi.app

import com.zibi.app.bubble.BubblePanelState
import com.zibi.app.bubble.BubblePanelTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePanelStateTest {

    @Test
    fun openingDefaultsToChat() {
        val state = BubblePanelState.open()
        assertTrue(state.expanded)
        assertEquals(BubblePanelTab.Chat, state.tab)
    }

    @Test
    fun selectingQuickChangesOnlyTheTab() {
        val state = BubblePanelState.open().select(BubblePanelTab.Quick)
        assertTrue(state.expanded)
        assertEquals(BubblePanelTab.Quick, state.tab)
    }

    @Test
    fun closingReturnsToCollapsedState() {
        val state = BubblePanelState.open().close()
        assertEquals(false, state.expanded)
    }

    @Test
    fun closedStateIsCollapsed() {
        val state = BubblePanelState()
        assertEquals(false, state.expanded)
        assertEquals(BubblePanelTab.Chat, state.tab)
    }
}