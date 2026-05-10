package com.masterdns.vpn.ui.profiles

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.util.ResolverAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            val id = profileRepository.insertProfile(profile)
            // Auto-select the first profile
            if (profiles.value.isEmpty()) {
                profileRepository.setSelectedProfile(id)
            }
        }
    }

    fun updateProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            profileRepository.updateProfile(profile)
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            ResolverAnalyzer.discardImportedResolver(ResolverAnalyzer.profileImportedResolver(profile))
            profileRepository.deleteProfile(profile)
        }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileRepository.setSelectedProfile(id)
        }
    }

    suspend fun importProfileFromToml(tomlContent: String, name: String): ProfileEntity? {
        return profileRepository.importProfileFromToml(tomlContent, name)
    }

    fun previewProfileFromToml(tomlContent: String, name: String): ProfileEntity? {
        return profileRepository.previewProfileFromToml(tomlContent, name)
    }

    suspend fun exportProfileToml(profileId: Long, lockIdentity: Boolean): String? {
        return profileRepository.exportProfileToml(profileId, lockIdentity)
    }

    fun exportProfile(context: Context, profileId: Long, profileName: String, lockIdentity: Boolean) {
        viewModelScope.launch {
            val toml = profileRepository.exportProfileToml(profileId, lockIdentity) ?: return@launch
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, toml)
                putExtra(Intent.EXTRA_SUBJECT, "$profileName.toml")
            }
            context.startActivity(Intent.createChooser(intent, "Export Profile"))
        }
    }
}
