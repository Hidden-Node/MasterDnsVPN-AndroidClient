package com.masterdns.vpn.util

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class SubscriptionFetchResult(
    val suggestedName: String,
    val drafts: List<ProfileDraftData>,
    val rawCount: Int
)

@OptIn(ExperimentalEncodingApi::class)
object SubscriptionFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 10
    private const val USER_AGENT = "MasterDns/1.0"
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024 // 4 MB

    private val base64UrlSafeDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val base64DefaultDecoder = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    suspend fun fetch(subscriptionUrl: String): Result<SubscriptionFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedUrl = subscriptionUrl.trim()
            if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
                !trimmedUrl.startsWith("https://", ignoreCase = true)
            ) {
                throw IllegalArgumentException("Invalid subscription URL scheme: must start with http:// or https://")
            }

            var currentUrl = trimmedUrl
            var redirects = 0
            var activeConn: HttpURLConnection? = null

            while (redirects <= MAX_REDIRECTS) {
                val urlObj = URL(currentUrl)
                val conn = (urlObj.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/plain, application/octet-stream, */*")
                }

                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (redirects == MAX_REDIRECTS) {
                        throw IOException("Too many redirects: exceeded limit of $MAX_REDIRECTS")
                    }
                    if (location.isNullOrBlank()) {
                        throw IllegalArgumentException("HTTP redirect $status without Location header")
                    }
                    val resolved = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                        location
                    } else {
                        URL(urlObj, location).toString()
                    }
                    if (!resolved.startsWith("http://", ignoreCase = true) && !resolved.startsWith("https://", ignoreCase = true)) {
                        throw IllegalArgumentException("HTTP redirect to unsupported scheme: $resolved")
                    }
                    currentUrl = resolved
                    redirects++
                } else if (status in 200..299) {
                    activeConn = conn
                    break
                } else {
                    val errorBody = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                    conn.disconnect()
                    throw IllegalArgumentException("Server returned HTTP $status: $errorBody")
                }
            }

            val conn = activeConn ?: throw IOException("Failed to establish connection")
            val rawBody = try {
                val rawStream: InputStream = conn.inputStream
                val stream: InputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var totalBytes = 0
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if (totalBytes > MAX_BODY_BYTES) {
                        throw IOException("Subscription response exceeds maximum allowed size of 4 MB")
                    }
                    output.write(buffer, 0, bytesRead)
                }
                output.toString(Charsets.UTF_8.name())
            } finally {
                conn.disconnect()
            }

            val hostName = runCatching { URL(trimmedUrl).host?.takeIf { it.isNotBlank() } }.getOrNull() ?: "Subscription"
            parsePayload(rawBody, hostName)
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    fun parsePayload(rawBody: String, suggestedName: String = "Subscription"): SubscriptionFetchResult {
        val cleanBody = rawBody.removePrefix("\uFEFF").trim()
        if (cleanBody.isBlank()) {
            throw IllegalArgumentException("Subscription response is empty")
        }

        val decodedBody = decodeIfBase64(cleanBody).removePrefix("\uFEFF")
        val drafts = mutableListOf<ProfileDraftData>()
        decodedBody.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\uFEFF").trim()
            if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("//") && !line.startsWith(";")) {
                runCatching { ProfileUrlCodec.decode(line) }.getOrNull()?.let { drafts.add(it) }
            }
        }

        if (drafts.isEmpty()) {
            throw IllegalArgumentException("No valid profile links found in subscription response")
        }

        return SubscriptionFetchResult(
            suggestedName = suggestedName,
            drafts = drafts,
            rawCount = drafts.size
        )
    }

    private fun decodeIfBase64(input: String): String {
        if (input.contains("masterdns://", ignoreCase = true) || input.contains("stormdns://", ignoreCase = true) || input.contains("cottendns://", ignoreCase = true)) {
            return input
        }
        val cleanInput = input.filterNot { it.isWhitespace() }
        val decoded = runCatching {
            base64UrlSafeDecoder.decode(cleanInput)
        }.recoverCatching {
            base64DefaultDecoder.decode(cleanInput)
        }.getOrNull()

        return if (decoded != null) {
            val text = String(decoded, Charsets.UTF_8)
            if (text.contains("masterdns://", ignoreCase = true) || text.contains("stormdns://", ignoreCase = true) || text.contains("cottendns://", ignoreCase = true)) text else input
        } else {
            input
        }
    }
}
