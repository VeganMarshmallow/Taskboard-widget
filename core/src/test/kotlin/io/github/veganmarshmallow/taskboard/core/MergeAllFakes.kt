package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.merge.MergeProblem
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllGitHub
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllRecords
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllState
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStateCodec
import io.github.veganmarshmallow.taskboard.core.mergeall.MergeAllStore
import io.github.veganmarshmallow.taskboard.core.mergeall.PlannedMerge
import java.time.Duration
import java.time.Instant

/** A 40-character SHA made of [c]. */
fun sha(c: Char) = c.toString().repeat(40)

fun mergeAllPr(number: Int, base: String = "main", head: String = "claude/$number", sha: String = sha('a'), open: Boolean = true, draft: Boolean = false) =
    PullRequest(PullRef(WIDGET, number), "PR $number", open, draft, false, true, "", base, head, sha, "main")

fun planned(vararg numbers: Int) = numbers.map { PlannedMerge(it + 100, "Task ${it + 100}", PullRef(WIDGET, it)) }

/**
 * A project repo for Merge all (#57): open PRs, how far behind main each is, and their checks.
 * `update-branch` gives a PR a new head at once; a merge leaves every other PR one commit behind.
 * [fail] makes the next call of an operation on a PR answer with a problem instead.
 */
class FakeMergeAllGitHub(var now: Instant = Instant.parse("2026-11-03T10:00:00Z")) : MergeAllGitHub {
    val prs = LinkedHashMap<Int, PullRequest>()
    val behind = HashMap<Int, Long>()
    val calls = mutableListOf<String>()
    private val failures = HashMap<String, ArrayDeque<MergeProblem>>()
    private var nextSha = 'b'

    /** The checks' answer for a PR; passed by default. */
    var checkResult: (PullRequest) -> MergeStep<PullRequest> = { MergeStep.Ok(it) }

    fun add(vararg pulls: PullRequest) = apply { pulls.forEach { prs[it.number] = it; behind[it.number] = 0 } }

    fun fail(op: String, number: Int, vararg problems: MergeProblem) = apply {
        failures.getOrPut("$op#$number") { ArrayDeque() }.addAll(problems)
    }

    private fun failure(op: String, number: Int): MergeStep.Stop? = failures["$op#$number"]?.removeFirstOrNull()?.let { MergeStep.Stop(it) }

    override fun pull(ref: PullRef): MergeStep<PullRequest> = failure("pull", ref.number) ?: MergeStep.Ok(prs.getValue(ref.number))

    override fun retarget(pr: PullRequest, base: String): MergeStep<PullRequest> = failure("retarget", pr.number) ?: run {
        calls += "retarget #${pr.number} to $base"
        val moved = prs.getValue(pr.number).copy(baseRef = base)
        prs[pr.number] = moved
        MergeStep.Ok(moved)
    }

    override fun behindBy(pr: PullRequest): MergeStep<Long> = failure("behind", pr.number) ?: MergeStep.Ok(behind.getValue(pr.number))

    override fun updateBranch(pr: PullRequest): MergeStep<Unit> = failure("update", pr.number) ?: run {
        calls += "update #${pr.number}"
        prs[pr.number] = prs.getValue(pr.number).copy(headSha = sha(nextSha++))
        behind[pr.number] = 0
        MergeStep.Ok(Unit)
    }

    override fun checks(pr: PullRequest): MergeStep<PullRequest> = failure("checks", pr.number) ?: checkResult(pr)

    /** PRs whose next merge goes through on GitHub but whose answer is lost (a timeout). */
    val lostAnswers = HashSet<Int>()

    override fun merge(pr: PullRequest): MergeStep<Unit> = failure("merge", pr.number) ?: run {
        calls += "merge #${pr.number}"
        mergedOnGitHub(pr.number)
        if (lostAnswers.remove(pr.number)) MergeStep.Stop(MergeProblem.Failed(BoardError.Timeout)) else MergeStep.Ok(Unit)
    }

    /** [number] merged (by the run, or by someone else): every other open PR falls one commit behind. */
    fun mergedOnGitHub(number: Int) {
        prs[number] = prs.getValue(number).copy(isMerged = true, isOpen = false)
        prs.values.filter { it.isOpen }.forEach { behind[it.number] = behind.getValue(it.number) + 1 }
    }

    override fun now(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}

/** A store that keeps only the encoded text, as the app's does: every load decodes it afresh. */
class TextMergeAllStore : MergeAllStore {
    var text: String? = null
    private var cancel: String? = null

    override fun load(): MergeAllState? = MergeAllStateCodec.decode(text)
    override fun start(state: MergeAllState): Boolean = (load()?.running != true).also { if (it) save(state) }
    override fun saveIf(state: MergeAllState): Boolean =
        load().let { it != null && it.runId == state.runId && it.running }.also { if (it) save(state) }
    override fun save(state: MergeAllState) { text = MergeAllStateCodec.encode(state) }
    override fun clear() { text = null; cancel = null }
    override fun requestCancel(runId: String) { cancel = runId }
    override fun cancelRequested(runId: String): Boolean = cancel == runId
}

/** [inner], with [beforeSaveIf] run just before each [saveIf]: something tapped after a step finished but before its save (#81). */
class BeforeSaveStore(private val inner: MergeAllStore, var beforeSaveIf: () -> Unit = {}) : MergeAllStore by inner {
    var refused = 0

    override fun saveIf(state: MergeAllState): Boolean {
        beforeSaveIf()
        return inner.saveIf(state).also { if (!it) refused++ }
    }
}

/** Key-value records like SharedPreferences: each call on its own. [onRead] runs after a read (to hold a race open). */
class MemoryMergeAllRecords(private val onRead: () -> Unit = {}) : MergeAllRecords {
    @Volatile var state: String? = null
    @Volatile var cancel: String? = null

    override fun readState(): String? = state.also { onRead() }
    override fun writeState(text: String) { state = text }
    override fun readCancel(): String? = cancel
    override fun writeCancel(runId: String) { cancel = runId }
    override fun clear() { state = null; cancel = null }
}
