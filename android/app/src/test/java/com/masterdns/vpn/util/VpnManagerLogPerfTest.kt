package com.masterdns.vpn.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VpnManagerLogPerfTest {

    @Before
    fun setUp() {
        VpnManager.clearLogs()
    }

    @After
    fun tearDown() {
        VpnManager.clearLogs()
    }

    @Test
    fun counterNeedleParity() {
        val batch = listOf(
            "[ERROR] core failed",
            "tunnel ERROR occurred",
            "[error] lower case",
            "[WARN] watch out",
            "got WARNING about resolvers",
            "got warn signal here",
            "errorish happened",
            "WARNINGS: blah",
            "INFO hello world"
        )
        batch.forEach { VpnManager.appendCoreLog(it) }

        val counters = VpnManager.logCounters.value
        assertEquals(9L, counters.total)
        assertEquals(3L, counters.errors)
        assertEquals(3L, counters.warnings)
    }

    @Test
    fun deliveryAndQuietAfterConvergence() = runBlocking {
        repeat(200) { index -> VpnManager.appendCoreLog("INFO perf line $index") }

        withTimeout(5000) {
            VpnManager.logEntries.first { it.size == 200 }
        }

        val emissions = AtomicInteger(0)
        val collector: Job = launch {
            VpnManager.logEntries.collect { emissions.incrementAndGet() }
        }
        try {
            delay(500)
        } finally {
            collector.cancel()
        }
        assertEquals(1, emissions.get())
    }
}
