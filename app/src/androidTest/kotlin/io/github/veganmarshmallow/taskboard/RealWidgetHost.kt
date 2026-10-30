package io.github.veganmarshmallow.taskboard

import android.app.Instrumentation
import android.app.job.JobScheduler
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.widget.BoardWidgetProvider
import io.github.veganmarshmallow.taskboard.widget.TabViews
import io.github.veganmarshmallow.taskboard.widget.WidgetRenderer
import org.junit.Assert.fail

/**
 * A real widget host, like a launcher: binds a widget id to [BoardWidgetProvider] through
 * AppWidgetManager and shows it in an [AppWidgetHostView]. Every update the app makes then travels
 * the whole system path (AppWidgetManager, system_server, this host's reapply), and a tap is a real
 * click on the hosted view that fires the PendingIntent the app put there.
 *
 * Binding needs the "bind widgets" grant, which the test gives itself through the shell.
 */
class RealWidgetHost(private val instrumentation: Instrumentation) : AutoCloseable {
    private val context = instrumentation.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    private val host = AppWidgetHost(context, HOST_ID)
    val widgetId: Int
    lateinit var view: AppWidgetHostView
        private set
    private lateinit var frame: FrameLayout

    init {
        shell("appwidget grantbind --package ${context.packageName}")
        host.deleteHost() // a crashed earlier run may have left ids behind
        host.startListening()
        widgetId = host.allocateAppWidgetId()
        val provider = ComponentName(context, BoardWidgetProvider::class.java)
        check(manager.bindAppWidgetIdIfAllowed(widgetId, provider)) { "could not bind a widget id (grantbind failed?)" }
        instrumentation.runOnMainSync {
            view = host.createView(context, widgetId, manager.getAppWidgetInfo(widgetId))
            frame = FrameLayout(context).apply { setBackgroundColor(WALLPAPER) }
            val d = context.resources.displayMetrics.density
            frame.addView(view, FrameLayout.LayoutParams((WIDTH_DP * d).toInt(), (HEIGHT_DP * d).toInt()))
        }
        awaitOnMain("the first widget update") { view.findViewById<View>(R.id.tabs) != null }
    }

    /**
     * Binding sent APPWIDGET_UPDATE, so the provider queued a refresh job, exactly as on a phone.
     * The emulator has no token, so that refresh would save "no token" with no board and wipe
     * whatever board the test seeded. Cancels pending jobs and waits until no refresh is running
     * (stable for [SETTLE_MS]), so the test can seed its board after this and keep it.
     */
    private fun stopBackgroundRefresh() {
        val jobs = context.getSystemService(JobScheduler::class.java)
        val tracker = AppGraph.refreshTracker(context)
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - quietSince < SETTLE_MS) {
            jobs?.cancelAll()
            if (tracker.isUpdating()) quietSince = SystemClock.uptimeMillis()
            if (SystemClock.uptimeMillis() > deadline) fail("a refresh started by binding the widget never finished")
            SystemClock.sleep(POLL_MS)
        }
    }

    /** Stores [state] as the cached board and draws it, after binding's own refresh is out of the way. */
    fun seed(state: WidgetState) {
        TokenStore(context).clear() // no refresh here may touch the network
        stopBackgroundRefresh()
        StateStore(context).save(state)
        WidgetRenderer(context).render(state, intArrayOf(widgetId), updating = false)
    }

    /** A real click on the tab's slot, on the main thread like a finger would be. */
    fun tap(section: Section) = instrumentation.runOnMainSync {
        check(view.findViewById<View>(TabViews.ids(section).slot).performClick()) { "$section slot has no click handler" }
    }

    /** The tabs the hosted view shows as selected right now: highlighted label shown, plain label hidden. */
    fun highlighted(): Set<Section> {
        var result: Set<Section> = emptySet()
        instrumentation.runOnMainSync {
            result = Section.entries.filter { section ->
                val ids = TabViews.ids(section)
                view.findViewById<View>(ids.selectedLabel)?.visibility == View.VISIBLE &&
                    view.findViewById<View>(ids.label)?.visibility != View.VISIBLE
            }.toSet()
        }
        return result
    }

    fun slotDescription(section: Section): String {
        var text = ""
        instrumentation.runOnMainSync { text = view.findViewById<View>(TabViews.ids(section).slot).contentDescription?.toString().orEmpty() }
        return text
    }

    /** Waits until exactly [section] is highlighted, failing with what is shown instead. */
    fun awaitHighlighted(section: Section, why: String, timeoutMs: Long = TIMEOUT_MS) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var shown: Set<Section>
        do {
            instrumentation.waitForIdleSync()
            shown = highlighted()
            if (shown == setOf(section)) return
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("$why: expected only $section highlighted, the host shows $shown")
    }

    /** Lets any late update arrive (a racing render, a queued broadcast), then checks again. */
    fun assertStaysHighlighted(section: Section, why: String) {
        SystemClock.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
        val shown = highlighted()
        if (shown != setOf(section)) fail("$why: $section was highlighted, then the host changed to $shown")
    }

    /** The widget as drawn by the host, cropped below the tabs (the list fills in asynchronously). */
    fun headerBitmap(): Bitmap {
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val d = context.resources.displayMetrics.density
            val w = (WIDTH_DP * d).toInt()
            val h = (HEIGHT_DP * d).toInt()
            frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, w, h)
            val tabs = view.findViewById<View>(R.id.tabs)
            val bottom = (tabs.bottom + (12 * d).toInt()).coerceAtMost(h)
            bitmap = Bitmap.createBitmap(w, bottom, Bitmap.Config.ARGB_8888).also { frame.draw(Canvas(it)) }
        }
        return bitmap
    }

    override fun close() {
        host.deleteAppWidgetId(widgetId)
        host.stopListening()
        host.deleteHost()
        // Binding sent APPWIDGET_UPDATE, which queued a refresh; don't let it run into later tests.
        context.getSystemService(JobScheduler::class.java)?.cancelAll()
    }

    private fun awaitOnMain(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            SystemClock.sleep(POLL_MS)
        }
        fail("timed out waiting for $what")
    }

    private fun shell(command: String) {
        // Reading the output to the end waits for the command to finish.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    companion object {
        const val HOST_ID = 0x7a5b
        const val WIDTH_DP = 340
        const val HEIGHT_DP = 300
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 20L
        private const val SETTLE_MS = 600L
        private val WALLPAPER = Color.rgb(0x5B, 0x6B, 0x7F)
    }
}
