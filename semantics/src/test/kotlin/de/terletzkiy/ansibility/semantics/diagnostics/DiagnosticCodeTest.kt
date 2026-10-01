package de.terletzkiy.ansibility.semantics.diagnostics

import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P001B_FALLBACK_ONLY_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P001_INEFFECTIVE_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P002_REDUNDANT_OVERRIDE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.P003_REQUIRED_VAR_UNREACHABLE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V101_MALFORMED_ENVELOPE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V102_FOLDED_VAULT_VALUE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V103_TRAILING_WHITESPACE
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V104_NO_ID_DECRYPTS
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V105_LABEL_SECRET_MISMATCH
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V106_UNKNOWN_VAULT_LABEL
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V107_PASTED_VAULT_BLOCK
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode.V108_UNVAULTED_KEY_FILE
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
            V107_PASTED_VAULT_BLOCK to Triple(Level.WARNING, Level.WARNING, false),
            V108_UNVAULTED_KEY_FILE to Triple(Level.WARNING, Level.WARNING, false),
        )
        for ((code, levels) in expected) {
            assertEquals(levels, Triple(code.documented, code.runtimeFaithful, code.requested), code.id)
        }
        assertEquals(
            (101..108).map { "ANS-V$it" },
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
    fun `strict raises weak warnings only`() {
        assertEquals(Level.WARNING, V105_LABEL_SECRET_MISMATCH.levelFor(Preset.STRICT))
        assertEquals(Level.WARNING, P002_REDUNDANT_OVERRIDE.levelFor(Preset.STRICT))
        assertEquals(Level.INFO, V106_UNKNOWN_VAULT_LABEL.levelFor(Preset.STRICT))
        assertEquals(Level.INFO, P001B_FALLBACK_ONLY_OVERRIDE.levelFor(Preset.STRICT))
        assertEquals(Level.ERROR, V101_MALFORMED_ENVELOPE.levelFor(Preset.RUNTIME_FAITHFUL))
    }
}
