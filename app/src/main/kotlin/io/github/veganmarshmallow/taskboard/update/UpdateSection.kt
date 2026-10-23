package io.github.veganmarshmallow.taskboard.update

import android.app.Activity
import android.content.ActivityNotFoundException
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.update.ReleasePage
import io.github.veganmarshmallow.taskboard.core.update.UpdateError
import io.github.veganmarshmallow.taskboard.core.update.UpdateMessages
import io.github.veganmarshmallow.taskboard.core.update.UpdatePresenter
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

    fun onOpen() {
        card.checkButton.setOnClickListener { UpdateController.check(activity) }
        card.updateButton.setOnClickListener { openReleasePage() }
        UpdateController.listen(::render)
        when (UpdateController.activity) {
            UpdatePresenter.Activity.Idle -> UpdateController.checkOnOpen(activity)
            is UpdatePresenter.Activity.Failed -> { UpdateController.reset(); UpdateController.checkOnOpen(activity) }
            UpdatePresenter.Activity.Checking -> Unit // keep it (e.g. after a rotation)
        }
        render()
    }

    fun onResume() = render()

    /** A new token may grant access that the last check lacked. */
    fun onTokenChanged() = UpdateController.check(activity)

    fun onDestroy() = UpdateController.listen(null)

    private fun render() {
        if (activity.isDestroyed) return
        card.bind(presenter.present(UpdateStore(activity).load(), InstalledVersion.of(activity), UpdateController.activity))
    }

    private fun openReleasePage() {
        val url = ReleasePage.toOpen(UpdateStore(activity).load(), InstalledVersion.of(activity).versionCode)
        try {
            activity.startActivity(ReleasePageIntent.of(url))
        } catch (e: ActivityNotFoundException) {
            UpdateController.fail(UpdateError.Unexpected("no browser to open the release page"))
        }
    }
}
