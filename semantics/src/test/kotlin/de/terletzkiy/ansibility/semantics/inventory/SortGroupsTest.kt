package de.terletzkiy.ansibility.semantics.inventory

import de.terletzkiy.ansibility.semantics.testutil.YamlText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Expected values were produced with ansible-core 2.21.4 (`Group.depth`, `Host.groups`, `sort_groups`). */
class SortGroupsTest {
    private val graph = YamlInventoryParser.parse(
        YamlText.parse(
            """
            d:
              children:
                c:
            c:
              hosts: {h: }
            a:
              children:
                b:
                  children:
                    c:
            B:
              hosts: {h: }
            lower:
              hosts: {h: }
              vars: {ansible_group_priority: 0}
            """.trimIndent(),
        ),
    )

    @Test
    fun `depth is the longest path from all`() {
        val depths = graph.groups.mapValues { it.value.depth }
        assertEquals(mapOf("all" to 0, "ungrouped" to 1, "d" to 1, "c" to 3, "a" to 1, "b" to 2, "B" to 1, "lower" to 1), depths)
        assertEquals(listOf("d", "b"), graph.group("c")!!.parents)
        assertEquals(listOf("ungrouped", "d", "a", "B", "lower"), graph.group("all")!!.children)
    }

    @Test
    fun `host groups are recorded in ansible-core's insertion order`() {
        assertEquals(listOf("d", "c", "b", "a", "B", "lower", "all"), graph.host("h")!!.groups)
    }

    @Test
    fun `sort_groups orders by depth, priority, then name by code point`() {
        assertEquals(listOf("all", "lower", "B", "a", "d", "b", "c"), graph.sortedGroupsOf("h", includeAll = true).map { it.name })
        assertEquals(listOf("lower", "B", "a", "d", "b", "c"), graph.sortedGroupsOf("h").map { it.name })
    }

    @Test
    fun `priority beats name but not depth`() {
        val g = YamlInventoryParser.parse(
            YamlText.parse(
                """
                zeta:
                  hosts: {h: }
                  vars: {ansible_group_priority: 0}
                alpha:
                  hosts: {h: }
                  vars: {ansible_group_priority: 7}
                  children:
                    child:
                      hosts: {h: }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("zeta", "alpha", "child"), g.sortedGroupsOf("h").map { it.name })
    }
}
