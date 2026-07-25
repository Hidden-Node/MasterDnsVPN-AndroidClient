package com.masterdns.vpn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportFactory

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
            wrapWithSqlCipher(context, builder)
            builder.addMigrations(Migration1To2)
            return builder.build()
        }

        /**
         * Wraps the Room builder with SQLCipher if AndroidKeystore is available.
         * On any keystore failure, falls back to plaintext so the app still opens.
         */
        private fun wrapWithSqlCipher(
            context: Context,
            builder: RoomDatabase.Builder<AppDatabase>
        ) {
            val passphrase = runCatching { DatabaseEncryptionKey.passphrase(context) }
                .getOrElse {
                    android.util.Log.w("AppDatabase", "Keystore unavailable; DB opened in plaintext fallback", it)
                    return
                }
            builder.openHelperFactory(SupportFactory(passphrase, null, false))
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
