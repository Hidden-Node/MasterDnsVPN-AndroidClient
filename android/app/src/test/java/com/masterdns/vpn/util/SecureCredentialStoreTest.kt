package com.masterdns.vpn.util

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecureCredentialStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun setUser_getUser_roundTrips() {
        SecureCredentialStore.setUser(context, "alice")
        assertEquals("alice", SecureCredentialStore.getUser(context))
    }

    @Test
    fun setPass_getPass_roundTrips() {
        SecureCredentialStore.setPass(context, "secret123")
        assertEquals("secret123", SecureCredentialStore.getPass(context))
    }

    @Test
    fun migrateFromDataStore_copiesAndIsIdempotent() {
        val prefs = preferencesOf(
            stringPreferencesKey("internet_sharing_user") to "migrated_user",
            stringPreferencesKey("internet_sharing_pass") to "migrated_pass"
        )
        SecureCredentialStore.migrateFromDataStore(context, prefs)
        assertEquals("migrated_user", SecureCredentialStore.getUser(context))
        assertEquals("migrated_pass", SecureCredentialStore.getPass(context))
        // Second call must not error and must leave the values in place.
        SecureCredentialStore.migrateFromDataStore(context, prefs)
        assertEquals("migrated_user", SecureCredentialStore.getUser(context))
        assertEquals("migrated_pass", SecureCredentialStore.getPass(context))
    }
}
