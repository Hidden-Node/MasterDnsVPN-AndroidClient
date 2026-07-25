package com.masterdns.vpn.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Derives a stable SQLCipher passphrase from AndroidKeystore.
 *
 * The keystore-backed AES key never leaves the device; the passphrase fed to
 * SQLCipher is derived deterministically by encrypting a fixed salt under the
 * keystore key. Re-installs lose the keystore entry (and thus the DB)
 * which is the desired failure mode for a credential store: a fresh install
 * starts clean rather than silently downgrading to plaintext.
 *
 * On devices where `AndroidKeystore` is unavailable (very old ROMs, some
 * emulators), this throws; the caller must fall back to opening the DB in
 * plaintext rather than silently corrupting it.
 */
internal object DatabaseEncryptionKey {

    private const val ALIAS = "masterdns_db_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_IV = "0123456789ab" // 12 bytes; fixed is fine here - keystore key is the secret, not the IV

    fun passphrase(context: Context): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val secretKey: SecretKey = (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey(keyStore)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, GCM_IV.toByteArray()))
        return cipher.doFinal("masterdns-sqlcipher-v1".toByteArray())
    }

    private fun generateKey(keyStore: KeyStore): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
