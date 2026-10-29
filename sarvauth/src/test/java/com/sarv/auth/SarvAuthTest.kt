package com.sarv.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class PkceTest {
    @Test fun challengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun sizesMatchWebSdk() {
        assertEquals(43, Pkce.verifier().length)
        assertEquals(22, Pkce.state().length)
        assertEquals(22, Pkce.nonce().length)
        assertNotEquals(Pkce.state(), Pkce.state())
    }

    @Test fun base64urlHasNoPadding() {
        assertEquals("-__-", Pkce.base64url(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte())))
    }
}

class ConfigurationTest {
    @Test fun defaults() {
        val config = testConfig()
        assertEquals(listOf("openid", "email", "profile"), config.scopes)
        assertEquals("https://oauth.sarv.com", config.oauthUrl)
        assertNull(config.postLogoutRedirectUri)
    }

    @Test(expected = SarvAuthException.InvalidConfiguration::class)
    fun rejectsUppercaseScheme() { SarvAuthConfiguration("c", "Com.Sarv:/cb") }

    @Test(expected = SarvAuthException.InvalidConfiguration::class)
    fun rejectsCustomSchemePostLogout() { SarvAuthConfiguration("c", "com.sarv:/cb", postLogoutRedirectUri = "com.sarv:/out") }

    @Test(expected = SarvAuthException.InvalidConfiguration::class)
    fun rejectsReservedExtraParameter() { testConfig(mapOf("state" to "x")) }

    @Test(expected = SarvAuthException.InvalidConfiguration::class)
    fun rejectsEmptyClientId() { SarvAuthConfiguration("", "a://b") }
}

