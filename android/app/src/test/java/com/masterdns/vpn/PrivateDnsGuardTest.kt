package com.masterdns.vpn

import com.masterdns.vpn.util.VpnManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDnsGuardTest {
    @Test
    fun hostname_isStrict() {
        assertTrue(VpnManager.isPrivateDnsStrictBypass("hostname"))
    }

    @Test
    fun off_isNotStrict() {
        assertFalse(VpnManager.isPrivateDnsStrictBypass("off"))
    }

    @Test
    fun null_isNotStrict() {
        assertFalse(VpnManager.isPrivateDnsStrictBypass(null))
    }

    @Test
    fun opportunistic_isNotStrict() {
        assertFalse(VpnManager.isPrivateDnsStrictBypass("opportunistic"))
    }
}
