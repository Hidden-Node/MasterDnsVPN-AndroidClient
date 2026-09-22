package com.masterdns.vpn.data.local

import android.content.Context
import android.database.Cursor
import android.util.Base64
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.File

/**
 * Central place for Room migrations. Each Migration(N, N+1) lives here.
 *
 * Convention: every migration that risks user data (table rebuilds, column
 * drops, type changes) MUST be wrapped in [SafetyExportMigration] so a JSON
 * snapshot of the current rows is written before the schema change:
 *
 * ```
 * val MIGRATION_2_3 = SafetyExportMigration(context, 2, 3,
 *     object : Migration(2, 3) {
 *         override fun migrate(db: SupportSQLiteDatabase) { ... }
 *     })
 * ```
 *
 * The migrations registered in [ALL] change no schema columns (the v1->v2
 * bump is a no-op), so they are deliberately not wrapped.
 */
object ProfileMigrations {

    private const val TAG = "ProfileMigrations"
    private const val BACKUP_PREFIX = "profiles_backup_"
    private const val MAX_KEPT_BACKUPS = 2

    private val gson = Gson()

    /**
     * Wraps [delegate] so a snapshot of `profiles` is exported before its
     * schema change runs.
     *
     * The snapshot goes to `noBackupFilesDir` — app-private and excluded from
     * platform auto-backup — because `cacheDir` can be cleared by the OS at
     * any moment, exactly when a backup would be needed. It contains
     * credentials (`encryptionKey`, `advancedJson`) as plain text;
     * this is intentional. Only the newest [MAX_KEPT_BACKUPS] files are kept.
     *
     * If the export throws, Room aborts the migration instead of silently
     * destroying data.
     */
    class SafetyExportMigration(
        private val context: Context,
        from: Int,
        to: Int,
        private val delegate: Migration
    ) : Migration(from, to) {

        override fun migrate(db: SupportSQLiteDatabase) {
            exportCurrentProfiles(db)
            delegate.migrate(db)
        }

        private fun pruneOldBackups(dir: File) {
            runCatching {
                dir.listFiles { f -> f.name.startsWith(BACKUP_PREFIX) }
                    ?.sortedByDescending(File::lastModified)
                    ?.drop(MAX_KEPT_BACKUPS)
                    ?.forEach { it.delete() }
            }.onFailure {
                Log.w(TAG, "Failed to prune old profile backups", it)
            }
        }

        /**
         * Dumps every row/column discovered at runtime via the cursor itself,
         * so future schema versions never need to update a hardcoded list.
         */
        private fun exportCurrentProfiles(db: SupportSQLiteDatabase) {
            val rows = mutableListOf<JsonObject>()
            db.query("SELECT * FROM profiles").use { c ->
                while (c.moveToNext()) {
                    val obj = JsonObject()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> obj.add(name, JsonNull.INSTANCE)
                            Cursor.FIELD_TYPE_INTEGER -> obj.addProperty(name, c.getLong(i))
                            Cursor.FIELD_TYPE_FLOAT -> obj.addProperty(name, c.getDouble(i))
                            Cursor.FIELD_TYPE_BLOB -> obj.addProperty(
                                name,
                                Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
                            )
                            else -> obj.addProperty(name, c.getString(i))
                        }
                    }
                    rows.add(obj)
                }
            }
            // An empty table has nothing to preserve; skip the export so a
            // disk-full device cannot turn a harmless upgrade into a
            // permanent crash loop.
            if (rows.isEmpty()) {
                Log.i(
                    TAG,
                    "No profiles before v$startVersion->v$endVersion; skipping safety export"
                )
                return
            }
            val dir = context.noBackupFilesDir
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Cannot create backup directory: $dir")
            }
            val file = File(dir, backupFileName(startVersion, endVersion))
            file.writeText(gson.toJson(snapshotJson(startVersion, rows)))
            Log.i(TAG, "Profile safety export written (${rows.size} rows): ${file.name}")
            pruneOldBackups(dir)
        }
    }

    private fun backupFileName(from: Int, to: Int): String =
        "${BACKUP_PREFIX}v${from}_to_v${to}_${System.currentTimeMillis()}.json"

    private fun snapshotJson(schemaVersion: Int, rows: List<JsonObject>): JsonObject =
        JsonObject().apply {
            addProperty("schemaVersion", schemaVersion)
            addProperty("exportedAt", System.currentTimeMillis())
            add("profiles", gson.toJsonTree(rows))
        }

    // v1 -> v2: storage-layer bump (SQLCipher removal, plan 025). No columns
    // changed; existing v1 installs keep their migration path.
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { /* no-op */ }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
