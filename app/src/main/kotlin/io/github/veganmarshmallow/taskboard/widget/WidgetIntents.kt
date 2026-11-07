package io.github.veganmarshmallow.taskboard.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.filter.FilterActivity
import io.github.veganmarshmallow.taskboard.newtask.NewTaskActivity
import io.github.veganmarshmallow.taskboard.questions.QuestionsActivity
import io.github.veganmarshmallow.taskboard.setup.SetupActivity

/** Every Intent / PendingIntent the widget uses, in one place so request codes never collide. */
object WidgetIntents {
    const val ACTION_REFRESH = "io.github.veganmarshmallow.taskboard.REFRESH"
    const val ACTION_APPROVE = "io.github.veganmarshmallow.taskboard.APPROVE"
    const val ACTION_SELECT_TAB = "io.github.veganmarshmallow.taskboard.SELECT_TAB"
    const val ACTION_NOOP = "io.github.veganmarshmallow.taskboard.NOOP"
    const val ACTION_MERGE_ALL_CANCEL = "io.github.veganmarshmallow.taskboard.MERGE_ALL_CANCEL"
    const val ACTION_MERGE_ALL_DISMISS = "io.github.veganmarshmallow.taskboard.MERGE_ALL_DISMISS"
    const val ACTION_MERGE_ALL_RESET = "io.github.veganmarshmallow.taskboard.MERGE_ALL_RESET"
    const val ACTION_CLEAR_FILTER = "io.github.veganmarshmallow.taskboard.CLEAR_FILTER"
    const val EXTRA_ISSUE = "issue"
    const val EXTRA_TAB = "tab"

    private const val RC_REFRESH = 1
    private const val RC_SETUP = 2
    private const val RC_ROW_TEMPLATE = 3
    // 4 was the header title's "open board" intent, removed in #16; not reused, to avoid stale PendingIntents.
    private const val RC_NEW_TASK = 5
    private const val RC_NOOP = 6
    private const val RC_UPDATES = 14
    private const val RC_MERGE_ALL = 15
    private const val RC_MERGE_ALL_CANCEL = 16
    private const val RC_MERGE_ALL_DISMISS = 17
    private const val RC_FILTER = 18
    private const val RC_FILTER_CLEAR = 19
    private const val RC_MERGE_ALL_RESET = 20
    private const val RC_QUESTIONS = 21
    /** Tab taps use request codes from here up (one per tab); the intent data also names the widget, so no two collide. */
    private const val RC_TAB_BASE = 1000

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

    fun selectTabIntent(context: Context, widgetId: Int, section: Section) =
        Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_SELECT_TAB)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(EXTRA_TAB, TabSelection.key(section))
            // Distinct data too, so PendingIntents for different widgets/tabs are never treated as equal.
            .setData(Uri.parse("taskboard://tab/$widgetId/${section.name}"))

    fun selectTab(context: Context, widgetId: Int, section: Section): PendingIntent = PendingIntent.getBroadcast(
        context, RC_TAB_BASE + section.ordinal, selectTabIntent(context, widgetId, section), IMMUTABLE,
    )

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

    /** Opens the app scrolled to the "App updates" card (the widget's update dot). */
    fun updates(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_UPDATES,
        Intent(context, SetupActivity::class.java).setAction(SetupActivity.ACTION_SHOW_UPDATES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    /** The Merge all line's button (#57): the plan and its confirmation, in [RowActionActivity]. */
    fun mergeAll(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_MERGE_ALL,
        RowAction.MergeAll.toFillIn().setClass(context, RowActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        IMMUTABLE,
    )

    fun cancelMergeAll(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RC_MERGE_ALL_CANCEL, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_MERGE_ALL_CANCEL), IMMUTABLE,
    )

    fun dismissMergeAll(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RC_MERGE_ALL_DISMISS, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_MERGE_ALL_DISMISS), IMMUTABLE,
    )

    fun resetMergeAll(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RC_MERGE_ALL_RESET, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_MERGE_ALL_RESET), IMMUTABLE,
    )

    /** The filter button and the "Filtered" line (#59). NEW_TASK + CLEAR_TOP: one sheet at a time. */
    fun filter(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_FILTER,
        Intent(context, FilterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        IMMUTABLE,
    )

    /** The header's Questions badge (#82): the Questions screen, asking in general. */
    fun questions(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_QUESTIONS, QuestionsActivity.intent(context), IMMUTABLE,
    )

    /** The "Filtered" line's Clear (#59). */
    fun clearFilter(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RC_FILTER_CLEAR, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_CLEAR_FILTER), IMMUTABLE,
    )

    fun listService(context: Context, widgetId: Int): Intent =
        Intent(context, BoardListService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // Unique data per widget so the system does not share one factory between widgets.
            .setData(Uri.parse("taskboard://widget/$widgetId"))

    fun viewUrl(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
