package com.masterdns.vpn.util

import com.google.common.truth.Truth.assertThat
import com.masterdns.vpn.data.local.ProfileEntity
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class SubscriptionFetcherTest {

    private val sampleProfileUrl1 = ProfileUrlCodec.encode(
        ProfileEntity(
            name = "SubServer1",
            domains = "[\"s1.example.com\"]",
            encryptionKey = "key1",
            encryptionMethod = 1
        )
    )

    private val sampleProfileUrl2 = ProfileUrlCodec.encode(
        ProfileEntity(
            name = "SubServer2",
            domains = "[\"s2.example.com\"]",
            encryptionKey = "key2",
            encryptionMethod = 2
        )
    )

    @Test
    fun parsePayload_plainTextMultipleProfiles_success() {
        val raw = "$sampleProfileUrl1\n$sampleProfileUrl2"
        val result = SubscriptionFetcher.parsePayload(raw, "TestSub")

        assertThat(result.suggestedName).isEqualTo("TestSub")
        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts[0].name).isEqualTo("SubServer1")
        assertThat(result.drafts[1].name).isEqualTo("SubServer2")
    }

    @Test
    fun parsePayload_base64EncodedProfiles_success() {
        val plain = "$sampleProfileUrl1\n$sampleProfileUrl2"
        val base64 = Base64.Default.encode(plain.toByteArray(Charsets.UTF_8))

        val result = SubscriptionFetcher.parsePayload(base64, "Base64Sub")

        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts[0].domains).containsExactly("s1.example.com")
        assertThat(result.drafts[1].domains).containsExactly("s2.example.com")
    }

    @Test
    fun parsePayload_base64EncodedProfilesWithWhitespaceAndSpaces_success() {
        val plain = "$sampleProfileUrl1\n$sampleProfileUrl2"
        val base64 = Base64.Default.encode(plain.toByteArray(Charsets.UTF_8))
        val base64WithSpaces = "  " + base64.chunked(16).joinToString("  \n  ") + " \r\n "

        val result = SubscriptionFetcher.parsePayload(base64WithSpaces, "SpacedSub")

        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts[0].domains).containsExactly("s1.example.com")
        assertThat(result.drafts[1].domains).containsExactly("s2.example.com")
    }

    @Test
    fun parsePayload_urlSafeBase64EncodedProfiles_success() {
        val plain = "$sampleProfileUrl1\n$sampleProfileUrl2"
        val base64 = Base64.UrlSafe.encode(plain.toByteArray(Charsets.UTF_8))

        val result = SubscriptionFetcher.parsePayload(base64, "UrlSafeSub")

        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts[0].domains).containsExactly("s1.example.com")
        assertThat(result.drafts[1].domains).containsExactly("s2.example.com")
    }

    @Test
    fun parsePayload_emptyOrBlank_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionFetcher.parsePayload("   ", "EmptySub")
        }
    }

    @Test
    fun parsePayload_noValidLinks_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionFetcher.parsePayload("some random non-link text", "InvalidSub")
        }
    }

    @Test
    fun parsePayload_with_comments_and_bom_succeeds() {
        val plain = "\uFEFF# Header comment\n// Secondary comment\n$sampleProfileUrl1\nsome-junk-line\n$sampleProfileUrl2"
        val result = SubscriptionFetcher.parsePayload(plain, "ResilientSub")
        assertThat(result.drafts).hasSize(2)
        assertThat(result.drafts[0].name).isEqualTo("SubServer1")
        assertThat(result.drafts[1].name).isEqualTo("SubServer2")
    }
}
