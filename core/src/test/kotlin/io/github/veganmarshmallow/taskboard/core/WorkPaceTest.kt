package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.HttpRequest
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.json.JsonParser
import io.github.veganmarshmallow.taskboard.core.merge.mergePull
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

/** The Work pace setting (#83): its values, `.board/settings.json` text, and the merge trailer. Contents API calls: [BoardSettingsApiTest]. */
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

    // --- review (#83) ---

    @Test fun onlyNonBlankNonObjectTextIsUnreadable() {
        for (text in listOf(null, "", "  \n", "{}", """{"pace":"x"}""", "\uFEFF{}")) assertFalse("for $text", BoardSettings.replacesUnreadable(text))
        for (text in listOf("not json {", "[1]", "\"fast\"", "42", """{"pace":"fast"""")) assertTrue("for $text", BoardSettings.replacesUnreadable(text))
    }

    @Test fun theFasterMergeMessageIsThePrTitleThenTheTrailer() {
        val requests = GitHubApi(FakeHttp(), "github_pat_TESTONLY").requests
        fun message(title: String, pace: WorkPace) =
            (JsonParser.parse(requests.mergePull(mergeAllPr(7, sha = sha('7')).copy(title = title), pace).body!!) as Map<*, *>)["commit_message"]
        assertEquals("Add the thing\n\nWork-Pace: fast", message("Add the thing", WorkPace.FAST))
        assertEquals("a blank title still leaves the trailer its own paragraph", "PR #7\n\nWork-Pace: fast", message("  ", WorkPace.FAST))
        assertNull("Save usage keeps GitHub's default message", message("Add the thing", WorkPace.SAVE))
    }
}
