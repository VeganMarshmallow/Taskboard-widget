package io.github.veganmarshmallow.taskboard.preview

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.tags.TagPill
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/** Fills a [FlowLayout] with one rounded, neutral pill per [TagPill]; hides it when there are none. */
object TagPillViews {
    fun bind(container: FlowLayout, pills: List<TagPill>) {
        container.removeAllViews()
        container.visibility = if (pills.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(container.context)
        val neutral = AppTheme.color(container.context, R.attr.tbCard)
        val text = AppTheme.color(container.context, R.attr.tbText)
        pills.forEach { pill ->
            val view = inflater.inflate(R.layout.preview_tag_pill, container, false) as TextView
            view.text = pill.text
            // #46: neutral pills in the theme's colours, so GitHub's label colours don't fight the status chip.
            view.backgroundTintList = ColorStateList.valueOf(neutral)
            view.setTextColor(text)
            container.addView(view)
        }
    }
}
