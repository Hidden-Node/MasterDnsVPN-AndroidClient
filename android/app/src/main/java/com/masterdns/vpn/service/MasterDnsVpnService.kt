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
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.util.ConfigGenerator
import com.masterdns.vpn.util.GlobalSettingsStore
import com.masterdns.vpn.util.VpnManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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
        private const val SOCKS_STARTUP_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SOCKS_POLL_INTERVAL_MS = 500L

        // Companion packages that browsers and apps rely on for network access.
        // These must be included alongside any user-selected app to ensure traffic
        // actually flows through the tunnel (e.g. Chrome uses WebView & GMS).
        private val BROWSER_COMPANION_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.chrome",          // system Chrome on some OEMs
            "com.google.android.captiveportallogin"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goClientJob: Job? = null
    private var httpProxyJob: Job? = null
    private var logTailJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mtuExportTargetUri: String? = null
    private var mtuConfigDir: File? = null
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
                acquireWakeLock()

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

                // Establish VPN TUN interface
                // Determine the DNS server to advertise to Android:
                // - When LOCAL_DNS is enabled, Go core runs its own DNS listener on 127.0.0.1.
                //   We advertise the VPN interface address (10.0.0.2) so DNS queries come
                //   through the TUN and get forwarded by tun2socks → Go core local DNS.
                // - Otherwise fall back to 8.8.8.8 / 8.8.4.4 which travel through the
                //   tunnel via tun2socks → SOCKS5 → Go core → resolver servers.
                val vpnDnsServer = if (localDnsEnabled && !proxyMode) "10.0.0.2" else "8.8.8.8"

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(vpnDnsServer)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val splitEnabled = globalSettings.splitTunnelingEnabled &&
                        globalSettings.splitPackagesCsv.isNotBlank()
                    if (splitEnabled) {
                        // Build the final allowed-app set:
                        // 1. User-selected packages
                        // 2. Browser companion packages (WebView, GMS, etc.) so browsers
                        //    actually route through the tunnel
                        val userSelected = globalSettings.splitPackagesCsv
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        // Determine which companion packages are actually installed
                        val pm = packageManager
                        val installedCompanions = BROWSER_COMPANION_PACKAGES.filter { pkg ->
                            runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
                        }.toSet()

                        // Do NOT include our own packageName here.
                        // tun2socks reads from the TUN fd directly (not via the route table),
                        // and Go core's outbound UDP sockets must bypass the TUN to reach
                        // resolver servers directly — exactly like the non-split path which
                        // uses addDisallowedApplication(packageName).
                        val finalAllowed = userSelected + installedCompanions

                        VpnManager.appendLog(
                            "Split tunnel: ${userSelected.size} user apps, " +
                            "${installedCompanions.size} companion packages added"
                        )

                        finalAllowed.forEach { pkg ->
                            try {
                                builder.addAllowedApplication(pkg)
                            } catch (e: Exception) {
                                VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
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
                runCatching {
                    if (mobile.Mobile.isRunning()) {
                        mobile.Mobile.stopClient()
                    } else {
                        VpnManager.appendLog("Go core already stopped")
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Error stopping Go core", e)
                }

                // Close TUN interface
                runCatching { vpnInterface?.close() }
                vpnInterface = null

                // Cancel coroutines
                goClientJob?.cancel()
                httpProxyJob?.cancel()
                logTailJob?.cancel()

                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                VpnManager.appendLog("VPN disconnected")
                exportMtuResultsIfNeeded()
                releaseWakeLock()

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

                // Delay to allow UI to update before stopping service
                delay(500L)
                runCatching { stopSelf() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopVpn", e)
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
            .setContentTitle(getString(R.string.app_name))
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
        releaseWakeLock()
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
        delay(400L)

        if (!isLocalPortInUse(port)) {
            VpnManager.appendLog("SOCKS5 port $port released successfully")
            return
        }

        throw IllegalStateException("SOCKS5 port $port is already in use. Change LISTEN_PORT or close the app using it.")
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:runtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun startInternetSharing(socksPort: Int, httpPort: Int, username: String, password: String) {
        httpProxyJob?.cancel()
        httpProxyJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                VpnManager.appendLog("HTTP proxy ready on 0.0.0.0:$httpPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleHttpProxyClient(client, username, password)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP proxy error", e)
            }
        }
    }

private suspend fun handleHttpProxyClient(client: java.net.Socket, username: String, password: String) {
        try {
            val input = client.getInputStream().bufferedReader()
            val output = client.getOutputStream().bufferedWriter()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }

            val method = parts[0]
            val url = parts[1]

            if (method == "CONNECT") {
                val hostPort = url.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrElse(1) { "80" }.toIntOrNull() ?: 80

                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()

                val upstream = java.net.Socket(host, port)
                upstream.soTimeout = 30000

                val clientIn = client.getInputStream()
                val clientOut = client.getOutputStream()
                val upIn = upstream.getInputStream()
                val upOut = upstream.getOutputStream()
                val buffer = ByteArray(8192)

                launch(Dispatchers.IO) {
                    try {
                        while (!client.isClosed && !upstream.isClosed) {
                            val r = clientIn.read(buffer)
                            if (r <= 0) break
                            upOut.write(buffer, 0, r)
                            upOut.flush()
                        }
                    } catch (_: Exception) {}
                }

                launch(Dispatchers.IO) {
                    try {
                        while (!client.isClosed && !upstream.isClosed) {
                            val r = upIn.read(buffer)
                            if (r <= 0) break
                            clientOut.write(buffer, 0, r)
                            clientOut.flush()
                        }
                    } catch (_: Exception) {}
                }
            } else {
                output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                output.flush()
            }
        } catch (_: Exception) {}
        runCatching { client.close() }
}
}
                    } catch (_: Exception) {}
                    runCatching { upstream.close() }
                }

                val clientToUpstream = client.getInputStream()
                val upstreamToClient = upstream.getOutputStream()
                val buffer = ByteArray(8192)
                while (!client.isClosed && !upstream.isClosed) {
                    val read = clientToUpstream.read(buffer)
                    if (read <= 0) break
                    upstreamToClient.write(buffer, 0, read)
                    upstreamToClient.flush()
                }
            } else {
                output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                output.flush()
            }
        } catch (_: Exception) {}
        runCatching { client.close() }
    }
}
