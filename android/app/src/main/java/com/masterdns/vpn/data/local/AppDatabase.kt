package com.masterdns.vpn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Room DB v2. Additive changes: bump version + AutoMigration. Destructive changes: manual Migration in ProfileMigrations wrapped in SafetyExportMigration. */
@Database(entities = [ProfileEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }
        }

        private fun build(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "masterdns_vpn.db"
            )
            builder.addMigrations(*ProfileMigrations.ALL)
            // Downgrading the app (e.g. rolling back an update) would
            // otherwise crash on every launch; profiles are cheap to
            // recreate, a permanently crashing app is not.
            builder.fallbackToDestructiveMigrationOnDowngrade()
            return builder.build()
        }
    }
}
