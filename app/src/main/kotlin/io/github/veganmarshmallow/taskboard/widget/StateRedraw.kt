package io.github.veganmarshmallow.taskboard.widget

import android.content.Context
import io.github.veganmarshmallow.taskboard.data.StateStore

/** Redraws every widget from the cached state without fetching (e.g. when the update dot changes). */
object StateRedraw {
    fun redraw(context: Context) = WidgetRenderer(context).render(StateStore(context).load())
}
