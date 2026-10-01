package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `PrecedenceEngine.effectiveOf` evaluates one name and always agrees with the full execution view. */
class EffectiveOfTest {
    private fun graph(text: String): InventoryGraph = YamlInventoryParser.parse(YamlText.parse(text.trimIndent()))

    private fun src(layer: VarLayer, owner: VarOwner, id: String, vararg entries: Pair<String, String>, order: Int = 0) =
        VarSource(layer, owner, id, order, entries.associate { (k, v) -> k to YScalar(v, ScalarStyle.PLAIN) as YValue })

    private fun yamlSrc(layer: VarLayer, owner: VarOwner, id: String, yaml: String, order: Int = 0) =
        requireNotNull(VarSource.fromDocument(layer, owner, id, order, YamlText.parse(yaml.trimIndent())))

    private val inventoryGraph = graph(
        """
        all:
          hosts:
            h:
        g:
          hosts:
            h:
        other:
          hosts:
            x:
        """,
    )

    private val inventorySources = listOf(
        src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.All, "L3all", "v" to "3all", "inv_only" to "a"),
        src(VarLayer.INVENTORY_GROUP_VARS_ALL, VarOwner.All, "L4", "v" to "4", "shadowed_by_role_vars" to "4"),
        src(VarLayer.PLAYBOOK_GROUP_VARS_ALL, VarOwner.All, "L5", "v" to "5"),
        src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g"), "L6", "v" to "6"),
        src(VarLayer.INVENTORY_HOST_VARS, VarOwner.Host("h"), "L9", "v" to "9", "host_only" to "h"),
        src(VarLayer.INVENTORY_HOST_VARS, VarOwner.Host("x"), "L9x", "v" to "no"),
    )

    private val executionSources = listOf(
        src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/a", "v" to "2a", "d" to "a", "default_only" to "a", order = 0),
        src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/running", "v" to "2b", "d" to "running", order = 1),
        src(VarLayer.PLAY_VARS, VarOwner.All, "play", "v" to "12", "p" to "12"),
        src(VarLayer.VARS_FILES, VarOwner.All, "vars_files", "p" to "13"),
        src(VarLayer.ROLE_VARS, VarOwner.All, "role_vars", "shadowed_by_role_vars" to "14"),
        src(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "block", "t" to "15a", order = 0),
        src(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "task", "t" to "15b", "tags" to "x", "when" to "y", order = 1),
        src(VarLayer.SET_FACT_REGISTER, VarOwner.Host("h"), "set_fact", "fact" to "17"),
        src(VarLayer.SET_FACT_REGISTER, VarOwner.Host("x"), "set_fact_other", "fact" to "no", "other_host_only" to "no"),
        src(VarLayer.ROLE_PARAMS, VarOwner.Group("g"), "role_params", "param" to "18"),
        src(VarLayer.ROLE_PARAMS, VarOwner.Group("other"), "role_params_other", "other_group_only" to "no"),
        src(VarLayer.EXTRA_VARS, VarOwner.All, "extra", "e" to "19"),
        // An inventory-level source among the execution sources is ignored: the inventory view already holds it.
        src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g"), "ignored-inventory", "v" to "no", "ignored_only" to "no"),
    )

    @Test
    fun `every name agrees with the execution view`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(inventoryGraph, "h", inventorySources)!!
        val view = engine.executionView(inventory, executionSources)
        val names = view.vars.keys
        assertEquals(
            setOf("v", "inv_only", "shadowed_by_role_vars", "host_only", "d", "default_only", "p", "t", "fact", "param", "e"),
            names,
        )
        for (name in names) {
            assertEquals(view[name], engine.effectiveOf(name, inventory, executionSources), name)
        }
        assertEquals(listOf("defaults/a", "defaults/running", "L3all", "L4", "L5", "L6", "L9", "play"), engine.effectiveOf("v", inventory, executionSources)!!.chain())
        assertEquals("role_vars", engine.effectiveOf("shadowed_by_role_vars", inventory, executionSources)!!.winner.source.originId)
    }

    @Test
    fun `names that no applicable source defines are null`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(inventoryGraph, "h", inventorySources)!!
        assertNull(engine.effectiveOf("missing", inventory, executionSources))
        assertNull(engine.effectiveOf("other_host_only", inventory, executionSources), "set_fact of another host")
        assertNull(engine.effectiveOf("other_group_only", inventory, executionSources), "role params of a group the host is not in")
        assertNull(engine.effectiveOf("ignored_only", inventory, executionSources), "inventory-level sources are not re-applied")
        assertNull(engine.effectiveOf("tags", inventory, executionSources), "task vars drop tags")
        assertNull(engine.effectiveOf("when", inventory, executionSources), "task vars drop when")
    }

    @Test
    fun `inventory-only names keep their inventory chain`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(inventoryGraph, "h", inventorySources)!!
        assertEquals(inventory["host_only"], engine.effectiveOf("host_only", inventory, executionSources))
        assertEquals(inventory["inv_only"], engine.effectiveOf("inv_only", inventory, emptyList()))
    }

    @Test
    fun `merge hash behaviour and shallow task vars agree with the view`() {
        val engine = PrecedenceEngine(HashBehaviour.MERGE)
        val sources = listOf(
            yamlSrc(VarLayer.INVENTORY_GROUP_VARS_ALL, VarOwner.All, "inv", "cfg: {inv: 1}"),
        )
        val inventory = engine.inventoryView(inventoryGraph, "h", sources)!!
        val execution = listOf(
            yamlSrc(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults", "cfg: {d: 1}"),
            yamlSrc(VarLayer.PLAY_VARS, VarOwner.All, "play", "cfg: {a: 1}"),
            yamlSrc(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "block", "cfg: {b: 1}", order = 0),
            yamlSrc(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "task", "cfg: {c: 1}", order = 1),
        )
        val expected = engine.executionView(inventory, execution)["cfg"]
        val actual = engine.effectiveOf("cfg", inventory, execution)
        assertNotNull(actual)
        assertEquals(expected, actual)
        assertEquals(listOf("d", "inv", "a", "c"), (actual!!.value as YMap).keys)
        assertEquals(listOf("play", "inv", "defaults"), actual.mergedFrom.map { it.source.originId })
    }

    private fun EffectiveVar.chain() = definitions.map { it.source.originId }
}
