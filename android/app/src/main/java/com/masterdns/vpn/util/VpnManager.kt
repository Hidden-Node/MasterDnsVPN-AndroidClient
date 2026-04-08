package com.masterdns.vpn.util

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.service.MasterDnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Singleton bridge between Kotlin UI and Go core.
 * Manages VPN lifecycle and connection state.
 */
object VpnManager {

    enum class VpnState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _uploadSpeedBps = MutableStateFlow(0L)
    val uploadSpeedBps: StateFlow<Long> = _uploadSpeedBps.asStateFlow()
    private val _downloadSpeedBps = MutableStateFlow(0L)
    val downloadSpeedBps: StateFlow<Long> = _downloadSpeedBps.asStateFlow()

    data class ScanStatus(
        val scanning: Boolean = false,
        val lastResolver: String = "",
        val lastDecision: String = "",
        val validCount: Int = 0,
        val rejectedCount: Int = 0,
        val activeResolvers: Int = 0,
        val syncedUploadMtu: Int = 0,
        val syncedDownloadMtu: Int = 0
    )

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var trafficMonitorJob: Job? = null

    private const val MAX_LOG_LINES = 500

    fun updateState(newState: VpnState) {
        _state.value = newState
        if (newState == VpnState.CONNECTED) {
            _scanStatus.value = _scanStatus.value.copy(
                lastResolver = "",
                lastDecision = "",
                scanning = false
            )
        }
    }

    fun setError(message: String) {
        _errorMessage.value = message
        _state.value = VpnState.ERROR
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun appendLog(line: String) {
        val normalizedLine = normalizeLogTimestampToLocal(line)
        val current = _logs.value.toMutableList()
        current.add(normalizedLine)
        if (current.size > MAX_LOG_LINES) {
            current.removeAt(0)
        }
        _logs.value = current
        parseScanLine(normalizedLine)
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _scanStatus.value = ScanStatus()
    }

    fun startTrafficMonitor(context: Context) {
        val appContext = context.applicationContext
        val uid = appContext.applicationInfo.uid
        trafficMonitorJob?.cancel()
        trafficMonitorJob = monitorScope.launch {
            var prevTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var prevRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            var prevTime = System.currentTimeMillis()
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val dt = (now - prevTime).coerceAtLeast(1L)
                _uploadSpeedBps.value = ((tx - prevTx).coerceAtLeast(0L) * 1000L) / dt
                _downloadSpeedBps.value = ((rx - prevRx).coerceAtLeast(0L) * 1000L) / dt
                prevTx = tx
                prevRx = rx
                prevTime = now
            }
        }
    }

    fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        _uploadSpeedBps.value = 0L
        _downloadSpeedBps.value = 0L
    }

    /**
     * Start the VPN service.
     */
    fun connect(context: Context, profile: ProfileEntity) {
        if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) return

        updateState(VpnState.CONNECTING)
        clearError()
        _scanStatus.value = ScanStatus(scanning = true)

        val intent = Intent(context, MasterDnsVpnService::class.java).apply {
            action = MasterDnsVpnService.ACTION_CONNECT
            putExtra(MasterDnsVpnService.EXTRA_PROFILE_ID, profile.id)
        }

        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to start VPN service: ${it.message}")
                appendLog("Failed to start VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

    /**
     * Stop the VPN service.
     */
    fun disconnect(context: Context) {
        if (_state.value == VpnState.DISCONNECTED) return

        updateState(VpnState.DISCONNECTING)
        stopTrafficMonitor()

        val intent = Intent(context, MasterDnsVpnService::class.java).apply {
            action = MasterDnsVpnService.ACTION_DISCONNECT
        }
        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to stop VPN service: ${it.message}")
                appendLog("Failed to stop VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

    private fun parseScanLine(line: String) {
        val scanMatch = Regex(
            "via\\s+([^\\s|]+)\\s*\\|.*totals:\\s*valid=(\\d+),\\s*rejected=(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (scanMatch != null) {
            val resolver = scanMatch.groupValues[1]
            val valid = scanMatch.groupValues[2].toIntOrNull() ?: _scanStatus.value.validCount
            val rejected = scanMatch.groupValues[3].toIntOrNull() ?: _scanStatus.value.rejectedCount
            val decision = when {
                line.contains("Accepted", ignoreCase = true) -> "Accepted"
                line.contains("Rejected", ignoreCase = true) -> "Rejected"
                else -> ""
            }
            _scanStatus.value = _scanStatus.value.copy(
                scanning = true,
                lastResolver = resolver,
                lastDecision = decision,
                validCount = valid,
                rejectedCount = rejected
            )
            return
        }

        val activeResolversMatch = Regex(
            "Active Resolvers\\s*[:=]\\s*[^\\d-]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (activeResolversMatch != null) {
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = activeResolversMatch.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        val totalActiveMatch = Regex(
            "total\\s+active\\s*[:=]\\s*[^\\d-]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (totalActiveMatch != null) {
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = totalActiveMatch.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        val remainingMatch = Regex(
            "remaining\\s*[:=]\\s*[^\\d-]*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (remainingMatch != null) {
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = remainingMatch.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        val syncedMatch = Regex(
            "Selected Synced Upload MTU:\\s*(\\d+)\\s*\\|\\s*Selected Synced Download MTU:\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(line)
        if (syncedMatch != null) {
            _scanStatus.value = _scanStatus.value.copy(
                syncedUploadMtu = syncedMatch.groupValues[1].toIntOrNull() ?: 0,
                syncedDownloadMtu = syncedMatch.groupValues[2].toIntOrNull() ?: 0
            )
            return
        }

        if (line.contains("Testing MTU sizes", ignoreCase = true)) {
            _scanStatus.value = _scanStatus.value.copy(scanning = true)
            return
        }

        if (line.contains("MTU Testing Completed", ignoreCase = true) ||
            line.contains("Session Initialized Successfully", ignoreCase = true)
        ) {
            _scanStatus.value = _scanStatus.value.copy(scanning = false)
        }
    }

    private fun normalizeLogTimestampToLocal(line: String): String {
        val candidates = listOf(
            // Example: 2026-04-05T10:20:30.123Z
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)(.*)$"),
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd HH:mm:ss.SSS"
            ),
            // Example: 2026-04-05T10:20:30Z
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)(.*)$"),
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026-04-05 10:20:30 UTC (check UTC variant first)
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+UTC(.*)$"),
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026-04-05 10:20:30 (from mtu_logging.go, no UTC)
            Triple(
                Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s(.*)$"),
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
            ),
            // Example: 2026/04/05 10:20:30 (from logger.go)
            Triple(
                Regex("^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2})(.*)$"),
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss"
            )
        )

        for ((regex, inputFormat, outputFormat) in candidates) {
            val match = regex.find(line) ?: continue
            val utcStamp = match.groupValues[1]
            val suffix = match.groupValues[2]
            val localStamp = convertUtcToLocal(utcStamp, inputFormat, outputFormat) ?: continue
            return "$localStamp$suffix"
        }
        return line
    }

    private fun convertUtcToLocal(
        utcValue: String,
        inputPattern: String,
        outputPattern: String
    ): String? {
        return try {
            val input = SimpleDateFormat(inputPattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed: Date = input.parse(utcValue) ?: return null
            val output = SimpleDateFormat(outputPattern, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            output.format(parsed)
        } catch (_: Exception) {
            null
        }
    }
}
