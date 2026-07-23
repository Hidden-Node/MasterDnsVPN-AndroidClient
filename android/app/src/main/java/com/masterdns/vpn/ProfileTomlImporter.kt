package com.masterdns.vpn

internal object ProfileTomlImporter {
    /**
     * Maximum allowed length per imported TOML value. Tuned to accommodate
     * realistic ENCRYPTION_KEY + DOMAINS lists while bounding the cost of
     * a malicious import.
     */
    internal const val MAX_VALUE_LENGTH = 4096

    /**
     * Parse TOML key=value lines into a flat map. Pure function.
     *
     * Behavior:
     *  - Comments via `#` are stripped per line.
     *  - `DOMAINS = [...]` array parsed as comma-joined string.
     *  - Quoted strings `"...` have surrounding quotes stripped.
     *  - Values containing control characters (\n, \r, or char code
     *    below 0x20) or exceeding MAX_VALUE_LENGTH are REJECTED —
     *    the entry is skipped (no key stored).
     */
    internal fun parseTomlValues(tomlContent: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        tomlContent.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || "=" !in line) return@forEach
            val key = line.substringBefore("=").trim()
            val valueRaw = line.substringAfter("=").trim()
            val parsed = when {
                key == "DOMAINS" -> valueRaw
                    .removePrefix("[")
                    .removeSuffix("]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                valueRaw.startsWith("\"") && valueRaw.endsWith("\"") ->
                    valueRaw.removeSurrounding("\"")
                else -> valueRaw
            }
            if (parsed.any { it == '\n' || it == '\r' || it.code < 0x20 }) return@forEach
            if (parsed.length > MAX_VALUE_LENGTH) return@forEach
            values[key] = parsed
        }
        return values
    }
}
