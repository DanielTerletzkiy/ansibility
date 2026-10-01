package de.terletzkiy.ansibility.semantics.precedence

import de.terletzkiy.ansibility.semantics.inventory.DirEntry
import de.terletzkiy.ansibility.semantics.inventory.DirectoryLister
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrecedenceEngineTest {
    private fun graph(text: String): InventoryGraph = YamlInventoryParser.parse(YamlText.parse(text.trimIndent()))

    private fun scalar(text: String) = YScalar(text, ScalarStyle.PLAIN)

    private fun src(
        layer: VarLayer,
        owner: VarOwner,
        id: String,
        vararg entries: Pair<String, String>,
        order: Int = 0,
        sourceIndex: Int = 0,
    ) = VarSource(layer, owner, id, order, entries.associate { (k, v) -> k to scalar(v) as YValue }, sourceIndex = sourceIndex)

    private fun yamlSrc(layer: VarLayer, owner: VarOwner, id: String, yaml: String, order: Int = 0) =
        requireNotNull(VarSource.fromDocument(layer, owner, id, order, YamlText.parse(yaml.trimIndent())))

    private fun EffectiveVar.chain() = definitions.map { it.source.originId }

    private val simple = graph(
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

    /** One definition of `v` at every inventory level, handed to the engine in scrambled order. */
    private val ladder = listOf(
        src(VarLayer.PLAYBOOK_HOST_VARS, VarOwner.Host("h"), "L10", "v" to "10"),
        src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g"), "L6", "v" to "6"),
        src(VarLayer.INVENTORY_FILE_HOST, VarOwner.Host("h"), "L8", "v" to "8"),
        src(VarLayer.PLAYBOOK_GROUP_VARS_ALL, VarOwner.All, "L5", "v" to "5"),
        src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g"), "L3g", "v" to "3g"),
        src(VarLayer.INVENTORY_HOST_VARS, VarOwner.Host("h"), "L9", "v" to "9"),
        src(VarLayer.PLAYBOOK_GROUP_VARS, VarOwner.Group("g"), "L7", "v" to "7"),
        src(VarLayer.INVENTORY_GROUP_VARS_ALL, VarOwner.All, "L4", "v" to "4"),
        src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.All, "L3all", "v" to "3all"),
    )

    @Test
    fun `default precedence orders levels 3 to 10`() {
        val v = PrecedenceEngine().inventoryView(simple, "h", ladder)!!["v"]!!
        assertEquals(listOf("L3all", "L3g", "L4", "L5", "L6", "L7", "L8", "L9", "L10"), v.chain())
        assertEquals("10", (v.value as YScalar).text)
        assertEquals("L10", v.winner.source.originId)
        assertEquals("L9", v.shadowed.first().source.originId, "the runner-up comes first")
        assertTrue(v.mergedFrom.isEmpty())
    }

    @Test
    fun `a custom VARIABLE_PRECEDENCE reorders the group levels only`() {
        val reversed = PrecedenceEntry.DEFAULT.reversed()
        val v = PrecedenceEngine(precedence = reversed).inventoryView(simple, "h", ladder)!!["v"]!!
        assertEquals(listOf("L7", "L6", "L5", "L4", "L3g", "L3all", "L8", "L9", "L10"), v.chain())
        val onlyAll = PrecedenceEngine(precedence = listOf(PrecedenceEntry.ALL_INVENTORY))
        assertEquals(listOf("L3all", "L8", "L9", "L10"), onlyAll.inventoryView(simple, "h", ladder)!!["v"]!!.chain())
    }

    @Test
    fun `sources of other groups and hosts, and execution layers, are ignored`() {
        val sources = listOf(
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("other"), "other-group", "a" to "1"),
            src(VarLayer.INVENTORY_HOST_VARS, VarOwner.Host("x"), "other-host", "b" to "1"),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("missing"), "missing-group", "c" to "1"),
            src(VarLayer.PLAY_VARS, VarOwner.All, "play", "d" to "1"),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("all"), "all-as-group", "e" to "1"),
        )
        val view = PrecedenceEngine().inventoryView(simple, "h", sources)!!
        assertEquals(listOf("e"), view.vars.keys.toList())
        assertEquals(VarLayer.INVENTORY_GROUP_VARS, view["e"]!!.winner.source.layer)
        assertEquals(listOf("all-as-group"), view.appliedSources.map { it.originId })
        assertNull(PrecedenceEngine().inventoryView(simple, "nope", sources))
    }

    @Test
    fun `group levels follow sort_groups and files follow their order`() {
        val g = graph(
            """
            a:
              hosts: {h: }
              children:
                b:
                  hosts: {h: }
            c:
              hosts: {h: }
              vars: {ansible_group_priority: 0}
            """,
        )
        val sources = listOf(
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("b"), "b/1", "v" to "b1", order = 1),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("b"), "b/0", "v" to "b0", order = 0),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("a"), "a", "v" to "a"),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("c"), "c", "v" to "c"),
        )
        val view = PrecedenceEngine().inventoryView(g, "h", sources)!!
        assertEquals(listOf("c", "a", "b"), view.groups)
        assertEquals(listOf("c", "a", "b/0", "b/1"), view["v"]!!.chain())
    }

    @Test
    fun `several inventory sources are source-major for files and group-major for inline vars`() {
        val g = graph("g1:\n  hosts: {h: }\ng2:\n  hosts: {h: }\n")
        val sources = listOf(
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g2"), "src1/g2", "f" to "1", sourceIndex = 1),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g1"), "src1/g1", "f" to "1", sourceIndex = 1),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g2"), "src0/g2", "f" to "1", sourceIndex = 0),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g1"), "src0/g1", "f" to "1", sourceIndex = 0),
            src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g2"), "inline1/g2", "i" to "1", sourceIndex = 1),
            src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g1"), "inline1/g1", "i" to "1", sourceIndex = 1),
            src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g2"), "inline0/g2", "i" to "1", sourceIndex = 0),
            src(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g1"), "inline0/g1", "i" to "1", sourceIndex = 0),
        )
        val view = PrecedenceEngine().inventoryView(g, "h", sources)!!
        assertEquals(listOf("src0/g1", "src0/g2", "src1/g1", "src1/g2"), view["f"]!!.chain())
        assertEquals(listOf("inline0/g1", "inline1/g1", "inline0/g2", "inline1/g2"), view["i"]!!.chain())
    }

    @Test
    fun `merge reproduces ansible-core's grouping because merge_hash is not associative`() {
        val g = graph(
            """
            g2:
              children:
                g1:
                  hosts: {h: }
            """,
        )
        val sources = listOf(
            yamlSrc(VarLayer.INVENTORY_FILE_GROUP, VarOwner.All, "all", "assoc: {from_all: 1}"),
            yamlSrc(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g2"), "g2", "assoc: scalar"),
            yamlSrc(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("g1"), "g1", "assoc: {from_g1: 1}"),
        )
        val merged = PrecedenceEngine(HashBehaviour.MERGE).inventoryView(g, "h", sources)!!["assoc"]!!
        // groups_inventory combines g2 then g1 first (scalar, then dict → dict), and only then merges into all_inventory.
        assertEquals(listOf("from_all", "from_g1"), (merged.value as YMap).keys)
        assertEquals(listOf("all"), merged.mergedFrom.map { it.source.originId })
        assertEquals(listOf("g2", "all"), merged.shadowed.map { it.source.originId })

        val replaced = PrecedenceEngine(HashBehaviour.REPLACE).inventoryView(g, "h", sources)!!["assoc"]!!
        assertEquals(listOf("from_g1"), (replaced.value as YMap).keys)
        assertTrue(replaced.mergedFrom.isEmpty())
    }

    @Test
    fun `execution view layers play inputs around the inventory`() {
        val engine = PrecedenceEngine()
        val inventory = engine.inventoryView(simple, "h", ladder)!!
        val sources = listOf(
            src(VarLayer.EXTRA_VARS, VarOwner.All, "extra", "v" to "19", "only_extra" to "x"),
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/other", "v" to "2a", "d" to "other", order = 0),
            src(VarLayer.ROLE_DEFAULTS, VarOwner.All, "defaults/running", "v" to "2b", "d" to "running", order = 1),
            src(VarLayer.PLAY_VARS, VarOwner.All, "play", "v" to "12"),
            src(VarLayer.VARS_FILES, VarOwner.All, "vars_files", "v" to "13"),
            src(VarLayer.ROLE_VARS, VarOwner.All, "role_vars", "v" to "14"),
            src(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "block", "v" to "15a", order = 0),
            src(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "task", "v" to "15b", "tags" to "t", "when" to "w", order = 1),
            src(VarLayer.INCLUDE_VARS, VarOwner.All, "include_vars", "v" to "16"),
            src(VarLayer.SET_FACT_REGISTER, VarOwner.Host("h"), "set_fact", "v" to "17"),
            src(VarLayer.SET_FACT_REGISTER, VarOwner.Host("x"), "set_fact_other", "v" to "no"),
            src(VarLayer.ROLE_PARAMS, VarOwner.Group("g"), "role_params", "v" to "18"),
            src(VarLayer.ROLE_PARAMS, VarOwner.Group("other"), "role_params_other", "v" to "no"),
            src(VarLayer.INVENTORY_GROUP_VARS, VarOwner.Group("g"), "ignored-inventory", "v" to "no"),
        )
        val view = engine.executionView(inventory, sources)
        assertEquals(
            listOf(
                "defaults/other", "defaults/running",
                "L3all", "L3g", "L4", "L5", "L6", "L7", "L8", "L9", "L10",
                "play", "vars_files", "role_vars", "block", "task", "include_vars", "set_fact", "role_params", "extra",
            ),
            view["v"]!!.chain(),
        )
        assertEquals("running", (view["d"]!!.value as YScalar).text)
        assertNull(view["tags"], "task vars drop tags and when")
        assertNull(view["when"])
        assertNotNull(view["only_extra"])
        assertEquals(inventory.vars.keys, view.inventory.vars.keys)
        assertEquals(view.vars.keys, engine.executionView(simple, "h", ladder + sources)!!.vars.keys)
    }

    @Test
    fun `block and task vars combine shallowly even under merge`() {
        val engine = PrecedenceEngine(HashBehaviour.MERGE)
        val inventory = engine.inventoryView(simple, "h", emptyList())!!
        val sources = listOf(
            yamlSrc(VarLayer.PLAY_VARS, VarOwner.All, "play", "cfg: {a: 1}"),
            yamlSrc(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "block", "cfg: {b: 1}", order = 0),
            yamlSrc(VarLayer.BLOCK_TASK_VARS, VarOwner.All, "task", "cfg: {c: 1}", order = 1),
        )
        val cfg = engine.executionView(inventory, sources)["cfg"]!!
        // task |= block replaces cfg; the result then merges into the play vars.
        assertEquals(listOf("a", "c"), (cfg.value as YMap).keys)
        assertEquals(listOf("play"), cfg.mergedFrom.map { it.source.originId })
    }

    @Test
    fun `inline and adjacent sources are built from a parsed inventory and a directory tree`() {
        val g = graph(
            """
            all:
              vars: {a: 1}
              hosts:
                h: {hv: 1}
            web:
              hosts: {h: }
              vars: {w: 1}
            """,
        )
        val inline = InventoryVarSources.inline(g) { "hosts.yml#$it" }
        assertEquals(
            listOf(
                Triple(VarLayer.INVENTORY_FILE_GROUP, VarOwner.All, "hosts.yml#0"),
                Triple(VarLayer.INVENTORY_FILE_GROUP, VarOwner.Group("web"), "hosts.yml#0"),
                Triple(VarLayer.INVENTORY_FILE_HOST, VarOwner.Host("h"), "hosts.yml#0"),
            ),
            inline.map { Triple(it.layer, it.owner, it.originId) },
        )
        assertEquals(setOf("a"), inline[0].keyRanges.keys)

        val files = mapOf(
            "group_vars/all.yml" to "x: 1",
            "group_vars/web/b.yml" to "x: 3",
            "group_vars/web/a.yml" to "x: 2",
            "group_vars/orphan.yml" to "x: 99",
            "group_vars/empty.yml" to "",
            "host_vars/h.yml" to "x: 4",
        )
        val lister = DirectoryLister { path ->
            val prefix = if (path.isEmpty()) "" else "$path/"
            val children = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            if (children.isEmpty()) null else children.map { it.substringBefore('/') }.distinct().map { DirEntry(it, '/' in children.first { c -> c.startsWith(it) }.removePrefix(it)) }
        }
        val adjacent = InventoryVarSources.adjacent(g, lister, playbookAdjacent = true, load = { YamlText.parse(files.getValue(it)) })
        assertEquals(
            listOf(
                Triple(VarLayer.PLAYBOOK_GROUP_VARS_ALL, "group_vars/all.yml", 0),
                Triple(VarLayer.PLAYBOOK_GROUP_VARS, "group_vars/web/a.yml", 0),
                Triple(VarLayer.PLAYBOOK_GROUP_VARS, "group_vars/web/b.yml", 1),
                Triple(VarLayer.PLAYBOOK_HOST_VARS, "host_vars/h.yml", 0),
            ),
            adjacent.map { Triple(it.layer, it.originId, it.order) },
        )
        val x = PrecedenceEngine().inventoryView(g, "h", inline + adjacent)!!["x"]!!
        assertEquals(listOf("group_vars/all.yml", "group_vars/web/a.yml", "group_vars/web/b.yml", "host_vars/h.yml"), x.chain())
    }

    @Test
    fun `vars documents that contribute nothing produce no source`() {
        assertNull(VarSource.fromDocument(VarLayer.PLAY_VARS, VarOwner.All, "f", 0, YamlText.parse("")))
        assertNull(VarSource.fromDocument(VarLayer.PLAY_VARS, VarOwner.All, "f", 0, YamlText.parse("{}")))
        assertNull(VarSource.fromDocument(VarLayer.PLAY_VARS, VarOwner.All, "f", 0, YamlText.parse("- a")))
        val source = VarSource.fromDocument(VarLayer.PLAY_VARS, VarOwner.All, "f", 0, YamlText.parse("a: 1\nb: 2\na: 3\n"))!!
        assertEquals(listOf("a", "b"), source.entries.keys.toList())
        assertEquals("3", (source.entries["a"] as YScalar).text)
        assertEquals(setOf("a", "b"), source.keyRanges.keys)
    }

    @Test
    fun `precedence config parsing`() {
        val parsed = PrecedenceEntry.parse(" all_inventory, 'groups_inventory',bogus,plugins_by_group,all_inventory")
        assertEquals(
            listOf(PrecedenceEntry.ALL_INVENTORY, PrecedenceEntry.GROUPS_INVENTORY, PrecedenceEntry.ALL_INVENTORY),
            parsed.entries,
        )
        assertEquals(listOf("bogus", "plugins_by_group"), parsed.ignored)
        assertEquals(PrecedenceEntry.DEFAULT, PrecedenceEntry.parse(PrecedenceEntry.DEFAULT.joinToString(",") { it.configName }).entries)
    }

    @Test
    fun `layer helpers`() {
        assertEquals(VarLayer.INVENTORY_GROUP_VARS_ALL, VarLayer.groupVars("all", playbookAdjacent = false))
        assertEquals(VarLayer.PLAYBOOK_GROUP_VARS_ALL, VarLayer.groupVars("all", playbookAdjacent = true))
        assertEquals(VarLayer.INVENTORY_GROUP_VARS, VarLayer.groupVars("web", playbookAdjacent = false))
        assertEquals(VarLayer.PLAYBOOK_GROUP_VARS, VarLayer.groupVars("web", playbookAdjacent = true))
        assertEquals(VarLayer.PLAYBOOK_HOST_VARS, VarLayer.hostVars(playbookAdjacent = true))
        assertEquals(VarOwner.All, VarOwner.group("all"))
        assertTrue(VarLayer.entries.filter { it.isInventoryLevel }.all { it.level in 3..10 || it == VarLayer.MOLECULE_INVENTORY })
    }
}
