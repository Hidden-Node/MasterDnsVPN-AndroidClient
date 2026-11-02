package com.masterdns.vpn

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.data.local.ProfileMigrations
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileMigrations2To3Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        cleanUpBackups()
    }

    @After
    fun cleanUpBackups() {
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("profiles_backup_") }?.forEach { it.delete() }
    }

    @Test
    fun migration2To3_addsColumnAndTable_andPreservesData() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS profiles (
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
                            )
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("""
                INSERT INTO profiles (name, domains, encryptionMethod, encryptionKey, protocolType,
                    listenPort, resolvers, resolverBalancingStrategy, packetDuplicationCount,
                    setupPacketDuplicationCount, uploadCompression, downloadCompression, logLevel,
                    isSelected, advancedJson, createdAt)
                VALUES ('PreMigration', '["test.com"]', 1, 'key', 'SOCKS5', 18000, '8.8.8.8', 3, 3, 4, 0, 0, 'INFO', 1, '{}', 1000)
            """.trimIndent())

            ProfileMigrations.migration2To3(context).migrate(db)

            // Verify safety export snapshot created
            val backups = context.noBackupFilesDir.listFiles { f -> f.name.startsWith("profiles_backup_v2_to_v3_") }
            assertThat(backups?.size ?: 0).isAtLeast(1)

            // Verify subscriptions table created
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='subscriptions'").use { cursor ->
                assertThat(cursor.count).isEqualTo(1)
            }

            // Verify subscriptionId column added to profiles
            db.query("PRAGMA table_info(profiles)").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(1))
                }
                assertThat(columns).contains("subscriptionId")
            }

            // Verify existing profile intact with null subscriptionId
            db.query("SELECT id, name, subscriptionId FROM profiles WHERE name='PreMigration'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(1)).isEqualTo("PreMigration")
                assertThat(cursor.isNull(2)).isTrue()
            }
        } finally {
            helper.close()
        }
    }
}
