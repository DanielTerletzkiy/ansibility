package de.terletzkiy.ansibility.settings

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Every Ansibility settings page can be created the way the Settings dialog creates it: through its extension point. */
class ConfigurablesInstantiateTest : BasePlatformTestCase() {
    fun testEveryAnsibilityConfigurableIsCreatedThroughItsExtensionPoint() {
        val ours = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.pluginDescriptor?.pluginId?.idString == "de.terletzkiy.ansibility" }
        assertTrue(ours.map { it.id }.toString(), ours.any { it.id == "de.terletzkiy.ansibility.settings.runner" })
        for (ep in ours) {
            val configurable = ep.createConfigurable()
            assertNotNull("${ep.id} cannot be created", configurable)
            try {
                assertNotNull("${ep.id} has no component", configurable!!.createComponent())
                configurable.reset()
            } finally {
                configurable?.disposeUIResources()
            }
        }
    }
}
