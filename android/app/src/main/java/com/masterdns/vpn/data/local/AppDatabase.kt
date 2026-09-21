package com.masterdns.vpn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ProfileEntity::class], version = 2, exportSchema = false)
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
            // If this install still carries an SQLCipher-encrypted DB (plan 011 era),
            // move it aside; Room cannot open it without the (removed) native library.
            SqlcipherFileDetector.quarantineEncryptedDb(context, "masterdns_vpn.db")
            builder.addMigrations(Migration1To2)
            return builder.build()
        }

        internal fun closeForMigration() {
            INSTANCE?.let { db ->
                if (db.isOpen) db.close()
            }
            INSTANCE = null
        }

        internal fun invalidateInstance() {
            INSTANCE = null
        }
    }
}
