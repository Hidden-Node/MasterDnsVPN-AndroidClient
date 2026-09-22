package com.masterdns.vpn

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.masterdns.vpn.data.local.Migration1To2
import com.masterdns.vpn.data.local.ProfileMigrations
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileMigrationsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun backupFiles(): List<File> =
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("profiles_backup_") }
            ?.sortedBy { it.name } ?: emptyList()

    private class RecordingDelegate : Migration(1, 2) {
        var ran = false
        override fun migrate(db: SupportSQLiteDatabase) {
            ran = true
        }
    }

    @Test
    fun `registers exactly migration 1 to 2`() {
        val ranges = ProfileMigrations.ALL.map { it.startVersion to it.endVersion }
        assertThat(ranges).containsExactly(1 to 2)
    }

    @Test
    fun `top-level Migration1To2 delegates to the hub`() {
        assertThat(Migration1To2).isSameInstanceAs(ProfileMigrations.MIGRATION_1_2)
    }

    @Test
    fun `MIGRATION_1_2 has correct versions`() {
        assertThat(ProfileMigrations.MIGRATION_1_2.startVersion).isEqualTo(1)
        assertThat(ProfileMigrations.MIGRATION_1_2.endVersion).isEqualTo(2)
    }

    @Test
    fun `safety export writes snapshot then runs delegate`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("CREATE TABLE profiles (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("INSERT INTO profiles (name) VALUES ('p1')")
            val delegate = RecordingDelegate()

            ProfileMigrations.SafetyExportMigration(context, 1, 2, delegate).migrate(db)

            val backups = backupFiles()
            assertThat(backups).hasSize(1)
            val json = JsonParser.parseString(backups.last().readText()).asJsonObject
            assertThat(json.get("schemaVersion").asInt).isEqualTo(1)
            val profiles = json.getAsJsonArray("profiles")
            assertThat(profiles.size()).isEqualTo(1)
            assertThat(profiles.get(0).asJsonObject.get("name").asString).isEqualTo("p1")
            assertThat(profiles.get(0).asJsonObject.get("id").asLong).isEqualTo(1L)
            assertThat(delegate.ran).isTrue()
        } finally {
            helper.close()
        }
    }

    @Test
    fun `safety export is skipped on empty table and delegate still runs`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("CREATE TABLE profiles (id INTEGER PRIMARY KEY, name TEXT)")
            val delegate = RecordingDelegate()

            ProfileMigrations.SafetyExportMigration(context, 1, 2, delegate).migrate(db)

            assertThat(backupFiles()).isEmpty()
            assertThat(delegate.ran).isTrue()
        } finally {
            helper.close()
        }
    }

    @Test
    fun `safety export prunes to at most MAX_KEPT_BACKUPS files`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("CREATE TABLE profiles (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("INSERT INTO profiles (name) VALUES ('p1')")
            val delegate = RecordingDelegate()
            val wrapper = ProfileMigrations.SafetyExportMigration(context, 1, 2, delegate)

            repeat(3) { wrapper.migrate(db) }

            assertThat(backupFiles().size).isAtMost(2)
        } finally {
            helper.close()
        }
    }

    @After
    fun cleanUpBackups() {
        backupFiles().forEach { it.delete() }
    }
}
