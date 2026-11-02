package io.github.veganmarshmallow.taskboard.setup

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.veganmarshmallow.taskboard.R
import io.github.veganmarshmallow.taskboard.core.http.Outcome
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow
import io.github.veganmarshmallow.taskboard.data.AppGraph
import io.github.veganmarshmallow.taskboard.data.TokenStore
import io.github.veganmarshmallow.taskboard.update.UpdateSection
import io.github.veganmarshmallow.taskboard.widget.WidgetIntents

/**
 * Launcher and widget-configuration screen: paste a token, test it, save it.
 * The saved token is never displayed again; the field only shows whether one is saved.
 * The rules live in [SetupFlow]; this class renders its decisions and runs the side effects.
 */
class SetupActivity : Activity() {
    private lateinit var tokenStore: TokenStore
    private lateinit var tokenField: EditText
    private lateinit var status: TextView
    private lateinit var testButton: Button
    private lateinit var saveButton: Button
    private val tester = ConnectionTester()
    private lateinit var updates: UpdateSection
    private val widgetId by lazy {
        intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }
    private val flow by lazy { SetupFlow(isConfiguring = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tokenStore = TokenStore(this)
        tokenField = findViewById(R.id.token)
        status = findViewById(R.id.status)
        testButton = findViewById(R.id.test)
        saveButton = findViewById(R.id.save)
        findViewById<Button>(R.id.create_token).setOnClickListener {
            try {
                startActivity(WidgetIntents.viewUrl(getString(R.string.token_url)))
            } catch (e: android.content.ActivityNotFoundException) {
                showStatus(getString(R.string.no_browser), isError = true)
            }
        }
        testButton.setOnClickListener { onTest() }
        saveButton.setOnClickListener { onSave() }
        findViewById<Button>(R.id.clear).setOnClickListener { onClear() }

        // As the widget's configure screen (shown on placement before Android 12), always accept the
        // widget: without a token it shows "No GitHub token set · tap to set up" instead of vanishing.
        if (flow.acceptsWidgetOnOpen) {
            setResult(RESULT_OK, widgetResult())
            sendBroadcast(WidgetIntents.refreshIntent(this))
        }
        showSavedState()
        updates = UpdateSection(this).also { it.onOpen() }
        if (intent?.action == ACTION_SHOW_UPDATES) scrollToUpdates()
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onDestroy() {
        updates.onDestroy()
        tester.shutdown()
        super.onDestroy()
    }

    private fun onTest() {
        when (val d = flow.onTest(typedToken(), tokenStore.get())) {
            is SetupFlow.ShowError -> showStatus(d.reason, isError = true)
            is SetupFlow.Test -> { showWarning(d.warning); runTest(d.token) }
        }
    }

    private fun runTest(token: String) {
        setBusy(true)
        showStatus(getString(R.string.testing), isError = false)
        tester.test(this, token) { result ->
            if (isDestroyed) return@test
            setBusy(false)
            when (result) {
                is Outcome.Success -> showStatus(getString(R.string.test_ok), isError = false)
                is Outcome.Failure -> showStatus(AppGraph.messages(this).message(result.error), isError = true)
            }
        }
    }

    private fun onSave() {
        when (val d = flow.onSave(typedToken(), tokenStore.get())) {
            is SetupFlow.ShowError -> showStatus(d.reason, isError = true)
            is SetupFlow.Save -> { showWarning(d.warning); save(d.token, d.finishWithOk) }
        }
    }

    private fun save(token: String, finishWithOk: Boolean) {
        tokenStore.save(token)
        tokenField.text.clear()
        showSavedState()
        sendBroadcast(WidgetIntents.refreshIntent(this))
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        updates.onTokenChanged()
        if (finishWithOk) {
            setResult(RESULT_OK, widgetResult())
            finish()
        }
    }

    private fun onClear() {
        tokenStore.clear()
        tokenField.text.clear()
        showSavedState()
        sendBroadcast(WidgetIntents.refreshIntent(this))
    }

    private fun typedToken() = tokenField.text.toString()

    private fun showWarning(warning: String?) {
        warning?.let { showStatus(it, isError = false) }
    }

    private fun showSavedState() {
        tokenField.hint = getString(if (tokenStore.hasToken()) R.string.token_hint_saved else R.string.token_hint)
        findViewById<Button>(R.id.clear).visibility = if (tokenStore.hasToken()) View.VISIBLE else View.GONE
    }

    private fun showStatus(text: String, isError: Boolean) {
        status.text = text
        status.setTextColor(getColor(if (isError) R.color.error else R.color.ok))
        status.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        testButton.isEnabled = !busy
        saveButton.isEnabled = !busy
    }

    private fun widgetResult() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun scrollToUpdates() {
        val scroll = findViewById<ScrollView>(R.id.setup_scroll)
        scroll.post { scroll.scrollTo(0, findViewById<View>(R.id.update_section).bottom) }
    }

    companion object {
        /** Launch action used by the widget's update dot. */
        const val ACTION_SHOW_UPDATES = "io.github.veganmarshmallow.taskboard.SHOW_UPDATES"
    }
}
