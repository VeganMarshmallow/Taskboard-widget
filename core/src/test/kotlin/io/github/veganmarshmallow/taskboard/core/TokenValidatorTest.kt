package io.github.veganmarshmallow.taskboard.core

import io.github.veganmarshmallow.taskboard.core.token.TokenValidator
import io.github.veganmarshmallow.taskboard.core.token.TokenValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenValidatorTest {
    @Test fun acceptsAndTrimsFineGrainedToken() {
        val r = TokenValidator.validate("  github_pat_11ABCDEFG_xyz\n") as Result.Valid
        assertEquals("github_pat_11ABCDEFG_xyz", r.token)
        assertNull(r.warning)
    }

    @Test fun warnsOnClassicToken() {
        val r = TokenValidator.validate("ghp_abcdef") as Result.Valid
        assertNotNull(r.warning)
    }

    @Test fun rejectsBadInput() {
        listOf("", "   ", "\n", "github_pat_ab cd", "github_pat_a\nb", "github_pat_\u0000x", "github_pat_é", "x".repeat(513))
            .forEach { assertTrue("input: $it", TokenValidator.validate(it) is Result.Invalid) }
    }
}
