package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.json.JsonWriter
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val NOW: Instant = Instant.parse("2026-10-22T12:00:00Z")
val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
const val TOKEN = "github_pat_TESTTOKEN"

fun issue(
    number: Int,
    vararg labels: String,
    title: String = "Task $number",
    open: Boolean = true,
    closedAt: Instant? = null,
    pr: Boolean = false,
) = Issue(number, title, labels.map(Labels::normalize).toSet(), open, closedAt, pr)

/** GitHub-shaped JSON for an issue. */
fun issueJson(
    number: Int,
    vararg labels: String,
    title: String = "Task $number",
    state: String = "open",
    closedAt: String? = null,
    pr: Boolean = false,
): Map<String, Any?> = buildMap {
    put("number", number.toLong())
    put("title", title)
    put("state", state)
    put("labels", labels.map { mapOf("id" to 1L, "name" to it, "color" to "ededed") })
    put("closed_at", closedAt)
    put("user", mapOf("login" to "VeganMarshmallow"))
    if (pr) put("pull_request", mapOf("url" to "https://api.github.com/repos/x/y/pulls/$number"))
}

fun page(vararg issues: Map<String, Any?>): String = JsonWriter.write(issues.toList())

/** Scripted HTTP fake: answers by URL prefix, records every request. */
class FakeHttp : HttpClient {
    val requests = mutableListOf<HttpRequest>()
    private val routes = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()

    fun on(match: (HttpRequest) -> Boolean, respond: (HttpRequest) -> HttpResponse) = apply { routes += match to respond }

    fun onUrlContaining(part: String, response: HttpResponse) = on({ part in it.url }) { response }

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val route = routes.firstOrNull { it.first(request) } ?: throw IOException("no route for ${request.url}")
        return route.second(request)
    }
}

fun ok(body: String, vararg headers: Pair<String, String>) =
    HttpResponse(200, headers.associate { it.first to listOf(it.second) }, body)

fun status(code: Int, vararg headers: Pair<String, String>, body: String = "{\"message\":\"x\"}") =
    HttpResponse(code, headers.associate { it.first to listOf(it.second) }, body)
