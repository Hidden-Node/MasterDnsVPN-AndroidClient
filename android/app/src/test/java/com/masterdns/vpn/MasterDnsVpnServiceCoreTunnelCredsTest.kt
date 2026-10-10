package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.service.MasterDnsVpnService
import org.junit.Test

// Blank rule (mirroring ConfigGenerator.cfg(), ConfigGenerator.kt:28-31):
// cfg() does advanced[key]?.trim() then returns fallback when the trimmed
// value is null or empty. So missing, "" and whitespace-only all fall back
// to "master_dns_vpn" (ConfigGenerator.kt:53-54); non-blank values are trimmed.
class MasterDnsVpnServiceCoreTunnelCredsTest {

    @Test
    fun `auth absent returns null null`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(emptyMap())
        assertThat(user).isNull()
        assertThat(pass).isNull()
    }

    @Test
    fun `auth false returns null null`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "false", "SOCKS5_USER" to "u", "SOCKS5_PASS" to "p")
        )
        assertThat(user).isNull()
        assertThat(pass).isNull()
    }

    @Test
    fun `auth true alone returns defaults`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(mapOf("SOCKS5_AUTH" to "true"))
        assertThat(user).isEqualTo("master_dns_vpn")
        assertThat(pass).isEqualTo("master_dns_vpn")
    }

    @Test
    fun `auth true with explicit values returns verbatim`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "true", "SOCKS5_USER" to "alice", "SOCKS5_PASS" to "s3cret")
        )
        assertThat(user).isEqualTo("alice")
        assertThat(pass).isEqualTo("s3cret")
    }

    @Test
    fun `blank user falls back while explicit pass kept`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "true", "SOCKS5_USER" to "   ", "SOCKS5_PASS" to "p")
        )
        assertThat(user).isEqualTo("master_dns_vpn")
        assertThat(pass).isEqualTo("p")
    }

    @Test
    fun `blank pass falls back while explicit user kept`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "true", "SOCKS5_USER" to "u", "SOCKS5_PASS" to "")
        )
        assertThat(user).isEqualTo("u")
        assertThat(pass).isEqualTo("master_dns_vpn")
    }

    @Test
    fun `blank both fall back to defaults`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "true", "SOCKS5_USER" to "", "SOCKS5_PASS" to "  ")
        )
        assertThat(user).isEqualTo("master_dns_vpn")
        assertThat(pass).isEqualTo("master_dns_vpn")
    }

    @Test
    fun `padded values are trimmed`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(
            mapOf("SOCKS5_AUTH" to "true", "SOCKS5_USER" to "  alice  ", "SOCKS5_PASS" to "\tp\n")
        )
        assertThat(user).isEqualTo("alice")
        assertThat(pass).isEqualTo("p")
    }

    @Test
    fun `auth check is case-insensitive`() {
        val (user, pass) = MasterDnsVpnService.coreTunnelCreds(mapOf("SOCKS5_AUTH" to "TRUE"))
        assertThat(user).isEqualTo("master_dns_vpn")
        assertThat(pass).isEqualTo("master_dns_vpn")
    }
}
