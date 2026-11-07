package io.github.veganmarshmallow.taskboard.questions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.ErrorMessages
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.questions.AskTopic
import io.github.veganmarshmallow.taskboard.core.questions.Questions
import io.github.veganmarshmallow.taskboard.core.questions.QuestionsScreen
import io.github.veganmarshmallow.taskboard.core.reply.ReplyAction
import io.github.veganmarshmallow.taskboard.core.reply.ReplyForm
import io.github.veganmarshmallow.taskboard.core.reply.ReplyResult
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.preview.AskDrafts
import io.github.veganmarshmallow.taskboard.preview.PreviewWorker
import io.github.veganmarshmallow.taskboard.preview.ReplyDrafts
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * The Questions screen (task #82), opened from the widget header's badge or a task sheet's "Ask
 * Claude": the ask box, Claude's open questions (each with the task sheet's reply box), and the
 * owner's questions with Claude's answers. Decisions live in core ([QuestionsScreen], the reply's
 * [ReplyForm]); this class wires the worker, the drafts ([AskDrafts], [ReplyDrafts]) and the views.
 */
class QuestionsActivity : Activity() {
    private lateinit var messages: ErrorMessages
    private lateinit var worker: PreviewWorker
    private lateinit var ask: AskBoxViews
    private lateinit var screen: QuestionsScreen
    private val cards = LinkedHashMap<Int, ClaudeQuestionViews>()
    private val cardForms = HashMap<Int, ReplyForm>()
    private val cardListeners = HashMap<Int, (ReplyForm, ReplyResult) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_questions)
        messages = AppGraph.messages(this)
        worker = PreviewWorker(this)
        ask = AskBoxViews(findViewById(R.id.questions_root), messages)
        ask.box.listen(
            onEdit = { text -> if (text != screen.ask.draft) update(screen.withAsk(screen.ask.edit(text))) },
            onUnblock = {},
            onRequestChanges = {},
            onSend = ::sendQuestion,
        )
        ask.onGeneral(::askInGeneral)
        findViewById<View>(R.id.questions_retry).setOnClickListener { onRetry() }

        val topic = intent?.getIntExtra(EXTRA_ISSUE, 0)?.takeIf { it > 0 }?.let { AskTopic(it, intent.getStringExtra(EXTRA_TITLE)) }
        val initial = QuestionsScreen(topic = topic)
        screen = initial.withAsk(AskDrafts.form(initial.askKey))
        AskDrafts.listen(screen.askKey, onAskFinished)
        update(screen)
        load()
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.close()
        if (::screen.isInitialized) AskDrafts.stopListening(screen.askKey, onAskFinished)
        cardListeners.forEach { (issue, listener) -> ReplyDrafts.stopListening(issue, listener) }
        super.onDestroy()
    }

    private fun load() {
        update(screen.loadStarted())
        worker.questions { result ->
            update(
                when (result) {
                    is Outcome.Success -> screen.loaded(result.value)
                    is Outcome.Failure -> screen.failed(result.error)
                },
            )
            if (result is Outcome.Success) showLists(result.value)
        }
    }

    private fun update(next: QuestionsScreen) {
        screen = next
        AskDrafts.remember(next.askKey, next.ask)
        ask.bind(next)
        bindStatus(next)
    }

    private fun bindStatus(screen: QuestionsScreen) {
        val status = findViewById<TextView>(R.id.questions_status)
        val error = screen.error
        val text = when {
            error != null -> getString(R.string.questions_load_failed, messages.message(error))
            screen.loading -> getString(R.string.questions_loading)
            else -> null
        }
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        status.text = text.orEmpty()
        status.setTextColor(if (error != null) getColor(R.color.widget_error_text) else AppTheme.color(this, R.attr.tbMuted))
        if (error != null) status.setBackgroundResource(R.drawable.error_background) else status.background = null
        val retry = findViewById<Button>(R.id.questions_retry)
        retry.visibility = if (error != null) View.VISIBLE else View.GONE
        retry.setText(if (error != null && messages.opensSetup(error)) R.string.preview_set_up else R.string.questions_retry)
    }

    private fun showLists(questions: Questions) {
        val fromClaude = findViewById<ViewGroup>(R.id.questions_from_claude)
        val toClaude = findViewById<ViewGroup>(R.id.questions_to_claude)
        fromClaude.removeAllViews()
        toClaude.removeAllViews()
        cards.clear()
        val inflater = LayoutInflater.from(this)
        questions.fromClaude.forEach { question ->
            val card = ClaudeQuestionViews(inflater.inflate(R.layout.item_claude_question, fromClaude, false), messages, question)
            fromClaude.addView(card.card)
            cards[question.issue] = card
            wireCard(card)
        }
        questions.toClaude.forEach { question ->
            val card = inflater.inflate(R.layout.item_owner_question, toClaude, false)
            OwnerQuestionViews.bind(card, question)
            toClaude.addView(card)
        }
        findViewById<View>(R.id.questions_from_claude_none).visibility = if (questions.fromClaude.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.questions_to_claude_none).visibility = if (questions.toClaude.isEmpty()) View.VISIBLE else View.GONE
    }

    /** A card's reply box lives in [ReplyDrafts], shared with the task sheet, so a send is never doubled. */
    private fun wireCard(card: ClaudeQuestionViews) {
        val issue = card.question.issue
        cardForms[issue] = cardForms[issue] ?: ReplyDrafts.form(issue)
        card.bind(cardForms.getValue(issue))
        card.reply.listen(
            onEdit = { text -> cardForms[issue]?.takeIf { it.draft != text }?.let { updateCard(issue, it.edit(text)) } },
            onUnblock = { checked -> cardForms[issue]?.let { updateCard(issue, it.unblockChecked(checked)) } },
            onRequestChanges = {},
            onSend = { sendReply(card) },
        )
        card.onOpen { startActivity(TaskPreviewActivity.intent(this, issue)) }
        val listener = cardListeners.getOrPut(issue) {
            { form: ReplyForm, result: ReplyResult ->
                updateCard(issue, form)
                if (result.posted) load()
            }
        }
        ReplyDrafts.listen(issue, listener)
    }

    private fun updateCard(issue: Int, form: ReplyForm) {
        cardForms[issue] = form
        ReplyDrafts.remember(issue, form)
        cards[issue]?.bind(form)
    }

    private fun sendReply(card: ClaudeQuestionViews) {
        val issue = card.question.issue
        val start = cardForms[issue]?.start(offerUnblock = card.question.needsOwner) ?: return
        updateCard(issue, start.form)
        worker.reply(issue, start.action)
    }

    /** [ReplyForm.start] refuses while a send is in flight, so a double tap asks once. */
    private fun sendQuestion() {
        val start = screen.ask.start(offerUnblock = false) ?: return
        val text = (start.action as? ReplyAction.Post)?.text ?: return
        update(screen.withAsk(start.form))
        worker.ask(screen.askKey, screen.topic?.number, text)
    }

    private fun askInGeneral() {
        AskDrafts.stopListening(screen.askKey, onAskFinished)
        update(screen.askGeneral(AskDrafts.form(QuestionsScreen.GENERAL)))
        AskDrafts.listen(screen.askKey, onAskFinished)
    }

    private val onAskFinished: (ReplyForm, ReplyResult) -> Unit = { form, result ->
        update(screen.withAsk(form))
        if (result.posted) load()
    }

    private fun onRetry() {
        val error = screen.error
        if (error != null && messages.opensSetup(error)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        } else {
            load()
        }
    }

    companion object {
        private const val EXTRA_ISSUE = "issue"
        private const val EXTRA_TITLE = "title"

        /** The screen, asking about task [issue] (titled [title]) when given, otherwise in general. */
        fun intent(context: Context, issue: Int? = null, title: String? = null): Intent =
            Intent(context, QuestionsActivity::class.java)
                .apply { if (issue != null) putExtra(EXTRA_ISSUE, issue).putExtra(EXTRA_TITLE, title) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
