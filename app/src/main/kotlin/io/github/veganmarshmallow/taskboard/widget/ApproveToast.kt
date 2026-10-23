package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.data.AppGraph

/** Tells the user how an approval went. Safe to call from any thread. */
object ApproveToast {
    fun show(context: Context, issue: Int, result: Outcome<Unit>) {
        val text = when (result) {
            is Outcome.Success -> context.getString(R.string.approved, issue)
            is Outcome.Failure -> context.getString(R.string.approve_failed, issue, AppGraph.messages(context).message(result.error))
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
