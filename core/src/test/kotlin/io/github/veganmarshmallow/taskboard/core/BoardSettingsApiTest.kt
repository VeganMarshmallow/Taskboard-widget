package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.pace.BoardSettings
import io.github.veganmarshmallow.taskboard.core.pace.BoardSettingsApi
import io.github.veganmarshmallow.taskboard.core.pace.SettingsFile
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** `.board/settings.json` (#83) read and written through the Contents API, against a fake GitHub. */
class BoardSettingsApiTest {
    /** The board repo's settings file on a fake GitHub: [text] null means there is none. */
    private class Contents(var text: String? = null, var sha: String = "sha-1") {
        var putStatus = ArrayDeque<Int>()
        var writes = 0
        var getStatus = 200

        fun answer(r: HttpRequest) = when {
            !r.url.endsWith("/repos/VeganMarshmallow/claude-workspace/contents/.board/settings.json") -> throw IOException("no route for ${r.url}")
            r.method == "GET" -> when {
                getStatus != 200 -> status(getStatus)
                text == null -> status(404, body = """{"message":"Not Found"}""")
                else -> ok(fileJson(text!!, sha))
            }
            r.method == "PUT" -> {
                val code = putStatus.removeFirstOrNull() ?: 200
                if (code in 200..299) {
                    val body = JsonParser.parse(r.body!!) as Map<*, *>
                    text = String(Base64.getDecoder().decode(body["content"] as String))
                    sha = "sha-${++writes + 1}"
                    status(if (code == 200 && body["sha"] == null) 201 else code, body = """{"content":{"sha":"$sha"}}""")
                } else {
                    status(code, body = """{"message":"conflict"}""")
                }
            }
            else -> throw IOException("unexpected ${r.method}")
        }
    }

    private val gh = Contents()
    private val http = FakeHttp().on({ true }, gh::answer)
    private val settings = BoardSettingsApi(GitHubApi(http, "github_pat_TESTONLY"))
    private val puts get() = http.requests.filter { it.method == "PUT" }.map { JsonParser.parse(it.body!!) as Map<*, *> }

    @Test fun noFileReadsAsSaveUsage() {
        assertEquals(Outcome.Success(null), settings.file())
        assertEquals(Outcome.Success(WorkPace.SAVE), settings.pace())
    }

    @Test fun aStoredFileIsDecoded() {
        gh.text = """{"pace":"fast","note":"שלום"}"""
        assertEquals(Outcome.Success(SettingsFile(gh.text!!, "sha-1")), settings.file())
        assertEquals(Outcome.Success(WorkPace.FAST), settings.pace())
    }

    @Test fun savingWithoutAFileCreatesIt() {
        assertEquals(Outcome.Success(WorkPace.FAST), settings.save(WorkPace.FAST))
        assertEquals("""{"pace":"fast"}""" + "\n", gh.text)
        assertNull("a new file is sent without a sha", puts.single()["sha"])
        assertTrue((puts.single()["message"] as String).contains("fast"))
    }

    @Test fun savingOverAFileSendsItsShaAndKeepsItsKeys() {
        gh.text = """{"theme":"ink","pace":"save"}"""
        assertEquals(Outcome.Success(WorkPace.FAST), settings.save(WorkPace.FAST))
        assertEquals("sha-1", puts.single()["sha"])
        assertEquals("""{"theme":"ink","pace":"fast"}""" + "\n", gh.text)
    }

    @Test fun savingTheStoredValueAgainWritesNothing() {
        gh.text = """{"pace":"fast"}"""
        assertEquals(Outcome.Success(WorkPace.FAST), settings.save(WorkPace.FAST))
        assertTrue(puts.isEmpty())
    }

    @Test fun aConflictReadsAgainAndRetriesOnce() {
        gh.text = """{"pace":"save"}"""
        gh.putStatus.addAll(listOf(409))
        assertEquals(Outcome.Success(WorkPace.FAST), settings.save(WorkPace.FAST))
        assertEquals(2, puts.size)
        assertEquals(2, http.requests.count { it.method == "GET" })
        assertEquals(WorkPace.FAST, BoardSettings.pace(gh.text))
    }

    @Test fun aFileCreatedMeanwhileIs422AndRetriedWithItsSha() {
        gh.putStatus.addAll(listOf(422))
        assertEquals(Outcome.Success(WorkPace.FAST), settings.save(WorkPace.FAST))
        assertEquals(2, puts.size)
    }

    @Test fun twoConflictsInARowFail() {
        gh.text = "{}"
        gh.putStatus.addAll(listOf(409, 409))
        val failed = settings.save(WorkPace.FAST) as Outcome.Failure
        assertTrue(failed.error is BoardError.Unexpected)
        assertEquals(BoardSettingsApi.ATTEMPTS, puts.size)
    }

    @Test fun noWriteAccessIsABadToken() {
        gh.putStatus.addAll(listOf(403))
        assertEquals(Outcome.Failure(BoardError.BadToken(403)), settings.save(WorkPace.FAST))
        assertEquals(1, puts.size)
    }

    @Test fun readErrorsAreClassified() {
        gh.getStatus = 401
        assertEquals(Outcome.Failure(BoardError.BadToken(401)), settings.pace())
        assertEquals("no write after a failed read", Outcome.Failure(BoardError.BadToken(401)), settings.save(WorkPace.FAST))
        assertTrue(puts.isEmpty())
        gh.getStatus = 502
        assertEquals(Outcome.Failure(BoardError.Server(502)), settings.pace())
    }

    @Test fun offlineIsANetworkError() {
        val offline = BoardSettingsApi(GitHubApi(FakeHttp(), "t"))
        assertEquals(Outcome.Failure(BoardError.Network), offline.pace())
        assertEquals(Outcome.Failure(BoardError.Network), offline.save(WorkPace.FAST))
    }

    @Test fun unreadableResponsesAreUnexpected() {
        for (body in listOf("[]", "{}", """{"sha":"x","content":"e30="}""", """{"sha":"x","encoding":"none","content":"{}"}""", "garbage")) {
            assertNull("for $body", BoardSettingsApi.decode(body))
        }
        val a = BoardSettingsApi(GitHubApi(FakeHttp().on({ true }) { ok("[]") }, "t"))
        assertTrue((a.pace() as Outcome.Failure).error is BoardError.Unexpected)
    }

    @Test fun base64WithLineBreaksIsDecoded() {
        val long = """{"pace":"fast","pad":"${"x".repeat(200)}"}"""
        val wrapped = Base64.getEncoder().encodeToString(long.toByteArray()).chunked(60).joinToString("\n")
        assertEquals(SettingsFile(long, "s"), BoardSettingsApi.decode("""{"sha":"s","encoding":"base64","content":"${wrapped.replace("\n", "\\n")}"}"""))
    }

    // --- review (#83): an unreadable file, other write errors ---

    @Test fun savingOverAnUnreadableFileReplacesItAndSaysSoInTheCommit() {
        gh.text = "pace = fast # not JSON"
        assertEquals(Outcome.Success(WorkPace.SAVE), settings.save(WorkPace.SAVE))
        assertEquals("""{"pace":"save"}""" + "\n", gh.text)
        assertEquals("sha-1", puts.single()["sha"])
        assertTrue((puts.single()["message"] as String).contains("was not a JSON object"))
    }

    @Test fun anOrdinarySaveHasAOneLineCommitMessage() {
        gh.text = """{"pace":"save"}"""
        settings.save(WorkPace.FAST)
        assertEquals("Work pace: fast (set in the widget)", puts.single()["message"])
    }

    @Test fun aServerErrorOnTheWriteFailsWithoutARetry() {
        gh.text = """{"pace":"save"}"""
        gh.putStatus.addAll(listOf(500))
        assertTrue(settings.save(WorkPace.FAST) is Outcome.Failure)
        assertEquals(1, puts.size)
        assertEquals("""{"pace":"save"}""", gh.text)
    }

    @Test fun aReadFailureBeforeTheWriteSendsNothing() {
        gh.getStatus = 500
        assertTrue(settings.save(WorkPace.FAST) is Outcome.Failure)
        assertTrue(puts.isEmpty())
    }


    @Test fun aRetryAfterAConflictSendsTheNewShaAndKeepsTheKeyWrittenMeanwhile() {
        gh.text = """{"pace":"save"}"""
        gh.putStatus.addAll(listOf(409))
        val racing = FakeHttp().on({ true }) { r ->
            val answer = gh.answer(r)
            if (r.method == "PUT" && answer.status == 409) {
                gh.text = """{"pace":"save","theme":"ink"}""" // another phone, or Claude, wrote first
                gh.sha = "sha-other"
            }
            answer
        }
        assertEquals(Outcome.Success(WorkPace.FAST), BoardSettingsApi(GitHubApi(racing, "github_pat_TESTONLY")).save(WorkPace.FAST))
        val sent = racing.requests.filter { it.method == "PUT" }.map { (JsonParser.parse(it.body!!) as Map<*, *>)["sha"] }
        assertEquals(listOf("sha-1", "sha-other"), sent)
        assertEquals("""{"pace":"fast","theme":"ink"}""" + "\n", gh.text)
    }

    private companion object {
        fun fileJson(text: String, sha: String): String {
            val b64 = Base64.getMimeEncoder().encodeToString(text.toByteArray()).replace("\r\n", "\\n")
            return """{"type":"file","encoding":"base64","sha":"$sha","path":".board/settings.json","content":"$b64"}"""
        }
    }
}
