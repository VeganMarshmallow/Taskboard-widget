package io.github.veganmarshmallow.taskboard.widget

import android.content.Intent
import android.widget.RemoteViewsService

/** Supplies the scrolling list's rows. */
class BoardListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = BoardListFactory(applicationContext)
}
