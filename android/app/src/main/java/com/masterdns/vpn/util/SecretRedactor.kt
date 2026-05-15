package com.masterdns.vpn.util

object SecretRedactor {
    private const val REDACTED = "<redacted>"
    private const val APP_PRIVATE_PATH = "<app-private-path>"

    private val keyValuePattern = Regex(
        """\b(ENCRYPTION_KEY|SOCKS5_PASS|SOCKS5_USER|SOCKS_PASSWORD|PROXY_PASSWORD|PASSWORD|SECRET|TOKEN)\s*[:=]\s*("[^"]*"|'[^']*'|[^\s,;]+)""",
        RegexOption.IGNORE_CASE
    )
    private val jsonKeyValuePattern = Regex(
        """"(encryptionKey|socks5Pass|socksPassword|password|secret|token)"\s*:\s*("[^"]*"|[^\s,}]+)""",
        RegexOption.IGNORE_CASE
    )
    private val profileLinkPattern = Regex(
        """\b(masterdnsvpn|masterdns)://[^\s]+""",
        RegexOption.IGNORE_CASE
    )
    private val appPrivatePathPattern = Regex(
        """(?:/data/(?:user/\d+|data)/com\.masterdns\.vpn|/storage/emulated/\d+/Android/data/com\.masterdns\.vpn|/sdcard/Android/data/com\.masterdns\.vpn)(/[^\s]*)?"""
    )

    fun redact(message: String): String {
        var redacted = message
        redacted = profileLinkPattern.replace(redacted) { "${it.groupValues[1]}://$REDACTED" }
        redacted = keyValuePattern.replace(redacted) {
            "${it.groupValues[1]}=${redactedValue(it.groupValues[2])}"
        }
        redacted = jsonKeyValuePattern.replace(redacted) {
            "\"${it.groupValues[1]}\": ${redactedValue(it.groupValues[2])}"
        }
        redacted = appPrivatePathPattern.replace(redacted) {
            val suffix = it.groupValues.getOrNull(1).orEmpty()
            val filename = suffix.substringAfterLast('/', missingDelimiterValue = "")
            if (filename.isBlank()) APP_PRIVATE_PATH else "$APP_PRIVATE_PATH/$filename"
        }
        return redacted
    }

    private fun redactedValue(original: String): String {
        return when {
            original.startsWith("\"") -> "\"$REDACTED\""
            original.startsWith("'") -> "'$REDACTED'"
            else -> REDACTED
        }
    }
}
