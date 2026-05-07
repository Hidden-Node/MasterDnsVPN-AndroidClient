package com.masterdns.vpn.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import java.io.File
import java.math.BigInteger
import java.net.InetAddress

data class ResolverStats(
    val fileName: String = "",
    val rawLines: Int = 0,
    val blankLines: Int = 0,
    val commentLines: Int = 0,
    val validIps: Int = 0,
    val duplicateIps: Int = 0,
    val invalidLines: Int = 0,
    val cidrRanges: Int = 0,
    val cidrExpandedIps: Int = 0,
    val skippedCidrs: Int = 0,
    val customPorts: Int = 0,
    val defaultPorts: Int = 0,
    val uniqueUsableIps: Int = 0,
    val cidrEnabled: Boolean = true
) {
    fun summary(): String {
        return "IPs: $uniqueUsableIps  Duplicates: $duplicateIps  Invalid: $invalidLines  CIDR: $cidrRanges -> $cidrExpandedIps IPs"
    }
}

object ResolverAnalyzer {
    private const val DEFAULT_PORT = 53
    private const val MAX_CIDR_HOSTS = 65536
    private val gson = Gson()

    fun analyzeText(
        text: String,
        fileName: String = "",
        cidrEnabled: Boolean = true
    ): ResolverStats {
        val seen = linkedSetOf<String>()
        var rawLines = 0
        var blankLines = 0
        var commentLines = 0
        var validIps = 0
        var duplicateIps = 0
        var invalidLines = 0
        var cidrRanges = 0
        var cidrExpandedIps = 0
        var skippedCidrs = 0
        var customPorts = 0
        var defaultPorts = 0

        text.lineSequence().forEach { raw ->
            rawLines++
            val line = raw.substringBefore("#").trim()
            when {
                raw.trim().isEmpty() -> {
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
                if (!cidrEnabled) {
                    skippedCidrs++
                    return@forEach
                }
                val hosts = expandCidr(entry.host)
                if (hosts == null) {
                    invalidLines++
                    return@forEach
                }
                if (hosts.isEmpty()) {
                    skippedCidrs++
                    return@forEach
                }
                hosts.forEach { ip ->
                    cidrExpandedIps++
                    if (!seen.add(ip)) duplicateIps++ else validIps++
                }
            } else {
                val ip = parseIp(entry.host)
                if (ip == null) {
                    invalidLines++
                    return@forEach
                }
                if (!seen.add(ip)) duplicateIps++ else validIps++
            }

            if (entry.port == DEFAULT_PORT && !entry.hasExplicitPort) {
                defaultPorts++
            } else {
                customPorts++
            }
        }

        return ResolverStats(
            fileName = fileName,
            rawLines = rawLines,
            blankLines = blankLines,
            commentLines = commentLines,
            validIps = validIps,
            duplicateIps = duplicateIps,
            invalidLines = invalidLines,
            cidrRanges = cidrRanges,
            cidrExpandedIps = cidrExpandedIps,
            skippedCidrs = skippedCidrs,
            customPorts = customPorts,
            defaultPorts = defaultPorts,
            uniqueUsableIps = seen.size,
            cidrEnabled = cidrEnabled
        )
    }

    fun statsToJson(stats: ResolverStats): String = gson.toJson(stats)

    fun statsFromJson(json: String): ResolverStats? {
        return runCatching { gson.fromJson(json, ResolverStats::class.java) }.getOrNull()
    }

    fun readDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex < 0 || !cursor.moveToFirst()) return@use null
            cursor.getString(nameIndex)
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun importUriToCache(context: Context, uri: Uri, cidrEnabled: Boolean): ImportedResolverFile? {
        val name = readDisplayName(context, uri) ?: "client_resolvers.txt"
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (text.isBlank()) return null

        val resolverDir = File(context.filesDir, "resolver_sources").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "client_resolvers.txt" }
        val outFile = File(resolverDir, "${System.currentTimeMillis()}_$safeName")
        outFile.writeText(text)

        val stats = analyzeText(text, name, cidrEnabled)
        return ImportedResolverFile(
            displayName = name,
            cachedPath = outFile.absolutePath,
            stats = stats
        )
    }

    fun analyzeCachedFile(path: String, fileName: String = "", cidrEnabled: Boolean = true): ResolverStats? {
        val file = File(path)
        if (!file.isFile) return null
        return analyzeText(file.readText(), fileName, cidrEnabled)
    }

    private data class Entry(
        val host: String,
        val port: Int,
        val hasExplicitPort: Boolean
    )

    private fun parseEntry(line: String): Entry? {
        val text = line.trim()
        if (text.isEmpty()) return null

        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end <= 0) return null
            val host = text.substring(1, end).trim()
            val remainder = text.substring(end + 1).trim()
            if (remainder.isEmpty()) return Entry(host, DEFAULT_PORT, false)
            if (!remainder.startsWith(":")) return null
            val port = remainder.drop(1).trim().toIntOrNull() ?: return null
            return if (port in 1..65535) Entry(host, port, true) else null
        }

        val slashBeforeColon = text.contains("/") && text.lastIndexOf(':') > text.lastIndexOf('/')
        val hasSingleColon = text.count { it == ':' } == 1
        val canHavePort = hasSingleColon || slashBeforeColon
        if (canHavePort) {
            val idx = text.lastIndexOf(':')
            val host = text.substring(0, idx).trim()
            val port = text.substring(idx + 1).trim().toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) {
                return Entry(host, port, true)
            }
        }

        return Entry(text, DEFAULT_PORT, false)
    }

    private fun parseIp(host: String): String? {
        val text = host.trim()
        val numericCandidate = when {
            "." in text && ":" !in text -> text.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
            ":" in text -> text.matches(Regex("[0-9A-Fa-f:.]+"))
            else -> false
        }
        if (!numericCandidate) return null
        return runCatching {
            val address = InetAddress.getByName(text)
            address.hostAddress
        }.getOrNull()
    }

    private fun expandCidr(value: String): List<String>? {
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
}

data class ImportedResolverFile(
    val displayName: String,
    val cachedPath: String,
    val stats: ResolverStats
)
