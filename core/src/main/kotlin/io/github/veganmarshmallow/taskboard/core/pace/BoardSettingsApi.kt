package io.github.veganmarshmallow.taskboard.core.pace

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.github.GitHubRequests
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.json.string
import io.github.veganmarshmallow.taskboard.core.merge.repoPath
import io.github.veganmarshmallow.taskboard.core.model.Repo
import java.util.Base64

/** `.board/settings.json` as GitHub has it now: its text and blob SHA (needed to overwrite it). */
data class SettingsFile(val text: String, val sha: String)

/**
 * Reads and writes the board's `.board/settings.json` (#83) through the Contents API, with the
 * owner's token (Contents: Read and write on the board repo). Neither call throws.
 * - A 404 on reading means there is no file yet: Save usage, and the first save creates it.
 * - Saving keeps the file's other keys ([BoardSettings.withPace]; a file that isn't a JSON object is
 *   replaced, and the commit message says so) and sends the SHA it read, so a
 *   write in between (another phone, Claude, the owner on github.com) is refused (409, or 422 for a
 *   file created meanwhile) instead of overwritten: then it reads again and tries once more.
 */
class BoardSettingsApi(private val api: GitHubApi, private val repo: Repo = Repo.BOARD) {
    /** The file now; Success(null) when it doesn't exist. */
    fun file(): Outcome<SettingsFile?> {
        val response = when (val sent = send { api.requests.contents(repo) }) {
            is Outcome.Failure -> return sent
            is Outcome.Success -> sent.value
        }
        if (response.status == 404) return Outcome.Success(null)
        ErrorClassifier.classify(response, api.clock.instant())?.let { return Outcome.Failure(it) }
        return decode(response.body)?.let { Outcome.Success(it) } ?: Outcome.Failure(BoardError.Unexpected("unreadable $PATH"))
    }

    /** The stored pace ([WorkPace.DEFAULT] when there is no file or it names none). */
    fun pace(): Outcome<WorkPace> = file().map { BoardSettings.pace(it?.text) }

    /** Stores [pace]; Success with it once GitHub has the new file. */
    fun save(pace: WorkPace): Outcome<WorkPace> {
        repeat(ATTEMPTS) {
            val current = when (val read = file()) {
                is Outcome.Failure -> return read
                is Outcome.Success -> read.value
            }
            if (current != null && BoardSettings.stores(current.text, pace)) {
                return Outcome.Success(pace) // already stored exactly so: no commit
            }
            val text = BoardSettings.withPace(current?.text, pace)
            val response = when (val sent = send { api.requests.putContents(repo, text, current?.sha, commitMessage(pace, current?.text)) }) {
                is Outcome.Failure -> return sent
                is Outcome.Success -> sent.value
            }
            if (response.status == 409 || response.status == 422) return@repeat // written meanwhile: read again
            ErrorClassifier.classify(response, api.clock.instant())?.let { return Outcome.Failure(it) }
            return Outcome.Success(pace)
        }
        return Outcome.Failure(BoardError.Unexpected("$PATH kept changing while saving"))
    }

    private fun commitMessage(pace: WorkPace, old: String?): String {
        val message = "Work pace: ${pace.value} (set in the widget)"
        return if (BoardSettings.replacesUnreadable(old)) "$message\n\nThe previous $PATH was not a JSON object; it was replaced (see this file's history)." else message
    }

    private fun send(build: () -> HttpRequest): Outcome<HttpResponse> = api.send(build()) { null }

    companion object {
        const val PATH = BoardSettings.PATH

        /** The first write, and one more after a conflict. */
        const val ATTEMPTS = 2

        /** A Contents API file response's text and SHA; null for anything else (a directory listing, bad base64). */
        internal fun decode(body: String): SettingsFile? = try {
            val obj = JsonParser.parse(body) as? Map<*, *>
            val sha = obj?.string("sha")?.takeIf { it.isNotEmpty() }
            val content = obj?.string("content")
            if (sha == null || content == null || obj.string("encoding")?.lowercase() != "base64") {
                null
            } else {
                SettingsFile(String(Base64.getMimeDecoder().decode(content), Charsets.UTF_8), sha)
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun contentsUrl(repo: Repo) = "${repoPath(repo)}/contents/${BoardSettings.PATH}"

internal fun GitHubRequests.contents(repo: Repo): HttpRequest = get(contentsUrl(repo))

/** Creates the file ([sha] null) or replaces the version [sha] names. */
internal fun GitHubRequests.putContents(repo: Repo, text: String, sha: String?, message: String): HttpRequest {
    val body = linkedMapOf<String, Any?>("message" to message, "content" to Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
    if (sha != null) body["sha"] = sha
    return withJson("PUT", contentsUrl(repo), body)
}
