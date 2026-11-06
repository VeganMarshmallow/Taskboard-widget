package io.github.veganmarshmallow.taskboard.data

import io.github.veganmarshmallow.taskboard.core.http.HttpClient
import io.github.veganmarshmallow.taskboard.core.work.RetryBackoff
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController

/** Release builds: no test overrides. Constant nulls, so [AppGraph] always uses the app's own pieces. */
internal object TestOverrides {
    val httpClient: (() -> HttpClient)? = null
    val retryBackoff: RetryBackoff? = null
    val newTask: NewTaskController? = null
    val online: (() -> Boolean)? = null
}
