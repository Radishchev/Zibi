package com.zibi.app

import com.zibi.app.protocol.string
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {
    @Test
    fun sensitiveValuesBecomePlaceholdersAndComeBackLocally() = runTest {
        val vault = newVault()
        val r = vault.redact("text Sam on +91 98765 43210 or sam@example.com")
        assertEquals("text Sam on [PHONE_1] or [EMAIL_1]", r.text)
        assertEquals(2, r.count)
        assertFalse(r.text.contains("98765"))
        assertEquals("text Sam on +91 98765 43210 or sam@example.com", vault.resolve(r.text))
    }

    @Test
    fun theSameValueAlwaysGetsTheSamePlaceholderEvenWrittenDifferently() = runTest {
        val vault = newVault()
        val a = vault.redact("call 9876543210").text
        val b = vault.redact("call 98765 43210 now").text
        val c = vault.redact("call 9123456780").text
        assertEquals("call [PHONE_1]", a)
        assertEquals("call [PHONE_1] now", b)
        assertEquals("call [PHONE_2]", c)
    }

    @Test
    fun valuesAreStoredEncryptedAndSurviveARestart() = runTest {
        val dao = FakeVaultDao()
        val first = newVault(dao)
        val placeholder = first.protect("my home address is 12 Rose Lane")
        assertEquals("[PRIVATE_1]", placeholder)
        // a new vault over the same database (the app restarted) still resolves it
        assertEquals("my home address is 12 Rose Lane", newVault(dao).resolve("see $placeholder").removePrefix("see "))
        // and does not hand out a clashing placeholder
        assertEquals("[PRIVATE_2]", newVault(dao).protect("another secret"))
    }

    @Test
    fun unknownPlaceholdersAreLeftAlone() = runTest {
        assertEquals("hi [PHONE_9]", newVault().resolve("hi [PHONE_9]"))
    }

    @Test
    fun jsonIsMaskedAndRestoredEverywhereButKeysAreUntouched() = runTest {
        val vault = newVault()
        val original = buildJsonObject {
            put("to", "9876543210")
            put("nested", JsonArray(listOf(JsonPrimitive("mail x@y.com"), JsonPrimitive("fine"))))
            put("count", 3)
        }
        val masked = vault.redactJson(original) as JsonObject
        assertEquals("[PHONE_1]", masked.string("to"))
        assertTrue(masked.toString().contains("[EMAIL_1]") && !masked.toString().contains("x@y.com"))
        assertEquals(original, vault.resolveJson(masked))
    }
}
