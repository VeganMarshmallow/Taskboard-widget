package io.github.veganmarshmallow.taskboard.filter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.work.nonFatal
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.widget.StateRedraw
import java.util.concurrent.Executors

/**
 * Saves the sheet's filter and redraws the widgets behind it as the owner changes it (#59), so the
 * board updates live. Typing is batched: the save and redraw run [DELAY_MS] after the last change,
 * off the main thread. [flush] does a pending one right away (the sheet is closing).
 *
 * [apply] does the save and redraw on the worker thread; tests pass a fake. Anything it throws, an
 * Error included (task #63), is logged and dropped, so the app doesn't crash and the next save still
 * runs; fatal VM errors still go up ([nonFatal]).
 */
class LiveFilter(
    context: Context,
    private val apply: (Context, BoardFilter) -> Unit = ::saveAndRedraw,
) {
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
            try {
                apply(app, filter)
            } catch (t: Throwable) {
                Log.w(TAG, "saving the filter failed: ${nonFatal(t).javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val DELAY_MS = 300L
        const val TAG = "LiveFilter"

        /** One thread, so saves land in the order they were made. */
        val WORKER = Executors.newSingleThreadExecutor { r -> Thread(r, "taskboard-filter").apply { isDaemon = true } }
    }
}

/** The real [LiveFilter] work: store the filter, then redraw every widget with it. */
private fun saveAndRedraw(app: Context, filter: BoardFilter) {
    FilterStore(app).save(filter)
    StateRedraw.redraw(app)
}
