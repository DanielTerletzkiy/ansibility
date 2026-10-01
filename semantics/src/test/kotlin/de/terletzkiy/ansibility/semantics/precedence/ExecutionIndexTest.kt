package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.InventoryGraph
import de.terletzkiy.ansibility.semantics.inventory.YamlInventoryParser
import de.terletzkiy.ansibility.semantics.testutil.YamlText
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * `PrecedenceEngine.executionIndex`: the per-name winners of the execution layers, combined with an inventory winner,
 * name exactly the winner `effectiveOf` (and the full execution view) finds, for every layer, owner and order.
 */
class ExecutionIndexTest {
    private val graph: InventoryGraph = YamlInventoryParser.parse(
        YamlText.parse(
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
            """.trimIndent(),
        ),
    )

    private fun src(layer: VarLayer, owner: VarOwner, id: String, vararg entries: Pair<String, String>, order: Int = 0, sourceIndex: Int = 0) =
        VarSource(layer, owner, id, order, entries.associate { (k, v) -> k to YScalar(v, ScalarStyle.PLAIN) as YValue }, sourceIndex = sourceIndex)

    private val inventorySources = listOf(
        src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.All, "L3", "v" to "3", "inv_only" to "3"),
        src(VarLayer.INVENTORY_GROUP_VARS_ALL, VarOwner.All, "L4", "v" to "4", "d" to "4"),
        src(VarLayer.INVENTORY_HOST_VARS, VarOwner.Host("h"), "L9", "host_only" to "9", "p" to "9"),
    )

    @Test
    fun `the index finds the winner of every layer`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(graph, "h", inventorySources)!!
        val execution = listOf(
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/a", "d" to "2a", "default_only" to "a", order = 0),
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/b", "default_only" to "b", order = 1),
            src(VarLayer.PLAY_VARS, VarOwner.All, "play", "p" to "12", "v" to "12"),
            src(VarLayer.VARS_FILES, VarOwner.All, "vars_files", "v" to "13"),
            src(VarLayer.ROLE_VARS, VarOwner.All, "role_vars", "r" to "14"),
            src(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "task", "t" to "15", "tags" to "x", "when" to "y"),
            src(VarLayer.SET_FACT_REGISTER, VarOwner.Host("x"), "other_host", "fact" to "no"),
            src(VarLayer.ROLE_PARAMS, VarOwner.Group("g"), "params", "r" to "18"),
            src(VarLayer.ROLE_PARAMS, VarOwner.Group("other"), "other_group", "og" to "no"),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g"), "ignored", "ignored_only" to "no"),
        )
        val index = engine.executionIndex(inventory.host, inventory.groups, execution)
        val names = engine.executionView(inventory, execution).vars.keys + setOf("missing", "fact", "og", "tags", "when", "ignored_only")
        for (name in names) assertEquals(engine.effectiveOf(name, inventory, execution)?.winner, index.winnerOf(name, inventory[name]?.winner), name)

        assertEquals("vars_files", index.above("v")?.source?.originId)
        assertEquals("defaults/b", index.defaults("default_only")?.source?.originId, "the highest role default")
        assertNull(index.above("d"), "inventory beats role defaults")
        assertEquals("L4", index.winnerOf("d", inventory["d"]?.winner)?.source?.originId)
        assertEquals("params", index.winnerOf("r", null)?.source?.originId)
        assertNull(index.above("tags"), "task vars drop tags")
        assertNull(index.above("fact"), "set_fact of another host")
        assertNull(index.above("og"), "role params of a group the host is not in")
        assertNull(index.above("ignored_only"), "inventory-level sources are not re-applied")
        assertEquals(setOf("d", "default_only", "p", "v", "r", "t"), index.names)
    }

    @Test
    fun `equal orders keep the given order as the view does`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(graph, "h", inventorySources)!!
        val execution = listOf(
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "main/a.yml", "x" to "a", order = 3),
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "main/b.yml", "x" to "b", order = 3),
            src(VarLayer.ROLE_VARS, VarOwner.All, "second-index", "y" to "1", order = 0, sourceIndex = 1),
            src(VarLayer.ROLE_VARS, VarOwner.All, "first-index", "y" to "0", order = 5, sourceIndex = 0),
        )
        val index = engine.executionIndex(inventory.host, inventory.groups, execution)
        assertSame(engine.effectiveOf("x", inventory, execution)!!.winner.source, index.defaults("x")!!.source)
        assertEquals("main/b.yml", index.defaults("x")!!.source.originId)
        assertEquals("second-index", index.above("y")!!.source.originId, "sourceIndex orders before order")
    }

    @Test
    fun `random source sets always agree with effectiveOf`() {
        val random = Random(20261001)
        val layers = VarLayer.entries.filter { !it.isInventoryLevel } + VarLayer.INVENTORY_GROUP_VARS
        val owners = listOf(VarOwner.All, VarOwner.Group("g"), VarOwner.Group("other"), VarOwner.Host("h"), VarOwner.Host("x"), VarOwner.group("all"))
        val names = listOf("a", "b", "c", "d", "v", "p", "tags", "when", "inv_only", "host_only")
        for (hash in HashBehaviour.entries) {
            val engine = PrecedenceEngine(hash)
            val inventory = engine.inventoryView(graph, "h", inventorySources)!!
            repeat(200) { round ->
                val sources = List(random.nextInt(1, 12)) { i ->
                    val entries = names.shuffled(random).take(random.nextInt(1, 5)).map { it to "$round-$i" }.toTypedArray()
                    src(layers.random(random), owners.random(random), "s$i", *entries, order = random.nextInt(0, 3), sourceIndex = random.nextInt(0, 2))
                }
                val index = engine.executionIndex(inventory.host, inventory.groups, sources)
                for (name in names) {
                    assertEquals(engine.effectiveOf(name, inventory, sources)?.winner, index.winnerOf(name, inventory[name]?.winner), "$hash round $round $name")
                }
            }
        }
    }
}
