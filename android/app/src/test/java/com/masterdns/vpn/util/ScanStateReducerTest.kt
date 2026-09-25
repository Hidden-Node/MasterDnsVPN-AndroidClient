package com.masterdns.vpn.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanStateReducerTest {

    private val empty = ScanStateBundle()

    @Test
    fun emptyLineReturnsPrevUnchanged() {
        val result = ScanStateReducer.reduce(empty, "")
        assertEquals(empty, result)
    }

    @Test
    fun nonMatchingLineReturnsPrevUnchanged() {
        val result = ScanStateReducer.reduce(empty, "INFO: foo bar baz")
        assertEquals(empty, result)
    }

    @Test
    fun indexedProgressUpdatesScanTotalFromCore() {
        val result = ScanStateReducer.reduce(empty, "scan progress: 12/50")
        assertEquals(50, result.scanStatus.scanTotalFromCore)
    }

    @Test
    fun indexedProgressWithNonNumericTotalLeavesPrevUnchanged() {
        val result = ScanStateReducer.reduce(empty, "scan progress: 12/abc")
        assertEquals(empty, result)
    }

    @Test
    fun scanTotalsAcceptedPopulatesAcceptedDecision() {
        val result = ScanStateReducer.reduce(
            empty,
            "via 8.8.8.8:53 | totals: valid=2, rejected=1, Accepted"
        )
        assertEquals("8.8.8.8:53", result.scanStatus.lastResolver)
        assertEquals(2, result.scanStatus.validCount)
        assertEquals(1, result.scanStatus.rejectedCount)
        assertEquals("Accepted", result.scanStatus.lastDecision)
        assertTrue(result.scanStatus.scanning)
    }

    @Test
    fun scanTotalsRejectedPopulatesRejectedDecision() {
        val result = ScanStateReducer.reduce(
            empty,
            "via 8.8.8.8:53 | totals: valid=2, rejected=1, Rejected"
        )
        assertEquals("Rejected", result.scanStatus.lastDecision)
        assertTrue(result.scanStatus.scanning)
    }

    @Test
    fun resolverAddedIsIdempotent() {
        val once = ScanStateReducer.reduce(empty, "✅ Accepted 8.8.8.8:53")
        assertEquals(listOf("8.8.8.8:53"), once.activeResolvers)
        val twice = ScanStateReducer.reduce(once, "✅ Accepted 8.8.8.8:53")
        assertEquals(listOf("8.8.8.8:53"), twice.activeResolvers)
    }

    @Test
    fun resolverRemovedDropsEntry() {
        val prev = ScanStateBundle(activeResolvers = listOf("8.8.8.8:53"))
        val result = ScanStateReducer.reduce(prev, "DNS Resolver disabled 8.8.8.8:53")
        assertTrue(result.activeResolvers.isEmpty())
    }

    @Test
    fun testingMtuSizesSetsScanningTrue() {
        val result = ScanStateReducer.reduce(empty, "INFO Testing MTU sizes for resolver 8.8.8.8")
        assertTrue(result.scanStatus.scanning)
    }

    @Test
    fun mtuTestingCompletedSetsScanningFalse() {
        val prev = ScanStateBundle(scanStatus = VpnManager.ScanStatus(scanning = true))
        val result = ScanStateReducer.reduce(prev, "MTU Testing Completed")
        assertEquals(false, result.scanStatus.scanning)
    }

    @Test
    fun sessionInitializedSetsScanningFalse() {
        val prev = ScanStateBundle(scanStatus = VpnManager.ScanStatus(scanning = true))
        val result = ScanStateReducer.reduce(prev, "Session Initialized Successfully")
        assertEquals(false, result.scanStatus.scanning)
    }

    @Test
    fun sessionInitBackoffSetsConnectionWarning() {
        val result = ScanStateReducer.reduce(empty, "Session init retry backoff: 1.5s")
        assertEquals("Session init retry backoff: 1.5s", result.connectionWarning)
    }

    @Test
    fun scanTotalsPreemptsActiveResolversOnSameLine() {
        val prev = ScanStateBundle(scanStatus = VpnManager.ScanStatus(activeResolvers = 0))
        val result = ScanStateReducer.reduce(
            prev,
            "via 8.8.8.8:53 | totals: valid=2, rejected=1, Accepted Active Resolvers: 5"
        )
        assertEquals(0, result.scanStatus.activeResolvers)
        assertEquals("8.8.8.8:53", result.scanStatus.lastResolver)
        assertEquals(2, result.scanStatus.validCount)
    }

    @Test
    fun syncedMtuUpdatesBothFields() {
        val result = ScanStateReducer.reduce(
            empty,
            "Selected Synced Upload MTU: 1280 | Selected Synced Download MTU: 1400"
        )
        assertEquals(1280, result.scanStatus.syncedUploadMtu)
        assertEquals(1400, result.scanStatus.syncedDownloadMtu)
    }

    @Test
    fun guardPlainChattyCoreOutputReturnsPrevUnchanged() {
        val prev = ScanStateBundle()
        assertEquals(prev, ScanStateReducer.reduce(prev, "INFO: heartbeat tick ok"))
        assertEquals(prev, ScanStateReducer.reduce(prev, "DEBUG dns query succeeded"))
        assertEquals(prev, ScanStateReducer.reduce(prev, "INFO upstream endpoint ready"))
    }

    @Test
    fun guardMtuNoiseWithoutPatternReturnsPrevUnchanged() {
        val prev = ScanStateBundle()
        val result = ScanStateReducer.reduce(prev, "INFO MTU probe size 1234 bytes done")
        assertEquals(prev, result)
    }

    @Test
    fun guardSlashNoiseWithoutProgressReturnsPrevUnchanged() {
        val prev = ScanStateBundle()
        val result = ScanStateReducer.reduce(prev, "INFO a/b experiment enabled")
        assertEquals(prev, result)
    }

    @Test
    fun guardKeywordFamiliesStillClassify() {
        assertEquals(
            7,
            ScanStateReducer.reduce(empty, "total active: 7").scanStatus.activeResolvers
        )
        assertEquals(
            4,
            ScanStateReducer.reduce(empty, "remaining: 4").scanStatus.activeResolvers
        )
        assertEquals(
            12,
            ScanStateReducer.reduce(empty, "total resolvers 12").scanStatus.scanTotalFromCore
        )
    }
}
