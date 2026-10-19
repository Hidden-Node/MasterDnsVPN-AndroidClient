package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.service.MasterDnsVpnService
import org.junit.Test

// Pins the never-both engine invariant in one place: exactly one TUN engine
// is selected per session, gVisor wins over FakeDNS, and the legacy
// tun2socks path is the fallback. Mirrors MasterDnsVpnServiceCoreTunnelCredsTest
// (companion-object seam, no Robolectric, no Service instance).
class MasterDnsVpnServiceSelectEngineTest {

    @Test
    fun fakeDnsOnly_selectsBridge() {
        assertThat(MasterDnsVpnService.selectEngine(false, true))
            .isEqualTo(MasterDnsVpnService.Companion.Engine.BRIDGE)
    }

    @Test
    fun gvisorWins_whenBothFlagsOn() {
        assertThat(MasterDnsVpnService.selectEngine(true, true))
            .isEqualTo(MasterDnsVpnService.Companion.Engine.GVISOR)
    }

    @Test
    fun gvisorAlone_selectsGvisor() {
        assertThat(MasterDnsVpnService.selectEngine(true, false))
            .isEqualTo(MasterDnsVpnService.Companion.Engine.GVISOR)
    }

    @Test
    fun bothFlagsOff_selectsTun2socks() {
        assertThat(MasterDnsVpnService.selectEngine(false, false))
            .isEqualTo(MasterDnsVpnService.Companion.Engine.TUN2SOCKS)
    }
}
