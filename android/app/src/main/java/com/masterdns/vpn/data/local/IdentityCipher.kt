package com.masterdns.vpn.data.local

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts identity fields for locked profile export.
 * This deters casual disclosure in shared TOML files; it is not a DRM boundary.
 */
object IdentityCipher {
    private const val PREFIX = "ENC:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val keyBytes: ByteArray by lazy {
        val a = byteArrayOf(
            0x4D, 0x61, 0x73, 0x74, 0x65, 0x72, 0x44, 0x6E,
            0x73, 0x56, 0x50, 0x4E, 0x2D, 0x47, 0x47, 0x2D
        )
        val b = byteArrayOf(
            0x4C, 0x6F, 0x63, 0x6B, 0x65, 0x64, 0x49, 0x64,
            0x65, 0x6E, 0x74, 0x69, 0x74, 0x79, 0x4B, 0x31
        )
        a + b
    }

    private fun keySpec() = SecretKeySpec(keyBytes, "AES")

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? {
        if (!isEncrypted(encoded)) return null
        return runCatching {
            val combined = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size <= IV_BYTES) return null
            val iv = combined.copyOfRange(0, IV_BYTES)
            val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
