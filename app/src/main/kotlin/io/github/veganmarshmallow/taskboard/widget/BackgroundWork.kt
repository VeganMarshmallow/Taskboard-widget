package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import io.github.veganmarshmallow.taskboard.core.work.RefreshGate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One background thread for all widget network work, so refreshes and approvals never race on
 * the stored state. Receivers hand work over with goAsync() and finish it when the job ends.
 * Refresh requests are coalesced by [RefreshGate]: repeated taps while a refresh is running queue
 * at most one more, instead of a line of refreshes that would outlive the broadcast time limit.
 */
object BackgroundWork {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-widget").apply { isDaemon = true }
    }
    private val refreshGate = RefreshGate()

    fun run(pending: BroadcastReceiver.PendingResult?, job: () -> Unit) {
        executor.execute {
            try {
                job()
            } finally {
                pending?.finish()
            }
        }
    }

    /** Runs [before] (always), then a refresh unless one is already waiting to run. */
    fun refresh(pending: BroadcastReceiver.PendingResult?, context: Context, before: () -> Unit = {}) {
        val queued = refreshGate.request()
        run(pending) {
            before()
            if (queued) {
                refreshGate.started()
                WidgetRefresher(context).refresh()
            }
        }
    }
}
