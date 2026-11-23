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
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.preview.AskDrafts
import io.github.veganmarshmallow.taskboard.preview.PreviewWorker
import io.github.veganmarshmallow.taskboard.preview.ReplyBoxController
import io.github.veganmarshmallow.taskboard.preview.ReplyDrafts
import io.github.veganmarshmallow.taskboard.preview.TaskPreviewActivity
import io.github.veganmarshmallow.taskboard.setup.SetupActivity
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * The Questions screen (task #82), opened from the widget header's badge or a task sheet's "Ask
 * Claude": the ask box, Claude's open questions (each with the task sheet's reply box), and the
 * owner's questions with Claude's answers. Decisions live in core ([QuestionsScreen]); each reply
 * box (the ask box over [AskDrafts], each card's over [ReplyDrafts]) is a [ReplyBoxController];
 * this class loads, renders the two lists and the status line.
 */
class QuestionsActivity : Activity() {
    private lateinit var messages: ErrorMessages
    private lateinit var worker: PreviewWorker
    private lateinit var ask: AskBoxViews
    private lateinit var screen: QuestionsScreen
    private lateinit var askBox: ReplyBoxController

    /** Each card's reply box by issue, kept across reloads so a card keeps its form; [shown] are on screen. */
    private val replies = HashMap<Int, ReplyBoxController>()
    private var shown: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_questions)
        messages = AppGraph.messages(this)
        worker = PreviewWorker(this)
        ask = AskBoxViews(findViewById(R.id.questions_root), messages)
        ask.onGeneral(::askInGeneral)
        findViewById<View>(R.id.questions_retry).setOnClickListener { onRetry() }

        val topic = intent?.getIntExtra(EXTRA_ISSUE, 0)?.takeIf { it > 0 }?.let { AskTopic(it, intent.getStringExtra(EXTRA_TITLE)) }
        val initial = QuestionsScreen(topic = topic)
        askBox = ReplyBoxController(
            AskDrafts, initial.askKey, ask.box,
            send = ::askClaude,
            show = { update(screen.withAsk(it)) },
            onPosted = ::load,
        )
        update(initial.withAsk(askBox.form))
        load()
    }

    /**
     * Back from a task sheet opened with "Open task": that sheet took over and then dropped the
     * card's [ReplyDrafts] listener, so listen again and pick up how a send ended meanwhile
     * (otherwise the card could stay on "Sending…").
     */
    override fun onResume() {
        super.onResume()
        shown.forEach { replies[it]?.resume() }
    }

    override fun onDestroy() {
        if (::worker.isInitialized) worker.close()
        if (::askBox.isInitialized) askBox.close()
        replies.values.forEach { it.close() }
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
        val inflater = LayoutInflater.from(this)
        questions.fromClaude.forEach { question ->
            val card = ClaudeQuestionViews(inflater.inflate(R.layout.item_claude_question, fromClaude, false), messages, question)
            fromClaude.addView(card.card)
            wireCard(card)
        }
        shown = questions.fromClaude.map { it.issue }
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
        val form = replies[issue]?.form ?: ReplyDrafts.form(issue)
        card.bind(form)
        replies[issue] = ReplyBoxController(
            ReplyDrafts, issue, card.reply, form,
            start = { it.start(offerUnblock = card.question.needsOwner) },
            send = { worker.reply(issue, it) },
            show = card::bind,
            onPosted = ::load,
        )
        card.onOpen { startActivity(TaskPreviewActivity.intent(this, issue)) }
    }

    private fun askClaude(action: ReplyAction) {
        val text = (action as? ReplyAction.Post)?.text ?: return
        worker.ask(screen.askKey, screen.topic?.number, text)
    }

    private fun askInGeneral() {
        screen = screen.askGeneral(screen.ask)
        askBox.switchTo(screen.askKey)
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
