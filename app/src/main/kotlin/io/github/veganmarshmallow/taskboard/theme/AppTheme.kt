package io.github.veganmarshmallow.taskboard.theme

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice
import io.github.veganmarshmallow.taskboard.data.ThemeStore

/**
 * The app's screens and the [ThemeChoice] (#46). Every screen's base theme (window, dialog or sheet)
 * stays in the manifest; the choice adds a token overlay on top (colours, shapes, type, component
 * styles as `?attr/tb…`), so both looks share every layout.
 */
object AppTheme {
    fun overlay(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.CALM -> R.style.ThemeOverlay_Taskboard_Calm
        ThemeChoice.INK -> R.style.ThemeOverlay_Taskboard_Ink
    }

    fun current(context: Context): ThemeChoice = ThemeStore(context).load()

    /** Call before `super.onCreate`, so the window and every inflated view see the tokens. */
    fun apply(activity: Activity, choice: ThemeChoice = current(activity)) {
        activity.theme.applyStyle(overlay(choice), true)
    }

    /** [base] (e.g. a dialog theme) with the chosen tokens on top, for views built outside an activity. */
    fun wrap(context: Context, base: Int, choice: ThemeChoice = current(context)): Context =
        ContextThemeWrapper(context, base).also { it.theme.applyStyle(overlay(choice), true) }

    /** A token colour (`R.attr.tb…`) in [context]'s theme. */
    fun color(context: Context, attr: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attr, value, true)) { "theme has no ${context.resources.getResourceEntryName(attr)}" }
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
