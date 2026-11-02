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
        val trimmedUrl = url.trim()
        val fetchResult = SubscriptionFetcher.fetch(trimmedUrl).getOrElse { return Result.failure(it) }
        val subName = name.ifBlank { fetchResult.suggestedName }
        val existing = subscriptionDao.getSubscriptionByUrl(trimmedUrl)
        val subId = if (existing != null) {
            val updated = existing.copy(
                name = if (name.isNotBlank()) name.trim() else existing.name,
                lastUpdated = System.currentTimeMillis(),
                profileCount = fetchResult.drafts.size
            )
            subscriptionDao.updateSubscription(updated)
            existing.id
        } else {
            val entity = SubscriptionEntity(
                name = subName,
                url = trimmedUrl,
                lastUpdated = System.currentTimeMillis(),
                profileCount = fetchResult.drafts.size
            )
            subscriptionDao.insertSubscription(entity)
        }
        val profileEntities = fetchResult.drafts.map { it.toProfileEntity().copy(subscriptionId = subId) }
        profileDao.replaceProfilesForSubscription(subId, profileEntities)
        val finalEntity = subscriptionDao.getSubscriptionById(subId) ?: SubscriptionEntity(
            id = subId,
            name = subName,
            url = trimmedUrl,
            lastUpdated = System.currentTimeMillis(),
            profileCount = fetchResult.drafts.size
        )
        return Result.success(finalEntity)
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
