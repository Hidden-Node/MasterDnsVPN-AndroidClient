package com.masterdns.vpn

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.util.GlobalSettings
import com.masterdns.vpn.util.SplitTunnelMode
import com.masterdns.vpn.util.parseImportedSettings
import com.masterdns.vpn.util.toExportJson
import org.junit.Test

class GlobalSettingsClipboardTest {
    @Test
    fun export_stripsSharingCredentials() {
        val settings = GlobalSettings(
            internetSharingUser = "secret-user",
            internetSharingPass = "secret-pass",
            internetSharingSocksPort = 8090
        )
        val json = settings.toExportJson()
        assertThat(json.contains("secret-user")).isFalse()
        assertThat(json.contains("secret-pass")).isFalse()
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.internetSharingSocksPort).isEqualTo(8090)
        assertThat(parsed.internetSharingUser).isEqualTo("")
        assertThat(parsed.internetSharingPass).isEqualTo("")
    }

    @Test
    fun roundTrip_preservesAllNonCredentialFields() {
        val original = GlobalSettings(
            connectionMode = "PROXY",
            splitTunnelingEnabled = true,
            splitTunnelMode = SplitTunnelMode.EXCLUDE,
            splitPackagesCsv = "com.example.a,com.example.b",
            internetSharingEnabled = true,
            internetSharingSocksPort = 8100,
            internetSharingHttpPort = 8101,
            internetSharingUser = "someone",
            internetSharingPass = "hunter2"
        )
        val json = original.toExportJson()
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isEqualTo(original.copy(internetSharingUser = "", internetSharingPass = ""))
    }

    @Test
    fun import_missingFields_fallsBackToDefaults() {
        assertThat(parseImportedSettings("{}")).isEqualTo(GlobalSettings())
    }

    @Test
    fun import_unknownEnumValue_fallsBackToInclude() {
        val parsed = parseImportedSettings("{\"splitTunnelMode\":\"SIDEWAYS\"}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.splitTunnelMode).isEqualTo(SplitTunnelMode.INCLUDE)
    }

    @Test
    fun import_extraUnknownKeys_ignored() {
        val parsed = parseImportedSettings("{\"connectionMode\":\"PROXY\",\"futureField\":123}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.connectionMode).isEqualTo("PROXY")
    }

    @Test
    fun import_wronglyTypedValues_fallBackToDefaults() {
        val parsed = parseImportedSettings("{\"internetSharingSocksPort\":\"not-a-number\"}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.internetSharingSocksPort).isEqualTo(8090)
    }

    @Test
    fun import_nonObjectJson_returnsNull() {
        assertThat(parseImportedSettings("[]")).isNull()
        assertThat(parseImportedSettings("\"just a string\"")).isNull()
        assertThat(parseImportedSettings("42")).isNull()
    }

    @Test
    fun import_malformedJson_returnsNull() {
        assertThat(parseImportedSettings("{not json")).isNull()
    }

    @Test
    fun import_blank_returnsNull() {
        assertThat(parseImportedSettings("")).isNull()
        assertThat(parseImportedSettings("   ")).isNull()
    }

    @Test
    fun import_lowercaseEnumValue_parsesCaseInsensitively() {
        val parsed = parseImportedSettings("{\"splitTunnelMode\":\"exclude\"}")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.splitTunnelMode).isEqualTo(SplitTunnelMode.EXCLUDE)
    }

    @Test
    fun roundTrip_preservesNotificationSpeedEnabledFalse() {
        val original = GlobalSettings(notificationSpeedEnabled = false)
        val json = original.toExportJson()
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.notificationSpeedEnabled).isFalse()
    }
}
