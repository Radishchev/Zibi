package com.zibi.app

import com.zibi.app.share.extractIncomingSharedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test
    fun acceptsAndTrimsOnlyExplicitPlainTextShares() {
        assertEquals(
            "Exam tomorrow at 9am",
            extractIncomingSharedText("android.intent.action.SEND", "text/plain", "  Exam tomorrow at 9am  "),
        )
        assertNull(extractIncomingSharedText("android.intent.action.SEND", "image/png", "not text"))
        assertNull(extractIncomingSharedText("android.intent.action.VIEW", "text/plain", "not shared"))
        assertNull(extractIncomingSharedText("android.intent.action.SEND", "text/plain", "  "))
    }

    @Test
    fun acceptsAndroidSelectionMenuTextActions() {
        assertEquals(
            "Assignment due Friday",
            extractIncomingSharedText("android.intent.action.PROCESS_TEXT", "text/plain", " Assignment due Friday "),
        )
    }
}
