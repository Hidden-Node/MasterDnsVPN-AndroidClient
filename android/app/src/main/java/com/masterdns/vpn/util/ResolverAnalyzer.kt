package com.masterdns.vpn.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.masterdns.vpn.data.local.ProfileEntity
import java.math.BigInteger
import java.net.InetAddress
import java.util.Locale

data class ResolverImportStats(
    val fileName: String = "",
    val rawLines: Int = 0,
    val blankLines: Int = 0,
    val commentLines: Int = 0,
    val validResolvers: Int = 0,
    val duplicateResolvers: Int = 0,
    val invalidLines: Int = 0,
    val cidrRanges: Int = 0,
    val cidrExpandedResolvers: Int = 0,
    val skippedCidrRanges: Int = 0,
    val customPorts: Int = 0,
    val defaultPorts: Int = 0,
    val truncated: Boolean = false
) {
    val uniqueResolvers: Int
        get() = validResolvers

    fun summary(): String {
        val capped = if (truncated) ", capped" else ""
        return "$uniqueResolvers usable, $duplicateResolvers duplicate, $invalidLines invalid$capped"
    }
}

data class ResolverImportResult(
    val normalizedText: String,
    val stats: ResolverImportStats
)

object ResolverAnalyzer {
    const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
    const val MAX_NORMALIZED_RESOLVERS = 65_536
    const val STATS_ADVANCED_KEY = "ANDROID_RESOLVER_IMPORT_STATS"
    const val SOURCE_ADVANCED_KEY = "ANDROID_RESOLVER_SOURCE"
    const val SOURCE_INLINE_IMPORT = "INLINE_IMPORT"

    private const val DEFAULT_PORT = 53
    private const val MAX_CIDR_HOSTS = 65_536
    private val gson = Gson()

    fun analyzeAndNormalize(text: String, fileName: String = ""): ResolverImportResult? {
        if (text.isBlank()) return null
        if (text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            return ResolverImportResult(
                normalizedText = "",
                stats = ResolverImportStats(fileName = fileName, truncated = true)
            )
        }

        val seen = linkedSetOf<String>()
        val normalized = ArrayList<String>()
        var rawLines = 0
        var blankLines = 0
        var commentLines = 0
        var duplicateResolvers = 0
        var invalidLines = 0
        var cidrRanges = 0
        var cidrExpandedResolvers = 0
        var skippedCidrRanges = 0
        var customPorts = 0
        var defaultPorts = 0
        var truncated = false

        fun addResolver(ip: String, entry: ResolverEntry) {
            if (normalized.size >= MAX_NORMALIZED_RESOLVERS) {
                truncated = true
                return
            }
            val key = "${ip.lowercase(Locale.US)}:${entry.port}"
            if (!seen.add(key)) {
                duplicateResolvers++
                return
            }
            normalized += formatRuntimeResolver(ip, entry.port)
            if (entry.hasExplicitPort) customPorts++ else defaultPorts++
        }

        text.lineSequence().forEach { raw ->
            if (truncated) return@forEach
            rawLines++
            val trimmedRaw = raw.trim()
            val line = raw.substringBefore("#").trim()
            when {
                trimmedRaw.isEmpty() -> {
                    blankLines++
                    return@forEach
                }
                line.isEmpty() -> {
                    commentLines++
                    return@forEach
                }
            }

            val entry = parseEntry(line)
            if (entry == null) {
                invalidLines++
                return@forEach
            }

            if (entry.host.contains("/")) {
                cidrRanges++
                val hosts = expandCidr(entry.host)
                when {
                    hosts == null -> invalidLines++
                    hosts.isEmpty() -> skippedCidrRanges++
                    else -> hosts.forEach { ip ->
                        cidrExpandedResolvers++
                        addResolver(ip, entry)
                    }
                }
                return@forEach
            }

            val ip = parseIp(entry.host)
            if (ip == null) {
                invalidLines++
                return@forEach
            }
            addResolver(ip, entry)
        }

        if (normalized.isEmpty()) return null

        val stats = ResolverImportStats(
            fileName = fileName,
            rawLines = rawLines,
            blankLines = blankLines,
            commentLines = commentLines,
            validResolvers = normalized.size,
            duplicateResolvers = duplicateResolvers,
            invalidLines = invalidLines,
            cidrRanges = cidrRanges,
            cidrExpandedResolvers = cidrExpandedResolvers,
            skippedCidrRanges = skippedCidrRanges,
            customPorts = customPorts,
            defaultPorts = defaultPorts,
            truncated = truncated
        )
        return ResolverImportResult(
            normalizedText = normalized.joinToString(separator = "\n", postfix = "\n"),
            stats = stats
        )
    }

