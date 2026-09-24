package com.masterdns.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonParsersTest {
    @Test
    fun parseAdvancedJson_returnsMap() {
        assertEquals(mapOf("A" to "1"), parseAdvancedJson("{\"A\":\"1\"}"))
    }

    @Test
    fun parseAdvancedJson_malformed_returnsEmpty() {
        assertEquals(emptyMap<String, String>(), parseAdvancedJson("not json"))
    }

    @Test
    fun parseDomainsJson_list() {
        assertEquals(listOf("a.com", "b.com"), parseDomainsJson("[\"a.com\",\"b.com\"]"))
    }

    @Test
    fun parseDomainsJson_singleString_fallback() {
        assertEquals(listOf("solo.com"), parseDomainsJson("\"solo.com\""))
    }

    @Test
    fun parseDomainsJson_malformed_returnsFallback() {
        assertEquals(listOf("not json"), parseDomainsJson("not json"))
    }
}
