package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.http.StreamingHttpClient
import java.time.Clock

/** The updater's operations, wired from one HTTP client and the saved token. */
class Updater(http: StreamingHttpClient, token: String, clock: Clock = Clock.systemUTC()) {
    private val requests = UpdateRequests(token = token)
    private val fetcher = AssetFetcher(http, clock)

    fun check(): UpdateResult<AvailableUpdate> = UpdateChecker(fetcher, requests).latest()

    fun download(
        update: AvailableUpdate,
        target: DownloadTarget,
        packageCheck: PackageCheck,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): UpdateResult<Unit> = ApkDownloader(fetcher, requests).download(update, target, packageCheck, onProgress, isCancelled)

    /**
     * Re-checks first and downloads what the release holds right now. CI recreates the release on
     * every push, so an asset id from an earlier check may already be gone. Returns the update that
     * was downloaded. Fails with [UpdateError.UpToDate] if the latest build is no longer newer than
     * [installedVersionCode].
     */
    fun downloadLatest(
        installedVersionCode: Long,
        target: DownloadTarget,
        packageCheck: PackageCheck,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): UpdateResult<AvailableUpdate> = check().flatMap { update ->
        if (!update.isNewerThan(installedVersionCode)) UpdateResult.Err(UpdateError.UpToDate)
        else download(update, target, packageCheck, onProgress, isCancelled).map { update }
    }
}
