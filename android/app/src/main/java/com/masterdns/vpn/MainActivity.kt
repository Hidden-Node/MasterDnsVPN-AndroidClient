package com.masterdns.vpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
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
            val imported = parseImportedProfile(uri, content)
            if (imported == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_invalid_toml_msg),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
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
        return contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
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

        val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Imported Profile"

        return ProfileEntity(
            name = fileName,
            domains = gson.toJson(domainList),
            encryptionKey = encryptionKey
        )
    }
}
