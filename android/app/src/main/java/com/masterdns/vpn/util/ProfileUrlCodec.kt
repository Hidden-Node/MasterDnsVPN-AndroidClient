package com.masterdns.vpn.util

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.masterdns.vpn.data.local.ProfileEntity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ProfileDraftData(
    val name: String,
    val domains: List<String>,
    val encryptionKey: String,
    val encryptionMethod: Int
) {
    fun toProfileEntity(defaultResolvers: String = "8.8.8.8"): ProfileEntity = ProfileEntity(
        name = name,
        domains = Gson().toJson(domains),
        encryptionKey = encryptionKey,
        encryptionMethod = encryptionMethod,
        resolvers = defaultResolvers
    )
}

@OptIn(ExperimentalEncodingApi::class)
object ProfileUrlCodec {
    const val SCHEME_MASTERDNS = "masterdns"
    const val SCHEME_STORMDNS = "stormdns"
    const val SCHEME_COTTENDNS = "cottendns"
    const val SCHEMA_MASTERDNS = "masterdns.profile"
    const val SCHEMA_STORMDNS = "stormdns.profile"
    private val SUPPORTED_SCHEMAS = setOf(SCHEMA_MASTERDNS, SCHEMA_STORMDNS)
    private val SUPPORTED_SCHEMES = setOf(SCHEME_MASTERDNS, SCHEME_STORMDNS, SCHEME_COTTENDNS)
    const val VERSION = 1

    private val gson = Gson()

    private val base64UrlSafeDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val base64DefaultDecoder = Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(profile: ProfileEntity): String {
        val rawDomains = parseDomainsJson(profile.domains)
        val validDomains = rawDomains.map { it.trim().trimEnd('.') }.filter { it.isNotBlank() }.distinct()
        require(validDomains.isNotEmpty()) { "Profile domain is required to share" }
        require(profile.encryptionKey.isNotBlank()) { "Profile encryption key is required to share" }

        val primaryDomain = validDomains.first()

        val serverObj = JsonObject().apply {
            addProperty("domain", primaryDomain)
            add("domains", JsonArray().apply { validDomains.forEach { add(it) } })
            addProperty("encryption_key", profile.encryptionKey.trim())
            addProperty("encryption_method", profile.encryptionMethod.coerceIn(0, 5))
        }

        val profileObj = JsonObject().apply {
            addProperty("name", profile.name.ifBlank { primaryDomain })
            add("server", serverObj)
        }

        val root = JsonObject().apply {
            addProperty("schema", SCHEMA_MASTERDNS)
            addProperty("version", VERSION)
            add("profile", profileObj)
        }

        val jsonBytes = gson.toJson(root).toByteArray(Charsets.UTF_8)
        val base64Payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(jsonBytes)
        return "$SCHEME_MASTERDNS://$base64Payload"
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(url: String): ProfileDraftData {
        val link = url.trim()
        val schemeEnd = link.indexOf("://")
        require(schemeEnd > 0) { "Invalid profile link: missing scheme" }

        val scheme = link.substring(0, schemeEnd).lowercase()
        require(scheme in SUPPORTED_SCHEMES) { "Unsupported profile scheme: $scheme" }

        val payload = link.substring(schemeEnd + 3).trim().substringBefore('#').substringBefore('?')
        require(payload.isNotBlank()) { "Profile link payload is empty" }

        val jsonBytes = runCatching {
            base64UrlSafeDecoder.decode(payload)
        }.recoverCatching {
            base64DefaultDecoder.decode(payload)
        }.getOrElse {
            throw IllegalArgumentException("Profile payload is not valid base64")
        }

        val root = JsonParser.parseString(String(jsonBytes, Charsets.UTF_8)).asJsonObject
        val schema = root.get("schema")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        require(schema in SUPPORTED_SCHEMAS) { "Unsupported profile schema: $schema" }

        val profileObj = root.getAsJsonObject("profile")
            ?: throw IllegalArgumentException("Missing profile object in link")
        val serverObj = profileObj.getAsJsonObject("server")
            ?: throw IllegalArgumentException("Missing server object in link")

        val domains = mutableListOf<String>()
        val domainsArray = serverObj.get("domains")?.takeIf { it.isJsonArray }?.asJsonArray
        if (domainsArray != null && !domainsArray.isEmpty) {
            domainsArray.forEach { elem ->
                val d = runCatching { elem.asString }.getOrNull()?.trim()?.trimEnd('.').orEmpty()
                if (d.isNotBlank() && !domains.contains(d)) {
                    domains.add(d)
                }
            }
        }
        if (domains.isEmpty()) {
            val rawDomain = serverObj.get("domain")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (rawDomain.isNotBlank()) {
                val parsed = rawDomain.split(',', ';', ' ', '\n')
                    .map { it.trim().trimEnd('.') }
                    .filter { it.isNotBlank() }
                domains.addAll(parsed.ifEmpty { listOf(rawDomain.trimEnd('.')) })
            }
        }
        require(domains.isNotEmpty()) { "Profile link missing server domain" }

        val primaryDomain = domains.first()
        val key = serverObj.get("encryption_key")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        require(key.isNotBlank()) { "Profile link missing encryption key" }

        val method = serverObj.get("encryption_method")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()?.coerceIn(0, 5) ?: 1
        val name = profileObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.ifBlank { primaryDomain } ?: primaryDomain

        return ProfileDraftData(
            name = name,
            domains = domains.distinct(),
            encryptionKey = key,
            encryptionMethod = method
        )
    }

    fun decodeMultiple(raw: String): List<ProfileDraftData> {
        val results = mutableListOf<ProfileDraftData>()
        raw.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isNotBlank()) {
                try {
                    results.add(decode(line))
                } catch (e: Exception) {
                    throw IllegalArgumentException("Line ${index + 1}: ${e.message ?: "Invalid profile link"}", e)
                }
            }
        }
        require(results.isNotEmpty()) { "Enter at least one profile link" }
        return results
    }
}
