package com.masterdns.vpn.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.local.SubscriptionEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.data.repository.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        subscriptionRepository.getAllSubscriptions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshingSubscriptions = MutableStateFlow(false)
    val isRefreshingSubscriptions: StateFlow<Boolean> = _isRefreshingSubscriptions.asStateFlow()

    fun addProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            profileRepository.insertProfileAndSelectIfFirst(profile)
        }
    }

    fun addProfiles(profiles: List<ProfileEntity>) {
        if (profiles.isEmpty()) return
        viewModelScope.launch {
            profiles.forEach { profile ->
                profileRepository.insertProfileAndSelectIfFirst(profile)
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
            profileRepository.deleteProfile(profile)
        }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileRepository.setSelectedProfile(id)
        }
    }

    fun addSubscription(
        name: String,
        url: String,
        onSuccess: (count: Int) -> Unit,
        onError: (String) -> Unit
    ) {
        if (_isRefreshingSubscriptions.value) return
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            try {
                subscriptionRepository.addSubscriptionAndFetch(name, url)
                    .onSuccess { sub -> onSuccess(sub.profileCount) }
                    .onFailure { err -> onError(err.message ?: "Failed to fetch subscription") }
            } finally {
                _isRefreshingSubscriptions.value = false
            }
        }
    }

    fun refreshSubscription(
        subId: Long,
        onComplete: (count: Int?, error: String?) -> Unit
    ) {
        if (_isRefreshingSubscriptions.value) return
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            try {
                subscriptionRepository.refreshSubscription(subId)
                    .onSuccess { count -> onComplete(count, null) }
                    .onFailure { err -> onComplete(null, err.message) }
            } finally {
                _isRefreshingSubscriptions.value = false
            }
        }
    }

    fun refreshAllSubscriptions(
        onComplete: (totalUpdated: Int, errors: List<String>) -> Unit
    ) {
        if (_isRefreshingSubscriptions.value) return
        viewModelScope.launch {
            _isRefreshingSubscriptions.value = true
            val subs = subscriptions.value
            var totalCount = 0
            val errors = mutableListOf<String>()
            try {
                subs.forEach { sub ->
                    subscriptionRepository.refreshSubscription(sub.id)
                        .onSuccess { totalCount += it }
                        .onFailure { errors.add("${sub.name}: ${it.message}") }
                }
                onComplete(totalCount, errors)
            } finally {
                _isRefreshingSubscriptions.value = false
            }
        }
    }

    fun deleteSubscription(subId: Long) {
        viewModelScope.launch {
            subscriptionRepository.deleteSubscription(subId)
        }
    }
}
