package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllEnd
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllMachine
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Merge all under the work pace (#83). Save usage updates and re-tests every PR the run left behind
 * (unchanged, #57). Faster merges such a PR as it is when its own checks are green, and falls back
 * to Save usage's update otherwise. The first PR is never merged behind its default branch.
 */
class MergeAllPaceTest {
    private val github = FakeMergeAllGitHub()

    private fun run(pace: WorkPace, vararg numbers: Int): MergeAllState {
        val machine = MergeAllMachine(github, pace = pace)
        var s = MergeAllState.start("run-83", planned(*numbers), github.now)
        repeat(500) {
            if (!s.running) return s
            val step = machine.step(s, cancel = false)
            step.waitFor?.let(github::advance)
            s = step.state
        }
        throw AssertionError("still running: $s")
    }

    @Test fun saveUsageUpdatesEachPrLeftBehind() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(32, sha = sha('2')), mergeAllPr(33, sha = sha('3')))
        val end = run(WorkPace.SAVE, 31, 32, 33)
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("merge #31", "update #32", "merge #32", "update #33", "merge #33"), github.calls)
    }

    @Test fun fasterMergesGreenPrsBackToBack() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(32, sha = sha('2')), mergeAllPr(33, sha = sha('3')))
        val end = run(WorkPace.FAST, 31, 32, 33)
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("merge #31", "merge #32", "merge #33"), github.calls)
    }

    @Test fun fasterStillUpdatesAFirstPrThatIsBehind() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(32, sha = sha('2')))
        github.behind[31] = 1
        run(WorkPace.FAST, 31, 32)
        assertEquals(listOf("update #31", "merge #31", "merge #32"), github.calls)
    }

    @Test fun fasterUpdatesAPrWhoseChecksAreNotGreen() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(32, sha = sha('2')))
        // #32's old head failed; after the update its new head passes.
        github.checkResult = { pr -> if (pr.headSha == sha('2')) MergeStep.Stop(MergeProblem.ChecksFailed(pr, listOf("build"))) else MergeStep.Ok(pr) }
        val end = run(WorkPace.FAST, 31, 32)
        assertEquals(MergeAllEnd.Done, end.end)
        assertEquals(listOf("merge #31", "update #32", "merge #32"), github.calls)
    }

    @Test fun fasterMergesAStackedPrRightAfterItsBase() {
        github.add(mergeAllPr(31, sha = sha('1')), mergeAllPr(34, base = "claude/31", sha = sha('4')))
        run(WorkPace.FAST, 31, 34)
        assertEquals(listOf("merge #31", "retarget #34 to main", "merge #34"), github.calls)
    }
}
