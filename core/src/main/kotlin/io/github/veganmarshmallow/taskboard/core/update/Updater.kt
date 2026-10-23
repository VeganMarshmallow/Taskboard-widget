package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import java.time.Clock

/** The update check, wired from one HTTP client and the saved token. */
class Updater(http: StreamingHttpClient, token: String, clock: Clock = Clock.systemUTC()) {
    private val requests = UpdateRequests(token = token)
    private val fetcher = AssetFetcher(http, clock)

    fun check(): UpdateResult<AvailableUpdate> = UpdateChecker(fetcher, requests).latest()
}
