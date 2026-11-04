package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllPhase
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRunner
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Merge all across jobs and process deaths (#57): the runner saves after every step, and a fresh
 * process (a new runner over the saved text) goes on from there without merging anything twice.
 */
class MergeAllRunnerTest {
    private val github = FakeMergeAllGitHub()
    private val store = TextMergeAllStore()

    /** A new process: nothing kept in memory but the saved text. */
    private fun process(budget: Duration = MergeAllRunner.BUDGET, ticker: () -> Long = System::nanoTime) =
        MergeAllRunner(MergeAllMachine(github), store, budget, ticker)

    /** Jobs until the run ends; each job is a fresh process, and waits pass on the fake clock. */
    private fun jobs(max: Int = 100): MergeAllState {
        repeat(max) {
            val result = process().run()
            val next = result.next ?: return result.state!!
            github.advance(next)
        }
        throw AssertionError("still running after $max jobs")
    }

    @Test fun nothingSavedIsNothingToDo() {
        assertEquals(MergeAllRunner.Result(null, null), process().run())
    }

    @Test fun aThreePrRunEndsAcrossJobs() {
        github.add(mergeAllPr(31), mergeAllPr(32), mergeAllPr(33))
        store.save(MergeAllState.start("run", planned(31, 32, 33), github.now))
        val end = jobs()
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("merge #31", "update #32", "merge #32", "update #33", "merge #33"), github.calls)
        assertEquals(end, store.load())
    }

    @Test fun aJobEndsAtTheFirstWaitAndSaysWhenToComeBack() {
        github.add(mergeAllPr(31))
        github.behind[31] = 1
        store.save(MergeAllState.start("run", planned(31), github.now))
        val result = process().run()
        assertEquals(MergeAllMachine.POLL, result.next)
        assertEquals(MergeAllPhase.WAIT, store.load()!!.phase)
    }

    @Test fun everyStepIsSavedAndShown() {
        github.add(mergeAllPr(31))
        store.save(MergeAllState.start("run", planned(31), github.now))
        val seen = mutableListOf<MergeAllPhase>()
        MergeAllRunner(MergeAllMachine(github), store, onStep = { seen += it.phase }).run()
        assertEquals(listOf(MergeAllPhase.UPDATE, MergeAllPhase.WAIT, MergeAllPhase.MERGE, MergeAllPhase.MERGE), seen)
    }

    @Test fun processDeathAfterTheMergeWentThroughCountsItOnce() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        val atMerge = MergeAllState.start("run", planned(31, 32), github.now).copy(phase = MergeAllPhase.MERGE, checkedHead = sha('a'))
        store.save(atMerge)
        // The merge PUT reached GitHub, then the process died before saving.
        github.mergedOnGitHub(31)
        val end = jobs()
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf(31, 32), end.merged.map { it.ref.number })
        assertEquals("PR #31 is not merged again", listOf("update #32", "merge #32"), github.calls)
    }

    @Test fun processDeathInEveryPhaseResumes() {
        for (phase in MergeAllPhase.entries) {
            val gh = FakeMergeAllGitHub().add(mergeAllPr(31), mergeAllPr(32))
            val s = TextMergeAllStore()
            s.save(MergeAllState.start("run", planned(31, 32), gh.now).copy(phase = phase, checkedHead = sha('a')))
            var result = MergeAllRunner(MergeAllMachine(gh), s).run()
            while (result.next != null) {
                gh.advance(result.next!!)
                result = MergeAllRunner(MergeAllMachine(gh), s).run()
            }
            assertEquals("resumed in $phase", MergeAllEnd.Done, s.load()!!.end)
            assertEquals("resumed in $phase", listOf("merge #31", "update #32", "merge #32"), gh.calls)
        }
    }

    @Test fun cancelFromAnotherThreadIsSeenByTheNextJob() {
        github.add(mergeAllPr(31), mergeAllPr(32))
        github.checkResult = { pr -> if (pr.number == 32) MergeStep.Stop(MergeProblem.ChecksRunning(pr, listOf("build"))) else MergeStep.Ok(pr) }
        store.save(MergeAllState.start("run", planned(31, 32), github.now))
        while (store.load()!!.merged.isEmpty() || store.load()!!.phase != MergeAllPhase.WAIT) {
            github.advance(process().run().next!!)
        }
        store.requestCancel("run")
        val end = process().run()
        assertNull(end.next)
        assertEquals(MergeAllEnd.Cancelled, end.state!!.end)
        assertEquals(listOf("merge #31", "update #32"), github.calls)
    }

    @Test fun aCancelForAnEarlierRunIsIgnored() {
        github.add(mergeAllPr(31))
        store.requestCancel("old-run")
        store.save(MergeAllState.start("new-run", planned(31), github.now))
        assertEquals(MergeAllEnd.Done, jobs().end)
    }

    @Test fun aJobOutOfTimeHandsOverToTheNext() {
        github.add(mergeAllPr(31))
        store.save(MergeAllState.start("run", planned(31), github.now))
        var t = 0L
        val result = process(budget = Duration.ofSeconds(1)) { t.also { t += 2_000_000_000L } }.run()
        assertEquals(MergeAllRunner.RESUME, result.next)
        assertTrue(store.load()!!.running)
    }

    @Test fun aBugStopsTheRunInsteadOfLeavingItStuck() {
        github.add(mergeAllPr(31))
        github.checkResult = { throw IllegalStateException("boom") }
        store.save(MergeAllState.start("run", planned(31), github.now))
        val result = process().run()
        assertNull(result.next)
        val end = result.state!!.end as MergeAllEnd.Stopped
        assertEquals(MergeAllStop(MergeAllStop.Kind.ERROR, "internal error: IllegalStateException"), end.stop)
        assertNotNull(store.load()!!.end)
    }
}
