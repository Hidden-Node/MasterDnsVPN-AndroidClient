package com.masterdns.vpn.util

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import androidx.core.content.ContextCompat
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
import java.util.ArrayDeque

/**
 * Singleton bridge between Kotlin UI and Go core.
 * Manages VPN lifecycle and connection state.
 */
object VpnManager {

    enum class VpnState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }
    enum class LogSource {
        CORE, ANDROID
    }
    data class LogEntry(
        val line: String,
        val source: LogSource
    )
    data class LogCounters(
        val total: Long = 0,
        val errors: Long = 0,
        val warnings: Long = 0
    )

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()
    private val _logCounters = MutableStateFlow(LogCounters())
    val logCounters: StateFlow<LogCounters> = _logCounters.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _uploadSpeedBps = MutableStateFlow(0L)
    val uploadSpeedBps: StateFlow<Long> = _uploadSpeedBps.asStateFlow()
    private val _downloadSpeedBps = MutableStateFlow(0L)
    val downloadSpeedBps: StateFlow<Long> = _downloadSpeedBps.asStateFlow()
    private val _uploadTotalBytes = MutableStateFlow(0L)
    val uploadTotalBytes: StateFlow<Long> = _uploadTotalBytes.asStateFlow()
    private val _downloadTotalBytes = MutableStateFlow(0L)
    val downloadTotalBytes: StateFlow<Long> = _downloadTotalBytes.asStateFlow()
    private val _connectedDurationSeconds = MutableStateFlow(0L)
    val connectedDurationSeconds: StateFlow<Long> = _connectedDurationSeconds.asStateFlow()

    data class ScanStatus(
        val scanning: Boolean = false,
        val lastResolver: String = "",
        val lastDecision: String = "",
        val validCount: Int = 0,
        val rejectedCount: Int = 0,
        val scanTotalFromCore: Int = 0,
        val activeResolvers: Int = 0,
        val syncedUploadMtu: Int = 0,
        val syncedDownloadMtu: Int = 0
    )

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private const val MAX_LOG_LINES = 500
    private const val LOG_EMIT_DELAY_MS = 150L
    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var trafficMonitorJob: Job? = null
    private var logEmitJob: Job? = null
    private val logBufferLock = Any()
    private val logBuffer = ArrayDeque<LogEntry>(MAX_LOG_LINES)
    private var logBufferVersion = 0L

    private val INDEXED_PROGRESS_REGEX = Regex(
        "(?:scan|scanning|resolver|resolvers|mtu|accepted|rejected).{0,40}?(\\d+)\\s*/\\s*(\\d+)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TOTAL_CANDIDATES_REGEX = Regex(
        "(?:valid\\s+resolvers|resolvers\\s+for\\s+scan|scan\\s+pool|resolver\\s+pool|total\\s+resolvers).{0,20}?(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val SCAN_TOTALS_REGEX = Regex(
        "via\\s+([^\\s|]+)\\s*\\|.*totals:\\s*valid=(\\d+),\\s*rejected=(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val ACTIVE_RESOLVERS_REGEX = Regex(
        "Active Resolvers\\s*[:=]\\s*[^\\d-]*(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val TOTAL_ACTIVE_REGEX = Regex(
        "total\\s+active\\s*[:=]\\s*[^\\d-]*(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val REMAINING_REGEX = Regex(
        "remaining\\s*[:=]\\s*[^\\d-]*(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val SYNCED_MTU_REGEX = Regex(
        "Selected Synced Upload MTU:\\s*(\\d+)\\s*\\|\\s*Selected Synced Download MTU:\\s*(\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val TIMESTAMP_CANDIDATES = listOf(
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)(.*)$"),
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm:ss.SSS"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)(.*)$"),
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+UTC(.*)$"),
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s(.*)$"),
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2})(.*)$"),
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )
    )

    private data class TimestampCandidate(
        val regex: Regex,
        val inputPattern: String,
        val outputPattern: String
    )

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
        appendLogInternal(line, LogSource.ANDROID)
    }

    fun appendCoreLog(line: String) {
        appendLogInternal(line, LogSource.CORE)
    }

    private fun appendLogInternal(line: String, source: LogSource) {
        val normalizedLine = normalizeLogTimestampToLocal(line)
        val upper = normalizedLine.uppercase()
        val isError = upper.contains("[ERROR]") || upper.contains(" ERROR ")
        val isWarn = upper.contains("[WARN]") || upper.contains(" WARNING ") || upper.contains(" WARN ")
        _logCounters.value = _logCounters.value.copy(
            total = _logCounters.value.total + 1,
            errors = _logCounters.value.errors + if (isError) 1 else 0,
            warnings = _logCounters.value.warnings + if (isWarn) 1 else 0
        )
        synchronized(logBufferLock) {
            if (logBuffer.size == MAX_LOG_LINES) {
                logBuffer.removeFirst()
            }
            logBuffer.addLast(LogEntry(normalizedLine, source))
            logBufferVersion++
        }
        scheduleLogEmission()
        parseScanLine(normalizedLine)
    }

    fun clearLogs() {
        synchronized(logBufferLock) {
            logBuffer.clear()
            logBufferVersion++
        }
        logEmitJob?.cancel()
        logEmitJob = null
        _logEntries.value = emptyList()
        _logs.value = emptyList()
        _logCounters.value = LogCounters()
        _scanStatus.value = ScanStatus()
    }

    private fun scheduleLogEmission() {
        if (logEmitJob?.isActive == true) return
        logEmitJob = monitorScope.launch {
            while (isActive) {
                delay(LOG_EMIT_DELAY_MS)
                val emittedVersion = emitLogs()
                val currentVersion = synchronized(logBufferLock) {
                    logBufferVersion
                }
                if (currentVersion == emittedVersion) break
            }
        }
    }

    private fun emitLogs(): Long {
        val snapshot: List<LogEntry>
        val version: Long
        synchronized(logBufferLock) {
            snapshot = logBuffer.toList()
            version = logBufferVersion
        }
        _logEntries.value = snapshot
        _logs.value = snapshot.map { it.line }
        return version
    }

    fun startTrafficMonitor(context: Context) {
        val appContext = context.applicationContext
        val uid = appContext.applicationInfo.uid
        trafficMonitorJob?.cancel()
        trafficMonitorJob = monitorScope.launch {
            var prevTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var prevRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            var prevTime = System.currentTimeMillis()
            val startedAt = prevTime
            _uploadTotalBytes.value = 0L
            _downloadTotalBytes.value = 0L
            _connectedDurationSeconds.value = 0L
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val dt = (now - prevTime).coerceAtLeast(1L)
                val uploadDelta = (tx - prevTx).coerceAtLeast(0L)
                val downloadDelta = (rx - prevRx).coerceAtLeast(0L)
                _uploadSpeedBps.value = (uploadDelta * 1000L) / dt
                _downloadSpeedBps.value = (downloadDelta * 1000L) / dt
                _uploadTotalBytes.value = _uploadTotalBytes.value + uploadDelta
                _downloadTotalBytes.value = _downloadTotalBytes.value + downloadDelta
                _connectedDurationSeconds.value = ((now - startedAt) / 1000L).coerceAtLeast(0L)
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

        runCatching { ContextCompat.startForegroundService(context, intent) }
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
        INDEXED_PROGRESS_REGEX.find(line)?.let { match ->
            val total = match.groupValues[2].toIntOrNull()
            if (total != null && total > 0) {
                _scanStatus.value = _scanStatus.value.copy(scanTotalFromCore = total)
            }
        }

        TOTAL_CANDIDATES_REGEX.find(line)?.let { match ->
            val total = match.groupValues[1].toIntOrNull()
            if (total != null && total > 0) {
                _scanStatus.value = _scanStatus.value.copy(scanTotalFromCore = total)
            }
        }

        SCAN_TOTALS_REGEX.find(line)?.let { match ->
            val resolver = match.groupValues[1]
            val valid = match.groupValues[2].toIntOrNull() ?: _scanStatus.value.validCount
            val rejected = match.groupValues[3].toIntOrNull() ?: _scanStatus.value.rejectedCount
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

        ACTIVE_RESOLVERS_REGEX.find(line)?.let { match ->
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = match.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        TOTAL_ACTIVE_REGEX.find(line)?.let { match ->
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = match.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        REMAINING_REGEX.find(line)?.let { match ->
            _scanStatus.value = _scanStatus.value.copy(
                activeResolvers = match.groupValues[1].toIntOrNull() ?: _scanStatus.value.activeResolvers
            )
            return
        }

        SYNCED_MTU_REGEX.find(line)?.let { match ->
            _scanStatus.value = _scanStatus.value.copy(
                syncedUploadMtu = match.groupValues[1].toIntOrNull() ?: 0,
                syncedDownloadMtu = match.groupValues[2].toIntOrNull() ?: 0
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
        if (!startsWithTimestamp(line)) return line

        for (candidate in TIMESTAMP_CANDIDATES) {
            val match = candidate.regex.find(line) ?: continue
            val utcStamp = match.groupValues[1]
            val suffix = match.groupValues[2]
            val localStamp = convertUtcToLocal(utcStamp, candidate.inputPattern, candidate.outputPattern) ?: continue
            return "$localStamp$suffix"
        }
        return line
    }

    private fun startsWithTimestamp(line: String): Boolean {
        if (line.length < 19) return false
        if (!line[0].isDigit() || !line[1].isDigit() || !line[2].isDigit() || !line[3].isDigit()) return false
        return (line[4] == '-' && line[7] == '-') || (line[4] == '/' && line[7] == '/')
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
