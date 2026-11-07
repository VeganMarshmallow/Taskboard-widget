package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import android.widget.RemoteViews
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.questions.QuestionBadge

/**
 * The widget header's Questions badge (task #82): "?" when no task waits on the owner's answer,
 * otherwise the count on an accent circle. Tapping it opens the Questions screen.
 */
object QuestionsBadgeViews {
    fun bind(views: RemoteViews, context: Context, board: Board?) {
        val count = QuestionBadge.count(board)
        views.setTextViewText(R.id.questions, QuestionBadge.text(count))
        views.setTextColor(R.id.questions, context.getColor(if (count > 0) android.R.color.white else R.color.widget_muted))
        views.setInt(R.id.questions, "setBackgroundResource", if (count > 0) R.drawable.questions_badge_background else 0)
        views.setContentDescription(
            R.id.questions,
            if (count > 0) context.resources.getQuantityString(R.plurals.questions_badge, count, count)
            else context.getString(R.string.questions_badge_none),
        )
        views.setOnClickPendingIntent(R.id.questions, WidgetIntents.questions(context))
    }
}
