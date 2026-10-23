package io.github.veganmarshmallow.taskboard.preview

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.tags.TagPill

/** Fills a [FlowLayout] with one rounded pill per [TagPill]; hides it when there are none. */
object TagPillViews {
    fun bind(container: FlowLayout, pills: List<TagPill>) {
        container.removeAllViews()
        container.visibility = if (pills.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(container.context)
        pills.forEach { pill ->
            val view = inflater.inflate(R.layout.preview_tag_pill, container, false) as TextView
            view.text = pill.text
            view.backgroundTintList = ColorStateList.valueOf(pill.background.argb)
            view.setTextColor(pill.textTone.rgb.argb)
            container.addView(view)
        }
    }
}
