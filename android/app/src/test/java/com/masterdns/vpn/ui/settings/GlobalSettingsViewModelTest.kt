package com.masterdns.vpn.ui.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlobalSettingsViewModelTest {

    private fun viewModel(): GlobalSettingsViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return GlobalSettingsViewModel(app)
    }

    @Test
    fun `requestIcon populates icons map for own package`() = runBlocking {
        val vm = viewModel()
        val pkg = ApplicationProvider.getApplicationContext<Application>().packageName
        vm.requestIcon(pkg)
        val icons = withTimeout(5000) {
            vm.icons.first { it.containsKey(pkg) }
        }
        assertTrue(icons.containsKey(pkg))
    }

    @Test
    fun `localIp emits null or dotted IPv4`() = runBlocking {
        val vm = viewModel()
        val ip = withTimeout(5000) {
            // Initial null is a valid emission; wait for any settled value.
            vm.localIp.first()
            // Give the IO load a chance to finish, then read the latest.
            var latest: String? = vm.localIp.value
            val deadline = System.currentTimeMillis() + 4000
            while (latest == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100)
                latest = vm.localIp.value
            }
            latest
        }
        assertTrue(ip == null || ip.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")))
    }
}
