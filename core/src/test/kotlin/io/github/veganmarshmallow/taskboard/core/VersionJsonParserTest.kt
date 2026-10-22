package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.update.MalformedVersionException
import io.github.veganmarshmallow.taskboard.core.update.VersionComparison
import io.github.veganmarshmallow.taskboard.core.update.VersionInfo
import io.github.veganmarshmallow.taskboard.core.update.VersionJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VersionJsonParserTest {
    private val sha = "a".repeat(64)

    private fun rejects(text: String) {
        try {
            VersionJsonParser.parse(text)
            fail("accepted: $text")
        } catch (e: MalformedVersionException) {
            // expected
        }
    }

    @Test fun parsesWhatCiPublishes() {
        val v = VersionJsonParser.parse(
            """{"versionCode":42,"versionName":"0.1.42","sha":"776dc5f6623ab408acd13439f7f081a22c508fd4","apkSha256":"$sha","apkSize":704244}""",
        )
        assertEquals(VersionInfo(42, "0.1.42", "776dc5f6623ab408acd13439f7f081a22c508fd4", sha, 704244), v)
        assertEquals("v0.1.42", v.label)
    }

    @Test fun onlyVersionCodeIsRequired() {
        val v = VersionJsonParser.parse("""{"versionCode":7}""")
        assertEquals(7L, v.versionCode)
        assertEquals("7", v.versionName)
        assertNull(v.commitSha); assertNull(v.apkSha256); assertNull(v.apkSize)
    }

    @Test fun explicitNullsAreTheSameAsMissing() {
        val v = VersionJsonParser.parse("""{"versionCode":7,"versionName":null,"sha":null,"apkSha256":null,"apkSize":null}""")
        assertEquals("7", v.versionName)
        assertNull(v.apkSha256)
    }

    @Test fun acceptsIntegralDoublesAndBomAndWhitespaceAndUnknownFields() {
        assertEquals(42L, VersionJsonParser.parse("﻿ \n{\"versionCode\":42.0,\"extra\":{\"a\":[1,2]}}\n").versionCode)
        assertEquals(42L, VersionJsonParser.parse("""{"versionCode":4.2e1}""").versionCode)
    }

    @Test fun normalisesChecksumAndCommitToLowerCase() {
        val v = VersionJsonParser.parse("""{"versionCode":1,"sha":"ABCDEF1","apkSha256":"${"AB".repeat(32)}"}""")
        assertEquals("abcdef1", v.commitSha)
        assertEquals("ab".repeat(32), v.apkSha256)
    }

    @Test fun rejectsMissingOrInvalidVersionCode() {
        rejects("""{}""")
        rejects("""{"versionCode":null}""")
        rejects("""{"versionCode":"42"}""")
        rejects("""{"versionCode":0}""")
        rejects("""{"versionCode":-1}""")
        rejects("""{"versionCode":4.5}""")
        rejects("""{"versionCode":true}""")
        rejects("""{"versionCode":[42]}""")
        rejects("""{"versionCode":2147483648}""") // above Android's Int versionCode
        rejects("""{"versionCode":1e30}""")
        rejects("""{"VersionCode":42}""") // keys are case-sensitive
    }

    @Test fun maxIntVersionCodeIsAccepted() {
        assertEquals(Int.MAX_VALUE.toLong(), VersionJsonParser.parse("""{"versionCode":2147483647}""").versionCode)
    }

    @Test fun rejectsNonObjectsAndBrokenJson() {
        rejects("")
        rejects("   ")
        rejects("[]")
        rejects("42")
        rejects("null")
        rejects("\"versionCode\"")
        rejects("""{"versionCode":42""")
        rejects("""{"versionCode":42}}""")
        rejects("<html>Not Found</html>")
        rejects("PK\u0003\u0004binary")
    }

    @Test fun rejectsHugeInput() {
        rejects("""{"versionCode":1,"pad":"${"x".repeat(70_000)}"}""")
    }

    @Test fun rejectsMalformedChecksumSizeAndCommit() {
        rejects("""{"versionCode":1,"apkSha256":"abc"}""")
        rejects("""{"versionCode":1,"apkSha256":"${"g".repeat(64)}"}""")
        rejects("""{"versionCode":1,"apkSha256":"${"a".repeat(65)}"}""")
        rejects("""{"versionCode":1,"apkSha256":42}""")
        rejects("""{"versionCode":1,"apkSize":0}""")
        rejects("""{"versionCode":1,"apkSize":-5}""")
        rejects("""{"versionCode":1,"apkSize":"704244"}""")
        rejects("""{"versionCode":1,"apkSize":1.5}""")
        rejects("""{"versionCode":1,"sha":"xyz"}""")
        rejects("""{"versionCode":1,"sha":"abc"}""")
    }

    @Test fun badVersionNamesFallBackToTheCode() {
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":""}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"  "}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"a\nb"}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"${"9".repeat(65)}"}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":12}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"\u0007x"}""").versionName)
        // Bidi controls would reorder "Update available (v…)".
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"1.0\u202e"}""").versionName)
        assertEquals("5", VersionJsonParser.parse("""{"versionCode":5,"versionName":"\u2066x"}""").versionName)
    }

    @Test fun unicodeVersionNameIsKept() {
        assertEquals("1.0-בטא", VersionJsonParser.parse("""{"versionCode":5,"versionName":"1.0-בטא"}""").versionName)
    }

    @Test fun comparisonUsesVersionCodeOnly() {
        val v = VersionInfo(42, "0.0.1")
        assertTrue(VersionComparison.isNewer(v, 41))
        assertFalse(VersionComparison.isNewer(v, 42))
        assertFalse(VersionComparison.isNewer(v, 43))
        assertTrue(VersionComparison.isNewer(v, 0))
        assertTrue(VersionComparison.isNewer(v, -1))
        assertFalse(VersionComparison.isNewer(VersionInfo(Int.MAX_VALUE.toLong(), "x"), Long.MAX_VALUE))
        // "0.1.10" vs "0.1.9" as strings would compare wrongly; codes don't.
        assertTrue(VersionComparison.isNewer(VersionInfo(10, "0.1.10"), 9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun versionInfoRejectsNonPositiveCodes() {
        VersionInfo(0, "x")
    }
}
