package com.sarv.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Login with Sarv for Android: OAuth 2.1 authorization code + PKCE against
 * `oauth.sarv.com`, in a Custom Tab.
 *
 * ```kotlin
 * val sarv = SarvAuthClient(context, SarvAuthConfiguration(
 *     clientId = "your-client-id",
 *     redirectUri = "com.example.app://oauth/callback",
 * ))
 * val result = sarv.signIn(activity)
 * val token = sarv.validAccessToken()   // refreshes when needed
 * ```
 *
 * Keep one instance per client id for the app's lifetime so refreshes are
 * serialised (Sarv rotates refresh tokens; replaying a used one revokes the
 * whole authorization).
 */
class SarvAuthClient internal constructor(
    val configuration: SarvAuthConfiguration,
    private val store: SarvTokenStore,
    private val http: OAuthHttp,
    private val prefs: SharedPreferences?,
) {
    @JvmOverloads
    constructor(
        context: Context,
        configuration: SarvAuthConfiguration,
        tokenStore: SarvTokenStore = KeystoreTokenStore(context, configuration.clientId),
    ) : this(
        configuration, tokenStore, OAuthHttp(configuration, UrlConnectionTransport()),
        context.applicationContext.getSharedPreferences("com.sarv.auth.state", Context.MODE_PRIVATE),
    )

    private val refreshMutex = Mutex()
    private val promptLoginKey = "promptLogin_${configuration.clientId}"
    @Volatile private var promptLoginInMemory = false

    /**
     * Set by [signOut]: the next sign-in sends `prompt=login` so Sarv asks for
     * the password instead of reusing the browser's Sarv session.
     */
    internal var promptLoginOnNextSignIn: Boolean
        get() = prefs?.getBoolean(promptLoginKey, false) ?: promptLoginInMemory
        set(value) {
            if (prefs != null) prefs.edit().putBoolean(promptLoginKey, value).apply() else promptLoginInMemory = value
        }

    /** Stored tokens, if any (possibly expired — use [validAccessToken]). */
    val tokens: SarvTokens? get() = runCatching { store.load() }.getOrNull()

    val isSignedIn: Boolean get() = tokens != null

    /** The signed-in user from the stored ID token, without a network call. */
    val currentUser: SarvUser? get() = tokens?.user

    // region Sign in

    /**
     * Opens Sarv's sign-in page in a Custom Tab and completes the flow:
     * `state` check, code exchange, `nonce` check, encrypted storage.
     * Throws [SarvAuthException.Cancelled] if the user closes the tab.
     */
    suspend fun signIn(context: Context): SarvSignInResult {
        val request = AuthorizationRequest(configuration, promptLogin = promptLoginOnNextSignIn)
        val callback = Authorizer.open(context, request.url)
        return completeSignIn(request, callback).also { promptLoginOnNextSignIn = false }
    }

    internal suspend fun completeSignIn(request: AuthorizationRequest, callbackUri: String): SarvSignInResult {
        val code = request.code(callbackUri)
        val response = http.postJson(
            "api/oauth/token",
            JSONObject()
                .put("grant_type", "authorization_code")
                .put("code", code)
                .put("redirect_uri", configuration.redirectUri)
                .put("code_verifier", request.codeVerifier)
                .put("client_id", configuration.clientId),
        )
        val tokens = SarvTokens.fromTokenResponse(response)
        if (configuration.requestsOpenId) {
            Jwt.idTokenProblem(tokens.idToken, configuration.oauthUrl, configuration.clientId, request.nonce)
                ?.let { throw SarvAuthException.InvalidIdToken(it) }
        }
        store.save(tokens)
        return SarvSignInResult(tokens, tokens.user)
    }

    // endregion
    // region Tokens

    /** An access token valid for at least another minute, refreshing first if needed. */
    suspend fun validAccessToken(): String {
        val current = tokens ?: throw SarvAuthException.NotSignedIn()
        if (!current.isExpired()) return current.accessToken
        return refresh().accessToken
    }

    /**
     * Refreshes the tokens if they are expired. Concurrent callers share one
     * refresh. Throws [SarvAuthException.SessionExpired] (and clears the
     * stored tokens) when Sarv rejects the refresh token; keeps them on
     * network errors.
     */
    suspend fun refresh(): SarvTokens = refreshMutex.withLock {
        val current = tokens ?: throw SarvAuthException.NotSignedIn()
        // Another caller may have refreshed while this one waited.
        if (!current.isExpired()) return@withLock current
        val refreshToken = current.refreshToken ?: run {
            store.clear()
            throw SarvAuthException.SessionExpired()
        }
        val response = try {
            http.postJson(
                "api/oauth/token",
                JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", refreshToken)
                    .put("client_id", configuration.clientId),
            )
        } catch (e: SarvAuthException.Server) {
            if (e.status == 400 || e.status == 401) {
                store.clear()
                throw SarvAuthException.SessionExpired()
            }
            throw e
        }
        SarvTokens.fromTokenResponse(response, previousRefreshToken = refreshToken).also(store::save)
    }

    /** The OIDC userinfo profile. */
    suspend fun fetchUser(): SarvUser {
        val claims = http.getJson("api/oauth/userinfo", validAccessToken())
        return SarvUser.fromClaims(claims) ?: throw SarvAuthException.InvalidResponse("userinfo had no `sub`")
    }

    // endregion
    // region Sign out

    enum class TokenTypeHint(internal val wire: String) {
        ACCESS_TOKEN("access_token"),
        REFRESH_TOKEN("refresh_token"),
    }

    /**
     * Revokes one token. `false` means Sarv found nothing to revoke (already
     * expired or rotated) — not an error. Rate limited to 10/min per IP.
     */
    suspend fun revoke(token: String, hint: TokenTypeHint? = null): Boolean {
        val body = JSONObject().put("token", token).put("client_id", configuration.clientId)
        hint?.let { body.put("token_type_hint", it.wire) }
        return http.postJson("api/oauth/revoke", body).optBoolean("revoked", false)
    }

    /**
     * Signs out: revokes the refresh token, then the access token (the refresh
     * token first, so it takes its whole family with it) and clears the stored
     * tokens. Revocation failures don't stop it.
     *
     * With [endSarvSession] (the default) the next sign-in also asks for the
     * password (`prompt=login`), and — when an https
     * [SarvAuthConfiguration.postLogoutRedirectUri] is set and [context] is
     * given — Sarv's sign-out page is opened in a Custom Tab.
     */
    suspend fun signOut(context: Context? = null, endSarvSession: Boolean = true) {
        val current = tokens
        store.clear()
        listOf(current?.refreshToken to TokenTypeHint.REFRESH_TOKEN, current?.accessToken to TokenTypeHint.ACCESS_TOKEN)
            .forEach { (token, hint) ->
                if (token != null) runCatching { revoke(token, hint) }
            }
        if (!endSarvSession) return
        promptLoginOnNextSignIn = true
        val url = logoutUrl()
        if (context != null && url != null) runCatching { Authorizer.open(context, url) }
    }

    /**
     * Sarv's end-session URL, or null without an https
     * [SarvAuthConfiguration.postLogoutRedirectUri] (Sarv won't redirect a
     * custom scheme after sign-out).
     */
    fun logoutUrl(state: String? = null): String? {
        val returnUri = configuration.postLogoutRedirectUri ?: return null
        val params = buildList {
            add("client_id" to configuration.clientId)
            add("post_logout_redirect_uri" to returnUri)
            state?.let { add("state" to it) }
        }
        return configuration.endpoint("api/oauth/logout") + "?" +
            params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
    }

    // endregion
}
