package com.masterdns.vpn.util

/**
 * Pure reducer that maps a (prev scan-state bundle, core log line) pair to a
 * new scan-state bundle. Extracted from VpnManager.parseScanLine in plan 017
 * to make the regex-based log-scraping contract testable in isolation.
 *
 * The mutation surface is reduced to a single input → output transformation;
 * StateFlow emission is the caller's concern (VpnManager wraps each call).
 */
internal data class ScanStateBundle(
    val scanStatus: VpnManager.ScanStatus = VpnManager.ScanStatus(),
    val activeResolvers: List<String> = emptyList(),
    val connectionWarning: String? = null
)

internal object ScanStateReducer {

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
    private val RESOLVER_ADDED_REGEX = Regex(
        "(?:✅ Accepted|🔄 DNS Resolver Reactivated).*?(\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+|\\[[a-fA-F0-9:]+\\]:\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val RESOLVER_REMOVED_REGEX = Regex(
        "DNS Resolver disabled.*?(\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+|\\[[a-fA-F0-9:]+\\]:\\d+)",
        RegexOption.IGNORE_CASE
    )
    private val SESSION_INIT_BACKOFF_REGEX = Regex(
        "Session init retry backoff:\\s*(.*)",
        RegexOption.IGNORE_CASE
    )

    internal fun reduce(prev: ScanStateBundle, line: String): ScanStateBundle {
        var scanStatus = prev.scanStatus
        var activeResolvers = prev.activeResolvers
        var connectionWarning = prev.connectionWarning

        RESOLVER_ADDED_REGEX.find(line)?.let { match ->
            val res = match.groupValues[1]
            activeResolvers = (activeResolvers.toSet() + res).toList()
        }

        RESOLVER_REMOVED_REGEX.find(line)?.let { match ->
            val res = match.groupValues[1]
            activeResolvers = (activeResolvers.toSet() - res).toList()
        }

        INDEXED_PROGRESS_REGEX.find(line)?.let { match ->
            val total = match.groupValues[2].toIntOrNull()
            if (total != null && total > 0) {
                scanStatus = scanStatus.copy(scanTotalFromCore = total)
            }
        }

        TOTAL_CANDIDATES_REGEX.find(line)?.let { match ->
            val total = match.groupValues[1].toIntOrNull()
            if (total != null && total > 0) {
                scanStatus = scanStatus.copy(scanTotalFromCore = total)
            }
        }

        // ponytail: pre-compute the 6 early-return matches, then a when cascade
        // mutates scanStatus for the first matching arm only, then a single
        // guarded return short-circuits the trailing blocks. Mirrors the
        // original parseScanLine early-returns (SCAN_TOTALS, ACTIVE_RESOLVERS,
        // TOTAL_ACTIVE, REMAINING, SYNCED_MTU, "Testing MTU sizes") so a line
        // matching multiple patterns only fires the first match.
        val scanMatch = SCAN_TOTALS_REGEX.find(line)
        val activeMatch = ACTIVE_RESOLVERS_REGEX.find(line)
        val totalActiveMatch = TOTAL_ACTIVE_REGEX.find(line)
        val remainingMatch = REMAINING_REGEX.find(line)
        val syncedMtuMatch = SYNCED_MTU_REGEX.find(line)
        val testingMtu = line.contains("Testing MTU sizes", ignoreCase = true)

        when {
            scanMatch != null -> {
                val resolver = scanMatch!!.groupValues[1]
                val valid = scanMatch!!.groupValues[2].toIntOrNull() ?: scanStatus.validCount
                val rejected = scanMatch!!.groupValues[3].toIntOrNull() ?: scanStatus.rejectedCount
                val decision = when {
                    line.contains("Accepted", ignoreCase = true) -> "Accepted"
                    line.contains("Rejected", ignoreCase = true) -> "Rejected"
                    else -> ""
                }
                scanStatus = scanStatus.copy(
                    scanning = true,
                    lastResolver = resolver,
                    lastDecision = decision,
                    validCount = valid,
                    rejectedCount = rejected
                )
            }
            activeMatch != null -> scanStatus = scanStatus.copy(
                activeResolvers = activeMatch!!.groupValues[1].toIntOrNull() ?: scanStatus.activeResolvers
            )
            totalActiveMatch != null -> scanStatus = scanStatus.copy(
                activeResolvers = totalActiveMatch!!.groupValues[1].toIntOrNull() ?: scanStatus.activeResolvers
            )
            remainingMatch != null -> scanStatus = scanStatus.copy(
                activeResolvers = remainingMatch!!.groupValues[1].toIntOrNull() ?: scanStatus.activeResolvers
            )
            syncedMtuMatch != null -> scanStatus = scanStatus.copy(
                syncedUploadMtu = syncedMtuMatch!!.groupValues[1].toIntOrNull() ?: 0,
                syncedDownloadMtu = syncedMtuMatch!!.groupValues[2].toIntOrNull() ?: 0
            )
            testingMtu -> scanStatus = scanStatus.copy(scanning = true)
        }

        if (scanMatch != null || activeMatch != null || totalActiveMatch != null ||
            remainingMatch != null || syncedMtuMatch != null || testingMtu
        ) {
            return ScanStateBundle(scanStatus, activeResolvers, connectionWarning)
        }

        if (line.contains("MTU Testing Completed", ignoreCase = true) ||
            line.contains("Session Initialized Successfully", ignoreCase = true)
        ) {
            scanStatus = scanStatus.copy(scanning = false)
        }

        SESSION_INIT_BACKOFF_REGEX.find(line)?.let { match ->
            val backoff = match.groupValues[1].trim()
            connectionWarning = "Session init retry backoff: $backoff"
        }

        return ScanStateBundle(scanStatus, activeResolvers, connectionWarning)
    }
}
