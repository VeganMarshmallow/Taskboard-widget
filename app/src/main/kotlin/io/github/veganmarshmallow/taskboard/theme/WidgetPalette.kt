package io.github.veganmarshmallow.taskboard.theme

import android.content.Context
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.theme.ThemeChoice

/**
 * The widget's look for a [ThemeChoice] (#46), as resource ids: launchers can't resolve theme
 * attributes, so the widget's layouts carry the default (calm) look and code swaps in another
 * theme's drawables and colours. Drawables go through `setBackgroundResource`, which the launcher
 * resolves itself, so they follow a light/dark switch at once; text colours go through [DayNightColor].
 */
data class WidgetPalette(
    val choice: ThemeChoice,
    val background: Int,
    val serifTitle: Boolean,
    val tabSelectedBackground: Int,
    val tabSelectedText: Int,
    val tabText: Int,
    val approveBackground: Int,
    val approveText: Int,
    val mergeBackground: Int,
    val mergeText: Int,
    val mergeMutedBackground: Int,
    val mergeMutedText: Int,
    val mergeAllBackground: Int,
) {
    companion object {
        val CALM = WidgetPalette(
            choice = ThemeChoice.CALM,
            background = R.drawable.widget_background,
            serifTitle = false,
            tabSelectedBackground = R.drawable.tab_selected_background,
            tabSelectedText = R.color.widget_accent_text,
            tabText = R.color.widget_header_text,
            approveBackground = R.drawable.approve_background,
            approveText = R.color.widget_accent_text,
            mergeBackground = R.drawable.merge_background,
            mergeText = R.color.widget_on_accent,
            mergeMutedBackground = R.drawable.merge_background_muted,
            mergeMutedText = R.color.merge_muted_text,
            mergeAllBackground = R.drawable.merge_all_bar_background,
        )

        val INK = WidgetPalette(
            choice = ThemeChoice.INK,
            background = R.drawable.widget_background_ink,
            serifTitle = true,
            tabSelectedBackground = R.drawable.tab_selected_background_ink,
            tabSelectedText = R.color.ink_text,
            tabText = R.color.ink_muted,
            approveBackground = R.drawable.approve_background_ink,
            approveText = R.color.ink_text,
            mergeBackground = R.drawable.merge_background_ink,
            mergeText = R.color.ink_on_primary,
            mergeMutedBackground = R.drawable.merge_background_muted_ink,
            mergeMutedText = R.color.ink_muted,
            mergeAllBackground = R.drawable.merge_all_bar_background_ink,
        )

        fun of(choice: ThemeChoice): WidgetPalette = when (choice) {
            ThemeChoice.CALM -> CALM
            ThemeChoice.INK -> INK
        }

        fun current(context: Context): WidgetPalette = of(AppTheme.current(context))
    }
}
