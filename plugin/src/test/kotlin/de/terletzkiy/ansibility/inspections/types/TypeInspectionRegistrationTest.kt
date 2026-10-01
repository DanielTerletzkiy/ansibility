package de.terletzkiy.ansibility.inspections.types

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.TypeCheckService
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.toHighlightDisplayLevel
import de.terletzkiy.ansibility.types.TypeCheckServiceImpl
import de.terletzkiy.ansibility.types.TypeCheckTestCase

/**
 * The type inspections are registered as plan A.6 wants them: one per code, YAML, Editor › Inspections › Ansible ›
 * Types, on by default at the "Documented types" level, each with a description page.
 */
class TypeInspectionRegistrationTest : BasePlatformTestCase() {

    fun testEveryCodeHasItsInspection() {
        val expected = mapOf(
            "AnsibleValueRejected" to DiagnosticCode.T001_VALUE_REJECTED,
            "AnsibleUnsupportedSubOption" to DiagnosticCode.T002_UNSUPPORTED_SUB_OPTION,
            "AnsibleMissingRequiredSubOption" to DiagnosticCode.T003_MISSING_REQUIRED_SUB_OPTION,
            "AnsibleChoiceMismatch" to DiagnosticCode.T004_CHOICE_MISMATCH,
            "AnsibleNullForTypedOption" to DiagnosticCode.T005_NULL_FOR_TYPED_OPTION,
            "AnsibleSpecShapeContradiction" to DiagnosticCode.T010_SHAPE_CONTRADICTION,
            "AnsibleCoercedScalar" to DiagnosticCode.T011_COERCED_SCALAR_TO_STR,
            "AnsibleScalarTypeMismatch" to DiagnosticCode.T013_SCALAR_TYPE_MISMATCH,
            "AnsibleLegacyCoercion" to DiagnosticCode.T014_LEGACY_COERCION,
            "AnsibleNullForOptional" to DiagnosticCode.T015_NULL_FOR_OPTIONAL,
            "AnsibleStringForNumberOrBool" to DiagnosticCode.T016_STRING_FOR_NUMBER_OR_BOOL,
        )
        val registered = LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.implementationClass.startsWith(PACKAGE) }.associateBy { it.shortName }
        assertEquals(expected.keys.sorted(), registered.keys.sorted())
        assertEquals(TypeCheckTestCase.inspections().map { it.shortName }.sorted(), expected.keys.sorted())
        for ((shortName, code) in expected) {
            val ep = registered.getValue(shortName)
            val wrapper = LocalInspectionToolWrapper(ep)
            assertEquals(shortName, "yaml", ep.language)
            assertEquals(shortName, listOf("Ansibility", "Types"), wrapper.groupPath.toList())
            assertTrue(shortName, ep.enabledByDefault)
            assertEquals(shortName, code.levelFor(Preset.DOCUMENTED_TYPES).toHighlightDisplayLevel(), wrapper.defaultLevel)
            assertTrue(shortName, wrapper.displayName.endsWith("(${code.id})"))
            assertTrue("$shortName has a description", wrapper.loadDescription()?.contains("<!-- tooltip end -->") == true)
        }
        assertEquals(HighlightDisplayLevel.WEAK_WARNING, LocalInspectionToolWrapper(registered.getValue("AnsibleNullForOptional")).defaultLevel)
    }

    fun testTheServiceIsRegistered() {
        assertInstanceOf(TypeCheckService.getInstance(project), TypeCheckServiceImpl::class.java)
    }

    private companion object {
        const val PACKAGE = "de.terletzkiy.ansibility.inspections.types."
    }
}
