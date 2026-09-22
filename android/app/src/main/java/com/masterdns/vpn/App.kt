package com.masterdns.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        cleanLegacyCredentialFiles(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_vpn_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "${getString(R.string.app_name)} connection status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "masterdns_vpn_service"
    }
}

// plan 036: plan 026 deleted SecureCredentialStore but left its files on
// devices that ran a dev ESP build (never in a release). Best-effort
// delete; failure is log noise, never a crash.
internal fun cleanLegacyCredentialFiles(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences("masterdns_credentials")
            context.deleteSharedPreferences("masterdns_migration")
        } else {
            // deleteSharedPreferences needs API 24; minSdk is 21.
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(dir, "masterdns_credentials.xml").delete()
            File(dir, "masterdns_migration.xml").delete()
        }
    }.onFailure {
        Log.i("App", "legacy credential cleanup skipped: ${it.message}")
    }
}
