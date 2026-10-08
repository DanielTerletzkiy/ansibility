package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.toHighlightDisplayLevel

/**
 * The argument_specs default inspections of plan amendment R23 are registered like the other variable checks: one per
 * code, group Ansibility › Variables, on by default at the SeverityPolicy level of the default preset, each with a
 * description page.
 */
class SpecDefaultRegistrationTest : BasePlatformTestCase() {
    fun testEveryCodeHasItsInspection() {
        val expected = mapOf(
            AnsibleSpecDefaultMismatchInspection.SHORT_NAME to DiagnosticCode.S003_SPEC_DEFAULT_MISMATCH,
            AnsibleSpecDefaultNotAppliedInspection.SHORT_NAME to DiagnosticCode.S004_SPEC_DEFAULT_NOT_APPLIED,
            AnsibleSpecDefaultUndocumentedInspection.SHORT_NAME to DiagnosticCode.S005_SPEC_DEFAULT_UNDOCUMENTED,
        )
        val registered = LocalInspectionEP.LOCAL_INSPECTION.extensionList.associateBy { it.shortName }
        for ((shortName, code) in expected) {
            val ep = registered[shortName] ?: error("$shortName is not registered")
            val wrapper = LocalInspectionToolWrapper(ep)
            assertEquals(shortName, listOf("Ansibility", "Variables"), wrapper.groupPath.toList())
            assertTrue(shortName, ep.enabledByDefault)
            assertEquals(shortName, code.levelFor(Preset.DOCUMENTED_TYPES).toHighlightDisplayLevel(), wrapper.defaultLevel)
            assertTrue(shortName, wrapper.displayName.endsWith("(${code.id})"))
            assertTrue("$shortName has a description", wrapper.loadDescription()?.contains("<b>${code.id}.</b>") == true)
        }
    }
}
