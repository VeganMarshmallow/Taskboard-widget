package io.github.veganmarshmallow.taskboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity
import io.github.veganmarshmallow.taskboard.setup.SetupActivity

/** Every Intent / PendingIntent the widget uses, in one place so request codes never collide. */
object WidgetIntents {
    const val ACTION_REFRESH = "io.github.veganmarshmallow.taskboard.REFRESH"
    const val ACTION_APPROVE = "io.github.veganmarshmallow.taskboard.APPROVE"
    const val ACTION_NOOP = "io.github.veganmarshmallow.taskboard.NOOP"
    const val EXTRA_ISSUE = "issue"

    private const val RC_REFRESH = 1
    private const val RC_SETUP = 2
    private const val RC_ROW_TEMPLATE = 3
    // 4 was the header title's "open board" intent, removed in #16; not reused, to avoid stale PendingIntents.
    private const val RC_NEW_TASK = 5
    private const val RC_NOOP = 6

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun refreshIntent(context: Context) =
        Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_REFRESH)

    fun approveIntent(context: Context, issue: Int) =
        Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_APPROVE).putExtra(EXTRA_ISSUE, issue)

    /** Sent for taps on non-interactive widget areas (header text, padding). [WidgetActionReceiver] ignores it. */
    fun noopIntent(context: Context) =
        Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_NOOP)

    fun swallowTap(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, RC_NOOP, noopIntent(context), IMMUTABLE)

    fun refresh(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, RC_REFRESH, refreshIntent(context), IMMUTABLE)

    fun setup(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_SETUP,
        Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), IMMUTABLE,
    )

    /** The + button. NEW_TASK + CLEAR_TOP: one form at a time, brought back if already open. */
    fun newTask(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_NEW_TASK,
        Intent(context, NewTaskActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        IMMUTABLE,
    )

    /**
     * Template for list rows. Must be mutable: the launcher merges each row's fill-in extras into it.
     * It targets our own non-exported activity, so the mutability cannot be abused to reach anything else.
     */
    fun rowTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_ROW_TEMPLATE,
        Intent(context, RowActionActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    fun listService(context: Context, widgetId: Int): Intent =
        Intent(context, BoardListService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // Unique data per widget so the system does not share one factory between widgets.
            .setData(Uri.parse("taskboard://widget/$widgetId"))

    fun viewUrl(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
