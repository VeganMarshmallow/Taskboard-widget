package io.github.veganmarshmallow.taskboard

import java.io.OutputStream

/**
 * A local HTTP server that answers "200 OK" and then sends one byte a second, forever: like a
 * stalling VPN or hotspot. Every read gets data, so the socket read timeout never fires; only a
 * limit on the whole refresh can end it. [connections] counts the clients that reached it.
 */
class TrickleServer : LocalHttpServer("trickle") {
    override fun answer(request: LocalHttpServer.Request, out: OutputStream) {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n[".toByteArray())
        out.flush()
        while (true) {
            Thread.sleep(1_000)
            out.write(' '.code)
            out.flush()
        }
    }
}
