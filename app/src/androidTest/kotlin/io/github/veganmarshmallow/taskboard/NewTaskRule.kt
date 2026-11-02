package io.github.veganmarshmallow.taskboard

import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import org.junit.rules.ExternalResource

/**
 * Gives each test a fresh New task [controller] built from [deps] (so a fresh session too), and
 * puts the app's own controller back afterwards, even when the test fails.
 */
class NewTaskRule(private val deps: () -> NewTaskDeps) : ExternalResource() {
    private lateinit var previous: NewTaskController
    lateinit var controller: NewTaskController
        private set

    override fun before() {
        previous = AppGraph.newTask
        controller = NewTaskController(deps())
        AppGraph.newTask = controller
    }

    override fun after() {
        AppGraph.newTask = previous
    }
}
