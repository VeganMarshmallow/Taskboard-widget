package io.github.veganmarshmallow.taskboard.setup

import android.app.Activity
import android.view.View
import android.widget.Button
import io.github.veganmarshmallow.taskboard.R

/**
 * The main screen's token steps (#46 round 2): folded behind "Setup steps" so the screen stays
 * clean, and shown in full (both the read and the merge permissions) when asked for.
 */
class TokenHelp(activity: Activity) {
    private val steps: View = activity.findViewById(R.id.token_help)
    private val toggle: Button = activity.findViewById(R.id.token_help_toggle)

    val isShown: Boolean get() = steps.visibility == View.VISIBLE

    fun bind() {
        toggle.setOnClickListener { show(!isShown) }
    }

    fun show(shown: Boolean) {
        steps.visibility = if (shown) View.VISIBLE else View.GONE
        toggle.setText(if (shown) R.string.token_help_hide else R.string.token_help_show)
    }
}
