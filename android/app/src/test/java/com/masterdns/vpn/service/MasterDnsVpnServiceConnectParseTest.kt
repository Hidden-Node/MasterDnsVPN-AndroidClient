package com.masterdns.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MasterDnsVpnServiceConnectParseTest {
    @Test
    fun parsesIpv6LiteralWithPort() {
        val (host, port) = MasterDnsVpnService.parseConnectTarget("[::1]:443")
        assertEquals("::1", host)
        assertEquals(443, port)
    }

    @Test
    fun parsesIpv6LiteralWithoutPort() {
        val (host, port) = MasterDnsVpnService.parseConnectTarget("[::1]")
        assertEquals("::1", host)
        assertEquals(80, port)
    }

    @Test
    fun parsesIpv4WithPort() {
        val (host, port) = MasterDnsVpnService.parseConnectTarget("127.0.0.1:8080")
        assertEquals("127.0.0.1", host)
        assertEquals(8080, port)
    }

    @Test
    fun parsesDomainWithoutPort() {
        val (host, port) = MasterDnsVpnService.parseConnectTarget("example.com")
        assertEquals("example.com", host)
        assertEquals(80, port)
    }

    @Test
    fun coercesOutOfRangePortToClampedValue() {
        val (host, port) = MasterDnsVpnService.parseConnectTarget("example.com:99999")
        assertEquals("example.com", host)
        assertEquals(65535, port)
    }
}
