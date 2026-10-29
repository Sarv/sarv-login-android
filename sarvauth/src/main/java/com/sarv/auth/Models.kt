package com.sarv.auth

import org.json.JSONObject

/** Tokens from Sarv's token endpoint, with the expiry as epoch millis. */
data class SarvTokens(
    val accessToken: String,
    val tokenType: String = "Bearer",
    /** Rotates on every refresh — the SDK stores the new one. */
    val refreshToken: String?,
    /** A statement about the sign-in, not a credential: never send it to an API. */
    val idToken: String?,
    val scope: String?,
    val expiresAtMillis: Long,
) {
    /** True when expired or within [leewayMillis] of expiring. */
    fun isExpired(leewayMillis: Long = 60_000, nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis - nowMillis <= leewayMillis

    /** The signed-in user per the ID token (or the access token). */
    val user: SarvUser?
        get() = (idToken?.let(Jwt::payload) ?: Jwt.payload(accessToken))?.let(SarvUser::fromClaims)

    internal fun toJson(): String = JSONObject()
        .put("accessToken", accessToken)
        .put("tokenType", tokenType)
        .put("refreshToken", refreshToken ?: JSONObject.NULL)
        .put("idToken", idToken ?: JSONObject.NULL)
        .put("scope", scope ?: JSONObject.NULL)
        .put("expiresAtMillis", expiresAtMillis)
        .toString()

    internal companion object {
        fun fromJson(json: String): SarvTokens? = runCatching {
            val o = JSONObject(json)
            SarvTokens(
                accessToken = o.getString("accessToken"),
                tokenType = o.stringOrNull("tokenType") ?: "Bearer",
                refreshToken = o.stringOrNull("refreshToken"),
                idToken = o.stringOrNull("idToken"),
                scope = o.stringOrNull("scope"),
                expiresAtMillis = o.getLong("expiresAtMillis"),
            )
        }.getOrNull()

        /** Wire format of `/api/oauth/token`. */
        fun fromTokenResponse(
            o: JSONObject,
            nowMillis: Long = System.currentTimeMillis(),
            previousRefreshToken: String? = null,
        ): SarvTokens {
            val accessToken = o.stringOrNull("access_token")
                ?: throw SarvAuthException.InvalidResponse("token response had no access_token")
            val expiresIn = if (o.has("expires_in")) o.optDouble("expires_in", 3600.0) else 3600.0
            return SarvTokens(
                accessToken = accessToken,
                tokenType = o.stringOrNull("token_type") ?: "Bearer",
                refreshToken = o.stringOrNull("refresh_token") ?: previousRefreshToken,
                idToken = o.stringOrNull("id_token"),
                scope = o.stringOrNull("scope"),
                expiresAtMillis = nowMillis + (expiresIn * 1000).toLong(),
            )
        }
    }
}

/**
 * Who signed in. [sub] is the stable key to store — it survives email and name
 * changes. Sarv exposes no roles or permissions.
 */
data class SarvUser(
    val sub: String,
    val email: String?,
    val emailVerified: Boolean?,
    val name: String?,
    val profile: String?,
    val phoneNumber: String?,
    val phoneNumberVerified: Boolean?,
) {
    internal companion object {
        fun fromClaims(claims: JSONObject): SarvUser? {
            val sub = claims.stringOrNull("sub") ?: return null
            return SarvUser(
                sub = sub,
                email = claims.stringOrNull("email"),
                emailVerified = claims.booleanOrNull("email_verified"),
                name = claims.stringOrNull("name"),
                profile = claims.stringOrNull("profile"),
                phoneNumber = claims.stringOrNull("phone_number"),
                phoneNumberVerified = claims.booleanOrNull("phone_number_verified"),
            )
        }
    }
}

/** What a successful sign-in returns. */
data class SarvSignInResult(val tokens: SarvTokens, val user: SarvUser?)
