package com.masterdns.vpn.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.masterdns.vpn.data.local.ProfileEntity
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class ProfileUrlCodecTest {

    @Test
    fun encode_and_decode_round_trip() {
        val original = ProfileEntity(
            name = "Test Server",
            domains = "[\"vpn.example.com\"]",
            encryptionKey = "secret-key-123",
            encryptionMethod = 2
        )

        val encodedUrl = ProfileUrlCodec.encode(original)
        assertThat(encodedUrl).startsWith("masterdns://")

        val decoded = ProfileUrlCodec.decode(encodedUrl)
        assertThat(decoded.name).isEqualTo("Test Server")
        assertThat(decoded.domains).containsExactly("vpn.example.com")
        assertThat(decoded.encryptionKey).isEqualTo("secret-key-123")
        assertThat(decoded.encryptionMethod).isEqualTo(2)

        val entity = decoded.toProfileEntity("1.1.1.1")
        assertThat(entity.name).isEqualTo("Test Server")
        assertThat(entity.domains).isEqualTo("[\"vpn.example.com\"]")
        assertThat(entity.encryptionKey).isEqualTo("secret-key-123")
        assertThat(entity.encryptionMethod).isEqualTo(2)
        assertThat(entity.resolvers).isEqualTo("1.1.1.1")
    }

    @Test
    fun encode_blank_name_falls_back_to_domain() {
        val original = ProfileEntity(
            name = "   ",
            domains = "[\"node1.example.org.\"]",
            encryptionKey = "key-abc",
            encryptionMethod = 1
        )

        val encoded = ProfileUrlCodec.encode(original)
        val decoded = ProfileUrlCodec.decode(encoded)
        assertThat(decoded.name).isEqualTo("node1.example.org")
        assertThat(decoded.domains).containsExactly("node1.example.org")
    }

    @Test
    fun decode_supported_alternative_schemes() {
        val original = ProfileEntity(
            name = "Node",
            domains = "[\"node.com\"]",
            encryptionKey = "key1",
            encryptionMethod = 0
        )
        val rawEncoded = ProfileUrlCodec.encode(original)
        val payload = rawEncoded.removePrefix("masterdns://")

        val stormUrl = "stormdns://$payload"
        val cottenUrl = "cottendns://$payload"

        val stormDecoded = ProfileUrlCodec.decode(stormUrl)
        assertThat(stormDecoded.name).isEqualTo("Node")

        val cottenDecoded = ProfileUrlCodec.decode(cottenUrl)
        assertThat(cottenDecoded.name).isEqualTo("Node")
    }

    @Test
    fun decode_supported_alternative_schemas() {
        for (schema in listOf("masterdns.profile", "stormdns.profile")) {
            val json = JsonObject().apply {
                addProperty("schema", schema)
                addProperty("version", 1)
                add("profile", JsonObject().apply {
                    addProperty("name", "Schema Test")
                    add("server", JsonObject().apply {
                        addProperty("domain", "schema.example.com")
                        addProperty("encryption_key", "schema-key")
                        addProperty("encryption_method", 3)
                    })
                })
            }
            val base64 = Base64.UrlSafe.encode(json.toString().toByteArray())
            val decoded = ProfileUrlCodec.decode("masterdns://$base64")
            assertThat(decoded.name).isEqualTo("Schema Test")
            assertThat(decoded.domains).containsExactly("schema.example.com")
            assertThat(decoded.encryptionKey).isEqualTo("schema-key")
            assertThat(decoded.encryptionMethod).isEqualTo(3)
        }
    }

    @Test
    fun decode_handles_padded_and_unpadded_base64() {
        val json = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject().apply {
                addProperty("name", "Padding")
                add("server", JsonObject().apply {
                    addProperty("domain", "pad.com")
                    addProperty("encryption_key", "pad-key")
                    addProperty("encryption_method", 1)
                })
            })
        }
        val bytes = json.toString().toByteArray()

        val padded = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT).encode(bytes)
        val unpadded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

        val decodedPadded = ProfileUrlCodec.decode("masterdns://$padded")
        val decodedUnpadded = ProfileUrlCodec.decode("masterdns://$unpadded")

        assertThat(decodedPadded.name).isEqualTo("Padding")
        assertThat(decodedUnpadded.name).isEqualTo("Padding")
    }

    @Test
    fun decode_handles_standard_base64() {
        val json = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject().apply {
                addProperty("name", "Standard Base64")
                add("server", JsonObject().apply {
                    addProperty("domain", "std.com")
                    addProperty("encryption_key", "std-key")
                    addProperty("encryption_method", 1)
                })
            })
        }
        val bytes = json.toString().toByteArray()
        val standard = Base64.Default.encode(bytes)

        val decoded = ProfileUrlCodec.decode("masterdns://$standard")
        assertThat(decoded.name).isEqualTo("Standard Base64")
    }

    @Test
    fun decode_ignores_query_and_fragment() {
        val original = ProfileEntity(
            name = "Query Test",
            domains = "[\"query.com\"]",
            encryptionKey = "key-q",
            encryptionMethod = 1
        )
        val base = ProfileUrlCodec.encode(original)
        val withQueryAndHash = "$base?tracker=123#header"

        val decoded = ProfileUrlCodec.decode(withQueryAndHash)
        assertThat(decoded.name).isEqualTo("Query Test")
        assertThat(decoded.domains).containsExactly("query.com")
    }

    @Test
    fun decode_invalid_links_throw() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("not-a-link")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("http://example.com/profile")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("masterdns://")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("masterdns://!!not-valid-base64!!")
        }
    }

    @Test
    fun decode_invalid_content_throws() {
        val notJson = Base64.UrlSafe.encode("plain text".toByteArray())
        assertThrows(Exception::class.java) {
            ProfileUrlCodec.decode("masterdns://$notJson")
        }

        val badSchemaJson = JsonObject().apply {
            addProperty("schema", "unsupported.schema")
            addProperty("version", 1)
        }
        val badSchema = Base64.UrlSafe.encode(badSchemaJson.toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("masterdns://$badSchema")
        }

        val missingServerJson = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject())
        }
        val missingServer = Base64.UrlSafe.encode(missingServerJson.toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("masterdns://$missingServer")
        }

        val missingKeyJson = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject().apply {
                add("server", JsonObject().apply {
                    addProperty("domain", "a.com")
                })
            })
        }
        val missingKey = Base64.UrlSafe.encode(missingKeyJson.toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decode("masterdns://$missingKey")
        }
    }

    @Test
    fun encode_validation_throws() {
        val noDomain = ProfileEntity(
            name = "Test",
            domains = "[]",
            encryptionKey = "key"
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.encode(noDomain)
        }

        val noKey = ProfileEntity(
            name = "Test",
            domains = "[\"domain.com\"]",
            encryptionKey = "  "
        )
        assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.encode(noKey)
        }
    }
}
