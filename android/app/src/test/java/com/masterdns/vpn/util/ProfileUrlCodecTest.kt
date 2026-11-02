package com.masterdns.vpn.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    @Test
    fun decodeMultiple_single_and_multi_line_succeeds() {
        val p1 = ProfileEntity(name = "P1", domains = "[\"p1.com\"]", encryptionKey = "k1", encryptionMethod = 1)
        val p2 = ProfileEntity(name = "P2", domains = "[\"p2.com\"]", encryptionKey = "k2", encryptionMethod = 2)
        val url1 = ProfileUrlCodec.encode(p1)
        val url2 = ProfileUrlCodec.encode(p2)

        val multiRaw = "$url1\n\n$url2\n"
        val drafts = ProfileUrlCodec.decodeMultiple(multiRaw)

        assertThat(drafts).hasSize(2)
        assertThat(drafts[0].name).isEqualTo("P1")
        assertThat(drafts[0].domains).containsExactly("p1.com")
        assertThat(drafts[1].name).isEqualTo("P2")
        assertThat(drafts[1].domains).containsExactly("p2.com")
    }

    @Test
    fun decodeMultiple_with_invalid_line_preserves_actual_line_number() {
        val p1 = ProfileEntity(name = "P1", domains = "[\"p1.com\"]", encryptionKey = "k1", encryptionMethod = 1)
        val url1 = ProfileUrlCodec.encode(p1)
        val multiRaw = "$url1\n\ninvalid-link"

        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileUrlCodec.decodeMultiple(multiRaw)
        }
        assertThat(error.message).contains("Line 3")
    }

    @Test
    fun encode_and_decode_multi_domain_round_trip() {
        val original = ProfileEntity(
            name = "Cluster Server",
            domains = "[\"node1.example.com\", \"node2.example.com\", \"node3.example.com\"]",
            encryptionKey = "cluster-secret",
            encryptionMethod = 2
        )

        val encodedUrl = ProfileUrlCodec.encode(original)
        assertThat(encodedUrl).startsWith("masterdns://")

        val decoded = ProfileUrlCodec.decode(encodedUrl)
        assertThat(decoded.name).isEqualTo("Cluster Server")
        assertThat(decoded.domains).containsExactly(
            "node1.example.com",
            "node2.example.com",
            "node3.example.com"
        ).inOrder()
        assertThat(decoded.encryptionKey).isEqualTo("cluster-secret")
        assertThat(decoded.encryptionMethod).isEqualTo(2)

        val entity = decoded.toProfileEntity()
        assertThat(entity.name).isEqualTo("Cluster Server")
        assertThat(entity.domains).isEqualTo("[\"node1.example.com\",\"node2.example.com\",\"node3.example.com\"]")
    }

    @Test
    fun decode_legacy_single_domain_payload_without_domains_array() {
        val json = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject().apply {
                addProperty("name", "Legacy Node")
                add("server", JsonObject().apply {
                    addProperty("domain", "legacy.example.com")
                    addProperty("encryption_key", "legacy-key")
                    addProperty("encryption_method", 1)
                })
            })
        }
        val base64 = Base64.UrlSafe.encode(json.toString().toByteArray())
        val decoded = ProfileUrlCodec.decode("masterdns://$base64")

        assertThat(decoded.name).isEqualTo("Legacy Node")
        assertThat(decoded.domains).containsExactly("legacy.example.com")
    }

    @Test
    fun decode_delimited_domains_string_in_legacy_domain_field() {
        val json = JsonObject().apply {
            addProperty("schema", "masterdns.profile")
            addProperty("version", 1)
            add("profile", JsonObject().apply {
                addProperty("name", "Delimited Node")
                add("server", JsonObject().apply {
                    addProperty("domain", "d1.com, d2.com; d3.com\nd4.com")
                    addProperty("encryption_key", "delim-key")
                    addProperty("encryption_method", 1)
                })
            })
        }
        val base64 = Base64.UrlSafe.encode(json.toString().toByteArray())
        val decoded = ProfileUrlCodec.decode("masterdns://$base64")

        assertThat(decoded.domains).containsExactly("d1.com", "d2.com", "d3.com", "d4.com").inOrder()
    }

    @Test
    fun encode_writes_both_domain_and_domains_fields() {
        val original = ProfileEntity(
            name = "Dual Field Test",
            domains = "[\"first.com\", \"second.com\"]",
            encryptionKey = "key123",
            encryptionMethod = 1
        )
        val encodedUrl = ProfileUrlCodec.encode(original)
        val payload = encodedUrl.removePrefix("masterdns://")
        val base64Decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        val jsonString = String(base64Decoder.decode(payload), Charsets.UTF_8)
        val root = JsonParser.parseString(jsonString).asJsonObject
        val serverObj = root.getAsJsonObject("profile").getAsJsonObject("server")

        assertThat(serverObj.get("domain").asString).isEqualTo("first.com")
        val domainsArray = serverObj.getAsJsonArray("domains")
        assertThat(domainsArray).isNotNull()
        assertThat(domainsArray.size()).isEqualTo(2)
        assertThat(domainsArray[0].asString).isEqualTo("first.com")
        assertThat(domainsArray[1].asString).isEqualTo("second.com")
    }
}
