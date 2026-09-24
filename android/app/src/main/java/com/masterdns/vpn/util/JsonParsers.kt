package com.masterdns.vpn.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private val gson = Gson()

fun parseAdvancedJson(json: String): Map<String, String> {
    return try {
        val type = object : TypeToken<Map<String, String>>() {}.type
        gson.fromJson<Map<String, String>>(json, type) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

fun parseDomainsJson(json: String): List<String> {
    return try {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(json, type) ?: emptyList()
    } catch (_: Exception) {
        // Fallback: treat as single domain
        listOf(json.trim().removeSurrounding("\""))
    }
}
