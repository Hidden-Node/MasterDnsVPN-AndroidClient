package com.masterdns.vpn.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MtuResultsFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty dir returns null within retry budget`() = runBlocking {
        val start = System.currentTimeMillis()
        assertThat(findMtuResultsFile(tmp.root)).isNull()
        assertThat(System.currentTimeMillis() - start).isLessThan(3000)
    }

    @Test
    fun `non-empty match is returned`() = runBlocking {
        val match = tmp.newFile("masterdnsvpn_success_test_123.log")
        match.writeText("1.2.3.4 UP 100 DOWN 200")
        assertThat(findMtuResultsFile(tmp.root)).isEqualTo(match)
    }

    @Test
    fun `zero-length match is ignored`() = runBlocking {
        tmp.newFile("masterdnsvpn_success_test_empty.log")
        assertThat(findMtuResultsFile(tmp.root)).isNull()
    }

    @Test
    fun `non-matching names are ignored`() = runBlocking {
        val other = tmp.newFile("other_results.log")
        other.writeText("data")
        assertThat(findMtuResultsFile(tmp.root)).isNull()
    }

    @Test
    fun `newest by lastModified wins`() = runBlocking {
        val old = tmp.newFile("masterdnsvpn_success_test_old.log")
        old.writeText("old")
        old.setLastModified(System.currentTimeMillis() - 10_000)
        val new = tmp.newFile("masterdnsvpn_success_test_new.log")
        new.writeText("new")
        new.setLastModified(System.currentTimeMillis())
        assertThat(findMtuResultsFile(tmp.root)).isEqualTo(new)
    }
}
