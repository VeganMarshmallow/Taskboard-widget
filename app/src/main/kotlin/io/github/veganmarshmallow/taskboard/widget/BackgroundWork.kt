package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A plain background thread for short local work. Widget refreshes and approvals do NOT run here:
 * they are JobScheduler jobs ([BoardJobs]), because work under a receiver's goAsync() is killed
 * after about 10 seconds, which left the widget stuck on "updating…" (issue #17).
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
