package com.masterdns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileTomlImporterTest {
    @Test
    fun parses_simpleKeyValuePair() {
        val out = ProfileTomlImporter.parseTomlValues("ENCRYPTION_KEY = \"abc\"")
        assertEquals("abc", out["ENCRYPTION_KEY"])
    }

    @Test
    fun parses_domainsArray() {
        val out = ProfileTomlImporter.parseTomlValues("DOMAINS = [\"a.com\", \"b.com\"]")
        assertEquals("a.com, b.com", out["DOMAINS"])
    }

    @Test
    fun strips_commentsAfterHash() {
        val out = ProfileTomlImporter.parseTomlValues("ENCRYPTION_KEY = \"abc\" # trailing")
        assertEquals("abc", out["ENCRYPTION_KEY"])
    }

    @Test
    fun skips_blankAndCommentOnlyLines() {
        val out = ProfileTomlImporter.parseTomlValues(
            """

            # full comment
            ENCRYPTION_KEY = "x"
            """.trimIndent()
        )
        assertEquals("x", out["ENCRYPTION_KEY"])
        assertFalse(out.containsKey(""))
    }

    @Test
    fun rejects_valuesWithEmbeddedControlChar() {
        // A value containing a raw control char (here bell, 0x07 — chosen
        // because lineSequence() splits on \n/\r but NOT on \u0007, so
        // the char reaches the rejection guard intact) must be dropped
        // so it cannot reach ConfigGenerator interpolation. Guards the
        // full class of control-char injection, of which newline was the
        // headline case (newline is now handled by escaping at the
        // ConfigGenerator output side AND by lineSequence() splitting at
        // the input side; other control chars are handled here).
        val malicious = "ENCRYPTION_KEY = \"x\u0007y\""
        val out = ProfileTomlImporter.parseTomlValues(malicious)
        assertFalse("expected ENCRYPTION_KEY to be rejected", out.containsKey("ENCRYPTION_KEY"))
    }

    @Test
    fun rejects_valuesExceedingMaxLength() {
        val longValue = "x".repeat(ProfileTomlImporter.MAX_VALUE_LENGTH + 1)
        val out = ProfileTomlImporter.parseTomlValues("ENCRYPTION_KEY = \"$longValue\"")
        assertFalse(out.containsKey("ENCRYPTION_KEY"))
    }

    @Test
    fun accepts_valuesAtMaxLength() {
        val exactLength = "x".repeat(ProfileTomlImporter.MAX_VALUE_LENGTH)
        val out = ProfileTomlImporter.parseTomlValues("ENCRYPTION_KEY = \"$exactLength\"")
        assertEquals(exactLength, out["ENCRYPTION_KEY"])
    }
}
