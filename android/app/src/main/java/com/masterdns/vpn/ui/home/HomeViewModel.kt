package com.masterdns.vpn.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: ProfileRepository
) : ViewModel() {

    val selectedProfile: StateFlow<ProfileEntity?> =
        profileRepository.getSelectedProfileFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
