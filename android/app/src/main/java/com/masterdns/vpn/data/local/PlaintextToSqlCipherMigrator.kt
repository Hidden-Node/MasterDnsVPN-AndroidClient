package com.masterdns.vpn.data.local

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * One-time migrator: copies an existing v1 plaintext `masterdns_vpn.db` into
 * a fresh SQLCipher-encrypted file. Runs synchronously from `App.onCreate()`
 * BEFORE any Hilt-injected `AppDatabase` consumer starts.
 *
 * Option A: drive Room codegen first (getInstance + touch writableDatabase),
 * close, then ATTACH the renamed plaintext file and INSERT...SELECT the rows.
 *
 * Never throws - a thrown exception from App.onCreate() crash loops the app.
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

        // Step 0: Keystore availability pre-check. Passphrase is probed for its
        // throwing side-effect; the value is unused (Room's SupportFactory owns
        // the actual encryption decision in AppDatabase.wrapWithSqlCipher).
        runCatching { DatabaseEncryptionKey.passphrase(context) }.getOrElse {
            Log.w(TAG, "keystore unavailable; skipping migration", it)
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            return
        }

        val dbFile = context.getDatabasePath(DB_NAME)
        // Step 1: Pre-check - file exists? plaintext? (byte-header check, no native lib)
        if (!dbFile.exists()) {
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            return
        }
        val alreadyEncrypted = runCatching { isSqlcipherFile(dbFile) }.getOrElse { true }
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

        var db: AppDatabase? = null  // visible to catch block
        try {
            if (!dbFile.renameTo(plainFile)) {
                throw java.io.IOException("rename failed: $dbFile -> $plainFile")
            }
            if (walFile.exists()) walFile.renameTo(plainWalFile)
            if (shmFile.exists()) shmFile.renameTo(plainShmFile)

            // Step 4: Drive Room codegen on a fresh encrypted file. KEEP THE HANDLE
            // OPEN through step 7r2 - closing here would force a second SQLCipher
            // open (and its native-lib dependency) in the r1 sequence.
            db = AppDatabase.getInstance(context)
            db.openHelper.writableDatabase.execSQL("SELECT 1")

            // Step 5r2: Detect cipher mode on Room's handle (same handle step 4 just
            // touched - no fresh open). PRAGMA cipher_version returns a row on
            // SQLCipher and throws on plain SQLite -> isCipher autodetection.
            val isCipher = runCatching {
                db.openHelper.writableDatabase.query("PRAGMA cipher_version").use { it.moveToFirst() }
            }.isSuccess

            // Step 6r2: ATTACH + INSERT + count-verify + DETACH on Room's handle.
            copyFailureInjector?.invoke()  // test seam - throws if set by test
            val plainPath = plainFile.absolutePath
            val attachSql = if (isCipher)
                "ATTACH DATABASE '$plainPath' AS plain_db KEY ''"
            else
                "ATTACH DATABASE '$plainPath' AS plain_db"
            db.openHelper.writableDatabase.execSQL(attachSql)
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO main.profiles SELECT * FROM plain_db.profiles"
            )
            val plainCount = db.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM plain_db.profiles").use { it.moveToFirst(); it.getInt(0) }
            val mainCount = db.openHelper.writableDatabase
                .query("SELECT COUNT(*) FROM main.profiles").use { it.moveToFirst(); it.getInt(0) }
            if (plainCount != mainCount) {
                throw IllegalStateException("row count mismatch: plain=$plainCount main=$mainCount")
            }
            db.openHelper.writableDatabase.execSQL("DETACH DATABASE 'plain_db'")

            // Step 7r2: Commit. Close Room handle, drop singleton, delete backups.
            db.close()
            db = null
            plainFile.delete()
            plainWalFile.delete()
            plainShmFile.delete()
            AppDatabase.invalidateInstance()
            flags.edit().putBoolean(KEY_FLAG_DONE, true).commit()
            Log.i(TAG, "migration complete: copied $mainCount rows from plaintext to SQLCipher")
        } catch (t: Throwable) {
            // Step 8r2: Rollback. Never throws out of runIfNeeded.
            Log.e(TAG, "migration failed; rolling back", t)
            runCatching { db?.close() }
            runCatching { AppDatabase.invalidateInstance() }
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

    /**
     * Returns true iff [file] starts with the SQLCipher salt (random-looking bytes)
     * rather than the SQLite plaintext header ("SQLite format 3\0" = 16 bytes).
     * Pure byte check - no native-lib dependency, runs on host JVM under Robolectric.
     */
    private fun isSqlcipherFile(file: java.io.File): Boolean {
        if (file.length() < 16) return false
        val header = ByteArray(16)
        java.io.RandomAccessFile(file, "r").use { it.readFully(header) }
        val isPlain = String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3" &&
            header[15] == 0.toByte()
        return !isPlain
    }
}
