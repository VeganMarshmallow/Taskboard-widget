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
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.core.reply.StatusMove
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.merge.MergeFlow
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.widget.ApproveDialog
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * Bottom-sheet preview of one task, opened by tapping a widget row. Shows the cached row at once,
 * then loads the issue and its latest Claude comment. All decisions live in [PreviewScreen] (core);
 * this class only wires clicks, the worker and the views.
 */
class TaskPreviewActivity : Activity() {
    private lateinit var views: PreviewViews
    private lateinit var worker: PreviewWorker
    private lateinit var screen: PreviewScreen

    /** The merge dialog's flow while it is open (task #30); read by instrumented tests. */
    var mergeFlow: MergeFlow? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
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
        findViewById<View>(R.id.preview_merge).setOnClickListener { startMerge() }
        findViewById<View>(R.id.preview_retry).setOnClickListener { onRetry() }
        views.reply.listen(
            onEdit = ::onReplyEdited,
            onUnblock = { update(screen.withReply(screen.reply.unblockChecked(it))) },
            onRequestChanges = { update(screen.withReply(screen.reply.requestChangesChecked(it))) },
            onSend = ::sendReply,
        )
        ReplyDrafts.listen(number, onReplyFinished)

        val cached = TaskPreview.fromBoard(StateStore(this).load().board, number)
        update(
            PreviewScreen.initial(
                number, cached,
                approvedEarlier = savedInstanceState?.getBoolean(STATE_APPROVED) == true,
                mergedEarlier = savedInstanceState?.getBoolean(STATE_MERGED) == true,
            ).withReply(restoredReply(number, savedInstanceState)),
        )
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::screen.isInitialized) {
            outState.putBoolean(STATE_APPROVED, screen.justApproved)
            outState.putBoolean(STATE_MERGED, screen.justMerged)
            outState.putString(STATE_REPLY_DRAFT, screen.reply.draft)
            outState.putBoolean(STATE_REPLY_UNBLOCK, screen.reply.unblock)
            outState.putBoolean(STATE_REPLY_REQUEST_CHANGES, screen.reply.requestChanges)
            outState.putString(STATE_REPLY_PENDING_MOVE, screen.reply.pendingMove?.name)
        }
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.close()
        if (::screen.isInitialized) ReplyDrafts.stopListening(screen.number, onReplyFinished)
        mergeFlow?.close()
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

    private fun startMerge() {
        if (mergeFlow != null) return
        mergeFlow = MergeFlow(
            this, screen.number,
            onMerged = { update(screen.mergeSucceeded()); load() },
            onClosed = { mergeFlow = null },
        ).also { it.start() }
    }

    /**
     * The reply box comes back from [ReplyDrafts] while the process lives (sheet closed and
     * reopened, or recreated), including a send still running; after process death, from saved state.
     */
    private fun restoredReply(number: Int, saved: Bundle?): ReplyForm =
        if (saved == null || ReplyDrafts.knows(number)) ReplyDrafts.form(number)
        else ReplyForm(
            draft = saved.getString(STATE_REPLY_DRAFT).orEmpty(),
            unblock = saved.getBoolean(STATE_REPLY_UNBLOCK, true),
            requestChanges = saved.getBoolean(STATE_REPLY_REQUEST_CHANGES),
            pendingMove = StatusMove.entries.firstOrNull { it.name == saved.getString(STATE_REPLY_PENDING_MOVE) },
        )

    private fun onReplyEdited(text: String) {
        if (text == screen.reply.draft) return
        update(screen.withReply(screen.reply.edit(text)))
    }

    /**
     * [ReplyForm.start] refuses while a send is in flight, also one started by an earlier sheet
     * for this issue ([ReplyDrafts]), so a double tap or a reopened sheet posts once.
     */
    private fun sendReply() {
        val start = screen.reply.start(screen.offerUnblock, screen.offerRequestChanges) ?: return
        update(screen.withReply(start.form))
        worker.reply(screen.number, start.action)
    }

    private val onReplyFinished: (ReplyForm, ReplyResult) -> Unit = { form, result ->
        update(screen.withReply(form))
        if (result.posted) load()
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
        ReplyDrafts.remember(next.number, next.reply)
        views.bind(next)
    }

    companion object {
        private const val EXTRA_ISSUE = "issue"
        private const val STATE_APPROVED = "approved"
        private const val STATE_MERGED = "merged"
        private const val STATE_REPLY_DRAFT = "reply_draft"
        private const val STATE_REPLY_UNBLOCK = "reply_unblock"
        private const val STATE_REPLY_REQUEST_CHANGES = "reply_request_changes"
        private const val STATE_REPLY_PENDING_MOVE = "reply_pending_move"

        fun intent(context: Context, issue: Int): Intent =
            Intent(context, TaskPreviewActivity::class.java)
                .putExtra(EXTRA_ISSUE, issue)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
