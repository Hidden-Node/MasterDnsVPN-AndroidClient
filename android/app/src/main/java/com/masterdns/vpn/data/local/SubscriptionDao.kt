package com.masterdns.vpn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query(
        """
        SELECT id, name, url, lastUpdated,
               (SELECT COUNT(*) FROM profiles WHERE subscriptionId = subscriptions.id) AS profileCount
        FROM subscriptions ORDER BY id ASC
        """
    )
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query(
        """
        SELECT id, name, url, lastUpdated,
               (SELECT COUNT(*) FROM profiles WHERE subscriptionId = subscriptions.id) AS profileCount
        FROM subscriptions WHERE id = :id LIMIT 1
        """
    )
    suspend fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun getSubscriptionByUrl(url: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscriptionById(id: Long)
}
