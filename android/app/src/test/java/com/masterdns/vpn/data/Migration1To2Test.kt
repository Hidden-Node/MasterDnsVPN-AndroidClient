package com.masterdns.vpn.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.ProfileMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration1To2Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate_doesNotThrowOnEmptySchema() {
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
            db.execSQL("CREATE TABLE marker (x INTEGER)")
            ProfileMigrations.MIGRATION_1_2.migrate(db)
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use {
                val names = mutableListOf<String>()
                while (it.moveToNext()) names.add(it.getString(0))
                assertFalse("migrate must not create a profiles table", names.contains("profiles"))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migration_hasCorrectVersions() {
        assertEquals(1, ProfileMigrations.MIGRATION_1_2.startVersion)
        assertEquals(2, ProfileMigrations.MIGRATION_1_2.endVersion)
    }
}
