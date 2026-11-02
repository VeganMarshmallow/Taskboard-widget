package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.Save
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.ShowError
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.Test as TestToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundaries and odd inputs for [SetupFlow], pinned to the behaviour SetupActivity had on main. */
class SetupFlowEdgeCasesTest {
    private val launcher = SetupFlow(isConfiguring = false)
    private val configuring = SetupFlow(isConfiguring = true)
    private val prefix = "github_pat_"

    @Test fun lengthBoundaryIs512AfterTrimming() {
        val max = prefix + "a".repeat(512 - prefix.length)
        assertEquals(TestToken(max), launcher.onTest("  $max \n", null))
        assertTrue(launcher.onTest(max + "a", null) is ShowError)
    }

    @Test fun hugeInputIsRejectedNotCrashing() {
        val huge = prefix + "a".repeat(1_000_000)
        assertEquals(ShowError("That's too long to be a GitHub token"), configuring.onSave(huge, "github_pat_SAVED"))
    }

    @Test fun invalidCharactersAreErrorsWithTheValidatorsReason() {
        val spaces = ShowError("Token contains spaces or line breaks — paste just the token")
        val unexpected = ShowError("Token contains unexpected characters")
        assertEquals(spaces, launcher.onTest("${prefix}a\tb", null))
        assertEquals(spaces, launcher.onTest("${prefix}\u0000x", null))
        assertEquals(spaces, launcher.onTest("${prefix}\u007Fx", null)) // DEL is an ISO control char
        assertEquals(unexpected, launcher.onTest("${prefix}é", null))
        assertEquals(unexpected, launcher.onTest("$prefix😀", null)) // emoji (surrogate pair)
    }

    @Test fun whitespaceOnlyFieldWithoutSavedTokenAsksToPaste() {
        listOf("", " ", "\n", "\t \r\n").forEach {
            assertEquals("input: [$it]", ShowError("Paste a token first"), launcher.onTest(it, null))
            assertEquals("input: [$it]", ShowError("Paste a token first"), configuring.onSave(it, null))
        }
    }

    @Test fun savedTokenIsUsedVerbatimWithoutRevalidation() {
        // main used the stored token as-is; it was validated when it was saved.
        assertEquals(TestToken(" ghp_x "), launcher.onTest("", " ghp_x "))
        assertEquals(Save("ghp_x", warning = null, finishWithOk = false), launcher.onSave("", "ghp_x"))
    }

    @Test fun invalidTypedTokenNeverFallsBackToSaved() {
        val d = configuring.onSave("${prefix}a b", "github_pat_SAVED")
        assertTrue(d is ShowError)
    }

    @Test fun saveFromLauncherWithSavedTokenStaysOpen() {
        assertEquals(Save("github_pat_S", warning = null, finishWithOk = false), launcher.onSave("  ", "github_pat_S"))
    }

    @Test fun testDecisionDoesNotDependOnConfiguring() {
        listOf("", "  ", "ghp_x", "${prefix}ok", "${prefix}a b").forEach { typed ->
            listOf(null, "github_pat_S").forEach { saved ->
                assertEquals("[$typed]/$saved", launcher.onTest(typed, saved), configuring.onTest(typed, saved))
            }
        }
    }

    @Test fun saveDiffersByConfiguringOnlyInFinishWithOk() {
        val a = launcher.onSave("ghp_x", null) as Save
        val b = configuring.onSave("ghp_x", null) as Save
        assertEquals(a.copy(finishWithOk = true), b)
    }

    @Test fun decisionsAreStableAcrossRepeatedCalls() {
        assertEquals(configuring.onSave("${prefix}x", null), configuring.onSave("${prefix}x", null))
        assertTrue(configuring.acceptsWidgetOnOpen)
    }
}
