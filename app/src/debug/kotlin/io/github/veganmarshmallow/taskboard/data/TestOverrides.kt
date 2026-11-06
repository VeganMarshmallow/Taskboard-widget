package io.github.veganmarshmallow.taskboard.data

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController

/**
 * Debug builds only (the variant instrumented tests run against): lets androidTest swap pieces of
 * [AppGraph]. Null means "use the app's own". Release builds get a copy with constant nulls
 * (src/release), so production code has nothing to set.
 */
internal object TestOverrides {
    /** A slow local server instead of GitHub. */
    @Volatile var httpClient: (() -> HttpClient)? = null

    /** Seconds instead of minutes. [AppGraph.retryPlanner] rebuilds its planner when this changes. */
    @Volatile var retryBackoff: RetryBackoff? = null

    /** A New task controller with fake dependencies (`NewTaskRule`). */
    @Volatile var newTask: NewTaskController? = null

    /** Whether the device is online, for Merge all's jobs (#78); null: ask Android. */
    @Volatile var online: (() -> Boolean)? = null
}