class AuthorizationRequestTest {
    @Test fun authorizeUrl() {
        val request = AuthorizationRequest(testConfig(mapOf("prompt" to "login")), state = "s1", codeVerifier = "v".repeat(43), nonce = "n1")
        val q = AuthorizationRequest.queryParameters(request.url)
        assertTrue(request.url.startsWith("https://oauth.sarv.com/api/oauth/authorize?"))
        assertEquals("code", q["response_type"])
        assertEquals("client-1", q["client_id"])
        assertEquals("com.sarv.test:/oauth2redirect", q["redirect_uri"])
        assertEquals("openid email profile", q["scope"])
        assertEquals("s1", q["state"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(Pkce.challenge("v".repeat(43)), q["code_challenge"])
        assertEquals("n1", q["nonce"])
        assertEquals("login", q["prompt"])
    }

    @Test fun promptLoginAfterSignOut() {
        val request = AuthorizationRequest(testConfig(), promptLogin = true)
        assertEquals("login", AuthorizationRequest.queryParameters(request.url)["prompt"])
    }

    @Test fun noNonceWithoutOpenId() {
        assertNull(AuthorizationRequest(SarvAuthConfiguration("c", "a://b", scopes = listOf("email"))).nonce)
    }

    @Test fun callbackChecks() {
        val request = AuthorizationRequest(testConfig(), state = "good")
        assertEquals("abc", request.code("com.sarv.test:/oauth2redirect?code=abc&state=good"))
        assertThrows<SarvAuthException.StateMismatch> { request.code("com.sarv.test:/oauth2redirect?code=abc&state=bad") }
        assertThrows<SarvAuthException.MissingCode> { request.code("com.sarv.test:/oauth2redirect?state=good") }
        val failed = assertThrows<SarvAuthException.AuthorizationFailed> {
            request.code("com.sarv.test:/oauth2redirect?error=access_denied&error_description=No")
        }
        assertEquals("access_denied", failed.error)
        assertEquals("No", failed.description)
    }
}

class ClientTest {
    @Test fun signInExchangesCodeChecksNonceAndStores() = runBlocking {
        val store = InMemoryTokenStore()
        var sent: JSONObject? = null
        val client = testClient(store) { request ->
            assertTrue(request.url.endsWith("/api/oauth/token"))
            assertEquals("application/json", request.headers["Content-Type"])
            sent = JSONObject(request.body!!)
            HttpResponse(200, tokenJson(nonce = "nonce-1"))
        }
        val request = AuthorizationRequest(client.configuration, state = "st", nonce = "nonce-1")
        val result = client.completeSignIn(request, "com.sarv.test:/oauth2redirect?code=c1&state=st")

        assertEquals("authorization_code", sent!!.getString("grant_type"))
        assertEquals("c1", sent!!.getString("code"))
        assertEquals(request.codeVerifier, sent!!.getString("code_verifier"))
        assertEquals("client-1", sent!!.getString("client_id"))
        assertEquals("user-1", result.user?.sub)
        assertEquals("a@sarv.com", result.user?.email)
        assertEquals("r1", store.load()?.refreshToken)
    }

    @Test fun signInRejectsWrongNonce() = runBlocking {
        val store = InMemoryTokenStore()
        val client = testClient(store) { HttpResponse(200, tokenJson(nonce = "someone-else")) }
        val request = AuthorizationRequest(client.configuration, state = "st", nonce = "mine")
        assertThrowsSuspend<SarvAuthException.InvalidIdToken> {
            client.completeSignIn(request, "com.sarv.test:/oauth2redirect?code=c&state=st")
        }
        assertNull(store.load())
    }

    @Test fun signInRejectsIdTokenForAnotherAppOrIssuerOrExpired() = runBlocking {
        val callback = "com.sarv.test:/oauth2redirect?code=c&state=st"
        for (override in listOf(mapOf("aud" to "other-app"), mapOf("iss" to "https://evil.example"), mapOf<String, Any>("exp" to 1000))) {
            val client = testClient(InMemoryTokenStore()) { HttpResponse(200, tokenJson(nonce = "n", overrides = override)) }
            val request = AuthorizationRequest(client.configuration, state = "st", nonce = "n")
            assertThrowsSuspend<SarvAuthException.InvalidIdToken> { client.completeSignIn(request, callback) }
        }
    }

    @Test fun signOutRevokesInOrderClearsAndForcesPasswordNextTime() = runBlocking {
        val store = InMemoryTokenStore(
            SarvTokens(accessToken = "a", refreshToken = "r", idToken = null, scope = null, expiresAtMillis = Long.MAX_VALUE)
        )
        val hints = mutableListOf<String>()
        val client = testClient(store) { request ->
            hints += JSONObject(request.body!!).getString("token_type_hint")
            HttpResponse(200, """{"revoked":true}""")
        }
        client.signOut()
        assertEquals(listOf("refresh_token", "access_token"), hints)
        assertNull(store.load())
        assertTrue(client.promptLoginOnNextSignIn)
        assertNull(client.logoutUrl())
    }

    @Test fun serverDetailAndRateLimit() = runBlocking {
        var status = 400
        val client = testClient(InMemoryTokenStore()) {
            HttpResponse(status, if (status == 400) """{"detail":"Bad code"}""" else """{"detail":"Slow down"}""")
        }
        val request = AuthorizationRequest(client.configuration, state = "st")
        val callback = "com.sarv.test:/oauth2redirect?code=c&state=st"
        val server = assertThrowsSuspend<SarvAuthException.Server> { client.completeSignIn(request, callback) }
        assertEquals(400, server.status)
        assertEquals("Bad code", server.detail)
        status = 429
        val limited = assertThrowsSuspend<SarvAuthException.RateLimited> { client.completeSignIn(request, callback) }
        assertEquals("Slow down", limited.detail)
    }

    @Test fun concurrentCallersShareOneRefreshAndRotationIsStored() = runBlocking {
        val store = InMemoryTokenStore(expired(refreshToken = "r-old"))
        val calls = AtomicInteger()
        val client = testClient(store) { request ->
            calls.incrementAndGet()
            val body = JSONObject(request.body!!)
            assertEquals("refresh_token", body.getString("grant_type"))
            assertEquals("r-old", body.getString("refresh_token"))
            Thread.sleep(200)
            HttpResponse(200, """{"access_token":"new","token_type":"Bearer","expires_in":3600,"refresh_token":"r-new"}""")
        }
        val tokens = List(3) { async { client.validAccessToken() } }.awaitAll()
        assertEquals(listOf("new", "new", "new"), tokens)
        assertEquals(1, calls.get())
        assertEquals("r-new", store.load()?.refreshToken)
    }

    @Test fun rejectedRefreshClearsSession() = runBlocking {
        val store = InMemoryTokenStore(expired(refreshToken = "r"))
        val client = testClient(store) { HttpResponse(400, """{"detail":"Invalid refresh token"}""") }
        assertThrowsSuspend<SarvAuthException.SessionExpired> { client.validAccessToken() }
        assertNull(store.load())
    }

    @Test fun refreshNetworkErrorKeepsTokens() = runBlocking {
        val store = InMemoryTokenStore(expired(refreshToken = "r"))
        val client = testClient(store) { throw IOException("offline") }
        assertThrowsSuspend<SarvAuthException.Network> { client.validAccessToken() }
        assertNotNull(store.load())
    }

    @Test fun revokeAndLogoutUrl() = runBlocking {
        val client = testClient(InMemoryTokenStore()) { request ->
            assertTrue(request.url.endsWith("/api/oauth/revoke"))
            assertEquals("refresh_token", JSONObject(request.body!!).getString("token_type_hint"))
            HttpResponse(200, """{"revoked":true}""")
        }
        assertTrue(client.revoke("r", SarvAuthClient.TokenTypeHint.REFRESH_TOKEN))

        val httpsConfig = SarvAuthConfiguration(
            "client-1", "com.sarv.test:/oauth2redirect", postLogoutRedirectUri = "https://app.example.com/signed-out",
        )
        val https = SarvAuthClient(httpsConfig, InMemoryTokenStore(), OAuthHttp(httpsConfig) { error("unused") }, null)
        val q = AuthorizationRequest.queryParameters(https.logoutUrl(state = "s")!!)
        assertEquals("client-1", q["client_id"])
        assertEquals("https://app.example.com/signed-out", q["post_logout_redirect_uri"])
        assertEquals("s", q["state"])
    }
}

// region Helpers

internal fun testConfig(extra: Map<String, String> = emptyMap()) = SarvAuthConfiguration(
    clientId = "client-1",
    redirectUri = "com.sarv.test:/oauth2redirect",
    extraParameters = extra,
)

private fun testClient(store: SarvTokenStore, transport: HttpTransport): SarvAuthClient {
    val config = testConfig()
    return SarvAuthClient(config, store, OAuthHttp(config, transport), null)
}

private fun expired(refreshToken: String) = SarvTokens(
    accessToken = "old", refreshToken = refreshToken, idToken = null, scope = null,
    expiresAtMillis = System.currentTimeMillis() - 10_000,
)

private fun jwt(claims: JSONObject): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    return enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray()) + "." +
        enc.encodeToString(claims.toString().toByteArray()) + ".sig"
}

private fun tokenJson(nonce: String, overrides: Map<String, Any> = emptyMap()): String {
    val claims = JSONObject()
        .put("iss", "https://oauth.sarv.com").put("aud", "client-1")
        .put("exp", System.currentTimeMillis() / 1000 + 3600)
        .put("sub", "user-1").put("email", "a@sarv.com").put("nonce", nonce)
    overrides.forEach { (k, v) -> claims.put(k, v) }
    val idToken = jwt(claims)
    return JSONObject()
        .put("access_token", "a1").put("token_type", "Bearer").put("expires_in", 3600)
        .put("refresh_token", "r1").put("id_token", idToken).put("scope", "openid email profile")
        .toString()
}

private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try { block() } catch (e: Throwable) {
        if (e is T) return e
        fail("expected ${T::class.simpleName}, got $e")
    }
    fail("expected ${T::class.simpleName}")
    throw IllegalStateException()
}

private suspend inline fun <reified T : Throwable> assertThrowsSuspend(block: () -> Unit): T {
    try { block() } catch (e: Throwable) {
        if (e is T) return e
        fail("expected ${T::class.simpleName}, got $e")
    }
    fail("expected ${T::class.simpleName}")
    throw IllegalStateException()
}

// endregion
