package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.net.UrlConnectionHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A local HTTP server that answers "200 OK" and then sends one byte a second, forever: like a
 * stalling VPN or hotspot. Every read gets data, so the socket read timeout never fires; only a
 * limit on the whole refresh can end it.
 */
class TrickleServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    val connections = AtomicInteger()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "trickle-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                connections.incrementAndGet()
                sockets.add(socket)
                thread(isDaemon = true, name = "trickle-serve") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
            }
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n[".toByteArray())
            out.flush()
            while (true) {
                Thread.sleep(1_000)
                out.write(' '.code)
                out.flush()
            }
        } catch (e: Exception) {
            // Client gave up or the server closed: done.
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The real HTTP client, with GitHub's API host swapped for this server. */
    fun client(): HttpClient {
        val real = UrlConnectionHttpClient()
        return HttpClient { request: HttpRequest ->
            val url = request.url.replace("https://api.github.com", "http://127.0.0.1:$port")
            val response: HttpResponse = real.execute(request.copy(url = url))
            response
        }
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}
