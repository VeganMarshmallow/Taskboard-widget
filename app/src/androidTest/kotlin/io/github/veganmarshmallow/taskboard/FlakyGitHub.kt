package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.FakeHttpOnDevice
import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake GitHub that fails like a broken VPN while [down] (the DNS lookup fails, as it does behind
 * a VPN whose DNS is broken), then answers with canned issues once it is back up (#29).
 */
class FlakyGitHub(@Volatile var down: Boolean = true) : HttpClient {
    private val canned = FakeHttpOnDevice()
    val failures = AtomicInteger()
    val successes = AtomicInteger()
    val requests: Int get() = failures.get() + successes.get()

    override fun execute(request: HttpRequest): HttpResponse {
        if (down) {
            failures.incrementAndGet()
            throw UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname")
        }
        return synchronized(canned) { canned.execute(request) }.also { successes.incrementAndGet() }
    }
}
