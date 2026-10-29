package com.sarv.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where tokens live between launches. Default: [KeystoreTokenStore]. */
interface SarvTokenStore {
    fun load(): SarvTokens?
    fun save(tokens: SarvTokens)
    fun clear()
}

/**
 * Tokens encrypted with an AES-256-GCM key held in the Android Keystore
 * (never leaves the device), stored in private SharedPreferences, one entry
 * per client id. Unreadable data (e.g. a key wiped by a restore) is dropped.
 */
class KeystoreTokenStore(context: Context, clientId: String) : SarvTokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val entry = "tokens_$clientId"

    @Synchronized
    override fun load(): SarvTokens? {
        val stored = prefs.getString(entry, null) ?: return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            val json = String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
            SarvTokens.fromJson(json)
        }.getOrNull() ?: run { clear(); null }
    }

    @Synchronized
    override fun save(tokens: SarvTokens) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(tokens.toJson().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(entry, Base64.getEncoder().encodeToString(sealed)).apply()
    }

    @Synchronized
    override fun clear() {
        prefs.edit().remove(entry).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "com.sarv.auth.tokens"
        const val KEY_ALIAS = "com.sarv.auth.tokenKey"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** Process-lifetime store, for tests or apps that keep tokens elsewhere. */
class InMemoryTokenStore(private var tokens: SarvTokens? = null) : SarvTokenStore {
    @Synchronized override fun load(): SarvTokens? = tokens
    @Synchronized override fun save(tokens: SarvTokens) { this.tokens = tokens }
    @Synchronized override fun clear() { tokens = null }
}
