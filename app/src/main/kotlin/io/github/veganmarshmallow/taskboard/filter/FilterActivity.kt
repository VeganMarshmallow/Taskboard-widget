package io.github.veganmarshmallow.taskboard.filter

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.board.Board
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilter
import io.github.veganmarshmallow.taskboard.core.filter.BoardFilterCodec
import io.github.veganmarshmallow.taskboard.core.filter.FilterOptions
import io.github.veganmarshmallow.taskboard.core.filter.filtered
import io.github.veganmarshmallow.taskboard.core.filter.openCount
import io.github.veganmarshmallow.taskboard.core.model.Priority
import io.github.veganmarshmallow.taskboard.data.FilterStore
import io.github.veganmarshmallow.taskboard.data.StateStore
import io.github.veganmarshmallow.taskboard.theme.AppTheme

/**
 * The filter sheet (#59), over the home screen like the task preview, opened from the widget's
 * filter button or its "Filtered" line. Search text plus chips for the repos, types and
 * priorities on the board; every change applies at once (the widget behind updates, and the sheet
 * says how many tasks are shown), Clear resets it, Done or a tap outside closes it.
 */
class FilterActivity : Activity() {
    private lateinit var text: FilterText
    private lateinit var live: LiveFilter
    private lateinit var repos: FilterChips<String>
    private lateinit var types: FilterChips<String>
    private lateinit var priorities: FilterChips<Priority>
    private lateinit var summary: TextView
    private lateinit var clear: TextView
    private lateinit var search: EditText
    private lateinit var noOptions: TextView
    private var board: Board? = null
    private var filter = BoardFilter.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        text = FilterText(this)
        live = LiveFilter(this)
        board = StateStore(this).load().board
        filter = savedInstanceState?.getString(STATE)?.let(BoardFilterCodec::decode) ?: FilterStore(this).load()
        bindViews()
        render()
    }

    private fun bindViews() {
        summary = findViewById(R.id.filter_summary)
        clear = findViewById(R.id.filter_clear)
        noOptions = findViewById(R.id.filter_no_options)
        search = findViewById(R.id.filter_search)
        repos = FilterChips(findViewById<View>(R.id.filter_repos_section), findViewById<ViewGroup>(R.id.filter_repos), text::repo) { change(filter.toggleRepo(it)) }
        types = FilterChips(findViewById<View>(R.id.filter_types_section), findViewById<ViewGroup>(R.id.filter_types), text::type) { change(filter.toggleType(it)) }
        priorities = FilterChips(findViewById<View>(R.id.filter_priorities_section), findViewById<ViewGroup>(R.id.filter_priorities), text::priority) {
            change(filter.togglePriority(it))
        }
        search.setText(filter.text)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = change(filter.withText(s?.toString().orEmpty()))
        })
        clear.setOnClickListener {
            change(BoardFilter.NONE)
            search.setText("")
        }
        findViewById<View>(R.id.filter_done).setOnClickListener { finish() }
    }

    private fun change(newFilter: BoardFilter) {
        if (newFilter == filter) return
        filter = newFilter
        live.update(newFilter)
        render()
    }

    private fun render() {
        val options = FilterOptions.of(board, filter)
        repos.bind(options.repos) { filter.hasRepo(it) }
        types.bind(options.types) { filter.hasType(it) }
        priorities.bind(options.priorities) { it in filter.priorities }
        noOptions.visibility = if (options.isEmpty) View.VISIBLE else View.GONE
        val shown = board
        summary.text = if (shown == null) getString(R.string.filter_no_board) else text.showing(shown.filtered(filter).openCount, shown.openCount)
        clear.visibility = if (filter.isActive) View.VISIBLE else View.INVISIBLE
    }

    override fun onPause() {
        super.onPause()
        live.flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE, BoardFilterCodec.encode(filter))
    }

    private companion object {
        const val STATE = "filter"
    }
}
