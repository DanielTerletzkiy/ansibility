package de.terletzkiy.ansibility.docs

import com.intellij.openapi.project.DumbService
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.SiteClassifier
import de.terletzkiy.ansibility.api.SiteDocumentation
import de.terletzkiy.ansibility.api.SiteNavigation

/** The docs fragment registers its extensions; the classifier loads whether or not the vars classifier it follows exists. */
class DocsRegistrationTest : BasePlatformTestCase() {

    fun testExtensionsAreRegistered() {
        val classifiers = SiteClassifier.EP_NAME.extensionList
        val ours = classifiers.filterIsInstance<TaskSiteClassifier>()
        assertEquals("order=\"after ansibilityVars\" loads even when that id is absent", 1, ours.size)
        assertEquals(1, SiteDocumentation.EP_NAME.extensionList.filterIsInstance<TaskSiteDocumentation>().size)
        assertEquals(1, SiteNavigation.EP_NAME.extensionList.filterIsInstance<DocsNavigation>().size)
        assertEquals(1, DocumentationLinkHandler.EP_NAME.extensionList.filterIsInstance<AnsibleDocLinkHandler>().size)
    }

    fun testVarsClassifierRunsFirstWhenPresent() {
        val classifiers = SiteClassifier.EP_NAME.extensionList
        val ours = classifiers.indexOfFirst { it is TaskSiteClassifier }
        val vars = classifiers.indexOfFirst { it.javaClass.packageName.endsWith(".vars") }
        if (vars >= 0) assertTrue("the vars classifier is asked before ours: $classifiers", vars < ours)
    }

    fun testExtensionsWorkWhileIndexing() {
        assertTrue(DumbService.isDumbAware(TaskSiteClassifier()))
        assertTrue(DumbService.isDumbAware(TaskSiteDocumentation()))
        assertTrue(DumbService.isDumbAware(DocsNavigation()))
    }
}
