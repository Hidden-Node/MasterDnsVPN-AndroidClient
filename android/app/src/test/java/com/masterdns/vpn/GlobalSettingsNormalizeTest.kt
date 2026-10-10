package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.ui.settings.normalize
import com.masterdns.vpn.util.GlobalSettings
import org.junit.Test

class GlobalSettingsNormalizeTest {
    @Test
    fun socksOutOfRange_clampsTo8090() {
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 0)).internetSharingSocksPort).isEqualTo(8090)
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 70000)).internetSharingSocksPort).isEqualTo(8090)
    }

    @Test
    fun httpOutOfRange_clampsTo8091() {
        assertThat(normalize(GlobalSettings(internetSharingHttpPort = 0)).internetSharingHttpPort).isEqualTo(8091)
        assertThat(normalize(GlobalSettings(internetSharingHttpPort = 70000)).internetSharingHttpPort).isEqualTo(8091)
    }

    @Test
    fun inRangePorts_passThroughIncludingBoundaries() {
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 8090, internetSharingHttpPort = 8091)).internetSharingSocksPort).isEqualTo(8090)
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 8090, internetSharingHttpPort = 8091)).internetSharingHttpPort).isEqualTo(8091)
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 1025, internetSharingHttpPort = 1025)).internetSharingSocksPort).isEqualTo(1025)
        assertThat(normalize(GlobalSettings(internetSharingSocksPort = 65535, internetSharingHttpPort = 65535)).internetSharingHttpPort).isEqualTo(65535)
    }

    @Test
    fun lowercaseConnectionMode_uppercases() {
        assertThat(normalize(GlobalSettings(connectionMode = "vpn")).connectionMode).isEqualTo("VPN")
        assertThat(normalize(GlobalSettings(connectionMode = "proxy")).connectionMode).isEqualTo("PROXY")
    }

    @Test
    fun csvWithBlanksAndDups_dedups() {
        assertThat(normalize(GlobalSettings(splitPackagesCsv = "a, ,a,b")).splitPackagesCsv).isEqualTo("a,b")
    }
}
