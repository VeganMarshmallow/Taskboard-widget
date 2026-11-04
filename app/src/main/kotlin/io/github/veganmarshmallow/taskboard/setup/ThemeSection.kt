package io.github.veganmarshmallow.taskboard.setup

import android.app.Activity
import android.widget.RadioGroup
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice
import io.github.veganmarshmallow.taskboard.data.ThemeStore
import io.github.veganmarshmallow.taskboard.widget.StateRedraw

/**
 * The theme switch in settings (#46): shows the saved choice; picking another saves it, redraws
 * every widget from its cached board (no network), and recreates this screen in the new look.
 */
class ThemeSection(private val activity: Activity, private val store: ThemeStore = ThemeStore(activity)) {
    private val group: RadioGroup = activity.findViewById(R.id.theme_choice)

    fun bind() {
        group.check(buttonOf(store.load()))
        group.setOnCheckedChangeListener { _, checkedId -> choiceOf(checkedId)?.let(::pick) }
    }

    private fun pick(choice: ThemeChoice) {
        if (choice == store.load()) return
        store.save(choice)
        StateRedraw.redraw(activity)
        activity.recreate()
    }

    companion object {
        fun buttonOf(choice: ThemeChoice): Int = when (choice) {
            ThemeChoice.CALM -> R.id.theme_calm
            ThemeChoice.INK -> R.id.theme_ink
        }

        fun choiceOf(buttonId: Int): ThemeChoice? = when (buttonId) {
            R.id.theme_calm -> ThemeChoice.CALM
            R.id.theme_ink -> ThemeChoice.INK
            else -> null
        }
    }
}
