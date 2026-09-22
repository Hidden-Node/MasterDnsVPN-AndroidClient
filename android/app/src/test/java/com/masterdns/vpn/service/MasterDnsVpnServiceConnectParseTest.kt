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
    fun rejectsOutOfRangePorts() {
        // Intentional divergence from Goose (whose parser has no clamp): a
        // port outside 1..65535 would be truncated to a different port by the
        // 2-byte SOCKS5 serialization, so the parser must reject it.
        assertNull(parseProxyTarget("CONNECT", "example.com:99999"))
    }

    @Test
    fun rejectsPortZero() {
        assertNull(parseProxyTarget("CONNECT", "example.com:0"))
    }

    @Test
    fun acceptsHighestValidPort() {
        val t = parseProxyTarget("CONNECT", "example.com:65535")!!
        assertEquals(65535, t.port)
    }

    @Test
    fun absoluteFormRejectsMalformedAndOutOfRangePorts() {
        assertNull(parseProxyTarget("GET", "http://example.com:99999/x"))
        assertNull(parseProxyTarget("GET", "http://example.com:99999999999/x"))
        val t = parseProxyTarget("GET", "http://example.com:8080/x")!!
        assertEquals(8080, t.port)
    }

    @Test
    fun rejectsUnbracketedIpv6() {
        assertNull(parseProxyTarget("CONNECT", "::1"))
    }
}
