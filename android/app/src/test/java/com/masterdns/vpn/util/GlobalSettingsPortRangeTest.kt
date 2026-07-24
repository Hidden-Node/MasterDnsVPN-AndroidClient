package com.masterdns.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSettingsPortRangeTest {
    @Test
    fun clampsBelow1025UpToFloor() {
        val settings = GlobalSettings(internetSharingSocksPort = 80, internetSharingHttpPort = 443)
        val socks = settings.internetSharingSocksPort.coerceIn(1025, 65535)
        val http = settings.internetSharingHttpPort.coerceIn(1025, 65535)
        assertEquals(1025, socks)
        assertEquals(1025, http)
    }

    @Test
    fun clampsAbove65535DownToCeiling() {
        val settings = GlobalSettings(internetSharingSocksPort = 99999)
        val socks = settings.internetSharingSocksPort.coerceIn(1025, 65535)
        assertEquals(65535, socks)
    }

    @Test
    fun preservesValidPort() {
        val settings = GlobalSettings(internetSharingSocksPort = 8090, internetSharingHttpPort = 18000)
        assertEquals(8090, settings.internetSharingSocksPort.coerceIn(1025, 65535))
        assertEquals(18000, settings.internetSharingHttpPort.coerceIn(1025, 65535))
    }
}
