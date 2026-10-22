package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorClassifier
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.http.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ErrorClassifierTest {
    private fun classify(r: HttpResponse) = ErrorClassifier.classify(r, NOW)

    @Test fun successIsNotAnError() {
        listOf(200, 201, 204, 299).forEach { assertNull(classify(status(it))) }
    }

    @Test fun unauthorized() = assertEquals(BoardError.BadToken(401), classify(status(401)))

    @Test fun forbiddenWithoutRateLimit() {
        assertEquals(BoardError.BadToken(403), classify(status(403)))
        assertEquals(BoardError.BadToken(403), classify(status(403, "x-ratelimit-remaining" to "4999")))
    }

    @Test fun forbiddenWithPrimaryRateLimit() {
        val reset = 1_792_671_300L
        assertEquals(
            BoardError.RateLimited(Instant.ofEpochSecond(reset)),
            classify(status(403, "X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "$reset")),
        )
    }

    @Test fun rateLimitWithoutUsableReset() {
        assertEquals(BoardError.RateLimited(null), classify(status(403, "x-ratelimit-remaining" to "0")))
        assertEquals(BoardError.RateLimited(null), classify(status(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "soon")))
        assertEquals(
            BoardError.RateLimited(NOW.plusSeconds(30)),
            classify(status(403, "x-ratelimit-remaining" to "0", "retry-after" to "30")),
        )
    }

    @Test fun secondaryRateLimitViaRetryAfter() {
        assertEquals(BoardError.RateLimited(NOW.plusSeconds(60)), classify(status(403, "Retry-After" to "60")))
        assertEquals(BoardError.RateLimited(NOW.plusSeconds(5)), classify(status(429, "retry-after" to " 5 ")))
        assertEquals(BoardError.BadToken(403), classify(status(403, "retry-after" to "-5")))
    }

    @Test fun tooManyRequests() {
        assertEquals(BoardError.RateLimited(null), classify(status(429)))
        val reset = 1_792_671_400L
        assertEquals(
            BoardError.RateLimited(Instant.ofEpochSecond(reset)),
            classify(status(429, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset")),
        )
    }

    @Test fun rateLimitHeadersIgnoredOnOtherStatuses() {
        assertEquals(BoardError.BadToken(401), classify(status(401, "x-ratelimit-remaining" to "0")))
        assertEquals(BoardError.NoAccess, classify(status(404, "x-ratelimit-remaining" to "0")))
    }

    @Test fun notFound() = assertEquals(BoardError.NoAccess, classify(status(404)))

    @Test fun serverErrors() {
        listOf(500, 502, 503, 504, 599).forEach { assertEquals(BoardError.Server(it), classify(status(it))) }
    }

    @Test fun otherStatusesAreUnexpected() {
        listOf(100, 301, 304, 400, 410, 422, 600).forEach { assertEquals(BoardError.Unexpected("HTTP $it"), classify(status(it))) }
    }

    @Test fun messagesAreSingleLineAndSetupRoutingIsRight() {
        val messages = ErrorMessages { "14:05" }
        val all = listOf(
            BoardError.NoToken, BoardError.BadToken(401), BoardError.BadToken(403), BoardError.NoAccess,
            BoardError.RateLimited(NOW), BoardError.RateLimited(null), BoardError.Network,
            BoardError.Server(502), BoardError.Unexpected("bad"),
        )
        all.forEach {
            val m = messages.widgetMessage(it)
            assertTrue(m.isNotBlank())
            assertFalse(m.contains('\n'))
            assertFalse(m.contains(TOKEN))
        }
        assertTrue(messages.message(BoardError.RateLimited(NOW)).contains("14:05"))
        assertTrue(messages.message(BoardError.RateLimited(null)).contains("few minutes"))
        assertTrue(messages.widgetMessage(BoardError.NoToken).endsWith("tap to set up"))
        assertTrue(messages.widgetMessage(BoardError.BadToken(401)).endsWith("tap to fix"))
        assertTrue(messages.widgetMessage(BoardError.Network).endsWith("tap to retry"))
        assertFalse(messages.message(BoardError.NoAccess).contains("tap"))
        assertTrue(messages.opensSetup(BoardError.NoToken))
        assertTrue(messages.opensSetup(BoardError.BadToken(401)))
        assertTrue(messages.opensSetup(BoardError.NoAccess))
        assertFalse(messages.opensSetup(BoardError.Network))
        assertFalse(messages.opensSetup(BoardError.RateLimited(null)))
    }
}
