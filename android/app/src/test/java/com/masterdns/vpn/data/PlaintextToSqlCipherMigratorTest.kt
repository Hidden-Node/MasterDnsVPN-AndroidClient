package com.masterdns.vpn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.PlaintextToSqlCipherMigrator
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaintextToSqlCipherMigratorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val flags get() = context.getSharedPreferences("masterdns_migration", Context.MODE_PRIVATE)
    private val dbFile get() = context.getDatabasePath("masterdns_vpn.db")
    private val plainFile get() = java.io.File(dbFile.parentFile, "masterdns_vpn.db.plain")

    @Before
    fun setUp() {
        flags.edit().clear().commit()
        context.deleteDatabase("masterdns_vpn.db")
        plainFile.delete()
        PlaintextToSqlCipherMigrator.copyFailureInjector = null
    }

    @After
    fun tearDown() {
        PlaintextToSqlCipherMigrator.copyFailureInjector = null
        context.deleteDatabase("masterdns_vpn.db")
        plainFile.delete()
    }

    private fun seedPlaintextV1Db(encryptionKey: String = "SENTINEL_KEY") {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null, null).use { db ->
            db.execSQL("""CREATE TABLE profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                domains TEXT NOT NULL,
                encryptionMethod INTEGER NOT NULL,
                encryptionKey TEXT NOT NULL,
                protocolType TEXT NOT NULL,
                listenPort INTEGER NOT NULL,
                resolvers TEXT NOT NULL,
                resolverBalancingStrategy INTEGER NOT NULL,
                packetDuplicationCount INTEGER NOT NULL,
                setupPacketDuplicationCount INTEGER NOT NULL,
                uploadCompression INTEGER NOT NULL,
                downloadCompression INTEGER NOT NULL,
                logLevel TEXT NOT NULL,
                isSelected INTEGER NOT NULL,
                advancedJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )""".trimIndent())
            db.execSQL("""INSERT INTO profiles VALUES (
                1, 'test', '[]', 1, '$encryptionKey', 'SOCKS5', 18000, '', 3, 3, 4, 0, 0, 'INFO', 0, '{}', 0
            )""".trimIndent())
        }
    }

    @Test
    fun runIfNeeded_whenFlagFailed_doesNotRetry() {
        flags.edit().putBoolean("FLAG_FAILED", true).putBoolean("FLAG_DONE", true).commit()
        seedPlaintextV1Db("SENTINEL_BEFORE")
        PlaintextToSqlCipherMigrator.runIfNeeded(context)
        assertTrue("plaintext file must still be present", dbFile.exists())
        assertFalse("plain backup must not have been created", plainFile.exists())
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null, null).use { db ->
            db.rawQuery("SELECT encryptionKey FROM profiles", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("SENTINEL_BEFORE", it.getString(0))
            }
        }
    }

    @Test
    fun runIfNeeded_whenFlagDone_isNoOp() {
        flags.edit().putBoolean("FLAG_DONE", true).commit()
        seedPlaintextV1Db("SENTINEL_DONE")
        PlaintextToSqlCipherMigrator.runIfNeeded(context)
        assertTrue(dbFile.exists())
        assertFalse(plainFile.exists())
    }

    @Test
    fun runIfNeeded_whenNoDbFile_isNoOp() {
        PlaintextToSqlCipherMigrator.runIfNeeded(context)
        assertTrue(
            "FLAG_DONE must be set on fresh-install no-DB path",
            flags.getBoolean("FLAG_DONE", false)
        )
        assertFalse("FLAG_FAILED must NOT be set", flags.getBoolean("FLAG_FAILED", false))
        assertFalse("no DB file should be created", dbFile.exists())
    }
}
