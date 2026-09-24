package com.masterdns.vpn.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun Context.readTextFromUri(uri: Uri): String {
    return contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.orEmpty()
}

fun Context.readDisplayName(uri: Uri): String? {
    return runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0 || !cursor.moveToFirst()) return@use null
            cursor.getString(idx)
        }
    }.getOrNull()
        ?.substringBeforeLast(".")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
