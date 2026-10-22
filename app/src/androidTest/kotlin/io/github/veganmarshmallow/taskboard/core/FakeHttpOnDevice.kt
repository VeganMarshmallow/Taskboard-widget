package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse

/** Canned GitHub responses: two pages of open issues (with a Link header) and one done issue. */
class FakeHttpOnDevice : HttpClient {
    var requests = 0
        private set

    override fun execute(request: HttpRequest): HttpResponse {
        requests++
        val url = request.url
        return when {
            "page=2" in url -> HttpResponse(200, body = """[{"number":3,"title":"לוח ✅","state":"open","labels":[{"name":"status: in-progress"}]}]""")
            "state=open" in url -> HttpResponse(
                200,
                mapOf("Link" to listOf("<https://api.github.com/repositories/9/issues?state=open&page=2>; rel=\"next\"")),
                """[{"number":2,"title":"Task","state":"open","labels":[{"name":"status: todo"}],"pull_request":null}]""",
            )
            else -> HttpResponse(200, body = """[{"number":4,"title":"Done","state":"closed","closed_at":"2026-10-21T10:00:00+03:00","labels":[{"name":"status: done"}]}]""")
        }
    }
}
