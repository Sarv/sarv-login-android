# Login with Sarv for Android

**SarvAuth** adds **Login with Sarv** to an Android app: the official button and an
OAuth 2.1 + PKCE / OpenID Connect client for `https://oauth.sarv.com`, following
[RFC 8252 — OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252). Same
protocol and rules as the web SDK [sarv-login-js](https://github.com/Sarv/sarv-login-js).

- Sign-in runs in a **Custom Tab** (the user's browser), never a `WebView` — the password
  stays out of your app, and Google sign-in to Sarv keeps working.
- **PKCE S256**, `state`, and ID-token `iss` / `aud` / `exp` / `nonce` checks, enforced.
- Sarv's token and revoke endpoints take **JSON** bodies — handled for you.
- Tokens encrypted with an **Android Keystore** AES-256-GCM key.
- **Refresh-token rotation** handled: one refresh in flight, the new refresh token saved.
- Depends only on `androidx.browser`, `androidx.core` and Kotlin coroutines.

> **Status: 0.1.0 preview.** Build it and run the unit tests
> (`./gradlew :sarvauth:testDebugUnitTest`) before shipping it in an app.

> Building for iOS? See [sarv-login-ios](https://github.com/Sarv/sarv-login-ios).
> Integrating without this SDK (e.g. with AppAuth)? See the
> [manual integration guide](docs/manual-integration.md).

---

## Contents

- [Requirements](#requirements)
- [Example app](#example-app)
- [How it works](#how-it-works)
- [1. Register a public app](#1-register-a-public-app)
- [2. Install](#2-install)
- [3. Claim the redirect scheme](#3-claim-the-redirect-scheme)
- [4. Exclude tokens from backups](#4-exclude-tokens-from-backups)
- [5. Configure the client](#5-configure-the-client)
- [6. Sign in](#6-sign-in)
- [7. Restore the session at launch](#7-restore-the-session-at-launch)
- [8. Call APIs](#8-call-apis)
- [9. Sign out](#9-sign-out)
- [10. Add another account](#10-add-another-account)
- [The button](#the-button)
- [Jetpack Compose](#jetpack-compose)
- [Complete example (Activity)](#complete-example-activity)
- [Errors](#errors)
- [Using a backend for the code exchange](#using-a-backend-for-the-code-exchange)
- [R8 / ProGuard](#r8--proguard)
- [Troubleshooting](#troubleshooting)
- [Security checklist](#security-checklist)
- [API reference](#api-reference)
- [Known limits](#known-limits)
- [Development](#development)

---

## Requirements

- Android 8.0 (API 26) or later — `minSdk 26`.
- `compileSdk` 34 or later in your app.
- Android Gradle Plugin 8.x, JDK 17, Kotlin with coroutines (the API is `suspend`).
- A browser on the device (Chrome, Firefox, Samsung Internet, … — any Custom Tabs browser).
- A Sarv developer account to register the app.

## Example app

[`example/`](example) is a small app built on this library: sign in, restore the session
at launch, show the profile and token state, get a fresh access token, and sign out. It
also shows the redirect-scheme placeholder and the backup rules in place.

1. Register a public app on the Sarv developer dashboard with the redirect URI
   `com.sarv.authexample:/oauth2redirect`. To use another one, change `REDIRECT_URI` and the
   `sarvAuthRedirectScheme` placeholder in `example/build.gradle.kts` together.
2. Set your client id in `example/src/main/java/com/sarv/authexample/ExampleApp.kt`.
3. Open this folder in Android Studio and run the `example` configuration, or:

   ```sh
   ./gradlew :example:installDebug
   ```

The app depends on the library module directly (`project(":sarvauth")`), so SDK changes
show up in the app straight away. Until a client id is set, the app runs but Sarv rejects
the sign-in.

## How it works

```
 App                       Custom Tab                         oauth.sarv.com
  |  signIn(activity)          |                                     |
  |  PKCE verifier, state, nonce                                     |
  |--------------------------->|  /api/oauth/authorize ------------->|
  |                            |  user signs in + consents           |
  |<---------------------------|  com.example.app:/oauth2redirect?code=…&state=…
  |  (RedirectActivity)  check state                                  |
  |  POST /api/oauth/token  (JSON: code + code_verifier) ------------>|
  |<--------------------------------------- access, refresh, id token-|
  |  check id_token iss / aud / exp / nonce, encrypt + save           |
```

## 1. Register a public app

In the Sarv developer dashboard, open **Apps → New App** and set:

| Field | Value |
| --- | --- |
| Client type | **public**. Anything shipped in an APK can be extracted, so an app has no `client_secret`; PKCE protects the flow instead. |
| Redirect URI | `com.example.app:/oauth2redirect` — a **lowercase** private-use scheme built from a domain you own, reversed (your application ID works). Note the **single** slash. Matched exactly, character for character. |
| Grant types | `authorization_code`, `refresh_token` |
| Scopes | `openid email profile`, plus any API scopes your app needs |

Copy the `client_id`. A public client has no secret.

> Shipping an iOS app too? Register its redirect URI on the same client, or use a separate
> client per platform. Both work.

## 2. Install

The library isn't on Maven Central yet. Pick one:

**A. Composite build (recommended while in preview).** Clone this repository next to your
app and include it — Gradle builds the library from source and substitutes it for the
`com.sarv:sarvauth` dependency:

```sh
git clone https://github.com/Sarv/sarv-login-android.git
```

```kotlin
// your app's settings.gradle.kts
includeBuild("../sarv-login-android")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.sarv:sarvauth:0.1.0")
}
```

**B. Local Maven.** Publish once, then depend on it like any library:

```sh
cd sarv-login-android && ./gradlew :sarvauth:publishToMavenLocal
```

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
// app/build.gradle.kts
dependencies { implementation("com.sarv:sarvauth:0.1.0") }
```

Your app also needs coroutines to call the `suspend` API (most apps already have it through
`androidx.lifecycle:lifecycle-runtime-ktx`, which provides `lifecycleScope`).

## 3. Claim the redirect scheme

The library's manifest registers `RedirectActivity` for your redirect scheme. Tell it the
scheme — the part of the redirect URI before the `:`:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["sarvAuthRedirectScheme"] = "com.example.app"
    }
}
```

It must be lowercase and identical to the redirect URI's scheme, or the browser shows the
result but never returns to the app. If it's missing, the build stops with *"Attribute
data@scheme … requires a placeholder substitution but no value for
<sarvAuthRedirectScheme> is provided"*. The library also declares the `INTERNET` permission
and the Custom Tabs `<queries>` entry needed on Android 11+; you don't add them.

## 4. Exclude tokens from backups

The tokens are encrypted with a Keystore key that never leaves the device, so a backup
restored on another device can't be decrypted (the SDK then drops it and the user signs in
again). Exclude the token file from Auto Backup so it isn't uploaded at all:

```xml
<!-- res/xml/sarv_backup_rules.xml — Android 11 and lower -->
<full-backup-content>
    <exclude domain="sharedpref" path="com.sarv.auth.tokens.xml" />
</full-backup-content>
```

```xml
<!-- res/xml/sarv_data_extraction_rules.xml — Android 12 and higher -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="com.sarv.auth.tokens.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="com.sarv.auth.tokens.xml" />
    </device-transfer>
</data-extraction-rules>
```

```xml
<!-- AndroidManifest.xml -->
<application
    android:fullBackupContent="@xml/sarv_backup_rules"
    android:dataExtractionRules="@xml/sarv_data_extraction_rules"
    … >
```

If your app already has backup rules, add the `<exclude … com.sarv.auth.tokens.xml />`
lines to them instead.

## 5. Configure the client

Create **one** `SarvAuthClient` and keep it for the app's lifetime — refreshes are
serialised per instance, and two instances refreshing at once would replay a rotated
refresh token. The `Application` class is a good home:

```kotlin
class App : Application() {
    val sarv: SarvAuthClient by lazy {
        SarvAuthClient(
            this,
            SarvAuthConfiguration(
                clientId = "YOUR_CLIENT_ID",
                redirectUri = "com.example.app:/oauth2redirect",
            ),
        )
    }
}

val Context.sarv: SarvAuthClient get() = (applicationContext as App).sarv
```

`SarvAuthConfiguration` options:

| Parameter | Default | Notes |
| --- | --- | --- |
| `clientId` | — | Required. |
| `redirectUri` | — | Required. Exactly as registered; lowercase scheme. |
| `scopes` | `listOf("openid", "email", "profile")` | Ask only for what you use — the consent screen lists every scope. |
| `oauthUrl` | `https://oauth.sarv.com` | |
| `extraParameters` | `emptyMap()` | Extra authorize parameters (`login_hint`, `prompt`, …). SDK-owned parameters (`state`, `code_challenge`, …) are rejected. |
| `postLogoutRedirectUri` | `null` | Only an `https://` URL on a registered redirect origin. See [Sign out](#9-sign-out). |

The constructor throws `SarvAuthException.InvalidConfiguration` for an empty client id, an
uppercase scheme, a reserved extra parameter or a non-https `postLogoutRedirectUri`.

`SarvAuthClient(context, configuration, tokenStore)` also accepts your own
`SarvTokenStore` implementation.

## 6. Sign in

```kotlin
lifecycleScope.launch {
    try {
        val result = sarv.signIn(this@MainActivity)
        result.user?.sub      // stable user id — store this, not the email
        result.user?.email
    } catch (e: SarvAuthException.Cancelled) {
        // The user closed the tab — do nothing.
    } catch (e: SarvAuthException) {
        showError(e.message)
    }
}
```

`signIn` opens Sarv's page in a Custom Tab, waits for the redirect, exchanges the code and
saves the tokens before returning. Pass an `Activity` so the tab opens in your task.

## 7. Restore the session at launch

Tokens survive app restarts. On launch, pick the first screen without a network call:

```kotlin
if (sarv.isSignedIn) showHome(sarv.currentUser)   // from the stored ID token
else showSignIn()
```

The stored access token may have expired while the app was closed — `validAccessToken()`
(next step) refreshes it. If the refresh token itself is no longer valid, the first API
call throws `SessionExpired`: send the user back to sign-in.

## 8. Call APIs

```kotlin
suspend fun authorizedRequest(url: URL): HttpURLConnection =
    (url.openConnection() as HttpURLConnection).apply {
        setRequestProperty("Authorization", "Bearer ${sarv.validAccessToken()}")   // refreshes when < 60 s left
    }

// OkHttp: add the header in an Interceptor with runBlocking { sarv.validAccessToken() },
// or fetch the token in your repository before building the request.
```

- Access tokens last **30 minutes**; refresh tokens last **30 days** and **rotate** on
  every use. The SDK saves the new refresh token before handing out the new access token,
  and concurrent callers share one refresh.
- `SessionExpired` — Sarv rejected the refresh token (expired, revoked or replayed). The
  stored tokens are already cleared; show sign-in.
- `Network` — offline or timed out. Tokens are kept; retry later.

User profile:

```kotlin
val user = sarv.fetchUser()   // GET /api/oauth/userinfo
// sub, email, emailVerified, name, profile, phoneNumber (with the `phone` scope)
```

Sarv says *who* someone is; it exposes no roles or permissions. What they may do in your
app is your app's decision.

## 9. Sign out

```kotlin
lifecycleScope.launch {
    sarv.signOut(this@MainActivity)
    showSignIn()
}
```

1. Revokes the **refresh token first**, then the access token — revoking the refresh token
   takes every access token issued under it along with it.
2. Clears the stored tokens. A failed revocation never blocks sign-out; an unrevoked access
   token expires within 30 minutes on its own.
3. Makes the next sign-in ask for the password. Sarv's sign-out page only redirects to an
   `https://` URL, so it can't return to the app; instead the **next sign-in sends
   `prompt=login`**. If you set an https `postLogoutRedirectUri`, `signOut(activity)` also
   opens Sarv's sign-out page in a Custom Tab (the user closes it when done).

`signOut(endSarvSession = false)` only revokes and clears; the next sign-in may then reuse
the browser session without a password.

## 10. Add another account

The browser's Sarv session signs the same person back in, so to switch to a different
account, sign the current one out first — `signOut` makes the next sign-in ask for the
password.

One `SarvAuthClient` holds one account's tokens, and the default `KeystoreTokenStore`
keeps one entry per client id. To keep several accounts signed in at once, create one
client per account and give each its own `SarvTokenStore` implementation (e.g. one that
prefixes its storage key with an account id).

## The button

```xml
<com.sarv.auth.ui.SarvLoginButton
    android:id="@+id/sarvLogin"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Login with Sarv"
    app:sarvVariant="brand"
    app:sarvSize="large"
    app:sarvTheme="auto" />
```

```kotlin
binding.sarvLogin.setOnClickListener { signIn() }
// or in code:
SarvLoginButton(context).apply {
    variant = SarvLoginButton.Variant.SURFACE
    size = SarvLoginButton.Size.LARGE
    theme = SarvLoginButton.Theme.AUTO
    text = "Continue with Sarv"
}
```

| Attribute | Values |
| --- | --- |
| `app:sarvVariant` | `brand` (Sarv blue `#3069B0`, white label) · `surface` (white / dark, bordered — for screens that already have a primary action) |
| `app:sarvSize` | `small` 30 dp · `medium` 38 dp · `large` 44 dp (use for the main action) |
| `app:sarvTheme` | `auto` (follows night mode) · `light` · `dark` |
| `android:text` | Defaults to "Login with Sarv" |

The height is fixed by `sarvSize`; colours and the Sarv mark match the
[web button](https://github.com/Sarv/sarv-login-js#what-it-looks-like). Disable it while a
sign-in runs (`isEnabled = false`).

## Jetpack Compose

Host the view with `AndroidView`:

```kotlin
@Composable
fun SarvLoginButtonCompose(enabled: Boolean = true, onClick: () -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            SarvLoginButton(context).apply {
                size = SarvLoginButton.Size.LARGE
                setOnClickListener { onClick() }
            }
        },
        update = { it.isEnabled = enabled },
    )
}

// Usage
val scope = rememberCoroutineScope()
val activity = LocalContext.current as Activity
SarvLoginButtonCompose { scope.launch { runCatching { sarv.signIn(activity) } } }
```

## Complete example (Activity)

```kotlin
class SignInActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignInBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (sarv.isSignedIn) return openHome()

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.sarvLogin.setOnClickListener { signIn() }
    }

    private fun signIn() {
        binding.sarvLogin.isEnabled = false
        lifecycleScope.launch {
            try {
                sarv.signIn(this@SignInActivity)
                openHome()
            } catch (e: SarvAuthException.Cancelled) {
                // closed by the user
            } catch (e: SarvAuthException) {
                AlertDialog.Builder(this@SignInActivity)
                    .setTitle("Sign in failed")
                    .setMessage(e.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } finally {
                binding.sarvLogin.isEnabled = true
            }
        }
    }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

class HomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                val user = sarv.fetchUser()
                title = user.name ?: user.email ?: user.sub
            } catch (e: SarvAuthException.SessionExpired) {
                backToSignIn()
            } catch (e: SarvAuthException.NotSignedIn) {
                backToSignIn()
            }
        }
    }

    fun onSignOutClicked() {
        lifecycleScope.launch {
            sarv.signOut(this@HomeActivity)
            backToSignIn()
        }
    }

    private fun backToSignIn() {
        startActivity(Intent(this, SignInActivity::class.java))
        finish()
    }
}
```

## Errors

Every failure is a `SarvAuthException` subclass. Server text (`detail`) is human-readable —
branch on the type, never on the message.

| Exception | Meaning | Typical handling |
| --- | --- | --- |
| `Cancelled` | The user closed the tab. | Ignore. |
| `NoBrowser` | No browser can open the sign-in page. | Ask the user to install or enable one. |
| `AuthorizationFailed(error, description)` | Sarv redirected back with `?error=` (e.g. `access_denied`). | Show `description`. |
| `MissingCode`, `StateMismatch` | The redirect wasn't a valid answer to this sign-in. The code was not used. | Ask the user to try again. |
| `InvalidIdToken` | The ID token failed `iss` / `aud` / `exp` / `nonce`. Tokens discarded. | Try again; check `clientId` / `oauthUrl`. |
| `RateLimited(detail)` | HTTP 429 — limits are per IP. | Wait a minute. |
| `Server(status, detail)` | Any other non-2xx answer. | Show `detail`; see [Troubleshooting](#troubleshooting). |
| `SessionExpired` | Refresh token rejected; stored tokens cleared. | Show sign-in. |
| `NotSignedIn` | No stored tokens. | Show sign-in. |
| `Network` | No answer (offline, timeout, TLS). Tokens kept. | Retry. |
| `InvalidResponse`, `InvalidConfiguration` | Unexpected server data; bad configuration. | Log; show a generic error. |

## Using a backend for the code exchange

Exchanging the code on **your** server keeps tokens off the device entirely and is still
the most secure option. In that setup use this SDK only for the button, and follow
[step 5 of the manual guide](docs/manual-integration.md#5-exchange-the-code) for the
authorization request and the server-side exchange.

## R8 / ProGuard

Nothing to add. The library uses no reflection and ships its own consumer rules.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Browser shows the result but never returns to the app | `sarvAuthRedirectScheme` doesn't match the redirect URI's scheme, or has uppercase letters. | Make them identical and lowercase ([step 3](#3-claim-the-redirect-scheme)). |
| `Server(400, "Invalid redirect_uri")` | The redirect URI differs from the registered one. | Compare scheme, slashes and path character by character (`com.example.app:/oauth2redirect`, one slash). |
| `InvalidConfiguration` at startup | Uppercase scheme, empty client id, reserved `extraParameters` key, or a custom-scheme `postLogoutRedirectUri`. | Read the message; fix the configuration. |
| `NoBrowser` | No browser installed or all are disabled (common on some test devices). | Install a browser. |
| `InvalidIdToken` | Wrong `clientId` or `oauthUrl`, or a device clock far off. | Check the configuration and the device time. |
| `SessionExpired` soon after sign-in | Two `SarvAuthClient` instances refreshed at once and replayed a rotated refresh token. | Keep a single shared instance ([step 5](#5-configure-the-client)). |
| Signed out after restoring onto a new device | The Keystore key isn't restored, so the copied tokens can't be decrypted. | Expected; exclude them from backups ([step 4](#4-exclude-tokens-from-backups)). |
| Next sign-in skips the password after sign-out | `signOut(endSarvSession = false)` was used. | Use the default `signOut(activity)`. |
| `RateLimited` | Per-IP limits: authorize and token 20/min, revoke 10/min. | Back off; many users behind one office IP share the limit. |

## Security checklist

- [ ] Client registered as **public**; no secret anywhere in the app.
- [ ] Sign-in through `SarvAuthClient.signIn` (Custom Tab), never a `WebView`.
- [ ] One shared `SarvAuthClient`, so refreshes never race.
- [ ] Token file excluded from backups ([step 4](#4-exclude-tokens-from-backups)).
- [ ] `sub` stored as the user's id, not the email.
- [ ] The ID token is never sent to an API — it's not a credential. Send the access token.
- [ ] Tokens are never logged.
- [ ] Sign-out calls `signOut(activity)`, so tokens are revoked and the next sign-in asks for the password.

## API reference

| `SarvAuthClient` | |
| --- | --- |
| `SarvAuthClient(context, configuration, tokenStore = KeystoreTokenStore(...))` | |
| `suspend fun signIn(context: Context): SarvSignInResult` | Pass an `Activity`. Returns `tokens` and `user`. |
| `isSignedIn`, `tokens`, `currentUser` | Stored state, no network. |
| `suspend fun validAccessToken(): String` | Refreshes when needed. |
| `suspend fun refresh(): SarvTokens` | Refreshes if expired; shared among concurrent callers. |
| `suspend fun fetchUser(): SarvUser` | `/api/oauth/userinfo`. |
| `suspend fun revoke(token, hint): Boolean` | `false` = nothing to revoke (already expired or rotated). |
| `suspend fun signOut(context: Context? = null, endSarvSession: Boolean = true)` | Revoke, clear, end the session. |
| `fun logoutUrl(state: String? = null): String?` | Sarv's end-session URL; null without an https `postLogoutRedirectUri`. |

| Type | |
| --- | --- |
| `SarvTokens` | `accessToken`, `refreshToken`, `idToken`, `scope`, `expiresAtMillis`, `isExpired()`, `user`. |
| `SarvUser` | `sub`, `email`, `emailVerified`, `name`, `profile`, `phoneNumber`, `phoneNumberVerified`. |
| `SarvTokenStore` | Interface; `KeystoreTokenStore`, `InMemoryTokenStore`. |
| `com.sarv.auth.ui.SarvLoginButton` | View; `Variant`, `Size`, `Theme`. |

## Known limits

- A sign-in in progress is lost if Android kills the app process while the Custom Tab is
  open; the user taps the button again.
- No ephemeral (cookie-free) Custom Tab yet for "add another account" — see
  [step 10](#10-add-another-account).
- Kotlin-first: the API is `suspend`, so Java callers need a coroutine bridge.

## Development

```sh
./gradlew :sarvauth:testDebugUnitTest
./gradlew :sarvauth:assembleRelease
./gradlew :sarvauth:publishToMavenLocal
./gradlew :example:assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
