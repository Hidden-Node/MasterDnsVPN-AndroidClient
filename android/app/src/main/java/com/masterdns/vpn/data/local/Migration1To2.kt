package com.masterdns.vpn.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration v1 -> v2: encrypts the existing SQLite file at the storage layer.
 *
 * The actual plaintext-to-encrypted migration of an EXISTING v1 install is
 * handled by `PlaintextToSqlCipherMigrator` (see Step 5 of plan 011). This
 * `Migration` object exists so Room does not throw
 * "A migration from 1 to 2 was required but not found" on a code path where
 * Step 5 has been bypassed or skipped (e.g. keystore unavailable -> App opens
 * at v2 with no v1 file present). The `migrate` body is intentionally a no-op:
 * no schema columns change between v1 and v2; if a future plan adds a column,
 * add the `ALTER TABLE` here.
 */
val Migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No row-level changes needed for v1 -> v2 (no schema columns change).
        // The plaintext->encrypted file copy is handled by
        // PlaintextToSqlCipherMigrator before Room opens the file.
    }
}
