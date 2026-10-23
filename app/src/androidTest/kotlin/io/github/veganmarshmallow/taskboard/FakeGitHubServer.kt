package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A local HTTP server standing in for api.github.com (task #30), like [TrickleServer] but with
 * scripted JSON answers. [route] picks the answer from method and path (with query); unknown
 * paths answer 404. Every request is recorded with its body, so tests can check what was sent.
 */
class FakeGitHubServer(private val route: (method: String, path: String) -> Answer?) : AutoCloseable {
    data class Answer(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    data class Received(val method: String, val path: String, val body: String, val authorization: String?)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val received = CopyOnWriteArrayList<Received>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-github-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                thread(isDaemon = true, name = "fake-github-serve") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { answer(it) }
    }

    private fun answer(it: Socket) {
        try {
            val input = BufferedInputStream(it.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length).also { bytes -> var read = 0; while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) break; read += n } }
            val (method, path) = requestLine.split(' ').let { parts -> parts[0] to parts.getOrElse(1) { "/" } }
            received += Received(method, path, String(body, Charsets.UTF_8), headers["authorization"])
            val answer = route(method, path) ?: Answer(404, """{"message":"Not Found"}""")
            val bytes = answer.body.toByteArray(Charsets.UTF_8)
            val extra = answer.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
            it.getOutputStream().apply {
                write("HTTP/1.1 ${answer.status} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        } catch (e: IOException) {
            // The client went away: nothing to answer.
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    /** The real HTTP client, with GitHub's API host swapped for this server. */
    fun client(): HttpClient {
        val real = UrlConnectionHttpClient()
        return HttpClient { request: HttpRequest -> real.execute(request.copy(url = request.url.replace("https://api.github.com", "http://127.0.0.1:$port"))) }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
