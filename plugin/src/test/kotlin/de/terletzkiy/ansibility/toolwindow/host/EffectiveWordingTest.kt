package de.terletzkiy.ansibility.toolwindow.host

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.context.host.ValueKind
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YEmpty
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YVault
import de.terletzkiy.ansibility.toolwindow.AnsibilityToolWindowBundle.message

/**
 * The wording behind the Effective vars columns (WU HA7a), on synthetic values: every layer, value kind and runtime
 * marker has its text, the Type column follows YAML 1.1 loading, and the Value column masks every secret.
 */
class EffectiveWordingTest : BasePlatformTestCase() {
    private val vars = LightVirtualFile("vars.yml", "")

    private fun ref(layer: VarsLayer = VarsLayer.INVENTORY_GROUP_VARS, preview: String? = "x", isVault: Boolean = false, file: LightVirtualFile = vars, role: String? = null) =
        VarSourceRef(file, 0, layer, group = if (role == null) "web" else null, host = null, preview = preview, isVault = isVault, role = role)

    fun testEveryLayerHasALevelText() {
        for (layer in VarsLayer.entries) {
            val text = EffectiveTables.layerText(ref(layer))
            assertFalse("$layer: $text", text.isBlank() || text.startsWith("!") || "{" in text)
            if (layer != VarsLayer.MOLECULE_INVENTORY) assertTrue("$layer: $text", text.startsWith("L${layer.level} "))
        }
        assertEquals("L6 env group_vars/web", EffectiveTables.layerText(ref(VarsLayer.INVENTORY_GROUP_VARS)))
        assertEquals("L2 role defaults of postfix", EffectiveTables.layerText(ref(VarsLayer.ROLE_DEFAULTS, role = "postfix")))
    }

    fun testEveryValueKindAndRuntimeMarkerHasAText() {
        for (kind in ValueKind.entries) {
            val text = message("effective.type.${kind.name}")
            assertFalse("$kind: $text", text.isBlank() || text.startsWith("!"))
        }
        for (kind in RuntimeMarkerKind.entries) {
            val text = message("effective.marker.${kind.name}")
            assertTrue("$kind: $text", text.startsWith("may be replaced at runtime by "))
            assertFalse(message("effective.layer.${kind.layer.name}", "").isBlank())
        }
    }

    fun testTheTypeColumnFollowsYamlLoading() {
        fun plain(text: String) = ValueKind.of(YScalar(text, ScalarStyle.PLAIN))
        assertEquals(ValueKind.INT, plain("8080"))
        assertEquals(ValueKind.FLOAT, plain("3.10"))
        assertEquals(ValueKind.BOOL, plain("yes"))
        assertEquals(ValueKind.NULL, plain("~"))
        assertEquals(ValueKind.DATE, plain("2026-10-01"))
        assertEquals(ValueKind.STR, plain("mail.example.de"))
        assertEquals("quoted scalars are strings", ValueKind.STR, ValueKind.of(YScalar("8080", ScalarStyle.DOUBLE_QUOTED)))
        assertEquals(ValueKind.TEMPLATE, ValueKind.of(YScalar("{{ system_ip_floating }}", ScalarStyle.DOUBLE_QUOTED)))
        assertEquals("an unsafe string never renders", ValueKind.STR, ValueKind.of(YScalar("{{ x }}", ScalarStyle.SINGLE_QUOTED, tag = "!unsafe")))
        assertEquals(ValueKind.VAULT, ValueKind.of(YVault()))
        assertEquals(ValueKind.NULL, ValueKind.of(YEmpty()))
        assertEquals(ValueKind.LIST, ValueKind.of(YSeq(emptyList())))
        assertEquals(ValueKind.DICT, ValueKind.of(YMap(emptyList())))
    }

    fun testTheValueColumnMasksEverySecret() {
        assertEquals("8080", EffectiveTables.valueText(ref(preview = "8080")))
        assertEquals("🔒 vault-encrypted", EffectiveTables.valueText(ref(preview = null, isVault = true)))
        assertEquals("a vault value never shows a preview", "🔒 vault-encrypted", EffectiveTables.valueText(ref(preview = "leak", isVault = true)))
        assertEquals("🔒 value hidden (vault file)", EffectiveTables.valueText(ref(preview = null, file = LightVirtualFile("vault.yml", ""))))
        assertEquals("🔒 value hidden (vault_ name)", EffectiveTables.valueText(ref(preview = null)))
    }
}
