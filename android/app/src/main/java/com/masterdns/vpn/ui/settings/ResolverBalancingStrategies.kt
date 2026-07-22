package com.masterdns.vpn.ui.settings

internal fun normalizeResolverBalancingStrategy(value: String?, fallback: Int): Int {
    val parsed = value?.trim()?.toIntOrNull()
    if (parsed != null && parsed in 1..8) return parsed
    if (parsed == 0) return 2
    return if (fallback in 1..8) fallback else 2
}
