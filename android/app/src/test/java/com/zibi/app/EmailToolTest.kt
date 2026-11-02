package com.zibi.app

import com.zibi.app.tools.MailApp
import com.zibi.app.tools.emailComposerSummary
import com.zibi.app.tools.matchMailApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailToolTest {
    private val installed = listOf(
        MailApp("Gmail", "com.google.android.gm"),
        MailApp("Outlook", "com.microsoft.office.outlook"),
        MailApp("Yahoo Mail", "com.yahoo.mobile.client.android.mail"),
        MailApp("Proton Mail", "ch.protonmail.android"),
    )

    @Test
    fun matches_by_label_case_insensitive() {
        assertEquals("com.google.android.gm", matchMailApp("gmail", installed)?.packageName)
        assertEquals("com.microsoft.office.outlook", matchMailApp("OUTLOOK", installed)?.packageName)
        assertEquals("com.yahoo.mobile.client.android.mail", matchMailApp("yahoo mail", installed)?.packageName)
    }

    @Test
    fun matches_by_package_name_or_fragment() {
        assertEquals("ch.protonmail.android", matchMailApp("ch.protonmail.android", installed)?.packageName)
        assertEquals("com.microsoft.office.outlook", matchMailApp("microsoft.office", installed)?.packageName)
        assertEquals("com.google.android.gm", matchMailApp("android.gm", installed)?.packageName)
    }

    @Test
    fun unknown_app_returns_null_so_tool_can_list_installed_ones() {
        assertNull(matchMailApp("telegram", installed))
        assertNull(matchMailApp("", installed))
    }

    @Test
    fun unresolved_contact_summary_explains_that_the_recipient_must_be_added() {
        assertEquals(
            "Email draft opened in Gmail. I couldn't find an email address for Sam; add it before sending.",
            emailComposerSummary("Sam", null, "Gmail"),
        )
    }

    @Test
    fun missing_recipient_summary_asks_user_to_add_one() {
        assertEquals(
            "Email draft opened in Gmail. Add a recipient before sending.",
            emailComposerSummary(null, null, "Gmail"),
        )
    }
}
