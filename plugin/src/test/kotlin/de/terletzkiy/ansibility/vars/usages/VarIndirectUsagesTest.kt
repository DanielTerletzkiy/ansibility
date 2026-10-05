package de.terletzkiy.ansibility.vars.usages

/**
 * Reads by name (FU2, `UseEntry.indirect`) and the outside names of braced implicit expressions in Find Usages
 * (plan amendment FU, F1.10/F1.11), on `testData/vars/site` plus a template and a task file written by the test:
 * `hostvars[h].web_port`, `vars['web_port']` and `lookup('vars', 'web_port')` start the search of `web_port`, appear
 * in their own groups and are highlighted as reads; `that: "{{ web_port }} == web_limit"` reads `web_limit` too.
 */
class VarIndirectUsagesTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyVarsData("site")
        createFile(
            INDIRECT,
            """
            a {{ hostvars[h].web_port }}
            b {{ vars['web_port'] }}
            c {{ lookup('vars', 'web_port') }}
            d {{ hostvars[h].item }}
            e {{ web_port }}
            """.trimIndent() + "\n",
        )
        createFile(
            BRACED,
            """
            ---
            - name: Check
              ansible.builtin.assert:
                that: "{{ web_port }} == web_limit"
            - name: Not evaluated
              ansible.builtin.debug:
                msg: "{{ web_port }} == web_limit"
            - name: Loop
              ansible.builtin.debug:
                msg: "{{ item }} {{ hostvars[h].item }}"
              when: vars['item'] > 0
              loop: [1, 2]
            - name: Both
              ansible.builtin.debug:
                msg: "{{ hostvars[h].web_port }} {{ web_port }}"
            """.trimIndent() + "\n",
        )
    }

    fun testEveryIndirectFormStartsTheSearchOfItsMember() {
        for (line in 1..3) {
            at(INDIRECT, line, "web_port", 2)
            val symbol = targetAtCaret()
            assertEquals("line $line", "web_port", symbol?.name)
            assertEquals("line $line", "Variable web_port · site", symbol?.presentableText)
        }
        at(INDIRECT, 2, "vars[", 1)
        assertEquals("the root of vars['web_port'] stays itself", "vars", targetAtCaret()?.name)
        at(INDIRECT, 4, "item", 1)
        assertNull("a hostvars member called item is some host's, and item is never searched across the root", targetAtCaret())
    }

    fun testIndirectReadsHaveTheirOwnGroups() {
        at(VarFindUsagesTest.TEMPLATE, 1, "web_port")
        val found = findUsagesViaActionWithGroups().map { (usage, group) -> describeUsages(listOf(usage)).single() to group }
        assertEquals((VarFindUsagesTest.WEB_PORT + INDIRECT_READS + BRACED_READS).sorted(), found.map { it.first }.sorted())
        val groups = found.toMap()
        assertEquals("Read: through hostvars", groups.getValue("$INDIRECT:1:web_port R"))
        assertEquals("Read: by name (vars)", groups.getValue("$INDIRECT:2:web_port R"))
        assertEquals("Read: by name (vars)", groups.getValue("$INDIRECT:3:web_port R"))
        assertEquals("a direct read next to them", "Read: template", groups.getValue("$INDIRECT:5:web_port R"))
        assertEquals("the braced part of a condition", "Read: condition", groups.getValue("$BRACED:4:web_port R"))

        val tree = myFixture.getUsageViewTreeTextRepresentation(targetAtCaret()!!)
        val order = listOf("Read: by name (vars) (2)", "Read: template (3)", "Read: through hostvars (1)", "Set: group_vars · dev (1)")
        val positions = order.map { group -> tree.indexOf(group).also { assertTrue("group '$group' in:\n$tree", it >= 0) } }
        assertEquals("reads by name are reads, before the writes:\n$tree", positions.sorted(), positions)
    }

    fun testHighlightingMarksIndirectReadsAsReads() {
        val expected = listOf("1:web_port", "2:web_port", "3:web_port", "5:web_port")
        at(INDIRECT, 5, "web_port")
        val fromDirect = highlightHandlerAtCaret()!!
        assertEquals(expected, fromDirect.readUsages.map { lineAndText(INDIRECT, it) }.sorted())
        assertEmpty(fromDirect.writeUsages)
        at(INDIRECT, 2, "web_port", 2)
        val fromIndirect = highlightHandlerAtCaret()
        assertInstanceOf(fromIndirect, VarHighlightUsagesHandler::class.java)
        assertEquals("the same marks from vars['web_port']", expected, fromIndirect!!.readUsages.map { lineAndText(INDIRECT, it) }.sorted())
    }

    fun testOutsideNamesOfABracedConditionAreReads() {
        at(BRACED, 4, "web_limit", 2)
        assertEquals("web_limit", targetAtCaret()?.name)
        val groups = findUsagesViaActionWithGroups().map { (usage, group) -> describeUsages(listOf(usage)).single() to group }
        assertEquals(listOf("$BRACED:4:web_limit R" to "Read: condition"), groups)
        assertEquals(listOf("4:web_limit"), highlightHandlerAtCaret()!!.readUsages.map { lineAndText(BRACED, it) })

        at(BRACED, 7, "web_limit", 2)
        assertNull("a msg: value is rendered, never evaluated: web_limit is text there", targetAtCaret())
    }

    fun testALoopVariableNeverCountsHostvarsMembers() {
        at(BRACED, 10, "item", 1)
        assertEquals("Loop variable item · site", targetAtCaret()?.presentableText)
        val groups = findUsagesViaActionWithGroups().map { (usage, group) -> describeUsages(listOf(usage)).single() to group }.sortedBy { it.first }
        assertEquals(
            "the direct use and vars['item'], never hostvars[h].item",
            listOf("$BRACED:10:item R" to "Read: task", "$BRACED:11:item R" to "Read: by name (vars)", "$BRACED:12:loop W" to "Set: loop"),
            groups,
        )
        assertEquals(listOf("10:item", "11:item"), highlightHandlerAtCaret()!!.readUsages.map { lineAndText(BRACED, it) }.sorted())
    }

    /**
     * The platform asks for a usage's group with its element only, and a YAML scalar is one leaf: a scalar holding a
     * direct and an indirect read is grouped by the direct one (both usages are listed, on one line).
     */
    fun testAScalarWithADirectAndAnIndirectReadIsGroupedByTheDirectOne() {
        at(BRACED, 15, "web_port }}", 1)
        val groups = findUsagesViaActionWithGroups().map { (usage, group) -> describeUsages(listOf(usage)).single() to group }
        assertEquals(listOf("Read: task", "Read: task"), groups.filter { it.first == "$BRACED:15:web_port R" }.map { it.second })
    }

    companion object {
        const val INDIRECT = "site/roles/web/templates/indirect.j2"
        const val BRACED = "site/roles/web/tasks/braced.yml"

        val INDIRECT_READS = listOf("$INDIRECT:1:web_port R", "$INDIRECT:2:web_port R", "$INDIRECT:3:web_port R", "$INDIRECT:5:web_port R")
        val BRACED_READS = listOf("$BRACED:4:web_port R", "$BRACED:7:web_port R", "$BRACED:15:web_port R", "$BRACED:15:web_port R")
    }
}
