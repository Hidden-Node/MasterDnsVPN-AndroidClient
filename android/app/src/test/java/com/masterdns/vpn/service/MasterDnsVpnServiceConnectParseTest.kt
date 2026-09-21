package com.masterdns.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MasterDnsVpnServiceConnectParseTest {
    @Test
    fun parsesIpv6LiteralWithPort() {
        val t = parseProxyTarget("CONNECT", "[::1]:443")!!
        assertEquals("::1", t.host)
        assertEquals(443, t.port)
    }

    @Test
    fun parsesIpv6LiteralWithoutPort() {
        val t = parseProxyTarget("CONNECT", "[::1]")!!
        assertEquals("::1", t.host)
        assertEquals(443, t.port)
    }

    @Test
    fun parsesIpv4WithPort() {
        val t = parseProxyTarget("CONNECT", "127.0.0.1:8080")!!
        assertEquals("127.0.0.1", t.host)
        assertEquals(8080, t.port)
    }

    @Test
    fun parsesDomainWithoutPort() {
        val t = parseProxyTarget("CONNECT", "example.com")!!
        assertEquals("example.com", t.host)
        assertEquals(443, t.port)
    }

    @Test
    fun doesNotClampOutOfRangePort() {
        // Goose's parser has no 1..65535 clamp: toIntOrNull() succeeds, so the
        // raw value is returned. Reference behavior, not a rejection.
        val t = parseProxyTarget("CONNECT", "example.com:99999")!!
        assertEquals("example.com", t.host)
        assertEquals(99999, t.port)
    }

    @Test
    fun rejectsUnbracketedIpv6() {
        assertNull(parseProxyTarget("CONNECT", "::1"))
    }
}
