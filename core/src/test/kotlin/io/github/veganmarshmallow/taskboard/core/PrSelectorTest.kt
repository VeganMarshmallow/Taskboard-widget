package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PrCandidate
import io.github.veganmarshmallow.taskboard.core.merge.PrSelector
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.merge.TaskMatch
import io.github.veganmarshmallow.taskboard.core.model.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrSelectorTest {
    private fun c(pr: PullRequest, linked: Boolean = false, match: TaskMatch = TaskMatch.EXACT) = PrCandidate(pr, linked, match)
    private fun select(vararg candidates: PrCandidate) = PrSelector.select(candidates.toList())
    private fun chosen(step: MergeStep<PullRequest>) = (step as MergeStep.Ok).value.number
    private fun problem(step: MergeStep<PullRequest>) = (step as MergeStep.Stop).problem

    @Test fun oneExactMatch() = assertEquals(4, chosen(select(c(pr(4)))))

    @Test fun exactBodyMatchBeatsLinkedOnes() {
        assertEquals(5, chosen(select(c(pr(4), linked = true, match = TaskMatch.NONE), c(pr(5)))))
        assertEquals(5, chosen(select(c(pr(4), linked = true, match = TaskMatch.MENTION), c(pr(5)))))
    }

    @Test fun linkedOrMentioningOnesAreNeverChosen() {
        assertEquals(MergeProblem.NotLinked(listOf(pr(4))), problem(select(c(pr(4), linked = true, match = TaskMatch.NONE))))
        assertEquals(MergeProblem.NotLinked(listOf(pr(6))), problem(select(c(pr(6), match = TaskMatch.MENTION))))
        assertEquals(MergeProblem.NotLinked(listOf(pr(4), pr(6))).candidates.size,
            (problem(select(c(pr(4), linked = true, match = TaskMatch.MENTION), c(pr(6), match = TaskMatch.MENTION))) as MergeProblem.NotLinked).candidates.size)
    }

    @Test fun notLinkedPointsAtTheSingleCandidate() {
        assertEquals(pr(4), MergeProblem.NotLinked(listOf(pr(4))).pr)
        assertEquals(null, MergeProblem.NotLinked(listOf(pr(4), pr(5))).pr)
    }

    @Test fun exactPrsInDifferentReposAreAmbiguousEvenIfOneIsStacked() {
        val other = pr(3, repo = Repo("VeganMarshmallow", "site"))
        val p = problem(select(c(pr(7, base = "claude/6-first")), c(other, linked = true)))
        assertTrue(p is MergeProblem.Ambiguous)
    }

    @Test fun repoCaseDoesNotCreateTwoCandidates() {
        val upper = pr(4, repo = Repo("veganmarshmallow", "TASKBOARD-WIDGET"))
        assertEquals(4, chosen(select(c(pr(4)), c(upper, linked = true))))
    }

    @Test fun unrelatedPrsAreIgnored() {
        assertEquals(MergeProblem.NotFound, problem(select(c(pr(4), match = TaskMatch.NONE))))
        assertEquals(MergeProblem.NotFound, problem(select()))
    }

    @Test fun severalPrefersTheOneOnTheDefaultBranch() {
        val stacked = pr(8, base = "claude/7-first")
        assertEquals(7, chosen(select(c(pr(7)), c(stacked))))
    }

    @Test fun severalOnTheDefaultBranchIsAmbiguous() {
        val p = problem(select(c(pr(7)), c(pr(9))))
        assertTrue(p is MergeProblem.Ambiguous)
        assertEquals(listOf(7, 9), (p as MergeProblem.Ambiguous).candidates.map { it.number })
    }

    @Test fun severalAllStackedIsAmbiguous() {
        assertTrue(problem(select(c(pr(7, base = "x")), c(pr(9, base = "y")))) is MergeProblem.Ambiguous)
    }

    @Test fun aSingleStackedOneIsChosenSoTheCheckCanExplain() {
        assertEquals(8, chosen(select(c(pr(8, base = "claude/7-first")))))
    }

    @Test fun duplicatesCountOnce() = assertEquals(4, chosen(select(c(pr(4)), c(pr(4), linked = true))))

    @Test fun mergedOrClosedWhenNothingIsOpen() {
        assertEquals(MergeProblem.AlreadyMerged(pr(4, open = false, merged = true)), problem(select(c(pr(4, open = false, merged = true), linked = true))))
        assertEquals(MergeProblem.Closed(pr(4, open = false)), problem(select(c(pr(4, open = false)))))
        // A merged one is reported before a closed one.
        assertTrue(problem(select(c(pr(3, open = false)), c(pr(4, open = false, merged = true)))) is MergeProblem.AlreadyMerged)
    }

    @Test fun anOpenOneWinsOverAMergedOne() = assertEquals(5, chosen(select(c(pr(4, open = false, merged = true)), c(pr(5)))))
}
