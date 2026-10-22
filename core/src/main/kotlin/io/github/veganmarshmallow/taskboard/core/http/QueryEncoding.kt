package io.github.veganmarshmallow.taskboard.core.http

import java.net.URLEncoder

/** Query-string encoding shared by API requests and web links. */
object QueryEncoding {
    /** Form-style encoding, but with %20 for spaces as GitHub expects in query strings. */
    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
