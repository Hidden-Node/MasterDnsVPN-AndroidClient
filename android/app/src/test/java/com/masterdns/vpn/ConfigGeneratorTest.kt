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
    fun escapeToml_newlineIsPassedThroughLiteral() {
        // Documented current behavior: escapeToml does NOT escape newlines,
        // so values containing \n can inject new top-level keys into the
        // generated TOML. This test locks in that behavior so plan 008
        // (TOML whitelist) can flip it explicitly.
        assertEquals("a\nb", ConfigGenerator.escapeToml("a\nb"))
    }

    @Test
    fun escapeToml_emptyString() {
        assertEquals("", ConfigGenerator.escapeToml(""))
    }
}
