package io.github.veganmarshmallow.taskboard.data

import android.content.Context
import io.github.veganmarshmallow.taskboard.core.snapshot.WidgetStateStore

/**
 * What the widget shows: the saved state, or a failed save's error over it (task #37). Every
 * instance reads and writes the process's one [AppGraph.shownStates], so the list factory, the
 * renderer and later refreshes all see the same state.
 */
class StateStore(context: Context) : WidgetStateStore by AppGraph.shownStates(context)
