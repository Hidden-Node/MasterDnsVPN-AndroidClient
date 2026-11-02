package com.masterdns.vpn.data.repository

import com.masterdns.vpn.data.local.ProfileDao
import com.masterdns.vpn.data.local.SubscriptionDao
import com.masterdns.vpn.data.local.SubscriptionEntity
import com.masterdns.vpn.util.SubscriptionFetcher
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val profileDao: ProfileDao
) {
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()

    suspend fun getSubscriptionById(id: Long): SubscriptionEntity? = subscriptionDao.getSubscriptionById(id)

    suspend fun addSubscriptionAndFetch(name: String, url: String): Result<SubscriptionEntity> {
        val fetchResult = SubscriptionFetcher.fetch(url).getOrElse { return Result.failure(it) }
        val subName = name.ifBlank { fetchResult.suggestedName }
        val entity = SubscriptionEntity(
            name = subName,
            url = url.trim(),
            lastUpdated = System.currentTimeMillis(),
            profileCount = fetchResult.drafts.size
        )
        val subId = subscriptionDao.insertSubscription(entity)
        val profileEntities = fetchResult.drafts.map { it.toProfileEntity().copy(subscriptionId = subId) }
        profileDao.replaceProfilesForSubscription(subId, profileEntities)
        return Result.success(entity.copy(id = subId))
    }

    suspend fun refreshSubscription(subId: Long): Result<Int> {
        val sub = subscriptionDao.getSubscriptionById(subId)
            ?: return Result.failure(IllegalArgumentException("Subscription not found"))
        val fetchResult = SubscriptionFetcher.fetch(sub.url).getOrElse { return Result.failure(it) }
        val profileEntities = fetchResult.drafts.map { it.toProfileEntity().copy(subscriptionId = subId) }
        profileDao.replaceProfilesForSubscription(subId, profileEntities)
        subscriptionDao.updateSubscription(
            sub.copy(
                lastUpdated = System.currentTimeMillis(),
                profileCount = fetchResult.drafts.size
            )
        )
        return Result.success(fetchResult.drafts.size)
    }

    suspend fun deleteSubscription(subId: Long) {
        profileDao.deleteProfilesBySubscriptionIdAndReselect(subId)
        subscriptionDao.deleteSubscriptionById(subId)
    }
}
