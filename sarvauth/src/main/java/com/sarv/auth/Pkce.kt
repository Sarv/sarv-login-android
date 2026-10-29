package com.sarv.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636), S256 only. Sizes match the web and iOS SDKs. */
internal object Pkce {
    private val random = SecureRandom()

    fun verifier(): String = base64url(randomBytes(32))
    fun state(): String = base64url(randomBytes(16))
    fun nonce(): String = base64url(randomBytes(16))

    /** base64url(SHA-256(verifier)). */
    fun challenge(verifier: String): String =
        base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** RFC 4648 §5: no padding, `-` and `_`. */
    fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)
}
