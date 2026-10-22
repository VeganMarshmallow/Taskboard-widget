package io.github.veganmarshmallow.taskboard.newtask

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.core.task.RepoSuggestions

/** Drop-down rows for the repo field, filtered by substring ([RepoSuggestions.matching]) rather than prefix. */
class RepoSuggestionAdapter(private val context: Context) : BaseAdapter(), Filterable {
    private var all: List<String> = emptyList()
    private var shown: List<String> = emptyList()

    fun setAll(repos: List<String>) {
        all = repos
        shown = repos
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView as? TextView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_dropdown_item_1line, parent, false) as TextView
        view.text = shown[position]
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val matches = RepoSuggestions.matching(all, constraint?.toString().orEmpty())
            return FilterResults().apply { values = matches; count = matches.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            shown = (results?.values as? List<String>).orEmpty()
            if (shown.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }
    }
}
