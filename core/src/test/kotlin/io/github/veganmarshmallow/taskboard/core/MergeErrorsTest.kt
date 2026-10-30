package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeErrors
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeErrorsTest {
    private val p = pr(12)
    private fun merge(code: Int, body: String = "{}", vararg headers: Pair<String, String>) =
        MergeErrors.forMerge(status(code, *headers, body = body), NOW, p)
    private fun read(code: Int, vararg headers: Pair<String, String>, body: String = "{}") =
        MergeErrors.forRead(status(code, *headers, body = body), NOW, WIDGET)

    @Test fun mergedIsSuccess() {
        assertNull(merge(200, """{"merged":true,"message":"Pull Request successfully merged"}"""))
        assertEquals(MergeProblem.Conflicts(p, "nope"), merge(200, """{"merged":false,"message":"nope"}"""))
    }

    @Test fun notMergeableIsConflicts() {
        assertEquals(MergeProblem.Conflicts(p, "Pull Request is not mergeable"), merge(405, """{"message":"Pull Request is not mergeable"}"""))
        assertEquals(MergeProblem.Conflicts(p, null), merge(405, "<html>"))
    }

    @Test fun alreadyMerged() = assertEquals(MergeProblem.AlreadyMerged(p), merge(405, """{"message":"Pull Request is already merged"}"""))

    @Test fun headChanged() = assertEquals(MergeProblem.HeadChanged(p), merge(409, """{"message":"Head branch was modified. Review and try the merge again."}"""))

    @Test fun accessErrors() {
        assertEquals(MergeProblem.NoRepoAccess(WIDGET, p), merge(403, """{"message":"Resource not accessible by personal access token"}"""))
        assertEquals(MergeProblem.NoRepoAccess(WIDGET, p), merge(404))
        assertEquals(MergeProblem.NoRepoAccess(WIDGET), read(403))
        assertEquals(MergeProblem.NoRepoAccess(WIDGET), read(404))
    }

    @Test fun rateLimitsAreNotAccessErrors() {
        val m = merge(403, "{}", "x-ratelimit-remaining" to "0")
        assertTrue(m is MergeProblem.Failed && m.error is BoardError.RateLimited)
        val r = read(403, "retry-after" to "30")
        assertEquals(MergeProblem.Failed(BoardError.RateLimited(NOW.plusSeconds(30))), r)
        assertTrue((read(429) as MergeProblem.Failed).error is BoardError.RateLimited)
    }

    @Test fun otherStatusesUseTheBoardClassification() {
        assertEquals(MergeProblem.Failed(BoardError.BadToken(401)), merge(401))
        assertEquals(MergeProblem.Failed(BoardError.BadToken(401)), read(401))
        assertEquals(MergeProblem.Failed(BoardError.Server(502)), merge(502))
        assertEquals(MergeProblem.Failed(BoardError.Server(500)), read(500))
        assertEquals(MergeProblem.Failed(BoardError.Unexpected("HTTP 418")), read(418))
        assertEquals(MergeProblem.Failed(BoardError.Unexpected("HTTP 422: Invalid merge_method")), merge(422, """{"message":"Invalid merge_method"}"""))
        assertNull(read(200))
    }

    @Test fun longMessagesAreCut() {
        val m = merge(405, """{"message":"${"x".repeat(1000)}"}""") as MergeProblem.Conflicts
        assertTrue(m.detail!!.length <= 160)
    }
}
