package io.github.veganmarshmallow.taskboard.setup

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.error.BoardError
import io.github.veganmarshmallow.taskboard.core.github.GitHubApi
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.pace.BoardSettingsApi
import io.github.veganmarshmallow.taskboard.core.pace.WorkPace
import io.github.veganmarshmallow.taskboard.core.pace.WorkPaceForm
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.WorkPaceStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Work pace choice in settings (#83): Save usage or Faster, stored in the board repo's
 * `.board/settings.json` ([BoardSettingsApi]). Opening the screen reads the file (the last known
 * value shows meanwhile, locked); a tap saves it and says it takes effect from Claude's next run.
 * What is shown and allowed is [WorkPaceForm]; this class renders it and runs the requests.
 * [onPaceShown] gets the pace on screen after every render (the Auto merge description follows it, #88).
 */
class WorkPaceSection(
    private val activity: Activity,
    private val store: WorkPaceStore = WorkPaceStore(activity),
    private val apiOrNull: () -> GitHubApi? = { AppGraph.apiOrNull(activity) },
    private val onPaceShown: (WorkPace) -> Unit = {},
) {
    private val group: RadioGroup = activity.findViewById(R.id.work_pace_choice)
    private val status: TextView = activity.findViewById(R.id.work_pace_status)
    private val retry: Button = activity.findViewById(R.id.work_pace_retry)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var form = WorkPaceForm.loading(store.cached())
    private var rendering = false

    /** The form on screen now; read by instrumented tests. */
    val shown: WorkPaceForm get() = form

    fun bind() {
        group.setOnCheckedChangeListener { _, id -> if (!rendering) paceOf(id)?.let(::pick) }
        retry.setOnClickListener { load() }
        load()
    }

    fun onDestroy() {
        executor.shutdownNow()
    }

    private fun load() {
        show(form.reloading())
        request({ BoardSettingsApi(it).pace() }) { show(form.loaded(it)) }
    }

    private fun pick(pace: WorkPace) {
        val saving = form.picked(pace) ?: return show(form) // a tap that changes nothing: put the shown option back
        show(saving)
        request({ BoardSettingsApi(it).save(pace) }) { show(form.saved(it)) }
    }

    /** Runs [call] off the main thread, then [done] on it unless the screen is gone. No token is a failure. */
    private fun request(call: (GitHubApi) -> Outcome<WorkPace>, done: (Outcome<WorkPace>) -> Unit) {
        val api = apiOrNull() ?: return done(Outcome.Failure(BoardError.NoToken))
        try {
            executor.execute {
                val result = call(api)
                if (result is Outcome.Success) store.remember(result.value)
                main.post { if (!activity.isDestroyed) done(result) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // the screen is closing
        }
    }

    private fun show(next: WorkPaceForm) {
        form = next
        rendering = true
        group.check(buttonOf(next.shown))
        rendering = false
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = next.enabled
        retry.visibility = if (next.canRetry) View.VISIBLE else View.GONE
        val text = statusText(next)
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        onPaceShown(next.shown)
    }

    private fun statusText(f: WorkPaceForm): String {
        val error = f.error?.let { AppGraph.messages(activity).message(it) }.orEmpty()
        return when (f.phase) {
            WorkPaceForm.Phase.LOADING -> activity.getString(R.string.pace_loading)
            WorkPaceForm.Phase.LOAD_FAILED -> activity.getString(R.string.pace_load_failed, error)
            WorkPaceForm.Phase.READY -> ""
            WorkPaceForm.Phase.SAVING -> activity.getString(R.string.pace_saving)
            WorkPaceForm.Phase.SAVED -> activity.getString(R.string.pace_saved)
            WorkPaceForm.Phase.SAVE_FAILED -> activity.getString(R.string.pace_save_failed, error) +
                if (f.needsWriteAccess) activity.getString(R.string.pace_needs_write) else ""
        }
    }

    companion object {
        fun buttonOf(pace: WorkPace): Int = when (pace) {
            WorkPace.SAVE -> R.id.pace_save
            WorkPace.FAST -> R.id.pace_fast
        }

        fun paceOf(buttonId: Int): WorkPace? = when (buttonId) {
            R.id.pace_save -> WorkPace.SAVE
            R.id.pace_fast -> WorkPace.FAST
            else -> null
        }
    }
}
