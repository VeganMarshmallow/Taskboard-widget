package io.github.veganmarshmallow.taskboard.net

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** [HttpClient] on top of HttpURLConnection. Must be called off the main thread. */
class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : HttpClient {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false // never forward the token to another host
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val headers = connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
            return HttpResponse(status, headers, stream?.let(::readLimited) ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String = stream.use { input ->
        val bytes = input.readNBytesCompat(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw IOException("Response too large")
        String(bytes, Charsets.UTF_8)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            // A refresh that ran out of time interrupts its thread; stop instead of reading a trickle forever.
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        /** A full page of 100 issues is ~0.5 MB; anything far beyond that is not a page of issues. */
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}
