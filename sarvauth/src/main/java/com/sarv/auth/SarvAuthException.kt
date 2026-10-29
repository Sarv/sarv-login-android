package com.sarv.auth

/**
 * Everything the SDK can throw. Server text ([Server.detail]) is
 * human-readable, never a machine code — branch on the type.
 */
sealed class SarvAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The configuration is unusable (missing client id, reserved parameter, …). */
    class InvalidConfiguration(detail: String) : SarvAuthException("Sarv login: $detail")
    /** The user closed the sign-in page. */
    class Cancelled : SarvAuthException("Sign in was cancelled.")
    /** No browser could open Sarv's sign-in page. */
    class NoBrowser : SarvAuthException("No browser is available to sign in with Sarv.")
    /** Sarv redirected back with `?error=` (e.g. `access_denied`). */
    class AuthorizationFailed(val error: String, val description: String?) :
        SarvAuthException(description ?: "Sign in failed ($error).")
    /** The callback carried no authorization code. */
    class MissingCode : SarvAuthException("Sign in failed: no authorization code was returned.")
    /** The callback's `state` is not this flow's. The code was not used. */
    class StateMismatch : SarvAuthException("Sign in failed: the response did not match this sign-in attempt.")
    /** The ID token failed a check (`iss`, `aud`, `exp` or `nonce`). Tokens discarded. */
    class InvalidIdToken(reason: String) : SarvAuthException("Sign in failed: $reason")
    /** Too many requests from this IP (HTTP 429). Not a credential problem. */
    class RateLimited(val detail: String?) :
        SarvAuthException(detail ?: "Too many attempts. Please wait a minute and try again.")
    /** Sarv rejected the refresh token; stored tokens were cleared — sign in again. */
    class SessionExpired : SarvAuthException("Your Sarv session has ended. Please sign in again.")
    /** No tokens stored — call signIn first. */
    class NotSignedIn : SarvAuthException("Not signed in.")
    /** Any other non-2xx answer from the authorization server. */
    class Server(val status: Int, val detail: String?) :
        SarvAuthException(detail ?: "Sarv returned an error ($status).")
    /** The request never got an answer (offline, timeout, TLS…). */
    class Network(cause: Throwable) : SarvAuthException(cause.message ?: "Network error.", cause)
    /** The server answered with something the SDK could not read. */
    class InvalidResponse(detail: String) : SarvAuthException("Unexpected response from Sarv: $detail")
}
