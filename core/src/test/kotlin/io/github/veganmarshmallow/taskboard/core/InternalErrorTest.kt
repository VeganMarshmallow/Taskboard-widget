package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStops
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.task.CreateErrors
import io.github.veganmarshmallow.taskboard.core.work.RefreshPolicy
import io.github.veganmarshmallow.taskboard.core.work.crashError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #55 review: how [BoardError.Internal] is named, shown, and treated by the retry and stop policies. */
class InternalErrorTest {
    private class NestedCrash : RuntimeException("secret")

    @Test fun crashesAreNamedByTheirClassNeverTheirMessage() {
        class LocalCrash : IllegalStateException("token github_pat_X")
        assertEquals(BoardError.Internal("NestedCrash"), crashError(NestedCrash()))
        assertEquals(BoardError.Internal("LocalCrash"), crashError(LocalCrash()))
        assertEquals(BoardError.Internal("IllegalArgumentException"), crashError(object : IllegalArgumentException("x") {}))
        assertEquals(BoardError.Internal("OutOfMemoryError"), crashError(OutOfMemoryError("y")))
        assertEquals(BoardError.Internal("Throwable"), crashError(object : Throwable("z") {}))
    }

    @Test fun aBlankTypeStillReadsAsASentence() {
        val messages = ErrorMessages { "14:05" }
        assertEquals("Something went wrong in the app", messages.message(BoardError.Internal("")))
        assertEquals("Something went wrong in the app", messages.message(BoardError.Internal("  ")))
    }

    @Test fun aCrashIsNeverRetriedOrTreatedAsMaybeSent() {
        val crash = BoardError.Internal("IllegalStateException")
        assertFalse(RefreshPolicy.shouldAutoRetry(crash))
        assertFalse(CreateErrors.mayAlreadyExist(crash))
        assertFalse(MergeAllStops.transient(MergeProblem.Failed(crash)))
        assertEquals(MergeAllStop(MergeAllStop.Kind.ERROR, "internal error: IllegalStateException"), MergeAllStops.of(crash))
    }
}
