package com.masterdns.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyCredentialCleanupTest {
    @Test
    fun deletesLegacyCredentialFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Simulate a dev install that ran an ESP build.
        context.getSharedPreferences("masterdns_credentials", Context.MODE_PRIVATE)
            .edit().putString("internet_sharing_user", "u").commit()
        context.getSharedPreferences("masterdns_migration", Context.MODE_PRIVATE)
            .edit().putBoolean("credentials_migrated_v1", true).commit()

        cleanLegacyCredentialFiles(context)

        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        assertFalse(File(dir, "masterdns_credentials.xml").exists())
        assertFalse(File(dir, "masterdns_migration.xml").exists())
    }

    @Test
    fun noOpWhenFilesAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Must not throw when there is nothing to delete (the release path).
        cleanLegacyCredentialFiles(context)
    }
}
