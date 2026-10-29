package com.sarv.auth

import java.net.URI

/**
 * Who the app is, and where Sarv sends the user back. Same defaults and rules
 * as the web SDK (`@sarv-in/login`) and SarvAuth for iOS.
 *
 * @param clientId public OAuth client id from the Sarv developer console.
 * @param redirectUri must match a registered redirect URI exactly (the server
 *   compares the whole string), e.g. `com.example.app:/oauth2redirect` (single
 *   slash, lowercase scheme). Its scheme must also be the app's
 *   `manifestPlaceholders["sarvAuthRedirectScheme"]`.
 * @param postLogoutRedirectUri an `https://` URL on the origin of a registered
 *   redirect URI, or null. Sarv does not redirect to custom schemes after
 *   sign-out, so custom-scheme apps leave it null and [SarvAuthClient.signOut]
 *   makes the next sign-in ask for the password (`prompt=login`) instead.
 */
class SarvAuthConfiguration(
    val clientId: String,
    val redirectUri: String,
    scopes: List<String> = DEFAULT_SCOPES,
    oauthUrl: String = DEFAULT_OAUTH_URL,
    val extraParameters: Map<String, String> = emptyMap(),
    postLogoutRedirectUri: String? = null,
) {
    /** Scopes to request; ask only for what the app uses. */
    val scopes: List<String> = scopes.ifEmpty { DEFAULT_SCOPES }
    val oauthUrl: String = oauthUrl.trimEnd('/')
    val postLogoutRedirectUri: String? = postLogoutRedirectUri

    init {
        if (clientId.isEmpty()) throw SarvAuthException.InvalidConfiguration("`clientId` is required.")
        val uri = runCatching { URI(redirectUri) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty()) {
            throw SarvAuthException.InvalidConfiguration(
                "`redirectUri` needs a scheme, e.g. com.example.app:/oauth2redirect."
            )
        }
        if (uri.scheme != uri.scheme.lowercase()) {
            throw SarvAuthException.InvalidConfiguration("`redirectUri` scheme must be lowercase.")
        }
        if (postLogoutRedirectUri != null && !postLogoutRedirectUri.startsWith("https://")) {
            throw SarvAuthException.InvalidConfiguration(
                "`postLogoutRedirectUri` must be an https:// URL — Sarv does not redirect to custom schemes after sign-out."
            )
        }
        extraParameters.keys.firstOrNull { it in RESERVED_PARAMETERS }?.let {
            throw SarvAuthException.InvalidConfiguration("`extraParameters.$it` is set by the SDK and cannot be overridden.")
        }
    }

    internal val requestsOpenId: Boolean get() = "openid" in scopes

    internal fun endpoint(path: String): String = "$oauthUrl/$path"

    companion object {
        const val DEFAULT_OAUTH_URL = "https://oauth.sarv.com"
        val DEFAULT_SCOPES = listOf("openid", "email", "profile")

        internal val RESERVED_PARAMETERS = setOf(
            "response_type", "client_id", "redirect_uri", "scope", "state",
            "code_challenge", "code_challenge_method", "nonce",
        )

    }
}
