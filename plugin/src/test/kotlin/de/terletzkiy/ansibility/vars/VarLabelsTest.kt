package de.terletzkiy.ansibility.vars

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind

/** Every layer, definition kind and Jinja local kind has a label in [AnsibilityVarsBundle] (keys are built from enum names). */
class VarLabelsTest : BasePlatformTestCase() {
    private fun assertLabel(key: String) {
        val text = AnsibilityVarsBundle.message(key, "x")
        assertFalse("missing $key", text.startsWith("!") && text.endsWith("!"))
        assertTrue("empty $key", text.isNotBlank())
    }

    fun testEveryEnumHasALabel() {
        VarsLayer.entries.forEach { assertLabel("layer.${it.name}") }
        VarDefKind.entries.forEach { assertLabel("kind.${it.name}") }
        JinjaLocalKind.entries.forEach { assertLabel("card.local.kind.${it.name}") }
    }

    fun testLayerLabelsNameTheOwner() {
        assertEquals("inventory group_vars/keycloak", AnsibilityVarsBundle.message("layer.INVENTORY_GROUP_VARS", "keycloak"))
        assertEquals("inventory host_vars/prod-prod1", AnsibilityVarsBundle.message("layer.INVENTORY_HOST_VARS", "prod-prod1"))
        assertEquals("inventory group_vars/all", AnsibilityVarsBundle.message("layer.INVENTORY_GROUP_VARS_ALL", "all"))
        assertEquals("level 4", AnsibilityVarsBundle.message("card.this.level", 4))
    }
}
