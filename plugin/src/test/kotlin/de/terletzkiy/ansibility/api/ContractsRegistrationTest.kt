package de.terletzkiy.ansibility.api

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The single root edit of WU CT0 (plan amendment R7/R8, "Packages and fragments"): the internal extension points in
 * `ansibility-core.xml`, the new area fragments included from `plugin.xml`, and the optional VCS dependency.
 */
class ContractsRegistrationTest : BasePlatformTestCase() {

    fun testPresentationExtensionPointsAreDeclared() {
        val area = ApplicationManager.getApplication().extensionArea
        for (name in listOf(CardSection.EP_NAME.name, ContextWidgetSegment.EP_NAME.name, ToolWindowNodeContributor.EP_NAME.name)) {
            assertTrue(name, area.hasExtensionPoint(name))
        }
        // Reading the lists must work whether or not a track has registered contributions yet.
        assertNotNull(CardSection.EP_NAME.extensionList)
        assertNotNull(ContextWidgetSegment.EP_NAME.extensionList)
        assertNotNull(ToolWindowNodeContributor.EP_NAME.extensionList)
    }

    fun testAreaFragmentsAreIncludedAndLoadable() {
        val includes = elements(parse(File(RESOURCES, "plugin.xml")), "xi:include").map { it.getAttribute("href") }
        for (fragment in NEW_FRAGMENTS) {
            assertTrue("plugin.xml includes $fragment", "/META-INF/$fragment" in includes)
        }
        for (href in includes) {
            val resource = javaClass.classLoader.getResource(href.removePrefix("/"))
            assertNotNull("$href is on the plugin classpath", resource)
            val root = resource!!.openStream().use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }.documentElement
            assertEquals(href, "idea-plugin", root.tagName)
        }
    }

    fun testOptionalDependenciesHaveTheirConfigFiles() {
        val depends = elements(parse(File(RESOURCES, "plugin.xml")), "depends").filter { it.getAttribute("optional") == "true" }
        val configFiles = depends.associate { it.textContent.trim() to it.getAttribute("config-file") }
        assertEquals("ansibility-vcs.xml", configFiles[VCS_MODULE])
        assertEquals("ansibility-vault.xml", configFiles[VAULT_EDITOR])
        for (configFile in configFiles.values) {
            assertTrue("$configFile exists", File(RESOURCES, configFile).isFile)
        }

        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))!!
        val vcs = plugin.dependencies.single { it.pluginId.idString == VCS_MODULE }
        assertTrue("the VCS dependency is optional", vcs.isOptional)
    }

    private fun parse(file: File): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    private fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val PLUGIN_ID = "de.terletzkiy.ansibility"
        const val VCS_MODULE = "com.intellij.modules.vcs"
        const val VAULT_EDITOR = "ru.sadv1r.ansible-vault-editor-idea-plugin"
        val NEW_FRAGMENTS = listOf("ansibility-vault-core.xml", "ansibility-host.xml", "ansibility-workspace.xml")

        /** The plugin's descriptor sources; Gradle runs the tests in the `plugin` module directory. */
        val RESOURCES = File("src/main/resources/META-INF")
    }
}
