package io.github.veganmarshmallow.taskboard.filter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R

/**
 * Fills a chip group (#59): one toggle chip per value, selected when [isPicked], and [onToggle] on a tap.
 * The group's [section] (its heading and chips) is hidden when there is nothing to offer.
 */
class FilterChips<T>(
    private val section: View,
    private val group: ViewGroup,
    private val label: (T) -> String,
    private val onToggle: (T) -> Unit,
) {
    private var shown: List<T>? = null

    /** Rebuilds the chips only when [values] change; otherwise updates them in place, so a tap keeps TalkBack focus. */
    fun bind(values: List<T>, isPicked: (T) -> Boolean) {
        section.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        if (values != shown) {
            group.removeAllViews()
            val inflater = LayoutInflater.from(group.context)
            values.forEach { value ->
                val chip = inflater.inflate(R.layout.filter_chip, group, false) as TextView
                chip.text = label(value)
                chip.setOnClickListener { onToggle(value) }
                group.addView(chip)
            }
            shown = values
        }
        values.forEachIndexed { i, value ->
            val chip = group.getChildAt(i) as TextView
            chip.isSelected = isPicked(value)
            chip.contentDescription = group.context.getString(
                if (chip.isSelected) R.string.filter_chip_on else R.string.filter_chip_off,
                chip.text,
            )
        }
    }
}
