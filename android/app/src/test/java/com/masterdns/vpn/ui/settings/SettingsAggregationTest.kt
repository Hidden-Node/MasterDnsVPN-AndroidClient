package com.masterdns.vpn.ui.settings

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.data.local.ProfileDao
import com.masterdns.vpn.data.local.ProfileEntity
import com.masterdns.vpn.data.repository.ProfileRepository
import com.masterdns.vpn.util.parseAdvancedJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsAggregationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var dao: ProfileDao
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.profileDao()
        viewModel = SettingsViewModel(ProfileRepository(dao), SavedStateHandle())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `importTomlValues merges quoted unquoted and domains list over base map`() {
        val base = mapOf(
            "ENCRYPTION_KEY" to "old",
            "LISTEN_PORT" to "18000",
            "LOG_LEVEL" to "INFO"
        )
        val toml = """
            ENCRYPTION_KEY = "secret quoted"
            LISTEN_PORT = 18001
            DOMAINS = ["a.com", "b.com"]
            NOT_A_REAL_KEY = "ignored"
        """.trimIndent()

        val merged = viewModel.importTomlValues(toml, base)

        assertEquals(
            mapOf(
                "ENCRYPTION_KEY" to "secret quoted",
                "LISTEN_PORT" to "18001",
                "LOG_LEVEL" to "INFO",
                "DOMAINS" to "a.com, b.com"
            ),
            merged
        )
    }

    @Test
    fun `saveSettings round trip writes only the two changed keys`() = runBlocking {
        val id = dao.insertProfileAndSelectIfFirst(
            ProfileEntity(name = "p", domains = "[\"v.example.com\"]")
        )
        val before = dao.getProfileById(id)!!

        viewModel.saveSettings(before, mapOf("ENCRYPTION_KEY" to "newkey123", "LISTEN_PORT" to "19000"))

        val updated = withTimeout(5000) {
            var cur = dao.getProfileById(id)!!
            while (cur.encryptionKey != "newkey123" || cur.listenPort != 19000) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                delay(50)
                cur = dao.getProfileById(id)!!
            }
            cur
        }

        assertEquals("newkey123", updated.encryptionKey)
        assertEquals(19000, updated.listenPort)
        assertEquals(before.name, updated.name)
        assertEquals(before.domains, updated.domains)
        assertEquals(before.encryptionMethod, updated.encryptionMethod)
        assertEquals(before.protocolType, updated.protocolType)
        assertEquals(before.resolverBalancingStrategy, updated.resolverBalancingStrategy)
        assertEquals(before.packetDuplicationCount, updated.packetDuplicationCount)
        assertEquals(before.setupPacketDuplicationCount, updated.setupPacketDuplicationCount)
        assertEquals(before.uploadCompression, updated.uploadCompression)
        assertEquals(before.downloadCompression, updated.downloadCompression)
        assertEquals(before.logLevel, updated.logLevel)
        assertEquals(parseAdvancedJson(before.advancedJson), parseAdvancedJson(updated.advancedJson))
    }
}
