package io.github.veganmarshmallow.taskboard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.core.board.Section
import io.github.veganmarshmallow.taskboard.core.board.TabSelection
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetState

/** Renders what a screenshot test pictures and saves it with [Screenshots]; call from the test thread. */
object ScreenshotCapture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    /** Renders the widget for [state] through [WidgetHost] (in [host]'s configuration), saves it as [name], returns the widget view. */
    fun widget(
        name: String,
        state: WidgetState,
        widthDp: Int = 340,
        heightDp: Int = 300,
        tab: Section = TabSelection.default(state.board),
        host: Context = context,
    ): View {
        lateinit var widget: View
        instrumentation.runOnMainSync {
            val rendered = WidgetHost(host).render(state, widthDp, heightDp, tab)
            widget = rendered.widget
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return widget
    }

    /** Renders the preview sheet for [screen] through [host] ([PreviewHost]), saves it as [name], returns the sheet view. */
    fun preview(
        name: String,
        screen: PreviewScreen,
        widthDp: Int,
        heightDp: Int,
        host: () -> PreviewHost = { PreviewHost(context) },
        prepare: (View) -> Unit = {},
    ): View {
        lateinit var sheet: View
        instrumentation.runOnMainSync {
            val rendered = host().render(screen, widthDp = widthDp, heightDp = heightDp, prepare = prepare)
            sheet = rendered.sheet
            Screenshots.save(context, name, WidgetHost.toBitmap(rendered.root))
        }
        return sheet
    }

    /** Saves [activity]'s window as [name]; [check] runs on the main thread against the same view first. */
    fun activity(name: String, activity: Activity, check: (View) -> Unit = {}) {
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            check(root)
            bitmap = WidgetHost.toBitmap(root)
        }
        Screenshots.save(context, name, bitmap!!)
    }
}
