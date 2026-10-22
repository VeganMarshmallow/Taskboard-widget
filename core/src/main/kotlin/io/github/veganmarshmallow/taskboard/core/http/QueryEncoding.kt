package io.github.veganmarshmallow.taskboard.core.http

import java.net.URLEncoder

/** Query-string encoding shared by API requests and web links. */
object QueryEncoding {
    /**
     * Form-style encoding, but with %20 for spaces as GitHub expects in query strings.
     * Uses the String-charset overload on purpose: `encode(String, Charset)` only exists on
     * Android API 33+, and this module runs on API 26+ (it crashed the widget with NoSuchMethodError).
     */
    @Suppress("UsePropertyAccessSyntax")
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
