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

/** The Work pace setting (#83): its values, `.board/settings.json` text, and reading/writing it on GitHub. */
class WorkPaceTest {
    @Test fun valuesAreReadIgnoringCaseAndSpaces() {
        assertEquals(WorkPace.SAVE, WorkPace.of("save"))
        assertEquals(WorkPace.FAST, WorkPace.of("fast"))
        assertEquals(WorkPace.FAST, WorkPace.of(" FAST\n"))
        assertEquals(WorkPace.FAST, WorkPace.of("Fast"))
        assertNull(WorkPace.ofOrNull("faster"))
        assertNull(WorkPace.ofOrNull(""))
        assertNull(WorkPace.ofOrNull(null))
        assertEquals("unknown is Save usage", WorkPace.SAVE, WorkPace.of("turbo"))
        assertEquals(WorkPace.SAVE, WorkPace.DEFAULT)
    }

    @Test fun missingOrBrokenFilesAreSaveUsage() {
        for (text in listOf(null, "", "   \n", "{", "not json", "[]", "\"fast\"", "null", "42", "{}", """{"pace":null}""",
            """{"pace":true}""", """{"pace":1}""", """{"pace":["fast"]}""", """{"pace":"warp"}""", """{"Pace":"fast"}""")) {
            assertEquals("for $text", WorkPace.SAVE, BoardSettings.pace(text))
        }
    }

    @Test fun storedValuesAreRead() {
        assertEquals(WorkPace.FAST, BoardSettings.pace("""{"pace": "fast"}"""))
        assertEquals(WorkPace.SAVE, BoardSettings.pace("""{"pace":"save"}"""))
        assertEquals(WorkPace.FAST, BoardSettings.pace("﻿{\n  \"pace\" : \" Fast \",\n  \"other\": {\"x\": [1, 2]}\n}\n"))
        assertEquals("extra keys are fine", WorkPace.FAST, BoardSettings.pace("""{"theme":"ink","pace":"fast","n":3}"""))
    }

    @Test fun writingKeepsTheOtherKeysInOrder() {
        val written = BoardSettings.withPace("""{"theme":"ink","pace":"save","n":3,"deep":{"a":[1,2.5,null,true]}}""", WorkPace.FAST)
        assertEquals("""{"theme":"ink","pace":"fast","n":3,"deep":{"a":[1,2.5,null,true]}}""" + "\n", written)
        assertEquals("""{"theme":"ink","pace":"fast"}""" + "\n", BoardSettings.withPace("""{"theme":"ink"}""", WorkPace.FAST))
    }

    @Test fun writingOverAMissingOrBrokenFileStartsFresh() {
        for (text in listOf(null, "", "{", "[1]", "\"x\"")) {
            assertEquals("for $text", """{"pace":"save"}""" + "\n", BoardSettings.withPace(text, WorkPace.SAVE))
        }
    }

    @Test fun writtenTextReadsBack() {
        for (pace in WorkPace.entries) assertEquals(pace, BoardSettings.pace(BoardSettings.withPace("""{"a":"\"q\"\n"}""", pace)))
    }

    @Test fun storesOnlyTheExactValue() {
        assertTrue(BoardSettings.stores("""{"pace":"fast"}""", WorkPace.FAST))
        assertFalse(BoardSettings.stores("""{"pace":"FAST"}""", WorkPace.FAST))
        assertFalse(BoardSettings.stores("{}", WorkPace.SAVE))
        assertFalse(BoardSettings.stores(null, WorkPace.SAVE))
    }

    @Test fun onlyFasterAddsAMergeTrailer() {
        assertEquals("Work-Pace: fast", WorkPace.FAST.mergeTrailer)
        assertNull(WorkPace.SAVE.mergeTrailer)
        assertTrue(WorkPace.FAST.mergesBackToBack)
        assertFalse(WorkPace.SAVE.mergesBackToBack)
    }

    // --- the Contents API ---

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

    private companion object {
        fun fileJson(text: String, sha: String): String {
            val b64 = Base64.getMimeEncoder().encodeToString(text.toByteArray()).replace("\r\n", "\\n")
            return """{"type":"file","encoding":"base64","sha":"$sha","path":".board/settings.json","content":"$b64"}"""
        }
    }
}
