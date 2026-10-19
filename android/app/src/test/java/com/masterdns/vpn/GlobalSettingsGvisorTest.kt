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

// The engine toggle defaults OFF: fresh prefs must not enable gVisor, a saved
// true must load back true through the DataStore, and the clipboard
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
    fun store_gvisorFlagDefaultsFalse_thenRoundTripsThroughSave() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fresh = GlobalSettingsStore.load(context)
        assertThat(fresh.gvisorEngineEnabled).isFalse()

        GlobalSettingsStore.save(context, fresh.copy(gvisorEngineEnabled = true))
        assertThat(GlobalSettingsStore.load(context).gvisorEngineEnabled).isTrue()
    }

    @Test
    fun clipboard_roundTripPreservesGvisorEngineEnabled() {
        val original = GlobalSettings(gvisorEngineEnabled = true)
        val json = original.toExportJson()
        assertThat(json.contains("gvisorEngineEnabled")).isTrue()
        val parsed = parseImportedSettings(json)
        assertThat(parsed).isEqualTo(original)
    }
}
