package com.masterdns.vpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.ui.navigation.AppNavigation
import com.masterdns.vpn.ui.theme.MasterDnsVPNTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    private val gson = Gson()
    @Volatile
    private var lastHandledImportUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleTomlImportIntent(intent)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MasterDnsVPNTheme {
                    AppNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTomlImportIntent(intent)
    }

    private fun handleTomlImportIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val uri = when {
            intent.data != null -> intent.data
            intent.clipData != null && intent.clipData!!.itemCount > 0 -> intent.clipData!!.getItemAt(0).uri
            else -> null
        } ?: return

        val uriToken = uri.toString()
        if (lastHandledImportUri == uriToken) return
        val mime = intent.type.orEmpty()
        val isTomlLike = mime.contains("toml", ignoreCase = true) ||
            uri.toString().lowercase().endsWith(".toml")
        if (!isTomlLike) return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            val content = readTextFromUri(uri)
            if (content.isBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_invalid_toml_msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val imported = parseImportedProfile(uri, content)
            if (imported == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_invalid_toml_msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            lastHandledImportUri = uriToken
            val id = profileRepository.insertProfile(imported)
            profileRepository.setSelectedProfile(id)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.profiles_toml_imported_msg),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty()
    }

    private fun parseImportedProfile(uri: Uri, tomlContent: String): ProfileEntity? {
        val values = mutableMapOf<String, String>()
        tomlContent.lineSequence().forEach { raw ->
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty() || "=" !in line) return@forEach
            val key = line.substringBefore("=").trim()
            val valueRaw = line.substringAfter("=").trim()
            val parsed = when {
                valueRaw.startsWith("\"") && valueRaw.endsWith("\"") -> valueRaw.removeSurrounding("\"")
                else -> valueRaw
            }
            values[key] = parsed
        }

        val domainRaw = values["DOMAIN"] ?: values["DOMAINS"] ?: return null
        val domainList = if (domainRaw.startsWith("[") && domainRaw.endsWith("]")) {
            domainRaw.removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } else {
            listOf(domainRaw.trim().removeSurrounding("\"")).filter { it.isNotBlank() }
        }
        if (domainList.isEmpty()) return null

        val encryptionKey = values["ENCRYPTION_KEY"]?.trim().orEmpty()
        if (encryptionKey.isBlank()) return null

        val fileName = readDisplayName(uri) ?: "Imported Profile"

        return ProfileEntity(
            name = fileName,
            domains = gson.toJson(domainList),
            encryptionKey = encryptionKey
        )
    }

    private fun readDisplayName(uri: Uri): String? {
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
}
