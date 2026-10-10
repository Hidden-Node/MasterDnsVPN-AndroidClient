package com.masterdns.vpn.util

/**
 * Gate for file-open TOML imports. `isFirstCreation` is
 * `savedInstanceState == null`. The Activity owns intent parsing and DB writes;
 * this object only answers should-handle/is-toml-like so the policy is
 * unit-testable.
 */
object ImportIntentGate {
    const val KEY_LAST_HANDLED_URI = "masterdns.lastHandledImportUri"

    fun isTomlLike(mimeType: String?, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        if (mimeType != null && mimeType.contains("toml", ignoreCase = true)) return true
        return uriString.lowercase().endsWith(".toml")
    }

    fun shouldHandle(
        isFirstCreation: Boolean,
        uriToken: String?,
        lastHandledUriToken: String?
    ): Boolean {
        if (!isFirstCreation) return false
        if (uriToken.isNullOrBlank()) return false
        return uriToken != lastHandledUriToken
    }
}
