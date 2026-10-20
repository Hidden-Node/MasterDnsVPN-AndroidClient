package com.masterdns.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.util.GlobalSettings
import com.masterdns.vpn.util.GlobalSettingsStore
import com.masterdns.vpn.util.parseImportedSettings
import com.masterdns.vpn.util.toExportJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The engine toggle defaults ON: fresh prefs must enable gVisor, a saved
// false must load back false through the DataStore, and the clipboard
// export/import round-trip must preserve the flag (booleans are never
// secrets, so export redaction does not apply).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlobalSettingsGvisorTest {

    @After
    fun resetEngineFlag() = runBlocking {
        GlobalSettingsStore.save(ApplicationProvider.getApplicationContext(), GlobalSettings())
    }

    @Test
    fun store_gvisorFlagDefaultsTrue_thenRoundTripsThroughSave() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = GlobalSettingsStore.load(context)
        assertThat(fresh.gvisorEngineEnabled).isTrue()

        GlobalSettingsStore.save(context, fresh.copy(gvisorEngineEnabled = false))
        assertThat(GlobalSettingsStore.load(context).gvisorEngineEnabled).isFalse()
    }

    @Test
    fun clipboard_roundTripPreservesGvisorEngineEnabled() {
        val original = GlobalSettings(gvisorEngineEnabled = false)
        val json = original.toExportJson()
        assertThat(json.contains("gvisorEngineEnabled")).isTrue()
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isEqualTo(original)
    }
}
