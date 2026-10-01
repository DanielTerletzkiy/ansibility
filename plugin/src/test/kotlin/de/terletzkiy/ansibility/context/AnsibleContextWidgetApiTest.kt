package de.terletzkiy.ansibility.context

import com.intellij.openapi.wm.StatusBarWidget
import junit.framework.TestCase

/**
 * The widget must not override (or bridge to) the deprecated `StatusBarWidget.getPresentation(PlatformType)`,
 * which the plugin verifier reports; Kotlin's default `-jvm-default=enable` mode would generate such a bridge, so the
 * plugin is compiled with `-jvm-default=no-compatibility` (no per-class annotation).
 */
class AnsibleContextWidgetApiTest : TestCase() {
    fun testNoDeprecatedPresentationBridge() {
        val declared = AnsibleContextWidget::class.java.declaredMethods.filter { it.name == "getPresentation" }
        assertEquals("declared getPresentation methods: $declared", emptyList<Any>(), declared)
        assertTrue(StatusBarWidget::class.java.isAssignableFrom(AnsibleContextWidget::class.java))
    }

    fun testPluginIsCompiledWithoutJvmDefaultCompatibility() {
        // `-jvm-default=enable` would add a DefaultImpls class to every Kotlin interface with default methods.
        val interfaceWithDefaults = de.terletzkiy.ansibility.coexist.CoexistenceSettings::class.java
        val defaultImpls = runCatching { Class.forName(interfaceWithDefaults.name + "\$DefaultImpls") }.getOrNull()
        assertNull("no-compatibility mode generates no DefaultImpls", defaultImpls)
        assertTrue(interfaceWithDefaults.getMethod("conflictNotifications").isDefault)
    }
}
