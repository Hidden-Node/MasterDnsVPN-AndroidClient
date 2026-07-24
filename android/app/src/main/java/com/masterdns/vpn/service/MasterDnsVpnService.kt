package com.masterdns.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.masterdns.vpn.App
import com.masterdns.vpn.MainActivity
import com.masterdns.vpn.R
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.util.ConfigGenerator
import com.masterdns.vpn.util.GlobalSettingsStore
import com.masterdns.vpn.util.VpnManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class MasterDnsVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.masterdns.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.masterdns.vpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "MasterDnsVPN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SOCKS_PORT = 18000
        private const val SOCKS_STARTUP_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SOCKS_POLL_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TIMEOUT_MS = SOCKS_STARTUP_TIMEOUT_MS + 5 * 60 * 1000L

        // Base companions many apps need for network functionality.
        private val BASE_COMPANION_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.captiveportallogin"
        )

        // Browser-specific companions.
        private val BROWSER_COMPANION_PACKAGES = setOf(
            "com.android.chrome" // system Chrome on some OEMs
        )


    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var stopJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goClientJob: Job? = null
    private var httpProxyJob: Job? = null
    private var sharingSocksJob: Job? = null
    private var sharingSocksServer: java.net.ServerSocket? = null
    private var sharingHttpServer: java.net.ServerSocket? = null
    private var logTailJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mtuExportTargetUri: String? = null
    private var mtuConfigDir: File? = null
    @Volatile
    private var tunBridgeActive = false
    @Volatile
    private var isStopping = false
    @Volatile
    private var socksAuthWarningShown = false
    @Volatile
    private var sessionBusyWarningShown = false
    @Volatile
    private var activeLocalSocksPort: Int = DEFAULT_SOCKS_PORT

    @Inject
    lateinit var profileRepository: ProfileRepository

    /**
     * Close any ParcelFileDescriptor left from a previous session before we
     * attempt establish() again. The fd must be closed AFTER the Go core
     * stops (see MasterDnsVpnService.kt:457's comment) to avoid EBADF in
     * tun2socks goroutines that are mid-read on the fd.
     *
     * Plan 013: called at the top of startVpn() so connect-after-disconnect
     * racy reconnects don't leak an fd when ensureGoCoreStopped() reports
     * "Go core may still be running" and we proceed anyway.
     */
    private fun closeStaleVpnInterface() {
        val stale = vpnInterface ?: return
        VpnManager.appendLog("Closing stale TUN interface from previous session (fd=${stale.fd})")
        vpnInterface = null
        tunBridgeActive = false
        runCatching { stale.close() }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
    }

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))
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
                sessionBusyWarningShown = false

                acquireWakeLock()

                // Load profile from DB
                val profile = profileRepository.getProfileById(profileId)
                    ?: throw IllegalStateException("Profile not found")
                val socksPort = profile.listenPort.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT
                activeLocalSocksPort = socksPort
                val globalSettings = GlobalSettingsStore.load(this@MasterDnsVpnService)
                val proxyMode = globalSettings.connectionMode.equals("PROXY", ignoreCase = true)
                val protocolOverride = "SOCKS5"
                val listenIpOverride: String? = null

                VpnManager.appendLog("Loading profile: ${profile.name}")
                ensureGoCoreStopped()
                closeStaleVpnInterface()
                ensureSocksPortAvailable(socksPort)

                // Generate config files
                val configDir = File(filesDir, "config")
                configDir.mkdirs()

                val configFile = File(configDir, "client_config.toml")
                val resolversFile = File(configDir, "client_resolvers.txt")
                mtuExportTargetUri = null
                mtuConfigDir = null
                val advanced = parseAdvanced(profile.advancedJson)
                val saveMtuToFile = advanced["SAVE_MTU_SERVERS_TO_FILE"].equals("true", ignoreCase = true)
                var runtimeProfile = profile
                if (saveMtuToFile) {
                    val configuredPath = advanced["MTU_SERVERS_FILE_NAME"]
                        ?.trim()
                        ?.ifBlank { "masterdnsvpn_success_test_{time}.log" }
                        ?: "masterdnsvpn_success_test_{time}.log"
                    val exportUri = advanced["MTU_EXPORT_URI"]?.trim().orEmpty()
                    if (exportUri.isNotBlank()) {
                        val advancedMutable = advanced.toMutableMap()
                        configDir.mkdirs()
                        val targetPath = "masterdnsvpn_success_test_{time}.log"
                        advancedMutable["MTU_SERVERS_FILE_NAME"] = targetPath
                        runtimeProfile = profile.copy(advancedJson = Gson().toJson(advancedMutable))
                        mtuExportTargetUri = exportUri
                        mtuConfigDir = configDir
                        VpnManager.appendLog("MTU results will be saved to config directory")
                        VpnManager.appendLog("MTU export destination selected via file manager")
                    } else {
                        VpnManager.appendLog("MTU results target: $configuredPath")
                    }
                }

                // Detect LOCAL_DNS_ENABLED with a privileged port (<=1024) — requires root on Android.
                // Automatically fall back to port 5353 to avoid a bind permission error on non-rooted devices.
                val advancedForDns = parseAdvanced(runtimeProfile.advancedJson)
                val localDnsEnabled = advancedForDns["LOCAL_DNS_ENABLED"].equals("true", ignoreCase = true)
                val localDnsPort = advancedForDns["LOCAL_DNS_PORT"]?.toIntOrNull() ?: 53
                val safeDnsPort: Int? = if (!proxyMode && localDnsEnabled && localDnsPort <= 1024) {
                    VpnManager.appendLog(
                        "WARNING: LOCAL_DNS_PORT=$localDnsPort requires root on Android. " +
                            "Automatically using port 5353 instead."
                    )
                    5353
                } else null
                val effectiveLocalDnsPort = safeDnsPort ?: localDnsPort
                if (!proxyMode && localDnsEnabled) {
                    ensureLocalDnsPortAvailable(effectiveLocalDnsPort)
                }

                configFile.writeText(
                    ConfigGenerator.generateConfig(
                        profile = runtimeProfile,
                        listenPort = socksPort,
                        listenIpOverride = listenIpOverride,
                        protocolOverride = protocolOverride,
                        localDnsEnabledOverride = if (proxyMode) false else null,
                        localDnsPortOverride = if (proxyMode) null else safeDnsPort
                    )
                )
                if (runtimeProfile.resolvers.isNotBlank()) {
                    resolversFile.writeText(ConfigGenerator.generateResolvers(runtimeProfile))
                } else if (!resolversFile.exists() || resolversFile.readText().isBlank()) {
                    resolversFile.writeText(ConfigGenerator.generateResolvers(runtimeProfile))
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
                    } catch (_: CancellationException) {
                        // Normal shutdown — coroutine was cancelled during disconnect.
                        VpnManager.appendLog("Go core stopped (coroutine cancelled)")
                    } catch (e: Exception) {
                        // Only log real errors, not context cancellation from Go.
                        val msg = e.message ?: ""
                        if (!msg.contains("context canceled", ignoreCase = true)) {
                            Log.e(TAG, "Go core error", e)
                            VpnManager.appendLog("Go core error: $msg")
                            runCatching {
                                VpnManager.setError("Go core error: $msg")
                            }
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

                // Start Internet Sharing proxies if enabled
                if (globalSettings.internetSharingEnabled) {
                    val socksPort = globalSettings.internetSharingSocksPort
                    val httpPort = globalSettings.internetSharingHttpPort
                    val user = globalSettings.internetSharingUser
                    val pass = globalSettings.internetSharingPass
                    startInternetSharing(socksPort, httpPort, user, pass)
                }

                if (proxyMode) {
                    VpnManager.appendLog("Proxy mode active: skipping Android VpnService TUN setup")
                    VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                    VpnManager.startTrafficMonitor(this@MasterDnsVpnService)
                    val notification = buildNotification("Proxy mode active on port $socksPort")
                    val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                    return@launch
                }

                val vpnDnsServers = if (globalSettings.customDnsServers.isNotBlank()) {
                    globalSettings.customDnsServers
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .also { servers ->
                            VpnManager.appendLog("Using custom DNS servers: ${servers.joinToString()}")
                        }
                } else if (globalSettings.fakeDnsEnabled) {
                    listOf("172.19.0.2").also {
                        VpnManager.appendLog("Using TUN bridge DNS: 172.19.0.2")
                    }
                } else if (localDnsEnabled && !proxyMode) {
                    listOf("10.0.0.2").also {
                        VpnManager.appendLog("Using local DNS via TUN address: 10.0.0.2")
                    }
                } else {
                    listOf("8.8.8.8")
                }

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1400)
                    .addAddress(if (globalSettings.fakeDnsEnabled) "172.19.0.1" else "10.0.0.2", if (globalSettings.fakeDnsEnabled) 30 else 32)
                    .addRoute("0.0.0.0", 0)
                    .setBlocking(true) // tun2socks/gVisor requires blocking fd reads

                // ponytail: IPv6 NOT routed into the TUN across all Android versions.
                // FakeDNSProxy only has IPv4 DNS to advertise and upstream SOCKS5 UDP_ASSOCIATE
                // rejects non-53 traffic (fakedns_proxy.go:339-345, socks_manager.go:665), so
                // routing ::/0 black-holes IPv6 QUIC on every Android version, not just 16+.
                // Re-enable when FakeDNSProxy parses atyp==4 BND AND an IPv6 DNS is configured
                // AND upstream SOCKS5 carries non-DNS IPv6 UDP.

                vpnDnsServers.forEach { builder.addDnsServer(it) }
                if (globalSettings.fakeDnsEnabled) {
                    builder.addRoute("198.18.0.0", 16)
                    VpnManager.appendLog("Added fake DNS route: 198.18.0.0/16")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val splitEnabled = globalSettings.splitTunnelingEnabled &&
                        globalSettings.splitPackagesCsv.isNotBlank()
                    if (splitEnabled) {
                        val userSelected = globalSettings.splitPackagesCsv
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        if (globalSettings.splitTunnelMode == com.masterdns.vpn.util.SplitTunnelMode.INCLUDE) {
                            val pm = packageManager
                            val appCompanions = mutableSetOf<String>()

                            (BASE_COMPANION_PACKAGES + BROWSER_COMPANION_PACKAGES).forEach { pkg ->
                                if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) {
                                    appCompanions.add(pkg)
                                }
                            }

                            // Do NOT include our own packageName here.
                            val finalAllowed = userSelected + appCompanions

                            VpnManager.appendLog(
                                "Split tunnel (Include): ${userSelected.size} apps, " +
                                "${appCompanions.size} companions"
                            )

                            finalAllowed.forEach { pkg ->
                                try {
                                    builder.addAllowedApplication(pkg)
                                } catch (e: Exception) {
                                    VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
                                }
                            }
                        } else {
                            VpnManager.appendLog("Split tunnel (Exclude): Bypassing ${userSelected.size} apps")
                            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                            userSelected.forEach { pkg ->
                                try {
                                    builder.addDisallowedApplication(pkg)
                                } catch (e: Exception) {
                                    VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
                                }
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

                if (globalSettings.fakeDnsEnabled) {
                    VpnManager.appendLog("Starting DNS-aware TUN bridge...")
                    runCatching {
                        mobile.Mobile.startTunBridge(vpnInterface!!.fd.toLong(), 1400L, "127.0.0.1:$socksPort")
                    }.onSuccess {
                        tunBridgeActive = true
                        VpnManager.appendLog("DNS-aware TUN bridge started")
                    }.onFailure { e ->
                        VpnManager.appendLog("DNS-aware TUN bridge failed: ${e.message}")
                        throw IllegalStateException("TUN bridge start failed: ${e.message}", e)
                    }
                } else {
                    VpnManager.appendLog("Starting tun2socks bridge: TUN fd -> socks5://127.0.0.1:$socksPort")
                    runCatching {
                        mobile.Mobile.startTun(vpnInterface!!.fd.toLong(), "127.0.0.1:$socksPort")
                    }.onFailure { e ->
                        VpnManager.appendLog("tun2socks bridge failed: ${e.message}")
                        throw IllegalStateException("TUN start failed: ${e.message}", e)
                    }
                }

                // Register Network Change detection to bounce TUN bridge
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                networkCallback?.let { cm.unregisterNetworkCallback(it) }
                networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        val caps = cm.getNetworkCapabilities(network)
                        if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return
                        if (isStopping) return

                        VpnManager.appendLog("Underlying network changed, updating VPN underlying network...")
                        setUnderlyingNetworks(arrayOf(network))
                    }

                    override fun onLost(network: android.net.Network) {
                        if (isStopping) return
                        VpnManager.appendLog("Underlying network lost, resetting VPN underlying network...")
                        setUnderlyingNetworks(null)
                    }
                }
                try {
                    val request = android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(request, networkCallback!!)
                } catch (e: Exception) {
                    VpnManager.appendLog("Failed to register network callback: ${e.message}")
                }

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
                // The TUN bridge calls (lines 367/372) now re-throw on Go-side
                // error so this catch-all fires and stopVpn() runs — previously
                // a startTunBridge failure left the UI reporting CONNECTED
                // with no traffic flowing.
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

        // plan 014: retain the cleanup Job in stopJob so a process pause/resume
        // can't GC it mid-teardown; onDestroy() joins it before cancelling scope.
        if (stopJob?.isActive == true) return
        stopJob = serviceScope.launch {
            try {
                connectJob?.cancel()
                VpnManager.appendLog("VPN stop requested")
                tunBridgeActive = false

                // Stop everything in Go layer via a single stopClient() call.
                // Go's StopClient() internally handles StopTun/StopTunBridge
                // with idempotent guards and panic recovery, so this is safe.
                val stopThread = Thread {
                    VpnManager.appendLog("Stopping Go core...")
                    runCatching {
                        mobile.Mobile.stopClient()
                    }.onFailure { e ->
                        VpnManager.appendLog("Go core stop error: ${e.message}")
                    }
                }
                stopThread.start()
                stopThread.join(5000L)
                if (stopThread.isAlive) {
                    VpnManager.appendLog("Go core stop timed out, proceeding anyway")
                } else {
                    VpnManager.appendLog("Go core stopped successfully")
                }

                // Close TUN fd AFTER Go core stops to avoid EBADF in goroutines.
                VpnManager.appendLog("Closing TUN interface...")
                val iface = vpnInterface
                vpnInterface = null
                runCatching { iface?.close() }

                // Cancel coroutines
                VpnManager.appendLog("Stopping Android session jobs...")
                goClientJob?.cancel()
                httpProxyJob?.cancel()
                sharingSocksJob?.cancel()
                logTailJob?.cancel()

                networkCallback?.let {
                    runCatching {
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        cm.unregisterNetworkCallback(it)
                    }
                    networkCallback = null
                }

                runCatching { sharingSocksServer?.close() }
                sharingSocksServer = null
                runCatching { sharingHttpServer?.close() }
                sharingHttpServer = null
                VpnManager.appendLog("Android session jobs stopped")

                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                VpnManager.appendLog("VPN disconnected")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    exportMtuResultsIfNeeded()
                }
                releaseWakeLock()

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    VpnManager.appendLog("Foreground notification stopped")
                }.onFailure {
                    Log.w(TAG, "Failed to stop foreground cleanly", it)
                    VpnManager.appendLog("Foreground notification stop failed: ${it.message}")
                }

                // Delay to allow UI to update before stopping service
                delay(500L)
                runCatching { stopSelf() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopVpn", e)
                // Ensure state is updated even on error so UI doesn't stay stuck
                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                runCatching { stopSelf() }
            } finally {
                // plan 014: reset synchronously so connect() doesn't race stopSelf()
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // plan 014: let any in-flight stopVpn() finish before cancelling serviceScope.
        val inFlightStop = stopJob
        if (inFlightStop != null && inFlightStop.isActive) {
            kotlinx.coroutines.runBlocking { inFlightStop.join() }
        }

        // Normal path: stopVpn() already ran — Go layer guards make re-calls no-ops.
        // Force-kill path: stopVpn() was never called, so do full cleanup.
        if (!isStopping) {
            // stopClient() internally handles StopTun + StopTunBridge + cancel
            // with idempotent guards and panic recovery.
            try { mobile.Mobile.stopClient() } catch (_: Exception) {}
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = null
        }
        networkCallback?.let {
            runCatching {
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            networkCallback = null
        }
        releaseWakeLock()
        isStopping = false
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

    private fun parseAdvanced(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun exportMtuResultsIfNeeded() {
        val target = mtuExportTargetUri?.takeIf { it.isNotBlank() } ?: return
        val dir = mtuConfigDir ?: return
        val sourceFile = resolveMtuResultsSourceFile(dir)
        if (sourceFile == null) {
            VpnManager.appendLog("MTU export skipped: no results generated")
            return
        }
        runCatching {
            val uri = Uri.parse(target)
            runCatching {
                grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } ?: error("Cannot open selected destination")
        }.onSuccess {
            VpnManager.appendLog("MTU results exported to selected destination")
            VpnManager.appendLog("Exported file: ${sourceFile.absolutePath}")
        }.onFailure {
            VpnManager.appendLog("MTU export failed: ${it.message}")
        }
    }

    private fun resolveMtuResultsSourceFile(dir: File): File? {
        repeat(5) {
            val sourceFile = dir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.startsWith("masterdnsvpn_success_test") && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }
            if (sourceFile != null) {
                return sourceFile
            }
            Thread.sleep(200L)
        }
        return null
    }

    private suspend fun ensureSocksPortAvailable(port: Int) {
        if (!isLocalPortInUse(port)) return
        VpnManager.appendLog("SOCKS5 port $port is busy, attempting to free it...")

        runCatching {
            if (mobile.Mobile.isRunning()) {
                mobile.Mobile.stopClient()
            }
        }

        repeat(15) {
            delay(300L)
            if (!isLocalPortInUse(port)) {
                VpnManager.appendLog("SOCKS5 port $port released successfully")
                return
            }
            VpnManager.appendLog("SOCKS5 port $port still busy, retrying...")
        }

        throw IllegalStateException("SOCKS5 port $port is already in use. Change LISTEN_PORT or close the app using it.")
    }

    private suspend fun ensureLocalDnsPortAvailable(port: Int) {
        if (!isLocalUdpPortInUse(port)) return
        VpnManager.appendLog("Local DNS UDP port $port is busy, attempting to free it...")

        runCatching {
            if (mobile.Mobile.isRunning()) {
                mobile.Mobile.stopClient()
            }
        }

        repeat(15) {
            delay(300L)
            if (!isLocalUdpPortInUse(port)) {
                VpnManager.appendLog("Local DNS UDP port $port released successfully")
                return
            }
            VpnManager.appendLog("Local DNS UDP port $port still busy, retrying...")
        }

        throw IllegalStateException(
            "Local DNS UDP port $port is already in use. Change LOCAL_DNS_PORT or close the app using it."
        )
    }

    private suspend fun ensureGoCoreStopped() {
        if (!mobile.Mobile.isRunning()) return
        VpnManager.appendLog("Go core is still running, stopping it first...")
        runCatching { mobile.Mobile.stopClient() }

        repeat(20) {
            delay(200L)
            if (!mobile.Mobile.isRunning()) {
                VpnManager.appendLog("Go core stopped successfully")
                return
            }
        }
        // Plan 013: previously this logged and continued, leaking the prior
        // vpnInterface fd if the Go core hung. Now we throw so the catch-all
        // at startVpn()'s bottom sets ERROR state and runs stopVpn(). The
        // user sees a "Go core did not stop cleanly" error instead of a
        // silent CONNECTING-forever or cascading fd leaks.
        throw IllegalStateException("Go core did not stop cleanly within 4 seconds")
    }

    private fun isLocalPortInUse(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            false
        }.getOrElse { true }
    }

    private fun isLocalUdpPortInUse(port: Int): Boolean {
        return runCatching {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            false
        }.getOrElse { true }
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
                        val rawLine = raf.readLine() ?: break
                        // RandomAccessFile.readLine() decodes as ISO-8859-1; convert back to UTF-8
                        // so emojis/symbols from Go core logs are preserved in the UI logs tab.
                        val line = String(rawLine.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                        if (line.isNotBlank()) {
                            VpnManager.appendCoreLog(line)
                            maybeReportSocksAuthIssue(line)
                            maybeReportSessionBusyIssue(line)
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

    private fun maybeReportSessionBusyIssue(line: String) {
        if (sessionBusyWarningShown) return
        val normalized = line.uppercase()
        val isSessionBusy = normalized.contains("SESSION RESTART REQUESTED: SESSION BUSY RECEIVED")
        if (!isSessionBusy) return

        sessionBusyWarningShown = true
        val message = "Server is busy and cannot accept new sessions at the moment."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:runtime").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    private suspend fun startInternetSharing(socksPort: Int, httpPort: Int, username: String, password: String) {
        // Match GooseRelayVPN behavior: free stale listeners and retry when sharing ports are busy.
        if (isLocalPortInUse(socksPort) || isLocalPortInUse(httpPort)) {
            VpnManager.appendLog("Sharing ports in use, attempting to free...")
            if (mobile.Mobile.isRunning()) {
                runCatching { mobile.Mobile.stopClient() }
            }
            delay(500L)
        }

        sharingSocksJob?.cancel()
        sharingSocksServer?.close()
        sharingSocksServer = null
        sharingSocksJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(socksPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingSocksServer = server
                VpnManager.appendLog("Sharing SOCKS5 proxy ready on 0.0.0.0:$socksPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleSharingSocksClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sharing SOCKS5 proxy error", e)
                VpnManager.appendLog("Sharing SOCKS5 proxy error: ${e.message}")
            }
        }

        httpProxyJob?.cancel()
        sharingHttpServer?.close()
        sharingHttpServer = null
        httpProxyJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingHttpServer = server
                VpnManager.appendLog("HTTP proxy ready on 0.0.0.0:$httpPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleHttpProxyClient(client, socksPort, username, password)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP proxy error", e)
                VpnManager.appendLog("HTTP proxy error: ${e.message}")
            }
        }
    }

    private suspend fun handleSharingSocksClient(client: java.net.Socket) {
        var upstream: java.net.Socket? = null
        try {
            upstream = java.net.Socket("127.0.0.1", activeLocalSocksPort)
            upstream.soTimeout = 30000
            bridgeBidirectional(client, upstream)
        } catch (_: Exception) {
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun readLineUnbuffered(input: java.io.InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.isEmpty()) return null
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                bytes.add(next.toByte())
            }
        }
        return String(bytes.toByteArray(), Charsets.ISO_8859_1)
    }

    private suspend fun handleHttpProxyClient(client: java.net.Socket, upstreamSocksPort: Int, username: String, password: String) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream().bufferedWriter()

            val requestLine = readLineUnbuffered(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }

            val method = parts[0]
            val url = parts[1]

            var authHeader: String? = null
            while (true) {
                val line = readLineUnbuffered(input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                    authHeader = value
                }
            }

            val requiresAuth = username.isNotBlank() || password.isNotBlank()
            if (requiresAuth && !isValidBasicProxyAuth(authHeader, username, password)) {
                output.write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"MasterDnsVPN\"\r\n" +
                        "Connection: close\r\n\r\n"
                )
                output.flush()
                return
            }

            if (method == "CONNECT") {
                val hostPort = url.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrElse(1) { "80" }.toIntOrNull() ?: 80

                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()

                val upstream = createSocks5Tunnel(upstreamSocksPort, host, port)
                upstream.soTimeout = 30000

                bridgeBidirectional(client, upstream)
            } else {
                output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                output.flush()
            }
} catch (_: Exception) {}
        runCatching { client.close() }
    }

    private suspend fun bridgeBidirectional(client: java.net.Socket, upstream: java.net.Socket) = coroutineScope {
        val upToClient = launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = upstream.getInputStream()
                val output = client.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }

        val clientToUp = launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = client.getInputStream()
                val output = upstream.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { upstream.shutdownOutput() }
            }
        }

        joinAll(upToClient, clientToUp)
        runCatching { upstream.close() }
        runCatching { client.close() }
    }

    private fun createSocks5Tunnel(socksPort: Int, targetHost: String, targetPort: Int): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", socksPort)
        socket.soTimeout = 15000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting, 0, greeting.size)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 upstream greeting failed")
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            throw IllegalArgumentException("Target host is too long")
        }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val header = ByteArray(4)
        readFully(input, header, 0, header.size)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 connect failed with code ${header[1].toInt() and 0xFF}")
        }

        val addrLen = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> {
                val size = input.read()
                if (size < 0) throw IllegalStateException("SOCKS5 malformed bind address length")
                size
            }
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 unsupported bind address type")
        }
        val skip = ByteArray(addrLen + 2)
        readFully(input, skip, 0, skip.size)
        return socket
    }

    private fun isValidBasicProxyAuth(header: String?, username: String, password: String): Boolean {
        if (username.isBlank() && password.isBlank()) return true
        val value = header?.trim().orEmpty()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val encoded = value.substringAfter(" ", "").trim()
        if (encoded.isBlank()) return false
        val decoded = runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$username:$password"
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) throw IllegalStateException("Unexpected EOF while reading SOCKS5 response")
            total += read
        }
    }
}
