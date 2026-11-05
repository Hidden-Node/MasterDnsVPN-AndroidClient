package com.masterdns.vpn.util

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class TrafficMonitorTest {

    @Before
    fun setUp() {
        VpnManager.stopTrafficMonitor()
    }

    @After
    fun tearDown() {
        VpnManager.stopTrafficMonitor()
    }

    @Test
    fun `stopTrafficMonitor resets speeds to zero`() {
        VpnManager.stopTrafficMonitor()
        assertThat(VpnManager.uploadSpeedBps.value).isEqualTo(0L)
        assertThat(VpnManager.downloadSpeedBps.value).isEqualTo(0L)
    }

    @Test
    fun `traffic state flows initialize with non-negative values`() {
        assertThat(VpnManager.uploadSpeedBps.value).isAtLeast(0L)
        assertThat(VpnManager.downloadSpeedBps.value).isAtLeast(0L)
        assertThat(VpnManager.uploadTotalBytes.value).isAtLeast(0L)
        assertThat(VpnManager.downloadTotalBytes.value).isAtLeast(0L)
        assertThat(VpnManager.connectedDurationSeconds.value).isAtLeast(0L)
    }

    @Test
    fun `computeTrafficDelta calculates positive difference`() {
        val delta = VpnManager.computeTrafficDelta(current = 1500L, previous = 1000L)
        assertThat(delta).isEqualTo(500L)
    }

    @Test
    fun `computeTrafficDelta returns zero when previous is negative or uninitialized`() {
        val delta = VpnManager.computeTrafficDelta(current = 1500L, previous = -1L)
        assertThat(delta).isEqualTo(0L)
    }

    @Test
    fun `computeTrafficDelta returns zero when current is negative or unsupported`() {
        val delta = VpnManager.computeTrafficDelta(current = -1L, previous = 1000L)
        assertThat(delta).isEqualTo(0L)
    }

    @Test
    fun `computeTrafficDelta returns zero on counter reset or reboot`() {
        val delta = VpnManager.computeTrafficDelta(current = 500L, previous = 1000L)
        assertThat(delta).isEqualTo(0L)
    }
}
