package de.terletzkiy.ansibility.semantics.diagnostics

import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P001B_FALLBACK_ONLY_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P001_INEFFECTIVE_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P002_REDUNDANT_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P003_REQUIRED_VAR_UNREACHABLE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V101_MALFORMED_ENVELOPE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V102_FOLDED_VAULT_VALUE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V103_TRAILING_WHITESPACE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V104_NO_ID_DECRYPTS
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V105_LABEL_SECRET_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V106_UNKNOWN_VAULT_LABEL
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V107_NOT_WHOLE_FILE_VAULT
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V108_PLAINTEXT_PRIVATE_KEY
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V114_UNTAGGED_VAULT_VALUE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The diagnostics contract (plan A.6) with the R7/R8 additions of the amendment (CT0). */
class DiagnosticCodeTest {

    @Test
    fun `ids are unique, well formed and found by id`() {
        val ids = DiagnosticCode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids")
        val pattern = Regex("""ANS-[A-Z]\d{3}b?""")
        for (code in DiagnosticCode.entries) {
            assertTrue(pattern.matches(code.id), code.id)
            assertEquals(code, DiagnosticCode.byId(code.id))
        }
        assertNull(DiagnosticCode.byId("ANS-V109"), "V109–V112 are allocated when their v1.x items are built")
        assertNull(DiagnosticCode.byId("ANS-V112"))
    }

    @Test
    fun `vault codes have the amendment's levels and origins`() {
        // code to (documented, runtime-faithful, requested)
        val expected = mapOf(
            V101_MALFORMED_ENVELOPE to Triple(Level.ERROR, Level.ERROR, true),
            V102_FOLDED_VAULT_VALUE to Triple(Level.ERROR, Level.ERROR, true),
            V103_TRAILING_WHITESPACE to Triple(Level.ERROR, Level.ERROR, true),
            V104_NO_ID_DECRYPTS to Triple(Level.WARNING, Level.WARNING, true),
            V105_LABEL_SECRET_MISMATCH to Triple(Level.WEAK_WARNING, Level.WEAK_WARNING, true),
            V106_UNKNOWN_VAULT_LABEL to Triple(Level.INFO, Level.INFO, true),
            // R21 (D159, D160): not a whole-file vault and a plaintext private key are errors the user asked for;
            // D163: ANS-V114 is an extra at WARNING.
            V107_NOT_WHOLE_FILE_VAULT to Triple(Level.ERROR, Level.ERROR, true),
            V108_PLAINTEXT_PRIVATE_KEY to Triple(Level.ERROR, Level.ERROR, true),
            V114_UNTAGGED_VAULT_VALUE to Triple(Level.WARNING, Level.WARNING, false),
        )
        for ((code, levels) in expected) {
            assertEquals(levels, Triple(code.documented, code.runtimeFaithful, code.requested), code.id)
        }
        assertEquals(
            (101..108).map { "ANS-V$it" } + "ANS-V114",
            DiagnosticCode.entries.map { it.id }.filter { it.startsWith("ANS-V1") }.sorted(),
        )
    }

    @Test
    fun `precedence codes are requested and P001b is the fallback-only info`() {
        assertEquals("ANS-P001b", P001B_FALLBACK_ONLY_OVERRIDE.id)
        val expected = mapOf(
            P001_INEFFECTIVE_OVERRIDE to Triple(Level.WARNING, Level.WARNING, true),
            P001B_FALLBACK_ONLY_OVERRIDE to Triple(Level.INFO, Level.INFO, true),
            P002_REDUNDANT_OVERRIDE to Triple(Level.WEAK_WARNING, Level.WEAK_WARNING, true),
            P003_REQUIRED_VAR_UNREACHABLE to Triple(Level.WARNING, Level.WARNING, true),
        )
        for ((code, levels) in expected) {
            assertEquals(levels, Triple(code.documented, code.runtimeFaithful, code.requested), code.id)
        }
    }

    @Test
    fun `spec default codes of amendment R23`() {
        // code to (documented, runtime-faithful, requested)
        val expected = mapOf(
            S003_SPEC_DEFAULT_MISMATCH to Triple(Level.ERROR, Level.ERROR, true),
            S004_SPEC_DEFAULT_NOT_APPLIED to Triple(Level.WARNING, Level.WARNING, false),
            S005_SPEC_DEFAULT_UNDOCUMENTED to Triple(Level.INFO, Level.INFO, false),
        )
        for ((code, levels) in expected) {
            assertEquals(levels, Triple(code.documented, code.runtimeFaithful, code.requested), code.id)
        }
        assertEquals(
            (1..5).map { "ANS-S00$it" },
            DiagnosticCode.entries.map { it.id }.filter { it.startsWith("ANS-S") }.sorted(),
        )
        assertEquals(Level.ERROR, S003_SPEC_DEFAULT_MISMATCH.levelFor(Preset.STRICT))
        assertEquals(Level.INFO, S005_SPEC_DEFAULT_UNDOCUMENTED.levelFor(Preset.STRICT))
    }

    @Test
    fun `strict raises weak warnings only`() {
        assertEquals(Level.WARNING, V105_LABEL_SECRET_MISMATCH.levelFor(Preset.STRICT))
        assertEquals(Level.WARNING, P002_REDUNDANT_OVERRIDE.levelFor(Preset.STRICT))
        assertEquals(Level.INFO, V106_UNKNOWN_VAULT_LABEL.levelFor(Preset.STRICT))
        assertEquals(Level.INFO, P001B_FALLBACK_ONLY_OVERRIDE.levelFor(Preset.STRICT))
        assertEquals(Level.ERROR, V101_MALFORMED_ENVELOPE.levelFor(Preset.RUNTIME_FAITHFUL))
    }
}
