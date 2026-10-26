package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.util.GlobalSettings
import com.masterdns.vpn.util.GlobalSettingsStore
import com.masterdns.vpn.util.SplitTunnelMode
import org.junit.Test

class GlobalSettingsStoreTest {
    @Test
    fun defaultSettings_hasIncludeSplitTunnelMode() {
        val settings = GlobalSettings()
        assertThat(settings.splitTunnelMode).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(settings.splitTunnelingEnabled).isFalse()
    }

    @Test
    fun parseSplitTunnelMode_parsesValidModes() {
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("INCLUDE")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("EXCLUDE")).isEqualTo(SplitTunnelMode.EXCLUDE)
    }

    @Test
    fun parseSplitTunnelMode_caseInsensitive() {
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("include")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("exclude")).isEqualTo(SplitTunnelMode.EXCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("Include")).isEqualTo(SplitTunnelMode.INCLUDE)
    }

    @Test
    fun parseSplitTunnelMode_fallbackOnInvalidOrNull() {
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("UNKNOWN")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode(null)).isEqualTo(SplitTunnelMode.INCLUDE)
    }

    @Test
    fun store_exposesSaveAsyncMethod() {
        val method = GlobalSettingsStore::class.java.methods.find { it.name == "saveAsync" }
        assertThat(method).isNotNull()
        assertThat(method!!.returnType).isEqualTo(kotlinx.coroutines.Job::class.java)
        assertThat(method.parameterTypes).asList().containsExactly(
            android.content.Context::class.java,
            GlobalSettings::class.java
        )
    }
}
