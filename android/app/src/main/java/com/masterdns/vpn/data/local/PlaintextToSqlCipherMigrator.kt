package com.masterdns.vpn.data.local

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlcipherDatabase

/**
 * One-time migrator: copies an existing v1 plaintext `masterdns_vpn.db` into
 * a fresh SQLCipher-encrypted file. Runs synchronously from `App.onCreate()`
 * BEFORE any Hilt-injected `AppDatabase` consumer starts.
 *
 * Option A: drive Room codegen first (getInstance + touch writableDatabase),
 * close, then ATTACH the renamed plaintext file and INSERT...SELECT the rows.
 *
 * Never throws - a thrown exception from App.onCreate() crashloops the app.
 * On any failure, sets FLAG_FAILED=true + FLAG_DONE=true (so it never retries
 * automatically) and returns normally; the user must clear app data to retry.
 */
internal object PlaintextToSqlCipherMigrator {

    private const val TAG = "PlaintextToSqlCipherMigrator"
    private const val FLAGS_FILE = "masterdns_migration"
    private const val KEY_FLAG_DONE = "FLAG_DONE"
    private const val KEY_FLAG_FAILED = "FLAG_FAILED"
    private const val DB_NAME = "masterdns_vpn.db"
    private const val DB_PLAIN_NAME = "masterdns_vpn.db.plain"

    @VisibleForTesting
    internal var copyFailureInjector: (() -> Unit)? = null

    /**
     * Public entry. Idempotent via the gating flag. Never throws.
     */
    fun runIfNeeded(context: Context) {
        return try {
            performCopy(context)
        } catch (t: Throwable) {
            Log.e(TAG, "migration crashed (should not happen - performCopy must catch its own)", t)
            context.getSharedPreferences(FLAGS_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FLAG_FAILED, true).putBoolean(KEY_FLAG_DONE, true).commit()
        }
    }

    @Synchronized
    private fun performCopy(context: Context) {
        val flags = context.getSharedPreferences(FLAGS_FILE, Context.MODE_PRIVATE)
        if (flags.getBoolean(KEY_FLAG_DONE, false)) return
        if (flags.getBoolean(KEY_FLAG_FAILED, false)) {
            Log.w(TAG, "migration previously failed; not retrying. Clear app data to retry.")
            return
        }

        // Step 0: Keystore availability pre-check.
        val passphrase = runCatching { DatabaseEncryptionKey.passphrase(context) }.getOrElse {
            Log.w(TAG, "keystore unavailable; skipping migration", it)
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            return
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        // Step 1: Pre-check - only migrate if the file exists AND is plaintext.
        if (!dbFile.exists()) {
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            return
        }
        // Detect whether the file is already SQLCipher by trying to open it
        // with the passphrase. If openOrCreateDatabase succeeds and PRAGMA
        // cipher_version returns, it's already encrypted - no migration needed.
        val alreadyEncrypted = runCatching {
            SqlcipherDatabase.openOrCreateDatabase(dbFile, String(passphrase), null).use { db ->
                db.execSQL("PRAGMA cipher_version")
            }
            true
        }.getOrElse { false }
        if (alreadyEncrypted) {
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            return
        }

        // Step 2: Gating flag was checked at the top of performCopy.

        // Step 3: Move-to-backup. Rename the plaintext file (and -wal/-shm).
        val plainFile = java.io.File(dbFile.parentFile, DB_PLAIN_NAME)
        val walFile = java.io.File(dbFile.parentFile, "$DB_NAME-wal")
        val shmFile = java.io.File(dbFile.parentFile, "$DB_NAME-shm")
        val plainWalFile = java.io.File(dbFile.parentFile, "$DB_PLAIN_NAME-wal")
        val plainShmFile = java.io.File(dbFile.parentFile, "$DB_PLAIN_NAME-shm")

        try {
            if (!dbFile.renameTo(plainFile)) {
                throw java.io.IOException("rename failed: $dbFile -> $plainFile")
            }
            if (walFile.exists()) walFile.renameTo(plainWalFile)
            if (shmFile.exists()) shmFile.renameTo(plainShmFile)

            // Step 4: Drive Room codegen on a fresh encrypted file. getInstance
            // + writableDatabase.execSQL("SELECT 1") forces CREATE TABLE.
            val db = AppDatabase.getInstance(context)
            db.openHelper.writableDatabase.execSQL("SELECT 1")
            AppDatabase.closeForMigration()

            // Step 5: Raw SQLCipher copy.
            SqlcipherDatabase.openOrCreateDatabase(dbFile, String(passphrase), null).use { cipherDb ->
                copyFailureInjector?.invoke()  // test seam - throws if set by test
                val plainPath = plainFile.absolutePath
                cipherDb.execSQL("ATTACH DATABASE '$plainPath' AS plain_db KEY ''")

                // Step 6: Insert + verify row count BEFORE DETACH.
                cipherDb.execSQL("INSERT INTO main.profiles SELECT * FROM plain_db.profiles")
                val plainCount = cipherDb.rawQuery("SELECT COUNT(*) FROM plain_db.profiles", null).use {
                    it.moveToFirst(); it.getInt(0)
                }
                val mainCount = cipherDb.rawQuery("SELECT COUNT(*) FROM main.profiles", null).use {
                    it.moveToFirst(); it.getInt(0)
                }
                if (plainCount != mainCount) {
                    throw IllegalStateException("row count mismatch: plain=$plainCount main=$mainCount")
                }
                cipherDb.execSQL("DETACH DATABASE 'plain_db'")
            }

            // Step 7: Commit. Delete the plain backup + its wal/shm.
            plainFile.delete()
            plainWalFile.delete()
            plainShmFile.delete()
            AppDatabase.invalidateInstance()
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            Log.i(TAG, "migration complete: copied rows from plaintext to SQLCipher")
        } catch (t: Throwable) {
            // Step 8: Rollback. Restore plain file, mark failed.
            Log.e(TAG, "migration failed; rolling back", t)
            runCatching { SqlcipherDatabase.openOrCreateDatabase(dbFile, String(passphrase), null).close() }
            runCatching { AppDatabase.invalidateInstance() }
            // Restore the plain file if the cipher file exists but plain doesn't.
            if (plainFile.exists() && dbFile.exists()) {
                dbFile.delete()
                plainFile.renameTo(dbFile)
                if (plainWalFile.exists()) plainWalFile.renameTo(walFile)
                if (plainShmFile.exists()) plainShmFile.renameTo(shmFile)
            }
            flags.edit().putBoolean(KEY_FLAG_FAILED, true).putBoolean(KEY_FLAG_DONE, true).commit()
            // DO NOT throw - see class KDoc.
        }
    }
}
