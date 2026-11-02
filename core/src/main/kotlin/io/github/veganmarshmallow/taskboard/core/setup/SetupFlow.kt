package io.github.veganmarshmallow.taskboard.core.setup

import io.github.veganmarshmallow.taskboard.core.token.TokenValidator

/**
 * The setup screen's token rules, free of Android types: which token Test and Save use, what
 * error or warning to show, and the widget-configure protocol (accept the widget on open, and
 * finish with OK after saving).
 *
 * [isConfiguring] is true when the screen was opened as a widget's configure screen.
 */
class SetupFlow(private val isConfiguring: Boolean) {

    /** What the Test button does. */
    sealed interface TestDecision

    /** What the Save button does. */
    sealed interface SaveDecision

    /** Show [reason] as an error and do nothing else. */
    data class ShowError(val reason: String) : TestDecision, SaveDecision

    /** Show [warning] (when set) as a non-error status, then test [token]. */
    data class Test(val token: String, val warning: String? = null) : TestDecision

    /**
     * Show [warning] (when set), save [token], and when [finishWithOk] is true return
     * RESULT_OK to the widget host and close the screen.
     */
    data class Save(val token: String, val warning: String?, val finishWithOk: Boolean) : SaveDecision

    /**
     * True when opening the screen must accept the widget right away (RESULT_OK), so a widget
     * placed without a token shows "tap to set up" instead of vanishing.
     */
    val acceptsWidgetOnOpen: Boolean get() = isConfiguring

    fun onTest(typed: String, saved: String?): TestDecision = when (val r = resolve(typed, saved)) {
        is TokenValidator.Result.Invalid -> ShowError(r.reason)
        is TokenValidator.Result.Valid -> Test(r.token, r.warning)
    }

    fun onSave(typed: String, saved: String?): SaveDecision = when (val r = resolve(typed, saved)) {
        is TokenValidator.Result.Invalid -> ShowError(r.reason)
        is TokenValidator.Result.Valid -> Save(r.token, r.warning, finishWithOk = isConfiguring)
    }

    /** The typed token, or the saved one when the field is blank (so Test works after saving). */
    private fun resolve(typed: String, saved: String?): TokenValidator.Result {
        val fallback = saved?.takeIf { it.isNotBlank() }
        if (typed.isBlank() && fallback != null) return TokenValidator.Result.Valid(fallback)
        return TokenValidator.validate(typed)
    }
}
