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
import io.github.veganmarshmallow.taskboard.core.task.TaskType
import io.github.veganmarshmallow.taskboard.data.AppGraph

/**
 * The "New task" form, opened from the widget's + button. A thin binder: validation, the draft
 * and the in-flight guard live in [NewTaskSession] (core), the request in [NewTaskController].
 * The draft survives rotation because the session is process-wide, not part of this Activity.
 */
class NewTaskActivity : Activity() {
    private val session get() = NewTaskController.session
    private lateinit var form: DraftForm
    private lateinit var submit: Button
    private lateinit var status: TextView
    private lateinit var repos: RepoSuggestionStore
    private val repoAdapter by lazy { RepoSuggestionAdapter(this) }
    private var keepDraft = true

    override fun onCreate(savedInstanceState: Bundle?) {
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
        form.write(session.draft)

        savedInstanceState?.getCharSequence(KEY_ERROR)?.let(::showError) // a failure shown before rotation
        submit.setOnClickListener { onSubmit() }
        findViewById<Button>(R.id.task_cancel).setOnClickListener { onCancel() }
    }

    override fun onResume() {
        super.onResume()
        NewTaskController.listener = ::render
        render()
    }

    override fun onPause() {
        // Back, Home and rotation keep what was typed; Cancel and a successful submit clear it.
        if (keepDraft && !session.isSubmitting) session.draft = form.read()
        NewTaskController.listener = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (status.visibility == View.VISIBLE) outState.putCharSequence(KEY_ERROR, status.text)
    }

    private fun bindRepoField() {
        repos = RepoSuggestionStore(this)
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
                NewTaskController.send(this, start.issue)
            }
        }
        render()
    }

    private fun onCancel() {
        if (session.isSubmitting) return // the request can't be recalled; the button is disabled anyway
        keepDraft = false
        session.discard()
        finish()
    }

    /** Shows the busy state and any finished result. */
    private fun render() {
        val busy = session.isSubmitting
        submit.isEnabled = !busy
        submit.setText(if (busy) R.string.task_submitting else R.string.task_submit)
        form.setEnabled(!busy)
        findViewById<Button>(R.id.task_cancel).isEnabled = !busy
        when (val result = session.takeResult()) {
            null -> Unit
            is Outcome.Success -> { keepDraft = false; finish() } // NewTaskEffects already toasted and refreshed
            is Outcome.Failure -> showError(getString(R.string.task_failed, AppGraph.messages(this).message(result.error)))
        }
    }

    private fun showError(text: CharSequence) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private companion object {
        const val KEY_ERROR = "error"
    }
}
