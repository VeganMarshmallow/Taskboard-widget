package io.github.veganmarshmallow.taskboard.core.github

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Outcome

/**
 * Fetches every page of a listing (issues, or comments for task #82) by following `Link: rel="next"`,
 * up to [maxPages]. [send] performs one request and returns the parsed page plus its Link header, or an error.
 */
class PagedFetcher<T>(
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val pageRequest: (String) -> HttpRequest,
    private val send: (HttpRequest) -> Outcome<Page<T>>,
) {
    init {
        require(maxPages >= 1) { "maxPages must be at least 1" }
    }

    data class Page<T>(val issues: List<T>, val linkHeader: String?)

    /** All items across pages. [truncated] is true when [maxPages] stopped us early. */
    data class Result<T>(val issues: List<T>, val truncated: Boolean)

    fun fetchAll(first: HttpRequest): Outcome<Result<T>> {
        val all = ArrayList<T>()
        val seenUrls = HashSet<String>()
        var request = first
        repeat(maxPages) {
            seenUrls.add(request.url)
            val page = when (val outcome = send(request)) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> return outcome
            }
            all.addAll(page.issues)
            val next = LinkHeader.nextUrl(page.linkHeader)
                ?: return Outcome.Success(Result(all, truncated = false))
            if (next in seenUrls) return Outcome.Success(Result(all, truncated = false)) // loop guard
            request = try {
                pageRequest(next)
            } catch (e: IllegalArgumentException) {
                return Outcome.Failure(BoardError.Unexpected("bad pagination link"))
            }
        }
        return Outcome.Success(Result(all, truncated = true))
    }

    companion object {
        /** 20 pages x 100 = 2000 issues per listing; far beyond what this board will hold. */
        const val DEFAULT_MAX_PAGES = 20
    }
}
