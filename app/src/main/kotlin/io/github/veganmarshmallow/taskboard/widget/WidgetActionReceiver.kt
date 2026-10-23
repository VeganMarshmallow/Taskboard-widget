package io.github.veganmarshmallow.taskboard.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.data.AppGraph

/** Handles the widget's refresh button and confirmed approvals, off the main thread. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            WidgetIntents.ACTION_NOOP -> Unit // a tap on the header: deliberately nothing (task #16)
            WidgetIntents.ACTION_REFRESH -> BackgroundWork.refresh(goAsync(), app)
            WidgetIntents.ACTION_APPROVE -> {
                val issue = intent.getIntExtra(WidgetIntents.EXTRA_ISSUE, 0)
                if (issue <= 0) return
                BackgroundWork.run(goAsync()) {
                    val result = WidgetRefresher(app).approve(issue)
                    report(app, issue, result)
                }
            }
        }
    }

    private fun report(context: Context, issue: Int, result: Outcome<Unit>) {
        val text = when (result) {
            is Outcome.Success -> context.getString(R.string.approved, issue)
            is Outcome.Failure -> context.getString(R.string.approve_failed, issue, AppGraph.messages(context).message(result.error))
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
