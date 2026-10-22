package io.github.veganmarshmallow.taskboard.core.http

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * HTTP for large or binary bodies (release assets): the body is streamed, not read into a String.
 * Implementations must NOT follow redirects; callers decide that with [Redirects].
 */
fun interface StreamingHttpClient {
    @Throws(IOException::class)
    fun open(request: HttpRequest): StreamingResponse
}

/** A response whose [body] must be read (or not) and then [close]d. Header names are case-insensitive. */
class StreamingResponse(
    val status: Int,
    headers: Map<String, List<String>> = emptyMap(),
    val body: InputStream? = null,
    private val onClose: () -> Unit = {},
) : Closeable {
    private val headers = headers.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, v) -> v.flatten() }

    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val isSuccessful: Boolean get() = status in 200..299

    /** Reads at most [limit] bytes of the body as UTF-8 (for error messages and small JSON files). */
    @Throws(IOException::class)
    fun readText(limit: Int): String {
        val input = body ?: return ""
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Same headers and status as a plain [HttpResponse], with [text] as the body. */
    fun toHttpResponse(text: String): HttpResponse = HttpResponse(status, headers, text)

    override fun close() {
        try {
            body?.close()
        } catch (e: IOException) {
            // Nothing useful to do; the connection is released below.
        } finally {
            onClose()
        }
    }
}
