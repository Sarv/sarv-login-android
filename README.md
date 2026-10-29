# Login with Sarv for Android

Add **Login with Sarv** to an Android app. Sign-in uses OAuth 2.1 with
Authorization Code + PKCE and OpenID Connect, following
[RFC 8252 — OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252).

There is no Sarv-specific SDK to install. `oauth.sarv.com` is a standards-based
authorization server with a published discovery document, so the OpenID
Foundation's reference client, [AppAuth for Android](https://github.com/openid/AppAuth-Android),
does the hard parts: it opens the system browser and generates PKCE, `state` and
`nonce`. It also checks `state` and receives the redirect. This guide covers
everything around that, end to end.

> Building for iOS? See [sarv-login-ios](https://github.com/Sarv/sarv-login-ios).
> For the web, use [sarv-login-js](https://github.com/Sarv/sarv-login-js).

---

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Endpoints](#endpoints)
- [1. Register a public app](#1-register-a-public-app)
- [2. Add AppAuth](#2-add-appauth)
- [3. Claim the redirect URI](#3-claim-the-redirect-uri)
- [4. Start sign-in](#4-start-sign-in)
- [5. Exchange the code](#5-exchange-the-code)
- [6. Store tokens safely](#6-store-tokens-safely)
- [7. Refresh the access token](#7-refresh-the-access-token)
- [8. Call Sarv APIs](#8-call-sarv-apis)
- [9. Sign out](#9-sign-out)
- [The button](#the-button)
- [Troubleshooting](#troubleshooting)
- [Security checklist](#security-checklist)

---

## How it works

```
 App                    Custom Tab (system browser)        oauth.sarv.com           Your backend
  |  1. build request         |                                  |                        |
  |  (PKCE, state, nonce)     |                                  |                        |
  |-------------------------->|  2. /api/oauth/authorize ------->|                        |
  |                           |     user signs in + consents     |                        |
  |<--------------------------|  3. com.example.app:/oauth2redirect?code=...&state=...   |
  |  4. AppAuth checks state  |                                  |                        |
  |------------------------------------------------- 5. code + code_verifier + nonce ---->|
  |                                                              |<-- 6. POST /token -----|
  |                                                              |--- tokens ------------>|
  |<----------------------------------------------------------------- 7. your session ----|
```

The browser is the system browser on purpose. An embedded `WebView` lets the
app read the user's password, cannot reuse the user's existing Sarv session,
and is forbidden by RFC 8252. Google also blocks its own sign-in inside
WebViews, so a user who signs in to Sarv with Google would be stuck.

## Requirements

- Android 5.0 (API 21) or higher.
- A browser that supports Custom Tabs (Chrome, Firefox, Samsung Internet, Edge…).
  AppAuth falls back to a regular browser tab if none is present.
- A Sarv developer account to register the app.

## Endpoints

AppAuth reads all of these from
[`https://oauth.sarv.com/.well-known/openid-configuration`](https://oauth.sarv.com/.well-known/openid-configuration);
they are listed here for reference.

| Purpose        | Endpoint |
| -------------- | -------- |
| Issuer         | `https://oauth.sarv.com` |
| Authorize      | `https://oauth.sarv.com/api/oauth/authorize` |
| Token          | `https://oauth.sarv.com/api/oauth/token` |
| User info      | `https://oauth.sarv.com/api/oauth/userinfo` |
| Revoke         | `https://oauth.sarv.com/api/oauth/revoke` |
| End session    | `https://oauth.sarv.com/api/oauth/logout` |
| JWKS           | `https://oauth.sarv.com/.well-known/jwks.json` |

- PKCE: `S256` only, required on every request.
- ID tokens: signed `RS256`.
- Access tokens last **30 minutes**; refresh tokens last **30 days** and
  **rotate** on every use.

> [!IMPORTANT]
> The token and revoke endpoints accept **JSON bodies only**
> (`Content-Type: application/json`). Standard OAuth clients, AppAuth included,
> send `application/x-www-form-urlencoded`, which is rejected with `422`. Use
> AppAuth for the authorization step only, and make the token and revoke calls
> yourself as shown below.

## 1. Register a public app

In the Sarv developer dashboard, open **Apps → New App** and set:

| Field         | Value |
| ------------- | ----- |
| Client type   | **public**. Anything shipped in an APK can be extracted, so a mobile app has no `client_secret`. PKCE protects the flow instead. |
| Redirect URI  | `com.example.app:/oauth2redirect`: a private-use scheme built from a domain you own, reversed. Note the **single** slash. Matched exactly, character for character. |
| Grant types   | `authorization_code`, `refresh_token` |
| Scopes        | `openid email profile`, plus any API scopes your app needs |

Copy the `client_id`. A public client has no secret to copy.

> If the same app also ships an iOS version, register the iOS redirect URI on the
> same client, or use a separate client per platform. Both work.

## 2. Add AppAuth

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.browser:browser:1.8.0")   // Custom Tabs
}
```

## 3. Claim the redirect URI

AppAuth ships `RedirectUriReceiverActivity`, which registers itself for the
scheme you give it here:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["appAuthRedirectScheme"] = "com.example.app"
    }
}
```

The scheme must be the part of your redirect URI before the `:`, and
lowercase.

<details>
<summary>Using an https App Link instead</summary>

A verified App Link (`https://app.example.com/oauth2redirect`) cannot be claimed
by another app on the device, and it is also accepted as a
`post_logout_redirect_uri` (see [Sign out](#9-sign-out)). It needs a
`/.well-known/assetlinks.json` on your domain and an explicit intent filter:

```xml
<activity
    android:name="net.openid.appauth.RedirectUriReceiverActivity"
    android:exported="true"
    tools:node="replace">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https"
              android:host="app.example.com"
              android:path="/oauth2redirect" />
    </intent-filter>
</activity>
```

Set `appAuthRedirectScheme` to an empty string in this case, and register the
https URI on the Sarv app.
</details>

## 4. Start sign-in

```kotlin
import net.openid.appauth.*

private const val ISSUER = "https://oauth.sarv.com"
private const val CLIENT_ID = "YOUR_CLIENT_ID"
private const val REDIRECT_URI = "com.example.app:/oauth2redirect"

class SignInActivity : AppCompatActivity() {

    private val authService by lazy { AuthorizationService(this) }

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult   // user closed the tab
        val response = AuthorizationResponse.fromIntent(data)
        if (response == null) {
            val error = AuthorizationException.fromIntent(data)
            Log.w("SarvLogin", "Sign-in failed: ${error?.errorDescription}", error)
            return@registerForActivityResult
        }
        // AppAuth has already rejected a mismatched state.
        onAuthorized(
            code = response.authorizationCode!!,
            codeVerifier = response.request.codeVerifier!!,
            nonce = response.request.nonce,
            redirectUri = response.request.redirectUri.toString(),
        )
    }

    fun startSignIn() {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(ISSUER)) { config, error ->
            if (config == null) {
                Log.w("SarvLogin", "Discovery failed", error)
                return@fetchFromIssuer
            }
            val request = AuthorizationRequest.Builder(
                config,
                CLIENT_ID,
                ResponseTypeValues.CODE,
                Uri.parse(REDIRECT_URI),
            )
                .setScopes("openid", "email", "profile")
                .build()   // PKCE (S256), state and nonce are generated here
            signIn.launch(authService.getAuthorizationRequestIntent(request))
        }
    }

    override fun onDestroy() {
        authService.dispose()
        super.onDestroy()
    }
}
```

Useful builder options:

| Option | Effect |
| ------ | ------ |
| `.setPrompt("login")` | Always ask for the password, even with a live Sarv session. |
| `.setPrompt("consent")` | Always show the consent screen. |
| `.setLoginHint("user@example.com")` | Pre-fill the email field. |

## 5. Exchange the code

> [!WARNING]
> Do **not** call `authService.performTokenRequest(...)`. It sends a
> form-encoded body, which this server rejects. Exchange the code with a JSON
> request instead, as shown below.

### Recommended: on your backend

Send `code`, `codeVerifier`, `nonce` and `redirectUri` to your own server and
let it call the token endpoint. Tokens then never live on the device, and
refresh happens in one place. That matters because refresh tokens rotate, and
replaying a used one revokes the whole authorization.

```js
// Node.js, with jose for ID token verification
import { createRemoteJWKSet, jwtVerify } from "jose";

const OAUTH = "https://oauth.sarv.com";
const CLIENT_ID = process.env.SARV_ANDROID_CLIENT_ID;
const JWKS = createRemoteJWKSet(new URL(`${OAUTH}/.well-known/jwks.json`));

app.post("/auth/sarv/mobile", async (req, res) => {
  const { code, codeVerifier, nonce, redirectUri } = req.body;
  try {
    const response = await fetch(`${OAUTH}/api/oauth/token`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        grant_type: "authorization_code",
        code,
        code_verifier: codeVerifier,
        redirect_uri: redirectUri,     // the exact string the app sent
        client_id: CLIENT_ID,
      }),
    });
    if (!response.ok) return res.status(401).json(await response.json());
    const tokens = await response.json();

    const { payload } = await jwtVerify(tokens.id_token, JWKS, {
      issuer: OAUTH,
      audience: CLIENT_ID,
    });
    if (payload.nonce !== nonce) return res.status(401).json({ error: "nonce mismatch" });

    // Keep tokens server-side, keyed to payload.sub; give the app your own session.
    res.json({ session: await createSession(payload.sub, tokens) });
  } catch (error) {
    res.status(502).json({ error: error.message });
  }
});
```

### Without a backend: in the app

A public client can make the same request itself. No secret is involved.

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SarvTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
)

private suspend fun postJson(path: String, body: JSONObject): JSONObject =
    withContext(Dispatchers.IO) {
        val connection = (URL("$ISSUER$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = connection.responseCode in 200..299
            val text = (if (ok) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (!ok) throw SarvAuthException(connection.responseCode, text)
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

class SarvAuthException(val status: Int, body: String) : Exception("HTTP $status: $body")

private fun JSONObject.toTokens() = SarvTokens(
    accessToken = getString("access_token"),
    refreshToken = optString("refresh_token").ifEmpty { null },
    idToken = optString("id_token").ifEmpty { null },
    expiresInSeconds = optLong("expires_in", 1800),
)

suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): SarvTokens =
    postJson(
        "/api/oauth/token",
        JSONObject()
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", codeVerifier)
            .put("redirect_uri", redirectUri)
            .put("client_id", CLIENT_ID),
    ).toTokens()
```

Then check the ID token's claims before trusting it. It arrived straight from
the token endpoint over TLS, so OIDC allows skipping the signature check here,
but `iss`, `aud`, `exp` and `nonce` must still match:

```kotlin
import android.util.Base64

fun idTokenProblem(idToken: String, expectedNonce: String?): String? {
    val claims = JSONObject(
        String(Base64.decode(idToken.split(".")[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
    )
    val audience = claims.opt("aud")
    val audienceOk = audience == CLIENT_ID ||
        (audience is org.json.JSONArray && (0 until audience.length()).any { audience.getString(it) == CLIENT_ID })
    return when {
        claims.optString("iss") != ISSUER -> "wrong issuer"
        !audienceOk -> "wrong audience"
        claims.optLong("exp") * 1000 < System.currentTimeMillis() -> "expired"
        expectedNonce != null && claims.optString("nonce") != expectedNonce -> "nonce mismatch"
        else -> null
    }
}
```

## 6. Store tokens safely

Skip this section if your backend holds the tokens.

- Keep the access token **in memory**. It lasts 30 minutes, so there is no need
  to persist it.
- Persist only the refresh token, **encrypted with a key from the Android
  Keystore** (AES-GCM, `setUserAuthenticationRequired` if you want biometrics
  to unlock it). Never put it in plain `SharedPreferences`, a file, or logs.
- Exclude the store from backups (`android:dataExtractionRules` /
  `android:fullBackupContent`), because a Keystore key does not survive a
  restore onto another device.

## 7. Refresh the access token

Refresh tokens **rotate**. Every refresh returns a new refresh token and
invalidates the one you sent. Sending a used refresh token again revokes the
entire authorization and signs the user out. So:

1. Save the new refresh token **before** using the new access token.
2. Never run two refreshes at once. Serialize them behind one lock.

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SarvSession(private val store: RefreshTokenStore) {
    private val refreshLock = Mutex()
    @Volatile private var current: SarvTokens? = null
    @Volatile private var expiresAtMillis = 0L

    suspend fun accessToken(): String = refreshLock.withLock {
        val tokens = current
        if (tokens != null && System.currentTimeMillis() < expiresAtMillis - 60_000) {
            return tokens.accessToken
        }
        val refreshToken = store.read() ?: throw SignedOutException()
        val fresh = try {
            postJson(
                "/api/oauth/token",
                JSONObject()
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", refreshToken)
                    .put("client_id", CLIENT_ID),
            ).toTokens()
        } catch (error: SarvAuthException) {
            if (error.status in 400..401) store.clear()   // revoked or replayed: sign in again
            throw error
        }
        fresh.refreshToken?.let(store::write)              // 1. persist first
        current = fresh
        expiresAtMillis = System.currentTimeMillis() + fresh.expiresInSeconds * 1000
        fresh.accessToken                                  // 2. then use
    }
}
```

`RefreshTokenStore` is your Keystore-backed storage from step 6.

## 8. Call Sarv APIs

Send the access token as a bearer token:

```kotlin
val connection = URL("$ISSUER/api/oauth/userinfo").openConnection() as HttpURLConnection
connection.setRequestProperty("Authorization", "Bearer ${session.accessToken()}")
```

`/api/oauth/userinfo` returns the OIDC profile: `sub`, `email`,
`email_verified`, `name`, and `phone_number` with the `phone` scope. Use `sub`
as the user's stable ID; an email address can change.

## 9. Sign out

Two things have to end: the tokens the app holds, and the user's Sarv session
in the browser. If you end only the tokens, the next sign-in skips the password
because the browser is still signed in to Sarv.

**1. Revoke the refresh token first, then the access token.** Revoking the
refresh token takes every access token issued under it along with it. In the
other order, a just-refreshed access token could survive.

```kotlin
suspend fun revoke(token: String, hint: String) {
    try {
        postJson(
            "/api/oauth/revoke",
            JSONObject()
                .put("token", token)
                .put("token_type_hint", hint)   // "refresh_token" or "access_token"
                .put("client_id", CLIENT_ID),
        )
    } catch (error: Exception) {
        Log.w("SarvLogin", "Revoke failed; continuing sign-out", error)
    }
}

suspend fun signOut(store: RefreshTokenStore, accessToken: String?) {
    store.read()?.let { revoke(it, "refresh_token") }
    accessToken?.let { revoke(it, "access_token") }
    store.clear()
}
```

A failed revocation should not block the sign-out. The user asked to leave, and
an unrevoked access token expires within 30 minutes on its own.

**2. Optionally end the browser session.** Open the end-session URL in a Custom
Tab:

```
https://oauth.sarv.com/api/oauth/logout?client_id=YOUR_CLIENT_ID&post_logout_redirect_uri=https://app.example.com/signed-out
```

The server only redirects after logout if `post_logout_redirect_uri` is
`https://` and on the origin of one of the app's registered redirect URIs. With
a custom-scheme redirect URI only, the tab is not sent back to the app, so
close it yourself or use an App Link (see [step 3](#3-claim-the-redirect-uri)).

## The button

Use the same button users see on the web, so they recognise it:

| | |
| - | - |
| Label | **Login with Sarv** (or "Continue with Sarv" / "Sign in to continue") |
| Mark | [`sarv-mark.svg`](https://github.com/Sarv/sarv-login-js/blob/main/assets/sarv-mark.svg). Import it with **File → New → Vector Asset** |
| Fill | Sarv brand blue `#3069B0`, white label |
| Alternative | White surface with a 1dp border, for screens that already have a primary action |
| Height | 40dp default, at least 48dp touch target |

See the [web button screenshots](https://github.com/Sarv/sarv-login-js#what-it-looks-like)
for reference.

## Troubleshooting

| Symptom | Cause | Fix |
| ------- | ----- | --- |
| `422` with `"Input should be a valid dictionary or object"` | Form-encoded body sent to `/token` or `/revoke` (e.g. `performTokenRequest`) | Send JSON, as in [step 5](#5-exchange-the-code). |
| `400 Missing required parameters for authorization_code grant` | `code_verifier` missing at `/token` | Send `response.request.codeVerifier`. |
| `400 Invalid redirect_uri` | Redirect URI differs from the registered one by even one character | Compare scheme, slashes, and trailing path exactly. |
| Browser shows the code but never returns to the app | `appAuthRedirectScheme` does not match the redirect URI's scheme, or it has uppercase letters | Make them identical and lowercase. |
| `invalid_grant` on refresh, then the user is signed out | A used refresh token was sent again: two refreshes raced, or the new token was not saved | Serialize refreshes and persist before use ([step 7](#7-refresh-the-access-token)). |
| `invalid_grant` on code exchange | Code already used, expired, or exchanged with a different verifier | Exchange each code once, immediately, with its own verifier. |
| Sign-in page opens in a WebView | A custom browser intent | Use `getAuthorizationRequestIntent`, which uses Custom Tabs. |

## Security checklist

- [ ] Client registered as **public**; no secret anywhere in the APK.
- [ ] Sign-in opens in Custom Tabs, never a `WebView`.
- [ ] Every attempt uses a fresh PKCE verifier, `state` and `nonce` (AppAuth's default).
- [ ] ID token `iss`, `aud`, `exp` and `nonce` are checked before it is trusted.
- [ ] Refresh token encrypted with a Keystore key and excluded from backups. Better still, kept on your backend.
- [ ] Refreshes serialized; the new refresh token is persisted before use.
- [ ] Sign-out revokes the refresh token, then the access token.
- [ ] Only the scopes the app needs are requested.
