package io.github.veganmarshmallow.taskboard.net

import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import java.net.HttpURLConnection
import java.net.URL

/**
 * [StreamingHttpClient] on HttpURLConnection, for release assets. Like [UrlConnectionHttpClient] it
 * never follows redirects itself: core's `Redirects` follows them and strips the token for any host
 * other than api.github.com. Must be called off the main thread.
 */
class UrlConnectionStreamingClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : StreamingHttpClient {

    override fun open(request: HttpRequest): StreamingResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            // Byte-exact body: the size and SHA-256 checks need the file as published, not re-encoded.
            connection.setRequestProperty("Accept-Encoding", "identity")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val body = when {
                status in 300..399 -> null
                status >= 400 -> connection.errorStream
                else -> connection.inputStream
            }
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            return StreamingResponse(status, headers, body) { connection.disconnect() }
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }
}
