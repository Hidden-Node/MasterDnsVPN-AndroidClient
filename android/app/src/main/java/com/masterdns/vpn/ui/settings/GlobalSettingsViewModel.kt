package com.masterdns.vpn.ui.settings

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.masterdns.vpn.util.GlobalSettings
import com.masterdns.vpn.util.GlobalSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlobalSettingsViewModel(app: Application) : AndroidViewModel(app) {
    data class AppEntry(
        val packageName: String,
        val label: String,
        val firstInstallTime: Long
    )

    val settings: StateFlow<GlobalSettings> = GlobalSettingsStore.observe(app.applicationContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlobalSettings())

    private val _installedApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val installedApps: StateFlow<List<AppEntry>> = _installedApps
    private var installedAppsJob: Job? = null
    private var installedAppsLoaded = false

    private val iconCache = LruCache<String, Bitmap>(100)
    private val _icons = MutableStateFlow<Map<String, Bitmap?>>(emptyMap())
    val icons: StateFlow<Map<String, Bitmap?>> = _icons
    private val iconInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp

    init {
        refreshLocalIp()
    }

    fun refreshLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            _localIp.value = getSystemLocalIp()
        }
    }

    fun save(settings: GlobalSettings) {
        viewModelScope.launch {
            GlobalSettingsStore.save(getApplication(), settings)
        }
    }

    fun loadInstalledAppsIfNeeded() {
        if (installedAppsLoaded || installedAppsJob?.isActive == true) return
        installedAppsJob = viewModelScope.launch {
            val appList = withContext(Dispatchers.IO) {
                val packageManager = getApplication<Application>().packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launcherPackages = packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.MATCH_ALL
                )
                    .asSequence()
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()

                packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
                    .asSequence()
                    .filter { app -> launcherPackages.contains(app.packageName) }
                    .filter { app -> app.packageName != getApplication<Application>().packageName }
                    .map { app: ApplicationInfo ->
                        val label = runCatching {
                            packageManager.getApplicationLabel(app).toString()
                        }.getOrDefault(app.packageName)
                        val installTime = runCatching {
                            packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA).firstInstallTime
                        }.getOrDefault(0L)
                        AppEntry(app.packageName, label, installTime)
                    }
                    .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                    .toList()
            }
            _installedApps.value = appList
            installedAppsLoaded = true
        }
    }

    fun requestIcon(pkg: String) {
        if (iconCache.get(pkg) != null) return
        if (_icons.value.containsKey(pkg)) return
        if (!iconInFlight.add(pkg)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = runCatching {
                    getApplication<Application>().packageManager.getApplicationIcon(pkg).toBitmap(32, 32)
                }.getOrNull()
                if (bmp != null) {
                    iconCache.put(pkg, bmp)
                    _icons.value = _icons.value + (pkg to bmp)
                }
            } finally {
                iconInFlight.remove(pkg)
            }
        }
    }
}

internal fun getSystemLocalIp(): String? {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
