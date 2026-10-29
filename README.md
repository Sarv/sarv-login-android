# Login with Sarv for Android

**SarvAuth** adds **Login with Sarv** to an Android app: the official button and an
OAuth 2.1 + PKCE / OpenID Connect client for `https://oauth.sarv.com`, following
[RFC 8252 — OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252). Same
protocol and rules as the web SDK [sarv-login-js](https://github.com/Sarv/sarv-login-js).

- Sign-in runs in a **Custom Tab**, never a `WebView` — the password stays out of your app.
- **PKCE S256**, `state`, and ID-token `iss` / `aud` / `exp` / `nonce` checks, enforced.
- Sarv's token and revoke endpoints take **JSON** bodies — handled for you.
- Tokens encrypted with an **Android Keystore** AES-256-GCM key.
- **Refresh-token rotation** handled: one refresh in flight, the new refresh token saved.
- minSdk 26; depends only on `androidx.browser`, `androidx.core` and Kotlin coroutines.

> **Status: 0.1.0 preview.** Build it and run the unit tests
> (`./gradlew :sarvauth:testDebugUnitTest`) before shipping it in an app.

> Building for iOS? See [sarv-login-ios](https://github.com/Sarv/sarv-login-ios).
> Integrating without this SDK (e.g. with AppAuth)? See the
> [manual integration guide](docs/manual-integration.md).

## Install

Not on Maven Central yet. Include the module, or publish it locally:

```sh
./gradlew :sarvauth:publishToMavenLocal      # then mavenLocal() + implementation("com.sarv:sarvauth:0.1.0")
```

## 1. Register a public app

In the Sarv developer dashboard, **Apps → New App**:

| Field | Value |
| --- | --- |
| Client type | **public** — an app has no `client_secret`; PKCE protects the flow. |
| Redirect URI | `com.example.app:/oauth2redirect` — a lowercase private-use scheme from a domain you own, reversed. Note the **single** slash. Matched exactly. |
| Grant types | `authorization_code`, `refresh_token` |
| Scopes | `openid email profile`, plus any API scopes your app needs |

## 2. Claim the redirect scheme

```kotlin
// app/build.gradle.kts — the part of the redirect URI before the ':'
android {
    defaultConfig {
        manifestPlaceholders["sarvAuthRedirectScheme"] = "com.example.app"
    }
}
```

The scheme must be lowercase and identical to the redirect URI's, or the browser shows the
code but never returns to the app.

## 3. Configure

```kotlin
val sarv = SarvAuthClient(
    context,
    SarvAuthConfiguration(
        clientId = "YOUR_CLIENT_ID",
        redirectUri = "com.example.app:/oauth2redirect",
    ),
)
```

Keep **one** `SarvAuthClient` for the app's lifetime (e.g. in your `Application`) so
refreshes are serialised.

## 4. Sign in

```kotlin
lifecycleScope.launch {
    val result = sarv.signIn(this@MainActivity)
    result.user?.sub      // stable user id — store this, not the email
}
```

Errors are `SarvAuthException` subclasses: `Cancelled` (tab closed), `NoBrowser`,
`AuthorizationFailed`, `StateMismatch`, `InvalidIdToken`, `RateLimited`, `SessionExpired`,
`Server(status, detail)`, `Network`, …

## 5. Call APIs

```kotlin
val token = sarv.validAccessToken()   // refreshes when < 60 s left
```

Access tokens last 30 minutes; refresh tokens 30 days and **rotate** on every use. Concurrent
callers share one refresh. `SessionExpired` means Sarv rejected the refresh token and the
stored tokens are already cleared — show sign-in again. Network errors keep them.

`sarv.fetchUser()` returns the userinfo profile. Sarv exposes no roles or permissions.

**Exchange on a backend instead?** That is still the most secure option — follow
[step 5 of the manual guide](docs/manual-integration.md#5-exchange-the-code) and use this
SDK only for the button.

## 6. Sign out

```kotlin
sarv.signOut(activity)
```

Revokes the refresh token, then the access token, and clears storage. Sarv's sign-out page
only redirects to an `https://` URL, so it can't return to the app; instead the **next
sign-in sends `prompt=login`** and Sarv asks for the password. With an https
`postLogoutRedirectUri` configured, `signOut(activity)` also opens Sarv's sign-out page.

**Backups:** a Keystore key is not restored to a new device, so exclude the SDK's
`com.sarv.auth.tokens` shared-preferences file from Auto Backup (`android:fullBackupContent`
/ `android:dataExtractionRules`). Unreadable tokens are dropped and the user signs in again.

## The button

```xml
<com.sarv.auth.ui.SarvLoginButton
    android:id="@+id/sarvLogin"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:sarvVariant="brand"
    app:sarvSize="large"
    app:sarvTheme="auto" />
```

```kotlin
binding.sarvLogin.setOnClickListener { lifecycleScope.launch { sarv.signIn(this@MainActivity) } }
```

`brand` (Sarv blue `#3069B0`) or `surface` (bordered); `small` / `medium` / `large` =
30 / 38 / 44 dp; `auto` follows night mode. In Compose, host it with `AndroidView`.

## Known limits (0.1.0)

- A sign-in in progress is lost if Android kills the app process while the Custom Tab is
  open; the user taps the button again.
- No ephemeral (cookie-free) Custom Tab yet for "add another account".

## Development

```sh
gradle wrapper            # once, to add the Gradle wrapper
./gradlew :sarvauth:testDebugUnitTest
./gradlew :sarvauth:assembleRelease
```

## License

MIT
