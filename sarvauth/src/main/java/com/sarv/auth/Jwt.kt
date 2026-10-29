package com.sarv.auth

import org.json.JSONObject
import java.util.Base64

/**
 * Reads a JWT payload WITHOUT verifying the signature — only safe for a token
 * that just came from the token endpoint over TLS.
 */
internal object Jwt {

    fun payload(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * null when the ID token is fit to trust (signature aside — it came straight
     * from the token endpoint over TLS), else why not: `iss`, `aud`, `exp` and,
     * when this sign-in sent one, `nonce`.
     */
    fun idTokenProblem(
        idToken: String?,
        issuer: String,
        clientId: String,
        nonce: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): String? {
        if (idToken == null) {
            return if (nonce == null) null else "the token response has no id_token, but this sign-in asked for one."
        }
        val payload = payload(idToken) ?: return "the id_token in the token response could not be decoded."
        if (payload.stringOrNull("iss") != issuer) {
            return "the id_token was issued by `${payload.stringOrNull("iss")}`, not $issuer."
        }
        val audience = payload.optJSONArray("aud")?.let { array -> List(array.length()) { array.optString(it) } }
            ?: listOfNotNull(payload.stringOrNull("aud"))
        if (clientId !in audience) return "the id_token is not for this app (`aud`)."
        val exp = if (payload.has("exp")) payload.optDouble("exp") else Double.NaN
        if (exp.isNaN() || (exp * 1000).toLong() + 60_000 <= nowMillis) return "the id_token has expired."
        if (nonce != null) {
            val claim = payload.stringOrNull("nonce")
            if (claim.isNullOrEmpty()) return "the id_token has no `nonce` claim, but this sign-in sent one."
            if (claim != nonce) {
                return "the id_token's `nonce` does not match this sign-in. The token was minted for a different login."
            }
        }
        return null
    }
}

internal fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
