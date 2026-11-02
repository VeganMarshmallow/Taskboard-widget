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

    /**
     * Stores [state] as the cached board and draws it, after binding's own refresh is out of the way.
     *
     * #50: the broadcasts that start a refresh (binding's APPWIDGET_UPDATE, and MY_PACKAGE_REPLACED
     * after the APKs were installed) can reach the app seconds late, on a freshly booted emulator
     * especially, so for the first test of a run. Arriving after [stopBackgroundRefresh], they
     * queued a refresh that, with no token, saved an empty board over the seeded one: the list
     * went empty under the still-highlighted tab. So wait for every queued broadcast to be handled
     * first; the cancel then catches whatever they scheduled. After seeding, the same wait runs
     * again and the stored board is checked, so anything that still slipped in is seen and the
     * board seeded again instead of the test tapping on a board that is about to be replaced.
     */
    fun seed(state: WidgetState) {
        TokenStore(context).clear() // no refresh here may touch the network
        repeat(SEED_ATTEMPTS) {
            awaitQuiet()
            StateStore(context).save(state)
            WidgetRenderer(context).render(state, intArrayOf(widgetId), updating = false)
            awaitQuiet()
            if (sameContent(StateStore(context).load(), state)) return
        }
        fail("the seeded board was replaced $SEED_ATTEMPTS times; ${storedSummary()}")
    }

    /** Every queued broadcast handled, every refresh it started cancelled or finished. */
    private fun awaitQuiet() {
        val output = shell("am wait-for-broadcast-idle")
            ?: throw AssertionError("am wait-for-broadcast-idle did not return within $SHELL_TIMEOUT_MS ms")
        if (BROADCAST_IDLE_ERRORS.any { output.contains(it, ignoreCase = true) }) fail("am wait-for-broadcast-idle failed: ${output.trim()}")
        stopBackgroundRefresh()
    }

    /** Same rows in every section and same error kind (a disk round trip may not keep [WidgetState] equal). */
    private fun sameContent(stored: WidgetState, seeded: WidgetState): Boolean {
        fun rows(s: WidgetState) = s.board?.sections?.map { section -> section.items.map { it.number } }
        return rows(stored) == rows(seeded) && stored.error?.javaClass == seeded.error?.javaClass
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

    /** The stored state in a few words, for failure messages: was the seeded board replaced? */
    fun storedSummary(): String {
        val state = StateStore(context).load()
        val board = state.board?.let { "${it.sections.sumOf { s -> s.items.size }} items" } ?: "none"
        return "stored board: $board, error: ${state.error?.javaClass?.simpleName ?: "none"}"
    }

    /** The text of a TextView in the hosted widget, e.g. the header's "last updated". */
    fun text(viewId: Int): String {
        var text = ""
        instrumentation.runOnMainSync { text = view.findViewById<android.widget.TextView>(viewId)?.text?.toString().orEmpty() }
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

    /** Waits for [read] to start with [expected], then lets late updates arrive and checks it still does. */
    fun assertTextSettles(why: String, expected: String, read: () -> String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!read().startsWith(expected) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        SystemClock.sleep(SETTLE_MS)
        instrumentation.waitForIdleSync()
        val shown = read()
        if (!shown.startsWith(expected)) fail("$why: expected \"$expected…\", the host shows \"$shown\"")
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

    /**
     * Runs [command] as the shell and returns its output, or null if it is still running after
     * [timeoutMs] (reading the output to the end is what waits for the command to finish).
     */
    private fun shell(command: String, timeoutMs: Long = SHELL_TIMEOUT_MS): String? {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        var output: String? = null
        val reader = Thread { ParcelFileDescriptor.AutoCloseInputStream(pfd).use { output = String(it.readBytes()) } }
        reader.start()
        reader.join(timeoutMs)
        return if (reader.isAlive) null else output
    }

    companion object {
        const val HOST_ID = 0x7a5b
        const val WIDTH_DP = 340
        const val HEIGHT_DP = 300
        private const val TIMEOUT_MS = 5_000L
        private const val POLL_MS = 20L
        private const val SETTLE_MS = 600L
        private const val SEED_ATTEMPTS = 3
        private const val SHELL_TIMEOUT_MS = 60_000L
        /** What `am` prints instead of waiting: an unknown command, a missing permission. */
        private val BROADCAST_IDLE_ERRORS = listOf("Unknown command", "Exception", "Error")
        private val WALLPAPER = Color.rgb(0x5B, 0x6B, 0x7F)
    }
}
