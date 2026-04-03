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
        val current = _logs.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LOG_LINES) {
            current.removeAt(0)
        }
        _logs.value = current
        parseScanLine(line)
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
}
