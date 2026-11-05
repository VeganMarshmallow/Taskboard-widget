package io.github.veganmarshmallow.taskboard.setup

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import io.github.veganmarshmallow.taskboard.R

/**
 * The main screen's token steps (#46 round 2): folded behind "Setup steps" so the screen stays
 * clean, and shown in full (both the read and the merge permissions) when asked for. Whether they
 * are open survives rotation ([save] / [bind]'s saved state), like the rest of the screen.
 */
class TokenHelp(activity: Activity) {
    private val steps: View = activity.findViewById(R.id.token_help)
    private val toggle: Button = activity.findViewById(R.id.token_help_toggle)

    val isShown: Boolean get() = steps.visibility == View.VISIBLE

    /** Wires the toggle; [saved] is the activity's saved state, so open steps stay open after a rotation. */
    fun bind(saved: Bundle? = null) {
        toggle.setOnClickListener { show(!isShown) }
        show(saved?.getBoolean(KEY_SHOWN) == true)
    }

    fun save(out: Bundle) = out.putBoolean(KEY_SHOWN, isShown)

    fun show(shown: Boolean) {
        steps.visibility = if (shown) View.VISIBLE else View.GONE
        toggle.setText(if (shown) R.string.token_help_hide else R.string.token_help_show)
    }

    private companion object {
        const val KEY_SHOWN = "token_help_shown"
    }
}
