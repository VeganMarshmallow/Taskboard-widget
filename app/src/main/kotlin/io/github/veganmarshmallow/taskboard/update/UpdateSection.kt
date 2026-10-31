package io.github.veganmarshmallow.taskboard.update

import android.app.Activity
import android.content.ActivityNotFoundException
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.update.ReleasePage
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.widget.TimeText

/**
 * The "App updates" card on the main screen (`section_update.xml`). Binds [UpdatePresenter.Model]
 * to views and handles the two buttons; all decisions come from core. Update opens the release page
 * in the browser: the app never downloads or installs APKs. Call [onOpen] from onCreate, [onResume]
 * and [onDestroy] from the matching lifecycle methods.
 */
class UpdateSection(private val activity: Activity) {
    private val card = UpdateCard(activity.findViewById(R.id.update_section))
    private val presenter = UpdatePresenter(UpdateMessages(TimeText(activity)::format))
    private val controller = AppGraph.updates(activity)
    private val onChange: () -> Unit = ::render

    fun onOpen() {
        card.checkButton.setOnClickListener { controller.check() }
        card.updateButton.setOnClickListener { openReleasePage() }
        controller.addListener(onChange)
        controller.opened()
        render()
    }

    fun onResume() = render()

    /** A new token may grant access that the last check lacked. */
    fun onTokenChanged() = controller.check()

    fun onDestroy() = controller.removeListener(onChange)

    private fun render() {
        if (activity.isDestroyed) return
        card.bind(presenter.present(UpdateStore(activity).load(), InstalledVersion.of(activity), controller.activity))
    }

    private fun openReleasePage() {
        val url = ReleasePage.toOpen(UpdateStore(activity).load(), InstalledVersion.of(activity).versionCode)
        try {
            activity.startActivity(ReleasePageIntent.of(url))
        } catch (e: ActivityNotFoundException) {
            controller.fail(UpdateError.Unexpected("no browser to open the release page"))
        }
    }
}
