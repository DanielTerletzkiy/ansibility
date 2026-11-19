package de.terletzkiy.ansibility

import com.intellij.codeInsight.intention.IntentionActionBean
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

class PluginLoadsTest : BasePlatformTestCase() {
    fun testPluginIsLoaded() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("de.terletzkiy.ansibility"))
        assertNotNull("Ansibility must be loaded in the test IDE", plugin)
    }

    /** The IDE logs an error for an intention without `intentionDescriptions/<class>/description.html` (2026.3). */
    fun testEveryIntentionHasADescription() {
        val ours = ExtensionPointName<IntentionActionBean>("com.intellij.intentionAction").extensionList.filter { it.pluginDescriptor.pluginId.idString == "de.terletzkiy.ansibility" }
        assertTrue("Ansibility registers intentions", ours.size >= 2)
        val missing = ours.filter { bean ->
            bean.pluginDescriptor.classLoader.getResource("intentionDescriptions/${bean.className.substringAfterLast('.')}/description.html") == null
        }.map { it.className }
        assertEmpty(missing)
    }

    fun testYamlIsAvailable() {
        val file = myFixture.configureByText("main.yml", "- name: Ping\n  ansible.builtin.ping:\n")
        assertInstanceOf(file, YAMLFile::class.java)
    }
}
