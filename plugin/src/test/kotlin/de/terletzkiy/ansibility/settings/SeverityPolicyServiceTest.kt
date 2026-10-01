package de.terletzkiy.ansibility.settings

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.context.ContextTestTree
import de.terletzkiy.ansibility.context.ContextTestTree.FALCON
import de.terletzkiy.ansibility.context.ContextTestTree.GOLDEN
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.diagnostics.Level
import de.terletzkiy.ansibility.semantics.diagnostics.Preset

/** [SeverityPolicy] as a project service: per-root settings and the platform mappings. */
class SeverityPolicyServiceTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ContextTestTree.create(myFixture)
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } finally {
            super.tearDown()
        }
    }

    private fun root(path: String): AnsibleRoot {
        val dir = myFixture.findFileInTempDir(path)
        return AnsibleWorkspaceImpl.getInstance(project)!!.roots().single { it.dir == dir }
    }

    fun testUsesTheSettingsOfTheFindingsRoot() {
        val policy = SeverityPolicy.getInstance(project)
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(preset = Preset.RUNTIME_FAITHFUL) }
        assertEquals(Level.WARNING, policy.level(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, root(FALCON)))
        assertEquals("other roots keep Documented types", Level.ERROR, policy.level(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, root(GOLDEN)))
        assertEquals("no root: defaults", Level.ERROR, policy.level(DiagnosticCode.T011_COERCED_SCALAR_TO_STR, null))
    }

    fun testTogglesOfTheRootApply() {
        val policy = SeverityPolicy.getInstance(project)
        val falcon = root(FALCON)
        val onModuleOption = FindingContext(onModuleOption = true)
        assertEquals(Level.OFF, policy.level(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, falcon, onModuleOption))
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(moduleOptionCoercions = true, redForClaudeCertainFailures = false) }
        assertEquals(Level.ERROR, policy.level(DiagnosticCode.T013_SCALAR_TYPE_MISMATCH, falcon, onModuleOption))
        assertEquals(Level.WARNING, policy.level(DiagnosticCode.R001_UNRESOLVED_REFERENCE, falcon))
        assertEquals(Level.ERROR, policy.level(DiagnosticCode.R001_UNRESOLVED_REFERENCE, root(GOLDEN)))
    }

    fun testDocsMismatchHelperUsesTheRootSetting() {
        val policy = SeverityPolicy.getInstance(project)
        val falcon = root(FALCON)
        assertEquals(Level.WARNING, policy.capForDocsMismatch(DiagnosticCode.M001_UNKNOWN_MODULE_OPTION, Level.ERROR, falcon))
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(unknownModuleOptionWhenDocsDiffer = DocsMismatchSeverity.OFF) }
        assertEquals(Level.OFF, policy.capForDocsMismatch(DiagnosticCode.M001_UNKNOWN_MODULE_OPTION, Level.ERROR, falcon))
    }

    fun testPlatformMappings() {
        assertEquals(HighlightDisplayLevel.ERROR, Level.ERROR.toHighlightDisplayLevel())
        assertEquals(HighlightDisplayLevel.WARNING, Level.WARNING.toHighlightDisplayLevel())
        assertEquals(HighlightDisplayLevel.WEAK_WARNING, Level.WEAK_WARNING.toHighlightDisplayLevel())
        assertEquals(HighlightDisplayLevel.CONSIDERATION_ATTRIBUTES, Level.INFO.toHighlightDisplayLevel())
        assertEquals(HighlightDisplayLevel.DO_NOT_SHOW, Level.OFF.toHighlightDisplayLevel())

        assertEquals(ProblemHighlightType.GENERIC_ERROR, Level.ERROR.toProblemHighlightType())
        assertEquals(ProblemHighlightType.WARNING, Level.WARNING.toProblemHighlightType())
        assertEquals(ProblemHighlightType.WEAK_WARNING, Level.WEAK_WARNING.toProblemHighlightType())
        assertEquals(ProblemHighlightType.INFORMATION, Level.INFO.toProblemHighlightType())
        assertNull(Level.OFF.toProblemHighlightType())

        assertEquals(HighlightSeverity.ERROR, Level.ERROR.toHighlightSeverity())
        assertEquals(HighlightSeverity.WARNING, Level.WARNING.toHighlightSeverity())
        assertEquals(HighlightSeverity.WEAK_WARNING, Level.WEAK_WARNING.toHighlightSeverity())
        assertEquals(HighlightSeverity.INFORMATION, Level.INFO.toHighlightSeverity())
        assertNull(Level.OFF.toHighlightSeverity())
    }
}
