package de.terletzkiy.ansibility

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.psi.YAMLFile

class PluginLoadsTest : BasePlatformTestCase() {
    fun testPluginIsLoaded() {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("de.terletzkiy.ansibility"))
        assertNotNull("Ansibility must be loaded in the test IDE", plugin)
    }

    fun testYamlIsAvailable() {
        val file = myFixture.configureByText("main.yml", "- name: Ping\n  ansible.builtin.ping:\n")
        assertInstanceOf(file, YAMLFile::class.java)
    }
}
