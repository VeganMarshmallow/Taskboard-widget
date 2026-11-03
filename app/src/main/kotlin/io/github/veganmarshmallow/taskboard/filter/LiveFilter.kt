package io.github.veganmarshmallow.taskboard.filter

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.util.concurrent.Executors

/**
 * Saves the sheet's filter and redraws the widgets behind it as the owner changes it (#59), so the
 * board updates live. Typing is batched: the save and redraw run [DELAY_MS] after the last change,
 * off the main thread. [flush] does a pending one right away (the sheet is closing).
 */
class LiveFilter(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var pending: BoardFilter? = null
    private val write = Runnable { flush() }

    fun update(filter: BoardFilter) {
        pending = filter
        main.removeCallbacks(write)
        main.postDelayed(write, DELAY_MS)
    }

    fun flush() {
        main.removeCallbacks(write)
        val filter = pending ?: return
        pending = null
        WORKER.execute {
            FilterStore(app).save(filter)
            StateRedraw.redraw(app)
        }
    }

    private companion object {
        const val DELAY_MS = 300L

        /** One thread, so saves land in the order they were made. */
        val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-filter").apply { isDaemon = true } }
    }
}
