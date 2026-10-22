package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One background thread for all widget network work, so refreshes and approvals never race on
 * the stored state. Receivers hand work over with goAsync() and finish it when the job ends.
 */
object BackgroundWork {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taskboard-widget").apply { isDaemon = true }
    }

    fun run(pending: BroadcastReceiver.PendingResult?, job: () -> Unit) {
        executor.execute {
            try {
                job()
            } finally {
                pending?.finish()
            }
        }
    }
}
