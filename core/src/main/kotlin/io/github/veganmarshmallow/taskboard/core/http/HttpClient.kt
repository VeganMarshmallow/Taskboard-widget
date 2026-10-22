package io.github.veganmarshmallow.taskboard.core.http

import java.io.IOException

/** The one HTTP operation core needs. Implementations throw [IOException] when the network fails. */
fun interface HttpClient {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}
