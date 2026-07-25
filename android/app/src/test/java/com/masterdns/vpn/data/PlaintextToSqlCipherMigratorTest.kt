package com.masterdns.vpn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.AppDatabase
import com.masterdns.vpn.data.local.PlaintextToSqlCipherMigrator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        AppDatabase.invalidateInstance()
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

    @Test
    fun performCopy_seedsProfileRowsIntoEncryptedDb() {
        seedPlaintextV1Db("SENTINEL_KEY")
        PlaintextToSqlCipherMigrator.runIfNeeded(context)

        assertTrue("masterdns_vpn.db must exist after migration", dbFile.exists())
        assertFalse("masterdns_vpn.db.plain must not linger after commit", plainFile.exists())
        assertTrue("FLAG_DONE must be set", flags.getBoolean("FLAG_DONE", false))
        assertFalse("FLAG_FAILED must NOT be set on success", flags.getBoolean("FLAG_FAILED", false))

        // Row must be readable through the post-migration Room handle.
        // (Under Robolectric the DB is plaintext because wrapWithSqlCipher falls back when
        // AndroidKeystore shim throws - that's expected; the assertion is about row-copy logic.)
        val rows = runBlocking { AppDatabase.getInstance(context).profileDao().getAllProfiles().first() }
        assertEquals(1, rows.size)
        assertEquals("SENTINEL_KEY", rows[0].encryptionKey)
    }

    @Test
    fun performCopy_rollbackRestoresPlaintextFile() {
        seedPlaintextV1Db("SENTINEL_KEY")
        PlaintextToSqlCipherMigrator.copyFailureInjector = { throw RuntimeException("forced rollback for test") }

        // Must NOT throw - runIfNeeded's outer try/catch swallows to avoid App.onCreate() crashloop.
        PlaintextToSqlCipherMigrator.runIfNeeded(context)

        // The plaintext file must have been restored as masterdns_vpn.db.
        assertTrue("masterdns_vpn.db must be restored after rollback", dbFile.exists())
        assertFalse("masterdns_vpn.db.plain must not linger after rollback", plainFile.exists())
        assertTrue("FLAG_FAILED must be set on rollback", flags.getBoolean("FLAG_FAILED", false))
        assertTrue("FLAG_DONE must be set on rollback (no auto-retry)", flags.getBoolean("FLAG_DONE", false))

        // The restored file must be openable as PLAIN SQLite (not SQLCipher) and contain the original row.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null, null).use { db ->
            db.rawQuery("SELECT encryptionKey FROM profiles", null).use { c ->
                assertTrue("row must be present after rollback restored the plaintext file", c.moveToFirst())
                assertEquals("SENTINEL_KEY", c.getString(0))
            }
        }
    }
}
