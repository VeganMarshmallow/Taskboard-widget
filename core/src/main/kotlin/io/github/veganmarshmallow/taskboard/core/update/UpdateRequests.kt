package io.github.veganmarshmallow.taskboard.core.update

import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.model.Repo

/** The REST requests the updater sends. Pure: no I/O. Only api.github.com URLs ever carry the token. */
class UpdateRequests(private val repo: Repo = APP_REPO, private val token: String) {
    init {
        require(token.isNotBlank()) { "token must not be blank" }
    }

    /** The release CI publishes from main. */
    fun latestRelease(): HttpRequest = get("${GitHubRequests.API}/repos/${repo.fullName}/releases/tags/$LATEST_TAG", JSON)

    /** Any release at all: tells "the token can read releases" (200) from "no access" (403/404). */
    fun releasesProbe(): HttpRequest = get("${GitHubRequests.API}/repos/${repo.fullName}/releases?per_page=1", JSON)

    /** The raw bytes of [asset]; GitHub answers with a redirect to a storage host. */
    fun asset(asset: ReleaseAsset): HttpRequest {
        require(GitHubRequests.isApiUrl(asset.apiUrl)) { "Refusing to send credentials to a non-GitHub URL" }
        return get(asset.apiUrl, BINARY)
    }

    private fun get(url: String, accept: String) = HttpRequest(
        "GET", url,
        mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to accept,
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "taskboard-widget",
        ),
    )

    companion object {
        val APP_REPO = Repo("VeganMarshmallow", "Taskboard-widget")
        const val LATEST_TAG = "latest"
        private const val JSON = "application/vnd.github+json"
        private const val BINARY = "application/octet-stream"
    }
}
