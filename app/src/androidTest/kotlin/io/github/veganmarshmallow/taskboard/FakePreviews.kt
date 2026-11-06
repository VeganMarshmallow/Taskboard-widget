package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.ReplyStep
import io.github.veganmarshmallow.taskboard.core.reply.StatusMove

/** Realistic preview states for instrumented tests and screenshots. */
object FakePreviews {
    /** The board's real label colours (VeganMarshmallow/claude-workspace). */
    val LABEL_COLORS = mapOf(
        "status: todo" to "d4d4d4", "status: in-progress" to "fbca04", "status: blocked" to "d93f0b",
        "type: feature" to "a2eeef", "type: bug" to "ee0701", "type: research" to "fef2c0", "type: refactor" to "bfd4f2",
        "priority: high" to "b60205", "priority: low" to "c2e0c6",
        "ready-for-claude" to "1d76db", "needs-owner" to "b60205", "created-by-claude" to "c5def5",
    )

    private fun details(n: Int, title: String, body: String, vararg labels: String): IssueDetails {
        val names = labels.map(Labels::normalize).toSet()
        return IssueDetails(Issue(n, title, names, isOpen = true), body, names.associateWith { LABEL_COLORS[it] })
    }

    val TODO_BODY = """
        ### What should be done
        Tapping a task opens a preview inside the app instead of going straight to GitHub:
        - Title, and a very short summary of the task (2–3 lines)
        - An **Approve** button for unapproved Todo tasks, with the confirmation
        - At the bottom, a link [Open on GitHub](https://github.com)

        ### Repo
        VeganMarshmallow/Taskboard-widget
    """.trimIndent()

    /** #12 waiting on the owner, with a reply typed (task #44). */
    fun blockedWithReply(): PreviewScreen = blockedWithClaudeLine()
        .withReply(ReplyForm(draft = "ECharts. The heatmap matters more than the bundle size."))

    /** The reply failed with no network; the text stays for another try (task #44). */
    fun replyFailedOffline(): PreviewScreen = blockedWithClaudeLine()
        .withReply(
            ReplyForm(draft = "כן, ECharts. ה־heatmap חשוב יותר.")
                .finished(ReplyResult.Failed(ReplyStep.COMMENT, BoardError.Network)),
        )

    /** #30 with its PR waiting for review, and a change request typed (task #65). */
    fun inReviewWithReply(requestChanges: Boolean): PreviewScreen = inReviewLoaded()
        .withReply(ReplyForm(draft = "The merge confirmation should name the branch too.").requestChangesChecked(requestChanges))

    /** The change request was posted but moving the labels failed: Send retries only the move (task #65). */
    fun requestChangesLabelsFailed(): PreviewScreen = inReviewLoaded()
        .withReply(
            ReplyForm(draft = "x", requestChanges = true)
                .finished(ReplyResult.Failed(ReplyStep.REQUEST_CHANGES, BoardError.Server(502))),
        )

    fun inReviewLoaded(): PreviewScreen = PreviewScreen.initial(30, null)
        .issueLoaded(details(30, "Widget: merge Claude's PRs from the widget", "### What should be done\nLet the owner merge Claude's PRs from the widget, without opening GitHub.\n\n### Repo\nVeganMarshmallow/Taskboard-widget", Labels.IN_REVIEW, "type: feature", Labels.READY_FOR_CLAUDE))
        .commentsLoaded(listOf(IssueComment(1, "✅ PR ready: https://github.com/VeganMarshmallow/Taskboard-widget/pull/12", null)))

    fun todoLoaded(): PreviewScreen = PreviewScreen.initial(11, null)
        .issueLoaded(details(11, "Widget: task preview with summary and approve", TODO_BODY, Labels.TODO, "type: feature"))

    fun blockedWithClaudeLine(): PreviewScreen = PreviewScreen.initial(12, null)
        .issueLoaded(details(12, "Which charting library for the dashboard?", "### What should be done\nPick a charting library for the **stats** dashboard and wire it up.\n\n### Repo\nVeganMarshmallow/portfolio-site", Labels.BLOCKED, Labels.NEEDS_OWNER, Labels.PRIORITY_HIGH, "type: research", Labels.READY_FOR_CLAUDE))
        .commentsLoaded(
            listOf(
                IssueComment(1, "🤖 Started in run 2026-10-22 10:00. Plan: compare 3 libraries", null),
                IssueComment(2, "⛔ Blocked: Chart.js or ECharts? ECharts is heavier but has the heatmap you asked for.", null),
            ),
        )

    fun hebrewInProgress(): PreviewScreen = PreviewScreen.initial(15, null)
        .issueLoaded(
            details(
                15, FakeBoards.HEBREW_TITLE,
                "### What should be done\n- לבנות **דף נחיתה** בעברית עם טופס הרשמה\n- תמיכה מלאה ב־RTL ובדיקות קצה לכל השדות, כולל שדות ריקים וארוכים מאוד ושגיאות שרת\n\n### Repo\nVeganMarshmallow/landing-page",
                Labels.IN_PROGRESS, Labels.READY_FOR_CLAUDE, "type: feature", "created-by-claude",
            ),
        )
        .commentsLoaded(listOf(IssueComment(1, "🤖 התחלתי לעבוד על הטופס. תוכנית: רכיב טופס, ולידציה, בדיקות", null)))

    fun cachedWhileLoading(): PreviewScreen =
        PreviewScreen.initial(17, TaskPreview.fromBoard(FakeBoards.realistic(), 17))

    fun failedWithCache(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.Network)

