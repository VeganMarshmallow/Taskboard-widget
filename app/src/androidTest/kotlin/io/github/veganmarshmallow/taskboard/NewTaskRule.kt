package io.github.veganmarshmallow.taskboard

import androidx.test.platform.app.InstrumentationRegistry
import io.github.veganmarshmallow.taskboard.data.NewTaskPrefs
import io.github.veganmarshmallow.taskboard.data.TestOverrides
import io.github.veganmarshmallow.taskboard.newtask.NewTaskController
import io.github.veganmarshmallow.taskboard.newtask.NewTaskDeps
import org.junit.rules.ExternalResource

/**
 * Gives each test a fresh New task [controller] built from [deps] (so a fresh session too), and
 * puts the previous override back afterwards (normally none, i.e. the app's own controller), even
 * when the test fails. The saved draft and submit marker ([NewTaskPrefs]) are cleared before and
 * after, so no test inherits another's.
 */
class NewTaskRule(private val deps: () -> NewTaskDeps) : ExternalResource() {
    private var previous: NewTaskController? = null
    lateinit var controller: NewTaskController
        private set

    override fun before() {
        clearSaved()
        previous = TestOverrides.newTask
        controller = NewTaskController(deps())
        TestOverrides.newTask = controller
    }

    /** Stands in for a new process: a fresh controller (built from [next]), keeping what is on disk. */
    fun restart(next: NewTaskDeps = deps()) {
        controller = NewTaskController(next)
        TestOverrides.newTask = controller
    }

    override fun after() {
        TestOverrides.newTask = previous
        clearSaved()
    }

    private fun clearSaved() {
        NewTaskPrefs(InstrumentationRegistry.getInstrumentation().targetContext).apply {
            draft = null
            marker = null
        }
    }
}
