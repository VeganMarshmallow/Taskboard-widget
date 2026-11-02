package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.Save
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.ShowError
import io.github.veganmarshmallow.taskboard.core.setup.SetupFlow.Test as TestToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowTest {
    private val launcher = SetupFlow(isConfiguring = false)
    private val configuring = SetupFlow(isConfiguring = true)
    private val pat = "github_pat_11ABC_xyz"
    private val savedPat = "github_pat_SAVED"

    // --- Test button ---

    @Test fun testWithEmptyFieldUsesSavedToken() {
        assertEquals(TestToken(savedPat), launcher.onTest("", savedPat))
    }

    @Test fun testWithBlankFieldUsesSavedToken() {
        assertEquals(TestToken(savedPat), launcher.onTest("  \n\t", savedPat))
    }

    @Test fun savedClassicTokenIsUsedWithoutWarning() {
        assertEquals(TestToken("ghp_old"), launcher.onTest("", "ghp_old"))
    }

    @Test fun testWithEmptyFieldAndNoSavedTokenIsAnError() {
        assertTrue(launcher.onTest("", null) is ShowError)
    }

    @Test fun blankSavedTokenCountsAsNoToken() {
        assertTrue(launcher.onTest("", "   ") is ShowError)
        assertTrue(launcher.onSave("", "") is ShowError)
    }

    @Test fun typedTokenWinsOverSavedAndIsTrimmed() {
        assertEquals(TestToken(pat), launcher.onTest("  $pat\n", savedPat))
    }

    @Test fun typedInvalidTokenIsAnErrorEvenWithSavedToken() {
        val d = launcher.onTest("github_pat_ab cd", savedPat)
        assertTrue(d is ShowError)
        assertTrue((d as ShowError).reason.isNotBlank())
    }

    @Test fun classicTypedTokenIsTestedWithWarning() {
        val d = launcher.onTest("ghp_abc", null) as TestToken
        assertEquals("ghp_abc", d.token)
        assertNotNull(d.warning)
    }

    @Test fun errorReasonMatchesValidator() {
        assertEquals(ShowError("Paste a token first"), launcher.onTest("", null))
        assertEquals(ShowError("That's too long to be a GitHub token"), launcher.onTest("x".repeat(513), null))
    }

    // --- Save button ---

    @Test fun saveWhileConfiguringFinishesWithOk() {
        assertEquals(Save(pat, warning = null, finishWithOk = true), configuring.onSave(pat, null))
    }

    @Test fun saveFromLauncherStaysOpen() {
        assertEquals(Save(pat, warning = null, finishWithOk = false), launcher.onSave(pat, null))
    }

    @Test fun saveWithEmptyFieldResavesSavedToken() {
        assertEquals(Save(savedPat, warning = null, finishWithOk = true), configuring.onSave("", savedPat))
    }

    @Test fun saveClassicTokenKeepsWarning() {
        val d = configuring.onSave("ghp_abc", null) as Save
        assertEquals("ghp_abc", d.token)
        assertNotNull(d.warning)
        assertTrue(d.finishWithOk)
    }

    @Test fun invalidSaveIsAnErrorAndNeverFinishes() {
        listOf("", "   ", "github_pat_a\nb", "github_pat_é", "x".repeat(513)).forEach {
            assertTrue("input: $it", configuring.onSave(it, null) is ShowError)
        }
    }

    @Test fun fineGrainedTokenHasNoWarning() {
        assertNull((launcher.onSave(pat, null) as Save).warning)
    }

    // --- Widget configure protocol ---

    @Test fun configuringAcceptsWidgetOnOpenEvenWithoutToken() {
        assertTrue(configuring.acceptsWidgetOnOpen)
    }

    @Test fun launcherDoesNotSetAResultOnOpen() {
        assertFalse(launcher.acceptsWidgetOnOpen)
    }
}
