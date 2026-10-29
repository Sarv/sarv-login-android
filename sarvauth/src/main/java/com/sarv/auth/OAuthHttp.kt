package com.sarv.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class HttpRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)
internal data class HttpResponse(val status: Int, val body: String)

/** Blocking HTTP, swappable in tests. */
internal fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

internal class UrlConnectionTransport : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            request.headers.forEach(connection::setRequestProperty)
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Token / revoke / userinfo calls. Sarv's token endpoint takes a JSON body
 * (not form-encoded) and answers errors as `{"detail": "..."}`.
 */
internal class OAuthHttp(private val configuration: SarvAuthConfiguration, private val transport: HttpTransport) {

    suspend fun postJson(path: String, body: JSONObject): JSONObject = send(
        HttpRequest(
            url = configuration.endpoint(path),
            method = "POST",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = body.toString(),
        )
    )

    suspend fun getJson(path: String, bearer: String): JSONObject = send(
        HttpRequest(
            url = configuration.endpoint(path),
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $bearer", "Accept" to "application/json"),
            body = null,
        )
    )

    private suspend fun send(request: HttpRequest): JSONObject = withContext(Dispatchers.IO) {
        val response = try {
            transport.execute(request)
        } catch (e: IOException) {
            throw SarvAuthException.Network(e)
        }
        if (response.status !in 200..299) {
            val detail = detail(response.body)
            if (response.status == 429) throw SarvAuthException.RateLimited(detail)
            throw SarvAuthException.Server(response.status, detail)
        }
        if (response.body.isBlank()) JSONObject()
        else runCatching { JSONObject(response.body) }.getOrElse {
            throw SarvAuthException.InvalidResponse("response was not JSON")
        }
    }

    companion object {
        /** `{"detail": "..."}` (what the server sends) or the RFC's `error_description`. */
        fun detail(body: String): String? {
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return body.trim().takeIf { it.isNotEmpty() }?.take(300)
            return json.stringOrNull("detail") ?: json.stringOrNull("error_description") ?: json.stringOrNull("error")
        }
    }
}
