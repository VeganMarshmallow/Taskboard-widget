package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A local HTTP/1.1 server on 127.0.0.1 for instrumented tests (#36): owns the socket, the accept
 * loop, request parsing and the [client] hookup into `AppGraph.httpClient`. Subclasses only say
 * how to [answer] one parsed request. Each connection carries one request and is closed after it.
 */
abstract class LocalHttpServer(private val name: String) : AutoCloseable {
    /** One parsed request. Header names are lowercase; [path] includes the query. */
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    val connections = AtomicInteger()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "$name-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                connections.incrementAndGet()
                sockets.add(socket)
                thread(isDaemon = true, name = "$name-serve") { serve(socket) }
            }
        }
    }

    /** Writes the whole response for [request] to [out]; may block (the socket closes after it returns). */
    protected abstract fun answer(request: Request, out: OutputStream)

    private fun serve(socket: Socket) {
        try {
            val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
            val out = socket.getOutputStream()
            answer(request, out)
            out.flush()
        } catch (e: IOException) {
            // The client went away or the server closed: nothing to answer.
        } catch (e: InterruptedException) {
            // The serving thread was stopped.
        } finally {
            runCatching { socket.close() }
            sockets.remove(socket)
        }
    }

    /** The request line, headers and a Content-Length body; null when the client hung up first. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val body = readBody(input, headers["content-length"]?.toIntOrNull() ?: 0)
        val parts = requestLine.split(' ')
        return Request(parts[0], parts.getOrElse(1) { "/" }, headers, body)
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length.coerceAtLeast(0))
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
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
        return HttpClient { request: HttpRequest ->
            real.execute(request.copy(url = request.url.replace("https://api.github.com", "http://127.0.0.1:$port")))
        }
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}
