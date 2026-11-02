package io.github.veganmarshmallow.taskboard

import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local HTTP server standing in for api.github.com (task #30), like [TrickleServer] but with
 * scripted JSON answers. [route] picks the answer from method and path (with query); unknown
 * paths answer 404. Every request is recorded with its body, so tests can check what was sent.
 */
class FakeGitHubServer(private val route: (method: String, path: String) -> Answer?) : LocalHttpServer("fake-github") {
    data class Answer(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    data class Received(val method: String, val path: String, val body: String, val authorization: String?)

    val received = CopyOnWriteArrayList<Received>()

    override fun answer(request: LocalHttpServer.Request, out: OutputStream) {
        received += Received(request.method, request.path, request.body, request.headers["authorization"])
        val answer = route(request.method, request.path) ?: Answer(404, """{"message":"Not Found"}""")
        val bytes = answer.body.toByteArray(Charsets.UTF_8)
        val extra = answer.headers.entries.joinToString("") { (k, v) -> "$k: $v\r\n" }
        out.write("HTTP/1.1 ${answer.status} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }
}
