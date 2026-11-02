package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.snapshot.ShownStateStore
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShownStateStoreTest {
    private val disk = MemoryStateStore()
    private val store = ShownStateStore(disk)
    private val saved = WidgetState(null, null, NOW)
    private val failure = saved.withError(BoardError.Unexpected("internal error: IllegalStateException"), NOW)

    @Test fun showsTheDiskWhenNothingIsUnsaved() {
        disk.state = saved
        assertEquals(saved, store.load())
    }

    @Test fun anUnsavedStateIsShownOverTheDiskWithoutWritingIt() {
        disk.state = saved
        store.showUnsaved(failure)
        assertEquals(failure, store.load())
        assertEquals(saved, disk.state)
        assertEquals(0, disk.saves)
    }

    @Test fun aSuccessfulSaveClearsTheUnsavedState() {
        store.showUnsaved(failure)
        store.save(saved)
        assertEquals(saved, store.load())
    }

    @Test fun aFailedSaveKeepsTheUnsavedStateAndThrows() {
        store.showUnsaved(failure)
        disk.failSaves = true
        assertThrows(IllegalStateException::class.java) { store.save(saved) }
        assertEquals(failure, store.load())
    }

    @Test fun anUnsavedStateIsShownEvenWhenTheDiskIsUnreadable() {
        disk.failLoads = true
        store.showUnsaved(failure)
        assertEquals(failure, store.load())
    }

    @Test fun theLatestUnsavedStateWins() {
        store.showUnsaved(saved.withError(BoardError.Timeout, NOW))
        store.showUnsaved(failure)
        assertEquals(failure, store.load())
    }

    @Test fun aNewProcessOnTheSameDiskShowsOnlyWhatWasSaved() {
        disk.state = saved
        store.showUnsaved(failure)
        assertEquals(saved, ShownStateStore(disk).load())
    }
}
