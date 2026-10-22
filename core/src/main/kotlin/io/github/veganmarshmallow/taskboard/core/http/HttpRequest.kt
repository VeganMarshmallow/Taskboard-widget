package io.github.veganmarshmallow.taskboard.core.http

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    /** Never print the Authorization header. */
    override fun toString(): String = "HttpRequest($method $url, headers=${headers.keys}, body=${body?.length ?: 0} chars)"
}
