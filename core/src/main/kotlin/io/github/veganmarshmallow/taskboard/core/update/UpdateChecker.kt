package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.http.StreamingResponse
import io.github.veganmarshmallow.taskboard.core.update.UpdateErrorMapper.Step
import java.io.IOException

/**
 * Reads the `latest` release of Taskboard-widget and its `version.json` (falling back to the
 * versionCode in the release notes for releases published before version.json existed).
 */
class UpdateChecker(private val fetcher: AssetFetcher, private val requests: UpdateRequests) {

    fun latest(): UpdateResult<AvailableUpdate> = readText(fetcher.fetch(requests.latestRelease(), Step.RELEASE), RELEASE_LIMIT)
        .let { result -> if (result.isNoRelease()) probeAccess() else result }
        .flatMap { parse(it, ReleaseJsonParser::parse) }
        .flatMap(::describe)

    private fun describe(release: Release): UpdateResult<AvailableUpdate> {
        val selection = ReleaseAssets.select(release)
        val apk = selection.apk ?: return UpdateResult.Err(UpdateError.NoRelease)
        val version = selection.versionJson?.let(::readVersionJson)
            ?: ReleaseNotesVersion.parse(release.notes)?.let { UpdateResult.Ok(it) }
            ?: return UpdateResult.Err(UpdateError.Malformed("the release says nothing about its version"))
        return version.map { AvailableUpdate(it, apk) }
    }

    private fun readVersionJson(asset: ReleaseAsset): UpdateResult<VersionInfo> =
        readText(fetcher.fetch(requests.asset(asset), Step.ASSET), VERSION_LIMIT).flatMap { parse(it, VersionJsonParser::parse) }

    /** A release 404 means "no release" only if the token can read the repo's releases at all. */
    private fun probeAccess(): UpdateResult<String> = when (val probe = fetcher.fetch(requests.releasesProbe(), Step.REPOSITORY)) {
        is UpdateResult.Ok -> { probe.value.close(); UpdateResult.Err(UpdateError.NoRelease) }
        is UpdateResult.Err -> probe
    }

    private fun readText(result: UpdateResult<StreamingResponse>, limit: Int): UpdateResult<String> = result.flatMap { response ->
        try {
            val text = response.readText(limit + 1)
            if (text.length > limit) UpdateResult.Err(UpdateError.Malformed("response too large")) else UpdateResult.Ok(text)
        } catch (e: IOException) {
            UpdateResult.Err(UpdateError.Network)
        } finally {
            response.close()
        }
    }

    private fun <T> parse(text: String, parser: (String) -> T): UpdateResult<T> = try {
        UpdateResult.Ok(parser(text))
    } catch (e: MalformedVersionException) {
        UpdateResult.Err(UpdateError.Malformed(e.message ?: "malformed"))
    }

    private fun UpdateResult<*>.isNoRelease() = this is UpdateResult.Err && error == UpdateError.NoRelease

    private companion object {
        const val RELEASE_LIMIT = 1024 * 1024
        const val VERSION_LIMIT = 64 * 1024
    }
}
