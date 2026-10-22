package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.model.Issue
import io.github.veganmarshmallow.taskboard.core.model.Labels
import io.github.veganmarshmallow.taskboard.core.preview.IssueComment
import io.github.veganmarshmallow.taskboard.core.preview.IssueDetails
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview

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

    fun justApproved(): PreviewScreen = todoLoaded().approveStarted().approveSucceeded()

    /** The task was deleted or moved since the widget loaded: stale row kept, no Approve. */
    fun issueGone(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.IssueGone(17))

    fun expiredToken(): PreviewScreen = cachedWhileLoading().issueFailed(BoardError.BadToken(401))
}
