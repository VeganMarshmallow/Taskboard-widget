package io.github.veganmarshmallow.taskboard.core.http

/** A response. Header names are matched case-insensitively. */
class HttpResponse(
    val status: Int,
    headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
) {
    private val headers: Map<String, List<String>> = headers.entries
        .groupBy({ it.key.lowercase() }, { it.value })
        .mapValues { (_, lists) -> lists.flatten() }

    /** First value of header [name], or null. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val isSuccessful: Boolean get() = status in 200..299

    override fun toString(): String = "HttpResponse($status, ${body.length} chars)"
}
