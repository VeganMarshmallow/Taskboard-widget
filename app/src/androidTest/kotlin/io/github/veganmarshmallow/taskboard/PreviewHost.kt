package io.github.veganmarshmallow.taskboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import io.github.veganmarshmallow.taskboard.core.preview.PreviewScreen
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.preview.PreviewViews

/**
 * Inflates the real preview layout with the preview's theme and binds a [PreviewScreen], the way
 * TaskPreviewActivity does, at the bottom of a dimmed "home screen". Must run on the main thread.
 * [fontScale] and [night] override the device settings (large text, dark mode); null keeps them.
 */
class PreviewHost(context: Context, fontScale: Float? = null, night: Boolean? = null) {
    private val themed = ContextThemeWrapper(configured(context, fontScale, night), R.style.Theme_Taskboard_Preview)

    class Rendered(val root: View, val sheet: View, val views: PreviewViews)

    fun render(screen: PreviewScreen, widthDp: Int = 380, heightDp: Int = 520): Rendered {
        val density = themed.resources.displayMetrics.density
        val frame = FrameLayout(themed).apply { setBackgroundColor(DIMMED_WALLPAPER) }
        val sheet = LayoutInflater.from(themed).inflate(R.layout.activity_task_preview, frame, false)
        frame.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val views = PreviewViews(sheet, AppGraph.messages(themed))
        views.bind(screen)
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, w, h)
        return Rendered(frame, sheet, views)
    }

    private companion object {
        val DIMMED_WALLPAPER: Int = Color.rgb(0x2E, 0x36, 0x40)

        fun configured(context: Context, fontScale: Float?, night: Boolean?): Context {
            if (fontScale == null && night == null) return context
            val config = Configuration(context.resources.configuration)
            fontScale?.let { config.fontScale = it }
            night?.let {
                val mode = if (it) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            }
            return context.createConfigurationContext(config)
        }
    }
}
