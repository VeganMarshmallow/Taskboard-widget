package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Work pace section's states (#83): loading, read failures, saves and failed saves. */
class WorkPaceFormTest {
    private val loading = WorkPaceForm.loading(WorkPace.SAVE)

    @Test fun loadingShowsTheLastKnownValueLocked() {
        assertEquals(WorkPace.SAVE, loading.shown)
        assertFalse(loading.enabled)
        assertNull("no tap while loading", loading.picked(WorkPace.FAST))
    }

    @Test fun aReadShowsTheStoredValue() {
        val ready = loading.loaded(Outcome.Success(WorkPace.FAST))
        assertEquals(WorkPaceForm(WorkPace.FAST, Phase.READY), ready)
        assertTrue(ready.enabled)
    }

    @Test fun aFailedReadKeepsTheOptionsLockedWithARetry() {
        val failed = loading.loaded(Outcome.Failure(BoardError.Network))
        assertEquals(Phase.LOAD_FAILED, failed.phase)
        assertEquals(BoardError.Network, failed.error)
        assertFalse(failed.enabled)
        assertTrue(failed.canRetry)
        assertEquals(WorkPaceForm.loading(WorkPace.SAVE), failed.reloading())
    }

    @Test fun pickingSavesAndSaysWhenItTakesEffect() {
        val saving = loading.loaded(Outcome.Success(WorkPace.SAVE)).picked(WorkPace.FAST)!!
        assertEquals(WorkPace.FAST, saving.shown)
        assertEquals(Phase.SAVING, saving.phase)
        assertFalse("one save at a time", saving.enabled)
        assertNull(saving.picked(WorkPace.SAVE))
        assertEquals(WorkPaceForm(WorkPace.FAST, Phase.SAVED), saving.saved(Outcome.Success(WorkPace.FAST)))
    }

    @Test fun pickingTheShownValueDoesNothing() {
        assertNull(loading.loaded(Outcome.Success(WorkPace.FAST)).picked(WorkPace.FAST))
    }

    @Test fun aFailedSavePutsTheStoredValueBack() {
        val failed = loading.loaded(Outcome.Success(WorkPace.SAVE)).picked(WorkPace.FAST)!!.saved(Outcome.Failure(BoardError.Timeout))
        assertEquals(WorkPace.SAVE, failed.shown)
        assertEquals(Phase.SAVE_FAILED, failed.phase)
        assertTrue("can try again by tapping", failed.enabled)
        assertFalse(failed.needsWriteAccess)
        assertEquals(Phase.SAVING, failed.picked(WorkPace.FAST)!!.phase)
    }

    @Test fun aRefusedSaveAsksForWriteAccess() {
        fun refused(error: BoardError) = loading.loaded(Outcome.Success(WorkPace.SAVE)).picked(WorkPace.FAST)!!.saved(Outcome.Failure(error))
        assertTrue(refused(BoardError.BadToken(403)).needsWriteAccess)
        assertTrue(refused(BoardError.NoAccess).needsWriteAccess)
        assertFalse(refused(BoardError.BadToken(401)).needsWriteAccess)
        assertFalse("a read failure is not about writing", loading.loaded(Outcome.Failure(BoardError.NoAccess)).needsWriteAccess)
    }
}
