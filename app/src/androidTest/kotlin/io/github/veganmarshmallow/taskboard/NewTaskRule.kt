package io.github.veganmarshmallow.taskboard

import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.NewTaskPrefs
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import org.junit.rules.ExternalResource

/**
 * Gives each test a fresh New task [controller] built from [deps] (so a fresh session too), and
 * puts the app's own controller back afterwards, even when the test fails. The saved draft and
 * submit marker ([NewTaskPrefs]) are cleared before and after, so no test inherits another's.
 */
class NewTaskRule(private val deps: () -> NewTaskDeps) : ExternalResource() {
    private lateinit var previous: NewTaskController
    lateinit var controller: NewTaskController
        private set

    override fun before() {
        clearSaved()
        previous = AppGraph.newTask
        controller = NewTaskController(deps())
        AppGraph.newTask = controller
    }

    /** Stands in for a new process: a fresh controller (built from [next]), keeping what is on disk. */
    fun restart(next: NewTaskDeps = deps()) {
        controller = NewTaskController(next)
        AppGraph.newTask = controller
    }

    override fun after() {
        AppGraph.newTask = previous
        clearSaved()
    }

    private fun clearSaved() {
        NewTaskPrefs(InstrumentationRegistry.getInstrumentation().targetContext).apply {
            draft = null
            marker = null
        }
    }
}
