package io.github.veganmarshmallow.taskboard.newtask

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.task.NewTaskSession
import io.github.veganmarshmallow.taskboard.core.task.SubmitRecovery
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * The "New task" form, opened from the widget's + button. A thin binder: validation, the draft
 * and the in-flight guard live in [NewTaskSession] (core), the request in [NewTaskController].
 * The draft survives rotation because the controller (and its session) is one per process, owned
 * by [AppGraph], not part of this Activity; it survives process death because the controller
 * also saves it to disk, and a submit cut short by process death is checked when the form opens.
 */
class NewTaskActivity : Activity() {
    private val controller = AppGraph.newTask
    private val session get() = controller.session
    private lateinit var form: DraftForm
    private lateinit var submit: Button
    private lateinit var status: TextView
    private lateinit var repos: RepoSuggestionStore
    private val repoAdapter by lazy { RepoSuggestionAdapter(this) }
    private var keepDraft = true

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_task)
        form = DraftForm(this)
        submit = findViewById(R.id.task_submit)
        status = findViewById(R.id.task_status)
        form.type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, TaskType.entries.map { it.value })
        bindRepoField()

        // Opening the form afresh: a success from an earlier, closed form was already toasted and
        // must not close this one. (After rotation, savedInstanceState is set and it is kept.)
        if (savedInstanceState == null) session.dropShownSuccess()
        controller.restore(this) // after process death: the saved draft, and a check of a cut-short submit
        form.write(session.draft)

        savedInstanceState?.let { saved -> // a message shown before rotation
            saved.getCharSequence(KEY_STATUS)?.let { showStatus(it, saved.getInt(KEY_STATUS_COLOR, R.color.error)) }
        }
        submit.setOnClickListener { onSubmit() }
        findViewById<Button>(R.id.task_cancel).setOnClickListener { onCancel() }
    }

    override fun onResume() {
        super.onResume()
        controller.listener = ::render
        render()
    }

    override fun onPause() {
        // Back, Home and rotation keep what was typed; Cancel and a successful submit clear it.
        if (keepDraft && !session.isSubmitting && controller.checking == null) {
            session.draft = form.read()
            controller.saveDraft(this, session.draft)
        }
        controller.listener = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (status.visibility == View.VISIBLE) {
            outState.putCharSequence(KEY_STATUS, status.text)
            outState.putInt(KEY_STATUS_COLOR, statusColor)
        }
    }

    private fun bindRepoField() {
        repos = RepoSuggestionStore(this, controller.deps)
        val field = form.repo
        field.setAdapter(repoAdapter)
        field.threshold = 1
        repoAdapter.setAll(repos.cached())
        field.setOnFocusChangeListener { _, focused -> if (focused && repoAdapter.count > 0 && field.text.isEmpty()) field.post { field.showDropDown() } }
        repos.refresh { repoAdapter.setAll(it) }
    }

    private fun onSubmit() {
        status.visibility = View.GONE
        when (val start = session.start(form.read())) {
            NewTaskSession.Start.Busy -> Unit
            is NewTaskSession.Start.Invalid -> form.showErrors(start.errors)
            is NewTaskSession.Start.Send -> {
                form.showErrors(emptyMap())
                controller.send(this, start.issue)
            }
        }
        render()
    }

    private fun onCancel() {
        if (session.isSubmitting) return // the request can't be recalled; the button is disabled anyway
        keepDraft = false
        controller.discard(this)
        finish()
    }

    /** Shows the busy state and any finished result or recovery check. */
    private fun render() {
        val checking = controller.checking
        val busy = session.isSubmitting || checking != null
        submit.isEnabled = !busy
        submit.setText(if (session.isSubmitting) R.string.task_submitting else R.string.task_submit)
        form.setEnabled(!busy)
        findViewById<Button>(R.id.task_cancel).isEnabled = !busy
        when (val result = session.takeResult()) {
            null -> Unit
            is Outcome.Success -> { keepDraft = false; finish() } // NewTaskEffects already toasted and refreshed
            is Outcome.Failure -> showStatus(NewTaskEffects.failureText(this, result.error), R.color.error)
        }
        if (checking != null) showStatus(getString(R.string.task_recovery_checking, checking.title), R.color.widget_muted)
        controller.takeRecovery()?.let(::showRecovery)
    }

    private fun showRecovery(result: SubmitRecovery.Result) {
        if (result is SubmitRecovery.Result.Created) form.write(session.draft) // the controller cleared it
        val color = when (result) {
            is SubmitRecovery.Result.Created -> R.color.ok
            is SubmitRecovery.Result.NotCreated -> R.color.widget_muted
            else -> R.color.error
        }
        showStatus(NewTaskEffects.recoveryText(this, result), color)
    }

    private var statusColor = R.color.error

    private fun showStatus(text: CharSequence, color: Int) {
        statusColor = color
        status.setTextColor(getColor(color))
        status.text = text
        status.visibility = View.VISIBLE
    }

    private companion object {
        const val KEY_STATUS = "error"
        const val KEY_STATUS_COLOR = "status_color"
    }
}