    fun failedNoToken(): PreviewScreen = PreviewScreen.initial(17, null).issueFailed(BoardError.NoToken)

    fun noDescription(): PreviewScreen = PreviewScreen.initial(18, null)
        .issueLoaded(details(18, "Refactor the settings screen", "", Labels.TODO, Labels.READY_FOR_CLAUDE, Labels.PRIORITY_LOW))

    /** Every kind of pill at once, plus labels that must stay hidden, to show wrapping. */
    fun manyTags(): PreviewScreen = PreviewScreen.initial(20, null)
        .issueLoaded(
            details(
                20, "Colourful tag pills in the task preview",
                "### What should be done\nShow tags as rounded, colourful pills.\n\n### Repo\nVeganMarshmallow/Taskboard-widget",
                Labels.TODO, "type: feature", "type: refactor", Labels.PRIORITY_LOW, Labels.NEEDS_OWNER, "created-by-claude", "bug",
            ),
        )

    /** A long project name and a long type label: each pill must fit the row and ellipsize. */
    fun longTags(): PreviewScreen = PreviewScreen.initial(21, null)
        .issueLoaded(
            details(
                21, "Long label names",
                "### What should be done\nCheck that very long pills never overflow.\n\n### Repo\n" +
                    "https://github.com/VeganMarshmallow/an-unusually-long-repository-name-for-testing/",
                Labels.TODO, "type: infrastructure-and-continuous-integration-maintenance", Labels.PRIORITY_HIGH,
            ),
        )

    /** Task #43: a real-shaped blocked comment, long, with a "What I need from you" part and the footer. */
    val LONG_BLOCKED_COMMENT = """
        ⛔ Blocked in run 2026-10-30 14:00. The **deploy** step can't continue:

        - The workflow pushes to `gh-pages`, but the repo has no deploy key.
        - I tried the default `GITHUB_TOKEN`; branch protection rejects it.
        - Without a key, the site isn't updated after a merge.

        **What I need from you:** add a `DEPLOY_KEY` secret (a deploy key with write access) to the repo. Then remove `needs-owner` and I'll continue.

        Everything else is done: the build passes and the [PR](https://github.com/x/y/pull/7) is ready apart from deploying.

        ---
        _Generated by [Claude Code](https://claude.ai/code)_
    """.trimIndent()

    fun blockedWithLongComment(): PreviewScreen = PreviewScreen.initial(43, null)
        .issueLoaded(details(43, "Deploy the portfolio site on merge", "### What should be done\nDeploy the site to GitHub Pages on every merge.\n\n### Repo\nVeganMarshmallow/portfolio-site", Labels.BLOCKED, Labels.NEEDS_OWNER, Labels.READY_FOR_CLAUDE, "type: feature"))
        .commentsLoaded(listOf(IssueComment(1, LONG_BLOCKED_COMMENT, null)))

    /** Task #43: a Hebrew blocked comment (RTL), long enough to collapse. */
    fun hebrewBlockedComment(): PreviewScreen = PreviewScreen.initial(15, null)
        .issueLoaded(details(15, FakeBoards.HEBREW_TITLE, "### What should be done\nלבנות דף נחיתה בעברית", Labels.BLOCKED, Labels.NEEDS_OWNER, Labels.READY_FOR_CLAUDE))
        .commentsLoaded(
            listOf(
                IssueComment(
                    1,
                    "⛔ חסום: טופס ההרשמה צריך לשלוח את הנתונים לשרת, אבל אין כתובת API.\n\n" +
                        "- בדקתי את קובץ ההגדרות ואת ה־README.\n- אין משתני סביבה בריפו.\n- בלי כתובת, הטופס לא יעבוד בייצור.\n\n" +
                        "**What I need from you:** כתובת ה־API של השרת, ואם צריך מפתח — להוסיף אותו כסוד `API_KEY`.\n\n" +
                        "---\n_Generated by [Claude Code](https://claude.ai/code)_",
                    null,
                ),
            ),
        )

    /** #46 round 2: Claude's "ready to merge" note, long enough to fold to one line. */
    const val READY_TO_MERGE_COMMENT = "✅ Ready to merge: PR #47 adds the Calm and Ink and paper themes, with a switch under " +
        "Settings → Appearance. The full CI run is green and the screenshots of both themes are in the PR body."

    /** #46 round 2: an in-review task with that note: Send, Merge and Open on GitHub share one line. */
    fun inReviewReadyToMerge(): PreviewScreen = inReviewWithNote(READY_TO_MERGE_COMMENT)

    /** The same in-review task with Claude's latest note set to [note]. */
    fun inReviewWithNote(note: String): PreviewScreen = PreviewScreen.initial(46, null)
        .issueLoaded(details(46, "Beautify the taskboard", "### What should be done\nTwo looks for the app and the widget, switchable in Settings.\n\n### Repo\nVeganMarshmallow/Taskboard-widget", Labels.IN_REVIEW, Labels.READY_FOR_CLAUDE, "type: feature"))
        .commentsLoaded(listOf(IssueComment(1, note, null)))

    fun justApproved(): PreviewScreen = todoLoaded().approveStarted().approveSucceeded()

    /** The task was deleted or moved since the widget loaded: stale row kept, no Approve. */
    fun issueGone(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.IssueGone(17))

    fun expiredToken(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.BadToken(401))

    /** #55: Approve hit a bug inside the app, not a GitHub answer. */
    fun approveCrashed(): PreviewScreen = todoLoaded().approveStarted().approveFailed(BoardError.Internal("IllegalStateException"))
}
