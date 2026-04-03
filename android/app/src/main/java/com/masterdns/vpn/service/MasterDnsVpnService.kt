package com.masterdns.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.masterdns.vpn.App
import com.masterdns.vpn.MainActivity
import com.masterdns.vpn.R
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.util.ConfigGenerator
import com.masterdns.vpn.util.GlobalSettingsStore
import com.masterdns.vpn.util.VpnManager
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

class MasterDnsVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.masterdns.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.masterdns.vpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "MasterDnsVPN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SOCKS_PORT = 18000
        private const val SOCKS_STARTUP_TIMEOUT_MS = 8 * 60 * 1000L
        private const val SOCKS_POLL_INTERVAL_MS = 500L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goClientJob: Job? = null
    private var logTailJob: Job? = null
    @Volatile
    private var isStopping = false
    @Volatile
    private var socksAuthWarningShown = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                if (profileId > 0) {
                    startVpn(profileId)
                }
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(profileId: Long) {
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            try {
                VpnManager.updateState(VpnManager.VpnState.CONNECTING)
                VpnManager.clearError()
                socksAuthWarningShown = false

                // Show foreground notification
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))

                // Load profile from DB
                val db = AppDatabase.getInstance(this@MasterDnsVpnService)
                val profile = db.profileDao().getProfileById(profileId)
                    ?: throw IllegalStateException("Profile not found")
                val socksPort = profile.listenPort.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT
                val globalSettings = GlobalSettingsStore.load(this@MasterDnsVpnService)
                val proxyMode = globalSettings.connectionMode.equals("PROXY", ignoreCase = true)
                val protocolOverride = "SOCKS5"
                val listenIpOverride: String? = null

                VpnManager.appendLog("Loading profile: ${profile.name}")

                // Generate config files
                val configDir = File(filesDir, "config")
                configDir.mkdirs()

                val configFile = File(configDir, "client_config.toml")
                val resolversFile = File(configDir, "client_resolvers.txt")

                configFile.writeText(
                    ConfigGenerator.generateConfig(
                        profile = profile,
                        listenPort = socksPort,
                        listenIpOverride = listenIpOverride,
                        protocolOverride = protocolOverride
                    )
                )
                if (profile.resolvers.isNotBlank()) {
                    resolversFile.writeText(ConfigGenerator.generateResolvers(profile))
                } else if (!resolversFile.exists() || resolversFile.readText().isBlank()) {
                    resolversFile.writeText(ConfigGenerator.generateResolvers(profile))
                } else {
                    VpnManager.appendLog("Using existing client_resolvers.txt from app storage")
                }

                VpnManager.appendLog("Config written to: ${configFile.absolutePath}")
                VpnManager.appendLog("Starting Go core...")

                // Start Go client in background thread
                val logFile = File(cacheDir, "vpn.log")
                if (!logFile.exists()) {
                    logFile.createNewFile()
                } else {
                    logFile.writeText("")
                }

                logTailJob?.cancel()
                logTailJob = launch(Dispatchers.IO) {
                    tailLogFile(logFile)
                }

                goClientJob = launch(Dispatchers.IO) {
                    try {
                        // Call the Go mobile wrapper
                        mobile.Mobile.startClient(
                            configFile.absolutePath,
                            logFile.absolutePath
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Go core error", e)
                        VpnManager.appendLog("Go core error: ${e.message}")
                        withContext(Dispatchers.Main) {
                            VpnManager.setError("Go core error: ${e.message}")
                        }
                    }
                }

                // Wait until SOCKS5 is actually listening. MTU test/session init may take a few minutes.
                waitForSocksProxyReady(
                    host = "127.0.0.1",
                    port = socksPort,
                    timeoutMs = SOCKS_STARTUP_TIMEOUT_MS
                )
                VpnManager.appendLog("SOCKS5 proxy is ready on 127.0.0.1:$socksPort")

                if (proxyMode) {
                    VpnManager.appendLog("Proxy mode active: skipping Android VpnService TUN setup")
                    VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                    VpnManager.startTrafficMonitor(this@MasterDnsVpnService)
                    val notification = buildNotification("Proxy mode active on port $socksPort")
                    val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                    return@launch
                }

                // Establish VPN TUN interface
                val builder = Builder()
                    .setSession("MasterDnsVPN")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("8.8.4.4")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val splitEnabled = globalSettings.splitTunnelingEnabled &&
                        globalSettings.splitPackagesCsv.isNotBlank()
                    if (splitEnabled) {
                        globalSettings.splitPackagesCsv
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && it != packageName }
                            .forEach { pkg ->
                                try {
                                    builder.addAllowedApplication(pkg)
                                } catch (e: Exception) {
                                    VpnManager.appendLog("Split tunnel ignore package '$pkg': ${e.message}")
                                }
                            }
                    } else {
                        // Exclude app itself by default to avoid self-loop traffic.
                        builder.addDisallowedApplication(packageName)
                    }
                }

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("VPN interface could not be established. Check VPN permission.")

                VpnManager.appendLog("TUN interface established (fd=${vpnInterface!!.fd})")

                // Start Go-based tun2socks bridge
                VpnManager.appendLog("Starting tun2socks bridge: TUN fd -> socks5://127.0.0.1:$socksPort")
                mobile.Mobile.startTun(vpnInterface!!.fd.toLong(), "127.0.0.1:$socksPort")

                // Update state
                VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                VpnManager.startTrafficMonitor(this@MasterDnsVpnService)
                VpnManager.appendLog("VPN connected successfully!")

                // Update notification
                val notification = buildNotification(getString(R.string.notification_connected))
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)

            } catch (e: CancellationException) {
                VpnManager.appendLog("Connection canceled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                VpnManager.appendLog("Error: ${e.message}")
                VpnManager.setError(e.message ?: "Unknown error")
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true
        serviceScope.launch {
            try {
                connectJob?.cancel()
                VpnManager.appendLog("Stopping VPN...")

                // Stop Go client and Tun bridge
                try {
                    if (mobile.Mobile.isRunning()) {
                        mobile.Mobile.stopClient()
                    } else {
                        VpnManager.appendLog("Go core already stopped")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Go core", e)
                }

                // Close TUN interface
                vpnInterface?.close()
                vpnInterface = null

                // Cancel coroutines
                goClientJob?.cancel()
                logTailJob?.cancel()

                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                VpnManager.appendLog("VPN disconnected")

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to stop foreground cleanly", it)
                }
                runCatching { stopSelf() }
            } finally {
                isStopping = false
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("MasterDnsVPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        if (!isStopping) {
            try {
                if (mobile.Mobile.isRunning()) {
                    mobile.Mobile.stopClient()
                }
            } catch (_: Exception) {
            }
            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private suspend fun waitForSocksProxyReady(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()

            val clientJob = goClientJob
            if (clientJob != null && clientJob.isCompleted && !mobile.Mobile.isRunning()) {
                throw IllegalStateException("Go core stopped before SOCKS5 became ready")
            }

            if (canConnect(host, port)) {
                return
            }
            delay(SOCKS_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Timed out waiting for SOCKS5 listener on $host:$port")
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tailLogFile(logFile: File) {
        // Continuously mirrors Go log file into Compose logs so Android UI shows real MTU/session progress.
        RandomAccessFile(logFile, "r").use { raf ->
            var pointer = 0L
            while (coroutineContext.isActive) {
                val length = raf.length()
                if (length < pointer) {
                    pointer = 0L
                }

                if (length > pointer) {
                    raf.seek(pointer)
                    while (true) {
                        val line = raf.readLine() ?: break
                        if (line.isNotBlank()) {
                            VpnManager.appendLog(line)
                            maybeReportSocksAuthIssue(line)
                        }
                    }
                    pointer = raf.filePointer
                }

                delay(250L)
            }
        }
    }

    private fun maybeReportSocksAuthIssue(line: String) {
        if (socksAuthWarningShown) return
        val normalized = line.uppercase()
        val authRelatedFailure = normalized.contains("SOCKS5_AUTH_FAILED") ||
            (normalized.contains("SOCKS5") &&
                normalized.contains("AUTH") &&
                normalized.contains("FAIL"))
        if (!authRelatedFailure) return

        socksAuthWarningShown = true
        val message = "SOCKS5 authentication failed. Check SOCKS5_AUTH, SOCKS5_USER, and SOCKS5_PASS in profile settings."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }
}
