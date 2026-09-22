package com.masterdns.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSettingsPortRangeTest {
    @Test
    fun clampsBelow1025UpToFloor() {
        assertEquals(1025, GlobalSettingsStore.coerceSharingPort(80))
        assertEquals(1025, GlobalSettingsStore.coerceSharingPort(443))
        assertEquals(1025, GlobalSettingsStore.coerceSharingPort(1024))
    }

    @Test
    fun clampsAbove65535DownToCeiling() {
        assertEquals(65535, GlobalSettingsStore.coerceSharingPort(99999))
    }

    @Test
    fun preservesValidPort() {
        assertEquals(8090, GlobalSettingsStore.coerceSharingPort(8090))
        assertEquals(18000, GlobalSettingsStore.coerceSharingPort(18000))
    }
}
