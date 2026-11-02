package com.zibi.app

import com.zibi.app.privacy.findSensitive
import com.zibi.app.privacy.luhn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SAME cases are in the backend test (tests/test_privacy.py). If you change one, change both. */
private val CASES = listOf(
    "call me on +91 98765 43210 tomorrow" to "call me on [PHONE] tomorrow",
    "my number is 9876543210" to "my number is [PHONE]",
    "ring (555) 123-4567 please" to "ring [PHONE] please",
    "email sam@example.com now" to "email [EMAIL] now",
    "card 4111 1111 1111 1111 expires soon" to "card [CARD] expires soon",
    "aadhaar 1234 5678 9012 ok" to "aadhaar [ID] ok",
    "PAN is ABCDE1234F" to "PAN is [ID]",
    "ssn 123-45-6789" to "ssn [ID]",
    "my password is hunter2 ok" to "my password is [SECRET] ok",
    "password: correct-horse" to "password: [SECRET]",
    "my pin 4821" to "my pin [SECRET]",
    "key AbCdEfGh1234567890AbCdEfGh1234567890 here" to "key [SECRET] here",
    // things that must NOT be hidden
    "pin the note to the top" to "pin the note to the top",
    "alarms every minute from 5:45 to 6:20, 36 alarms" to "alarms every minute from 5:45 to 6:20, 36 alarms",
    "due 2026-10-30 at 14:30" to "due 2026-10-30 at 14:30",
    "meet 10:30-11:30 tomorrow" to "meet 10:30-11:30 tomorrow",
    "set 3 5 7 9 11 13 15 alarms" to "set 3 5 7 9 11 13 15 alarms",
    "pi is 3.14159265 roughly" to "pi is 3.14159265 roughly",
    "short number 555-1234" to "short number 555-1234",
    "order 1234 5678 9012 3456 shipped" to "order 1234 5678 9012 3456 shipped",
    "Deep_Learning_Unit_1_notes_final.pdf" to "Deep_Learning_Unit_1_notes_final.pdf",
    "see Lab_Report_2026_10_30_Final_Version_v2.docx" to "see Lab_Report_2026_10_30_Final_Version_v2.docx",
    "key AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY ok" to "key [SECRET] ok",
    "card 4111 1111 1111 1111 and 1234 5678 9012" to "card [CARD] and [ID]",
    "text [PHONE_1] and [EMAIL_2] now" to "text [PHONE_1] and [EMAIL_2] now",
)

private fun mask(text: String): String {
    val out = StringBuilder()
    var pos = 0
    for (s in findSensitive(text)) {
        out.append(text, pos, s.start).append('[').append(s.kind).append(']')
        pos = s.end
    }
    return out.append(text, pos, text.length).toString()
}

class RedactorTest {
    @Test
    fun everySharedCaseMatchesTheBackend() {
        val failures = CASES.filter { (input, expected) -> mask(input) != expected }
            .map { (input, expected) -> "'$input' -> '${mask(input)}' (expected '$expected')" }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun luhnChecksCardNumbers() {
        assertTrue(luhn("4111111111111111"))
        assertFalse(luhn("4111111111111112"))
    }

    @Test
    fun spansNeverOverlap() {
        assertEquals(listOf("EMAIL"), findSensitive("mail 9876543210@example.com").map { it.kind })
    }
}
