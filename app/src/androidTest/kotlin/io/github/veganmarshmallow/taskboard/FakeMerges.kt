package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.board.BoardBuilder
import io.github.veganmarshmallow.taskboard.core.merge.PullRef
import io.github.veganmarshmallow.taskboard.core.merge.PullRequest
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.model.Repo
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState

/** Realistic merge data (task #30): a GitHub script for [FakeGitHubServer], boards and PRs for screenshots. */
object FakeMerges {
    val WIDGET = Repo("VeganMarshmallow", "Taskboard-widget")
    const val SHA = "0123456789abcdef0123456789abcdef01234567"
    const val TITLE = "Widget: merge Claude's PRs from the widget"

    fun pr(number: Int = 12, base: String = "main", title: String = TITLE) =
        PullRequest(PullRef(WIDGET, number), title, true, false, false, true, "", base, "claude/30-merge-from-widget", SHA, "main")

    /** Needs you with two PRs waiting and a question. */
    fun boardState(): WidgetState {
        fun issue(n: Int, title: String, vararg labels: String) = Issue(n, title, labels.map(Labels::normalize).toSet(), true)
        val board = BoardBuilder().build(
            listOf(
                issue(30, TITLE, Labels.IN_REVIEW, "type: feature"),
                issue(29, "Widget: recover from network errors", Labels.IN_REVIEW, Labels.PRIORITY_HIGH),
                issue(12, "Which charting library for the dashboard?", Labels.BLOCKED, Labels.NEEDS_OWNER),
                issue(17, "Weekly digest email of finished tasks ✉️", Labels.TODO),
            ),
            FakeBoards.NOW,
        )
        return WidgetState(board, null, FakeBoards.NOW)
    }

    fun pullJson(number: Int, base: String = "main", head: String = "claude/$number", body: String = "Closes VeganMarshmallow/claude-workspace#30", mergeable: String = "true") = """
        {"number":$number,"title":"$TITLE","state":"open","draft":false,"merged":false,"mergeable":$mergeable,
         "body":"$body","base":{"ref":"$base","repo":{"default_branch":"main"}},"head":{"ref":"$head","sha":"$SHA"}}
    """.trimIndent()

    /**
     * Issue #30 in review with Claude's PR link, PR #12 open on main with green GitHub Actions runs
     * (CI failed, then its re-run passed; Lint passed). [stacked] puts PR #12 on PR #11's branch
     * instead; [mergeStatus] is what the merge PUT answers; [actionsStatus] what the runs list answers.
     */
    fun script(stacked: Boolean = false, mergeStatus: Int = 200, actionsStatus: Int = 200): (String, String) -> FakeGitHubServer.Answer? = { method, path ->
        val board = "/repos/VeganMarshmallow/claude-workspace"
        val repo = "/repos/VeganMarshmallow/Taskboard-widget"
        val pr12 = if (stacked) pullJson(12, base = "claude/11-first") else pullJson(12)
        val pr11 = pullJson(11, head = "claude/11-first", body = "Closes VeganMarshmallow/claude-workspace#29")
        when {
            path == "$board/issues/30" -> FakeGitHubServer.Answer(200, """{"number":30,"title":"$TITLE","state":"open","labels":[{"name":"status: in-review"}],"body":"### Repo\n\nVeganMarshmallow/Taskboard-widget"}""")
            path.startsWith("$board/issues/30/comments") -> FakeGitHubServer.Answer(200, """[{"id":1,"body":"✅ ה-PR מוכן: https://github.com/VeganMarshmallow/Taskboard-widget/pull/12","created_at":"2026-10-23T10:00:00Z"}]""")
            path.startsWith("$board/issues") -> FakeGitHubServer.Answer(200, "[]") // the refresh after a merge
            path.startsWith("$repo/pulls?state=open") -> FakeGitHubServer.Answer(200, "[$pr12,$pr11]")
            method == "PUT" && path == "$repo/pulls/12/merge" -> when (mergeStatus) {
                200 -> FakeGitHubServer.Answer(200, """{"sha":"abc","merged":true,"message":"Pull Request successfully merged"}""")
                409 -> FakeGitHubServer.Answer(409, """{"message":"Head branch was modified. Review and try the merge again."}""")
                else -> FakeGitHubServer.Answer(mergeStatus, """{"message":"Pull Request is not mergeable"}""")
            }
            path == "$repo/pulls/12" -> FakeGitHubServer.Answer(200, pr12)
            path == "$repo/pulls/11" -> FakeGitHubServer.Answer(200, pr11)
            path == "$repo/actions/runs?head_sha=$SHA&per_page=100" -> if (actionsStatus == 200) {
                FakeGitHubServer.Answer(200, RUNS)
            } else {
                FakeGitHubServer.Answer(actionsStatus, """{"message":"Resource not accessible by personal access token"}""")
            }
            else -> null
        }
    }

    private fun run(id: Int, workflow: Int, name: String, attempt: Int, conclusion: String) =
        """{"id":$id,"workflow_id":$workflow,"name":"$name","head_sha":"$SHA","run_number":4,"run_attempt":$attempt,""" +
            """"created_at":"2026-10-30T10:0$attempt:00Z","status":"completed","conclusion":"$conclusion"}"""

    /** CI's first attempt failed and its re-run passed, so only the re-run counts. */
    private val RUNS = """{"total_count":3,"workflow_runs":[${run(2, 1, "CI", 2, "success")},${run(3, 2, "Lint", 1, "success")},${run(1, 1, "CI", 1, "failure")}]}"""
}
