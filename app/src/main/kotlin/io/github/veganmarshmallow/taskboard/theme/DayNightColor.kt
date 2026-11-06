package io.github.veganmarshmallow.taskboard.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews

/**
 * Sets a colour resource on a widget view from code. On Android 12+ both the light and the dark
 * value travel with the update and the launcher picks one when it shows the widget, so the colour
 * follows a light/dark switch at once. Older versions get the value for [context]'s current mode,
 * which the next redraw corrects.
 */
class DayNightColor(private val context: Context) {
    private val light by lazy { inMode(Configuration.UI_MODE_NIGHT_NO) }
    private val dark by lazy { inMode(Configuration.UI_MODE_NIGHT_YES) }

    fun setTextColor(views: RemoteViews, viewId: Int, colorRes: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setColorInt(viewId, "setTextColor", light.getColor(colorRes), dark.getColor(colorRes))
        } else {
            views.setTextColor(viewId, context.getColor(colorRes))
        }
    }

    private fun inMode(mode: Int): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        return context.createConfigurationContext(config)
    }
}
