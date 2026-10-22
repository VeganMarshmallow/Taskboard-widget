package io.github.veganmarshmallow.taskboard.preview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.BoardLinks
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.preview.TaskPreview
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.widget.ApproveDialog
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents

/**
 * Bottom-sheet preview of one task, opened by tapping a widget row. Shows the cached row at once,
 * then loads the issue and its latest Claude comment. All decisions live in [PreviewScreen] (core);
 * this class only wires clicks, the worker and the views.
 */
class TaskPreviewActivity : Activity() {
    private lateinit var views: PreviewViews
    private lateinit var worker: PreviewWorker
    private lateinit var screen: PreviewScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.getIntExtra(EXTRA_ISSUE, 0) ?: 0
        if (number <= 0) {
            finish()
            return
        }
        setContentView(R.layout.activity_task_preview)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)

        views = PreviewViews(findViewById(R.id.preview_root), AppGraph.messages(this))
        worker = PreviewWorker(this)
        findViewById<View>(R.id.preview_open).setOnClickListener { openOnGitHub(number) }
        findViewById<View>(R.id.preview_approve).setOnClickListener { confirmApprove() }
        findViewById<View>(R.id.preview_retry).setOnClickListener { onRetry() }

        update(PreviewScreen.initial(number, TaskPreview.fromBoard(StateStore(this).load().board, number)))
        load()
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.close()
        super.onDestroy()
    }

    private fun load() {
        update(screen.loadStarted())
        worker.load(
            screen.number,
            onIssue = { result ->
                update(
                    when (result) {
                        is Outcome.Success -> screen.issueLoaded(result.value)
                        is Outcome.Failure -> screen.issueFailed(result.error)
                    },
                )
            },
            onComments = { update(screen.commentsLoaded(it)) },
        )
    }

    private fun confirmApprove() {
        val task = screen.task ?: return
        ApproveDialog.show(this, task.number, task.title, onConfirm = ::approve)
    }

    private fun approve() {
        update(screen.approveStarted())
        worker.approve(screen.number) { result ->
            when (result) {
                is Outcome.Success -> { update(screen.approveSucceeded()); load() }
                is Outcome.Failure -> update(screen.approveFailed(result.error))
            }
        }
    }

    private fun onRetry() {
        if (views.opensSetup(screen)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        } else {
            load()
        }
    }

    private fun openOnGitHub(number: Int) {
        try {
            startActivity(WidgetIntents.viewUrl(BoardLinks().issue(number)))
            finish()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun update(next: PreviewScreen) {
        screen = next
        views.bind(next)
    }

    companion object {
        private const val EXTRA_ISSUE = "issue"

        fun intent(context: Context, issue: Int): Intent =
            Intent(context, TaskPreviewActivity::class.java)
                .putExtra(EXTRA_ISSUE, issue)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
