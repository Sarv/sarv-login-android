package com.sarv.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** One sign-in attempt: the values the callback and token exchange must match. */
internal class AuthorizationRequest(
    configuration: SarvAuthConfiguration,
    val state: String = Pkce.state(),
    val codeVerifier: String = Pkce.verifier(),
    nonce: String? = null,
    promptLogin: Boolean = false,
) {
    val nonce: String? = nonce ?: if (configuration.requestsOpenId) Pkce.nonce() else null

    val url: String = run {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to configuration.clientId)
            add("redirect_uri" to configuration.redirectUri)
            add("scope" to configuration.scopes.joinToString(" "))
            add("state" to state)
            add("code_challenge" to Pkce.challenge(codeVerifier))
            add("code_challenge_method" to "S256")
            this@AuthorizationRequest.nonce?.let { add("nonce" to it) }
            val extra = configuration.extraParameters.toMutableMap()
            if (promptLogin && "prompt" !in extra) extra["prompt"] = "login"
            addAll(extra.toSortedMap().toList())
        }
        configuration.endpoint("api/oauth/authorize") + "?" +
            params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
    }

    /** The authorization code from Sarv's redirect, after the `state` check. */
    fun code(callbackUri: String): String {
        val query = queryParameters(callbackUri)
        query["error"]?.let { throw SarvAuthException.AuthorizationFailed(it, query["error_description"]) }
        val code = query["code"]
        if (code.isNullOrEmpty()) throw SarvAuthException.MissingCode()
        if (query["state"] != state) throw SarvAuthException.StateMismatch()
        return code
    }

    companion object {
        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun queryParameters(uri: String): Map<String, String> {
            val raw = runCatching { URI(uri).rawQuery }.getOrNull() ?: return emptyMap()
            return raw.split("&").filter { it.isNotEmpty() }.associate { pair ->
                val index = pair.indexOf('=')
                val key = if (index < 0) pair else pair.substring(0, index)
                val value = if (index < 0) "" else pair.substring(index + 1)
                URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }
        }
    }
}
