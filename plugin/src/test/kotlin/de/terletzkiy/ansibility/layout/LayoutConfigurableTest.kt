package de.terletzkiy.ansibility.layout

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.settings.layout.LayoutConfigurable

/** The Layout settings page (F10.6) builds, resets and is unmodified until edited. */
class LayoutConfigurableTest : BasePlatformTestCase() {
    fun testPanelBuilds() {
        myFixture.addFileToProject("hosts.ini", "[web]\nw1\n")
        myFixture.addFileToProject("site.yml", "- hosts: web\n  tasks: []\n")
        val configurable = LayoutConfigurable(project)
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
