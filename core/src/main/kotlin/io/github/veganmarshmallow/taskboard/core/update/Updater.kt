package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.error.NetworkProbe
import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import java.time.Clock

/** The update check, wired from one HTTP client, the saved token and the device's [NetworkProbe]. */
class Updater(
    http: StreamingHttpClient,
    token: String,
    clock: Clock = Clock.systemUTC(),
    probe: NetworkProbe? = null,
) {
    private val requests = UpdateRequests(token = token)
    private val fetcher = AssetFetcher(http, clock, probe)

    fun check(): UpdateResult<AvailableUpdate> = UpdateChecker(fetcher, requests).latest()
}
