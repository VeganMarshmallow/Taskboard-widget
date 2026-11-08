package io.github.veganmarshmallow.taskboard.core.mergeall

import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.merge.FoundPr
import io.github.veganmarshmallow.taskboard.core.merge.MergeChecker
import io.github.veganmarshmallow.taskboard.core.merge.MergeStep
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.merge.PullsApi
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import java.time.Duration
import java.time.Instant

/** The GitHub calls a Merge all run makes (#57); [MergeAllMachine] is tested against a fake of it. */
interface MergeAllGitHub {
    /** The PR now, with its repo's default branch filled in. */
    fun pull(ref: PullRef): MergeStep<PullRequest>
    fun retarget(pr: PullRequest, base: String): MergeStep<PullRequest>
    fun behindBy(pr: PullRequest): MergeStep<Long>
    fun updateBranch(pr: PullRequest): MergeStep<Unit>

    /** The Merge button's checks (#38/#41): open, not a draft, mergeable, every workflow passed and present. */
    fun checks(pr: PullRequest): MergeStep<PullRequest>

    /** Re-reads [pr], refuses if it changed, then merges exactly its head SHA. */
    fun merge(pr: PullRequest): MergeStep<Unit>
    fun now(): Instant
}

/**
 * [MergeAllGitHub] over the merge package's [PullsApi] and [MergeChecker]: no check is written twice.
 * [pace] (#83) goes into each merge commit for the update-prs workflow ([PullsApi.merge]).
 */
class PullsMergeAllGitHub(
    api: GitHubApi,
    private val pace: WorkPace = WorkPace.DEFAULT,
    sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) : MergeAllGitHub {
    private val pulls = PullsApi(api)
    private val checker = MergeChecker(pulls, sleep)
    private val defaults = HashMap<String, String>()

    override fun pull(ref: PullRef): MergeStep<PullRequest> = pulls.pull(ref).then(::withDefault)

    override fun retarget(pr: PullRequest, base: String): MergeStep<PullRequest> = pulls.retarget(pr, base).then(::withDefault)

    override fun behindBy(pr: PullRequest): MergeStep<Long> = withDefault(pr).then { pulls.behindBy(it.repo, it.defaultBranch!!, it.headSha) }

    override fun updateBranch(pr: PullRequest): MergeStep<Unit> = pulls.updateBranch(pr)

    override fun checks(pr: PullRequest): MergeStep<PullRequest> = checker.check(FoundPr(pr, null))

    override fun merge(pr: PullRequest): MergeStep<Unit> = checker.recheck(pr).then { pulls.merge(it, pace) }

    override fun now(): Instant = pulls.now()

    private fun withDefault(pr: PullRequest): MergeStep<PullRequest> {
        pr.defaultBranch?.let { defaults[pr.repo.fullName] = it; return MergeStep.Ok(pr) }
        defaults[pr.repo.fullName]?.let { return MergeStep.Ok(pr.copy(defaultBranch = it)) }
        return pulls.defaultBranch(pr.repo).then { branch ->
            defaults[pr.repo.fullName] = branch
            MergeStep.Ok(pr.copy(defaultBranch = branch))
        }
    }
}