    fun withImportStats(profile: ProfileEntity, stats: ResolverImportStats): ProfileEntity {
        val advanced = parseAdvanced(profile.advancedJson).toMutableMap()
        advanced[SOURCE_ADVANCED_KEY] = SOURCE_INLINE_IMPORT
        advanced[STATS_ADVANCED_KEY] = gson.toJson(stats)
        return profile.copy(advancedJson = gson.toJson(advanced))
    }

    fun statsFromProfile(profile: ProfileEntity): ResolverImportStats? {
        val json = parseAdvanced(profile.advancedJson)[STATS_ADVANCED_KEY] ?: return null
        return runCatching { gson.fromJson(json, ResolverImportStats::class.java) }.getOrNull()
    }

    internal data class ResolverEntry(
        val host: String,
        val port: Int,
        val hasExplicitPort: Boolean
    )

    internal fun parseEntry(line: String): ResolverEntry? {
        val text = line.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end <= 0) return null
            val host = text.substring(1, end).trim()
            val remainder = text.substring(end + 1).trim()
            if (remainder.isEmpty()) return ResolverEntry(host, DEFAULT_PORT, false)
            if (!remainder.startsWith(":")) return null
            val port = remainder.drop(1).trim().toIntOrNull() ?: return null
            return if (port in 1..65535) ResolverEntry(host, port, true) else null
        }

        val colonCount = text.count { it == ':' }
        val canHavePort = colonCount == 1 || ("/" in text && text.lastIndexOf(':') > text.lastIndexOf('/'))
        if (canHavePort) {
            val idx = text.lastIndexOf(':')
            val host = text.substring(0, idx).trim()
            val port = text.substring(idx + 1).trim().toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) {
                return ResolverEntry(host, port, true)
            }
        }
        return ResolverEntry(text, DEFAULT_PORT, false)
    }

    internal fun parseIp(host: String): String? {
        val text = host.trim()
        val numericCandidate = when {
            "." in text && ":" !in text -> text.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
            ":" in text -> text.matches(Regex("[0-9A-Fa-f:.]+"))
            else -> false
        }
        if (!numericCandidate) return null
        return runCatching { InetAddress.getByName(text).hostAddress }.getOrNull()
    }

    internal fun expandCidr(value: String): List<String>? {
        val parts = value.split("/")
        if (parts.size != 2) return null
        val normalizedBase = parseIp(parts[0].trim()) ?: return null
        val base = runCatching { InetAddress.getByName(normalizedBase) }.getOrNull() ?: return null
        val bytes = base.address
        val totalBits = bytes.size * 8
        val prefixBits = parts[1].trim().toIntOrNull() ?: return null
        if (prefixBits !in 0..totalBits) return null
        val hostBits = totalBits - prefixBits
        if (hostBits > 16) return emptyList()

        val total = BigInteger.ONE.shiftLeft(hostBits)
        val usableStart = if (bytes.size == 4 && prefixBits < 31) BigInteger.ONE else BigInteger.ZERO
        val usableEndExclusive = if (bytes.size == 4 && prefixBits < 31) total.subtract(BigInteger.ONE) else total
        val usableCount = usableEndExclusive.subtract(usableStart)
        if (usableCount <= BigInteger.ZERO || usableCount > BigInteger.valueOf(MAX_CIDR_HOSTS.toLong())) {
            return emptyList()
        }

        val baseInt = BigInteger(1, bytes)
        val mask = BigInteger.ONE.shiftLeft(totalBits).subtract(BigInteger.ONE).xor(
            BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
        )
        val network = baseInt.and(mask)
        val result = ArrayList<String>(usableCount.toInt())
        var offset = usableStart
        while (offset < usableEndExclusive) {
            result += addressFromBigInteger(network.add(offset), bytes.size).hostAddress
            offset = offset.add(BigInteger.ONE)
        }
        return result
    }

    private fun addressFromBigInteger(value: BigInteger, byteCount: Int): InetAddress {
        val raw = value.toByteArray()
        val out = ByteArray(byteCount)
        val copyStart = maxOf(0, raw.size - byteCount)
        val copyLength = raw.size - copyStart
        System.arraycopy(raw, copyStart, out, byteCount - copyLength, copyLength)
        return InetAddress.getByAddress(out)
    }

    private fun formatRuntimeResolver(ip: String, port: Int): String {
        if (port == DEFAULT_PORT) return ip
        return if (":" in ip) "[$ip]:$port" else "$ip:$port"
    }

    private fun parseAdvanced(json: String): Map<String, String> {
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
