package com.masterdns.vpn

import com.masterdns.vpn.util.ResolverAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverAnalyzerTest {
    private val analyzer = ResolverAnalyzer

    @Test
    fun parseEntry_ipv4DefaultPort() {
        val e = analyzer.parseEntry("1.2.3.4")
        assertNotNull(e)
        assertEquals("1.2.3.4", e!!.host)
        assertEquals(53, e.port)
        assertEquals(false, e.hasExplicitPort)
    }

    @Test
    fun parseEntry_ipv4ExplicitPort() {
        val e = analyzer.parseEntry("1.2.3.4:5353")
        assertNotNull(e)
        assertEquals("1.2.3.4", e!!.host)
        assertEquals(5353, e.port)
        assertEquals(true, e.hasExplicitPort)
    }

    @Test
    fun parseEntry_ipv6BracketedWithPort() {
        val e = analyzer.parseEntry("[::1]:53")
        assertNotNull(e)
        assertEquals("::1", e!!.host)
        assertEquals(53, e.port)
    }

    @Test
    fun parseEntry_blankReturnsNull() {
        assertNull(analyzer.parseEntry(""))
    }

    @Test
    fun parseEntry_portOutOfRangeFallsThroughToDefaultHostString() {
        // Characterization of ACTUAL behavior: parseEntry does not reject
        // out-of-range ports as null. For "1.2.3.4:65536" the canHavePort
        // branch fails (65536 not in 1..65535) and execution falls through to
        // ResolverEntry(text, DEFAULT_PORT, false) — i.e. the whole "1.2.3.4:65536"
        // string becomes the host with default port 53 and hasExplicitPort=false.
        // This is the behavior plan 008 (TOML validation) will tighten; lock it in.
        val e = analyzer.parseEntry("1.2.3.4:65536")
        assertNotNull("parseEntry returns non-null for out-of-range port — fall-through to default entry", e)
        assertEquals("1.2.3.4:65536", e!!.host)
        assertEquals(53, e.port)
        assertEquals(false, e.hasExplicitPort)
    }

    @Test
    fun expandCidr_v4Slash30Yields2UsableHosts() {
        val hosts = analyzer.expandCidr("192.168.1.0/30") ?: return
        // /30 -> hostBits=2 -> total=4. For IPv4 prefixBits<31, usableStart=1,
        // usableEndExclusive=total-1=3 -> offsets 1..2 -> 192.168.1.1 and .2.
        assertEquals(2, hosts.size)
    }

    @Test
    fun expandCidr_hostBitsGT16ReturnsEmpty() {
        // hostBits>16 hits the `if (hostBits > 16) return emptyList()` guard
        // in ResolverAnalyzer.kt:220. For IPv4 /8: totalBits=32, prefixBits=8,
        // hostBits=24 (>16) -> empty.
        val hosts = analyzer.expandCidr("10.0.0.0/8")
        assertTrue("expected empty (hostBits=24 > 16 guard)", hosts?.isEmpty() == true)
    }

    @Test
    fun parseIp_normalizes_ipv4() {
        assertEquals("1.2.3.4", analyzer.parseIp("1.2.3.4"))
    }

    @Test
    fun parseIp_rejectsNonNumeric() {
        assertNull(analyzer.parseIp("example.com"))
    }

    @Test
    fun analyzeAndNormalize_truncatedLargeInput() {
        // MAX_IMPORT_BYTES = 2*1024*1024 = 2097152. Each "1.1.1.1\n" is 8 UTF-8
        // bytes (incl. the trailing newline). Use 300_000 repetitions ->
        // 300_000 * 8 = 2_400_000 bytes > 2_097_152 -> early-return with
        // truncated=true and empty normalizedText.
        val big = "1.1.1.1\n".repeat(300_000)
        val result = analyzer.analyzeAndNormalize(big, "big.txt")
        assertNotNull(result)
        assertEquals(true, result!!.stats.truncated)
    }
}
