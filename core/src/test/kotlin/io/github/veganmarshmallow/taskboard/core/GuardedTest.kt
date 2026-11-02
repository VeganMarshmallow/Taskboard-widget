package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.work.guarded
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Task #47: background work in the preview reports a crash as a failure instead of crashing the app. */
class GuardedTest {
    @After fun clearInterrupt() {
        Thread.interrupted()
    }

    private fun unexpected(type: String) = Outcome.Failure(BoardError.Unexpected("internal error: $type"))

    @Test fun passesResultsThrough() {
        assertEquals(Outcome.Success(Unit), guarded { Outcome.Success(Unit) })
        assertEquals(Outcome.Failure(BoardError.Network), guarded<Unit> { Outcome.Failure(BoardError.Network) })
        assertEquals(Outcome.Success(null), guarded { Outcome.Success(null) })
    }

    @Test fun runtimeExceptionsBecomeUnexpected() {
        assertEquals(unexpected("IllegalStateException"), guarded<Unit> { throw IllegalStateException("boom") })
        assertEquals(unexpected("NullPointerException"), guarded<Unit> { throw NullPointerException() })
    }

    @Test fun checkedExceptionsAndNonFatalErrorsBecomeUnexpected() {
        assertEquals(unexpected("IOException"), guarded<Unit> { throw java.io.IOException("disk") })
        assertEquals(unexpected("NoSuchMethodError"), guarded<Unit> { throw NoSuchMethodError("x") })
        assertEquals(unexpected("AssertionError"), guarded<Unit> { throw AssertionError("x") })
    }

    @Test fun theErrorNeverCarriesTheExceptionMessage() {
        val token = "github_pat_SECRET123"
        val r = guarded<Unit> { throw IllegalArgumentException("Authorization: Bearer $token") }
        val detail = ((r as Outcome.Failure).error as BoardError.Unexpected).detail
        assertFalse(detail, detail.contains(token))
        assertFalse(detail.contains("Bearer"))
    }

    @Test fun anonymousExceptionsStillGetADetail() {
        val r = guarded<Unit> { throw object : RuntimeException("x") {} }
        assertTrue(r is Outcome.Failure && (r.error as BoardError.Unexpected).detail.startsWith("internal error:"))
    }

    @Test fun fatalVmErrorsAreRethrown() {
        val oom = OutOfMemoryError("heap")
        try {
            guarded<Unit> { throw oom }
            fail("OutOfMemoryError must propagate")
        } catch (e: OutOfMemoryError) {
            assertSame(oom, e)
        }
        try {
            guarded<Unit> { throw StackOverflowError() }
            fail("StackOverflowError must propagate")
        } catch (e: StackOverflowError) {
            // expected
        }
    }

    @Test fun interruptionIsReportedAndTheFlagRestored() {
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(unexpected("InterruptedException"), guarded<Unit> { throw InterruptedException() })
        assertTrue("interrupt flag restored", Thread.currentThread().isInterrupted)
    }
}
