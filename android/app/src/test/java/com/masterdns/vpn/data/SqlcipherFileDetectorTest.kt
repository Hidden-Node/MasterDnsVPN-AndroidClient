package com.masterdns.vpn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.masterdns.vpn.data.local.SqlcipherFileDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SqlcipherFileDetectorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun cleanUpQuarantinedFiles() {
        val parent = context.getDatabasePath("masterdns_vpn.db").parentFile
        parent.listFiles { f -> f.name.contains(".encrypted.") }?.forEach { it.delete() }
    }

    private fun tempDbDir(): File {
        val dir = File(context.cacheDir, "sqlcipher-detector-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun plaintextFile_isNotDetected() {
        val f = File(tempDbDir(), "masterdns_vpn.db")
        f.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(24))
        assertFalse(SqlcipherFileDetector.isSqlcipherFile(f))
    }

    @Test
    fun garbageHeader_isDetected() {
        val f = File(tempDbDir(), "masterdns_vpn.db")
        f.writeBytes(ByteArray(40))
        assertTrue(SqlcipherFileDetector.isSqlcipherFile(f))
    }

    @Test
    fun quarantine_renamesDbAndSidecars_andReturnsTrue() {
        val parent = context.getDatabasePath("masterdns_vpn.db").parentFile
        parent.mkdirs()
        File(parent, "masterdns_vpn.db").writeBytes(ByteArray(40))
        File(parent, "masterdns_vpn.db-wal").writeBytes(ByteArray(16))

        assertTrue(SqlcipherFileDetector.quarantineEncryptedDb(context, "masterdns_vpn.db"))
        assertFalse(File(parent, "masterdns_vpn.db").exists())
        assertFalse(File(parent, "masterdns_vpn.db-wal").exists())
        assertTrue(parent.listFiles().any { it.name.matches(Regex("masterdns_vpn\\.db\\.encrypted\\.\\d+\\.bak")) })
        assertTrue(parent.listFiles().any { it.name.matches(Regex("masterdns_vpn\\.db-wal\\.encrypted\\.\\d+\\.bak")) })
    }

    @Test
    fun quarantine_isNoOpForPlaintext() {
        val parent = context.getDatabasePath("masterdns_vpn.db").parentFile
        parent.mkdirs()
        val f = File(parent, "masterdns_vpn.db")
        f.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(24))

        assertFalse(SqlcipherFileDetector.quarantineEncryptedDb(context, "masterdns_vpn.db"))
        assertTrue(f.exists())
    }
}
