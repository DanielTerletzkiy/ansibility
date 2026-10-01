package de.terletzkiy.ansibility.navigation

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.InspectionToolRegistrar
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.CompletionSource
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteNavigation
import de.terletzkiy.ansibility.inspections.references.AnsibleUnresolvedReferenceInspection

/** The navigation fragment registers its extensions, in the dispatcher order of plan A.4. */
class RefsRegistrationTest : BasePlatformTestCase() {

    fun testExtensionsAreRegistered() {
        assertEquals(1, SiteClassifier.EP_NAME.extensionList.filterIsInstance<RefsSiteClassifier>().size)
        assertEquals(1, SiteNavigation.EP_NAME.extensionList.filterIsInstance<RefsNavigation>().size)
        assertEquals(1, CompletionSource.EP_NAME.extensionList.filterIsInstance<RefsCompletionSource>().size)
    }

    /** `order="after ansibilityVars, before ansibilityTasks"` refers to ids of other fragments; it holds over the whole EP. */
    fun testClassifierRunsAfterVarsAndBeforeTasks() {
        val classifiers = SiteClassifier.EP_NAME.extensionList
        val ours = classifiers.indexOfFirst { it is RefsSiteClassifier }
        val vars = classifiers.indexOfFirst { it.javaClass.name == "de.terletzkiy.ansibility.vars.VarsSiteClassifier" }
        val tasks = classifiers.indexOfFirst { it.javaClass.name == "de.terletzkiy.ansibility.docs.TaskSiteClassifier" }
        assertTrue("vars before refs: $classifiers", vars in 0 until ours)
        assertTrue("refs before tasks: $classifiers", tasks > ours)
    }

    fun testInspectionIsRegisteredInTheAnsibleGroup() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == "AnsibleUnresolvedReference" }
        assertEquals(AnsibleUnresolvedReferenceInspection::class.java.name, ep.implementationClass)
        assertTrue(ep.enabledByDefault)
        assertEquals("ERROR", ep.level)
        val tool = InspectionToolRegistrar.getInstance().createTools().single { it.shortName == "AnsibleUnresolvedReference" }
        assertEquals("Unresolved reference (ANS-R001)", tool.displayName)
        assertEquals("Ansibility", tool.groupDisplayName)
        assertTrue("the description file exists", !tool.loadDescription().isNullOrBlank())
    }

    fun testClassifierWorksWhileIndexing() {
        assertTrue(DumbService.isDumbAware(RefsSiteClassifier()))
        assertFalse("index-backed", DumbService.isDumbAware(RefsCompletionSource()))
    }
}
