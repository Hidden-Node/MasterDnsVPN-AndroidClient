package com.masterdns.vpn

import com.masterdns.vpn.util.ConfigGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigGeneratorTest {
    @Test
    fun escapeToml_plainStringKeptAsIs() {
        assertEquals("hello world", ConfigGenerator.escapeToml("hello world"))
    }

    @Test
    fun escapeToml_quoteIsEscaped() {
        assertEquals("a\\\"b", ConfigGenerator.escapeToml("a\"b"))
    }

    @Test
    fun escapeToml_backslashIsEscaped() {
        assertEquals("a\\\\b", ConfigGenerator.escapeToml("a\\b"))
    }

    @Test
    fun escapeToml_newlineIsEscaped() {
        // Plan 008 fix: newlines MUST be escaped so they do not inject new
        // top-level TOML keys when ConfigGenerator interpolates a value
        // (e.g. ENCRYPTION_KEY) into the generated config.
        assertEquals("a\\nb", ConfigGenerator.escapeToml("a\nb"))
    }

    @Test
    fun escapeToml_carriageReturnIsEscaped() {
        assertEquals("a\\rb", ConfigGenerator.escapeToml("a\rb"))
    }

    @Test
    fun escapeToml_tabIsEscaped() {
        assertEquals("a\\tb", ConfigGenerator.escapeToml("a\tb"))
    }

    @Test
    fun escapeToml_combinedEscapeOrderBackslashFirst() {
        // Backslash must be escaped FIRST so subsequent escapes don't
        // double-encode a real backslash.
        // Input: a"b\c\nd  Expected: a\"b\\c\nd
        assertEquals("a\\\"b\\\\c\\nd", ConfigGenerator.escapeToml("a\"b\\c\nd"))
    }

    @Test
    fun escapeToml_emptyString() {
        assertEquals("", ConfigGenerator.escapeToml(""))
    }
}
