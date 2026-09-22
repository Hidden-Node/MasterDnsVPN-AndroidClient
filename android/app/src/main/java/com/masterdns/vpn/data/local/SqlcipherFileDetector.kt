package com.masterdns.vpn.data.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Detects a SQLCipher-encrypted DB file (any file whose 16-byte header is not
 * the plain-SQLite magic) and moves it aside so the plaintext Room builder
 * can create a fresh database instead of crash-looping. The renamed file is
 * kept as *.encrypted.bak — never hard-deleted.
 */
internal object SqlcipherFileDetector {
    private const val TAG = "SqlcipherFileDetector"
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isSqlcipherFile(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val header = ByteArray(16)
        // Fail safe: a file that disappears or truncates mid-read is treated
        // as unknown (not cipher) so the detector can never crash startup.
        val ok = runCatching {
            file.inputStream().use { readFully(it, header) }
        }.isSuccess
        if (!ok) {
            Log.w(TAG, "Could not read db header; treating as plaintext")
            return false
        }
        return !header.contentEquals(SQLITE_MAGIC)
    }

    /** Moves the encrypted db + sidecars aside. Returns true if anything moved. */
    fun quarantineEncryptedDb(context: Context, name: String): Boolean {
        val main = context.getDatabasePath(name)
        if (!isSqlcipherFile(main)) return false
        val stamp = System.currentTimeMillis()
        val parent = main.parentFile
        // Fail closed: only report success if the main file actually moved,
        // otherwise Room would still open the encrypted file and crash-loop.
        if (!main.renameTo(File(parent, "$name.encrypted.$stamp.bak"))) {
            Log.e(TAG, "Could not quarantine encrypted db; leaving it in place")
            return false
        }
        // Sidecars belong to the old (encrypted) connection; move them too so
        // the fresh plaintext DB starts clean.
        listOf("$name-wal", "$name-shm", "$name-journal").forEach { side ->
            val f = File(parent, side)
            if (f.exists() && !f.renameTo(File(parent, "$side.encrypted.$stamp.bak"))) {
                Log.w(TAG, "Could not quarantine sidecar $side")
            }
        }
        Log.w(TAG, "Encrypted DB quarantined as *.encrypted.$stamp.bak; starting a fresh plaintext DB")
        return true
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) throw IllegalStateException("Unexpected EOF reading db header")
            total += read
        }
    }
}
