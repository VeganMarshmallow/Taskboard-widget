package io.github.veganmarshmallow.taskboard.core.http

import java.net.URI
import java.net.URISyntaxException

/**
 * Redirect handling for release-asset downloads, done on purpose instead of by the HTTP stack.
 *
 * GitHub answers `GET /repos/.../releases/assets/{id}` with a 302 to a short-lived signed URL on a
 * storage host. The token must never go there: only a target on `https://api.github.com` keeps
 * the Authorization header, every other host gets just Accept and User-Agent. Once a hop has dropped
 * the token it never comes back, even if a later hop returns to api.github.com. Only https targets
 * are followed, and at most [MAX_HOPS] of them.
 */
object Redirects {
    const val MAX_HOPS = 5
    private val STATUSES = setOf(301, 302, 303, 307, 308)
    private const val API_HOST = "api.github.com"
    private val FOREIGN_SAFE_HEADERS = setOf("accept", "user-agent")

    fun isRedirect(status: Int): Boolean = status in STATUSES

    /** The request for [location], resolved against [previous]; null when the target is not safe to follow. */
    fun next(previous: HttpRequest, location: String?): HttpRequest? {
        val raw = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val target = try {
            URI(previous.url).resolve(URI(raw))
        } catch (e: URISyntaxException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!target.scheme.equals("https", ignoreCase = true)) return null
        val host = target.host?.takeIf { it.isNotEmpty() } ?: return null
        if (target.rawUserInfo != null) return null
        val headers = if (isApiHost(host, target.port)) {
            previous.headers
        } else {
            previous.headers.filterKeys { it.lowercase() in FOREIGN_SAFE_HEADERS }
        }
        return HttpRequest("GET", target.toString(), headers)
    }

    /** True when [url] is on api.github.com over https (the only host that may receive the token). */
    fun isApiUrl(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && uri.host != null && isApiHost(uri.host, uri.port)
    } catch (e: URISyntaxException) {
        false
    }

    private fun isApiHost(host: String, port: Int) = host.equals(API_HOST, ignoreCase = true) && (port == -1 || port == 443)
}
