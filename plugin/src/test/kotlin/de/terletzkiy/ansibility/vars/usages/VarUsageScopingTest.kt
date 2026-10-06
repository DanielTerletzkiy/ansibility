package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.GTDUOutcome
import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.RootKind

/**
 * Which occurrences one search covers (plan amendment FU, D-FU5; F1.10 "Scope", "Runtime names"), on
 * `testData/usages/runtime` (roles `alpha` and `beta` and the playbook `site.yml` each register their own `result`;
 * `alpha` loops over `alpha_servers` as `server` and renders `servers.j2` in that loop; its molecule scenario builds
 * from a `Dockerfile.j2`, and `unbound.j2` reads `item` though nothing renders it) and `testData/usages/nested`
 * (a project root whose `environments/` defines `db_user`, read by the nested playbook root `danger_zone/database`).
 */
class VarUsageScopingTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyUsagesData("runtime")
        copyUsagesData("nested")
    }

    // ------------------------------------------------------------------------------------------------ runtime names

    /** Acceptance 5: `register: result` in one role lists that role's uses first, the other owners in their own groups. */
    fun testARegisteredNameListsItsOwnRoleFirstAndOtherOwnersInTheirOwnGroups() {
        at(ALPHA_TASKS, 4, "result")
        val symbol = targetAtCaret()!!
        assertEquals("result", symbol.name)
        assertEquals(
            listOf(
                "runtime/roles/alpha/tasks/main.yml:29:result R",
                "runtime/roles/alpha/tasks/main.yml:4:result W",
                "runtime/roles/alpha/tasks/main.yml:7:result R",
                "runtime/roles/alpha/tasks/main.yml:8:result R",
                "runtime/roles/alpha/templates/alpha.conf.j2:1:result R",
                "runtime/roles/beta/tasks/main.yml:4:result W",
                "runtime/roles/beta/tasks/main.yml:7:result R",
                "runtime/site.yml:10:result W",
                "runtime/site.yml:13:result R",
            ),
            describeUsages(findUsagesViaAction()),
        )
        val tree = myFixture.getUsageViewTreeTextRepresentation(symbol)
        val groups = listOf(
            "Read: condition (1)", "Read: task (1)", "Read: template (1)", "Read: templated value (1)", "Set: register (1)",
            "Shared name · playbook site.yml (2)", "Shared name · role beta (2)",
        )
        val positions = groups.map { group -> tree.indexOf(group).also { assertTrue("group '$group' in:\n$tree", it >= 0) } }
        assertEquals("the role's own groups first:\n$tree", positions.sorted(), positions)
    }

    fun testFromAnotherOwnerItsOwnUsesComeFirst() {
        at(BETA_TASKS, 7, "result")
        val tree = myFixture.getUsageViewTreeTextRepresentation(targetAtCaret()!!)
        assertTrue(tree, tree.contains("Read: debug var (1)"))
        assertTrue(tree, tree.contains("Set: register (1)"))
        assertTrue(tree, tree.contains("Shared name · role alpha (5)"))
    }

    // ------------------------------------------------------------------------------------------------ loop variables

    fun testALoopVariableCoversItsTaskAndTheTemplatesItRenders() {
        val expected = listOf(
            "runtime/roles/alpha/tasks/main.yml:16:server R",
            "runtime/roles/alpha/tasks/main.yml:19:server W",
            "runtime/roles/alpha/templates/servers.j2:1:server R",
            "runtime/roles/alpha/templates/servers.j2:1:server R",
        )
        at(ALPHA_TASKS, 16, "server.name")
        assertEquals("from a use in the task (never the task outside the loop, line 22)", expected, describeUsages(findUsagesViaAction()))
        at(ALPHA_TASKS, 19, "server")
        assertEquals("from the loop_var value", expected, describeUsages(findUsagesViaAction()))
        at(SERVERS_TEMPLATE, 1, "server.port")
        assertEquals("from the rendered template", expected, describeUsages(findUsagesViaAction()))
    }

    fun testItemCoversOnlyItsOwnLoop() {
        at(ALPHA_TASKS, 25, "item")
        assertEquals(
            listOf("runtime/roles/alpha/tasks/main.yml:25:item R", "runtime/roles/alpha/tasks/main.yml:26:loop W"),
            describeUsages(findUsagesViaAction()),
        )
    }

    /** `item` where no task loop binds it: molecule's platform loop of a `Dockerfile.j2`, a template nothing renders. */
    fun testItemWithoutATaskLoopStaysInItsFile() {
        at(DOCKERFILE, 1, "item.image")
        val molecule = targetAtCaret()!!
        assertEquals("Loop variable item · runtime", molecule.presentableText)
        assertEquals(listOf("$DOCKERFILE:1:item R", "$DOCKERFILE:2:item R"), describeUsages(findUsagesViaAction()))
        at(UNBOUND_TEMPLATE, 1, "item")
        assertFalse("one target per file", molecule == targetAtCaret())
        assertEquals("never the root's other item uses", listOf("$UNBOUND_TEMPLATE:1:item R"), describeUsages(findUsagesViaAction()))
        assertEquals(
            "the target navigates to the first use",
            "$UNBOUND_TEMPLATE:1",
            describe(runReadActionBlocking { VarUsageSearch.primaryDeclaration(project, targetAtCaret()!!) }!!),
        )
    }

    // ------------------------------------------------------------------------------------------------ Jinja locals

    fun testAJinjaLocalStaysInItsFile() {
        val expected = listOf(
            "runtime/roles/alpha/templates/alpha.conf.j2:2:greeting R",
            "runtime/roles/alpha/templates/alpha.conf.j2:2:greeting R",
            "runtime/roles/alpha/templates/alpha.conf.j2:2:greeting W",
        )
        at(ALPHA_TEMPLATE, 2, "greeting }}")
        assertEquals("from a use", expected, describeUsages(findUsagesViaAction()))
        at(ALPHA_TEMPLATE, 2, "greeting =")
        assertEquals("from the binding", expected, describeUsages(findUsagesViaAction()))
        assertEquals("Template local greeting · alpha.conf.j2", targetAtCaret()!!.presentableText)
    }

    // ------------------------------------------------------------------------------------------------ nested roots

    /** F1.10 "Scope": the project root's inventory reaches the nested playbook roots that read it. */
    fun testInventoryDefinitionsReachTheNestedPlaybookRoots() {
        val nested = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots() }.single { it.dir == vf("nested/danger_zone/database") }
        assertEquals(RootKind.NESTED_PLAYBOOK, nested.kind)
        val expected = listOf(
            "nested/danger_zone/database/roles/clone/tasks/main.yml:4:db_user R",
            "nested/environments/prod/group_vars/all/vars.yml:2:db_user W",
            "nested/site.yml:7:db_user R",
        )
        at(NESTED_VARS, 2, "db_user")
        assertEquals(expected, describeUsages(findUsagesViaAction()))
        at(NESTED_SITE, 7, "db_user")
        assertEquals("from the project root's use", expected, describeUsages(findUsagesViaAction()))
    }

    // ------------------------------------------------------------------------------------------------ Ctrl+B (D-FU2, D-FU4)

    /** D-FU2: before, Ctrl+B on alpha's `alpha_fact` went to beta's same-named `set_fact`; now it shows the usages. */
    fun testCtrlBOnASetFactKeyShowsUsages() {
        assertEquals("no role declares alpha_fact", GTDUOutcome.SU, gtdu(at(ALPHA_TASKS, 29, "alpha_fact")))
        assertEquals(emptyList<String>(), gotoTargets(ALPHA_TASKS, offsetAt(ALPHA_TASKS, 29, "alpha_fact", 1)).map(::describe))
        assertEquals(
            listOf("runtime/roles/alpha/tasks/main.yml:29:alpha_fact W", "runtime/roles/beta/tasks/main.yml:10:alpha_fact W"),
            describeUsages(findUsagesViaAction()),
        )
        val tree = myFixture.getUsageViewTreeTextRepresentation(targetAtCaret()!!)
        assertTrue(tree, tree.contains("Set: set_fact (1)") && tree.contains("Shared name · role beta (1)"))
        assertEquals("a set_fact key is a definition even when alpha's defaults declare the name", GTDUOutcome.SU, gtdu(at(ALPHA_TASKS, 30, "alpha_declared")))
        assertEquals(emptyList<String>(), gotoTargets(ALPHA_TASKS, offsetAt(ALPHA_TASKS, 30, "alpha_declared", 1)).map(::describe))
    }

    fun testARegisterValueStartsFindUsagesWhileCtrlBIsUnchanged() {
        val offset = at(SITE, 10, "result")
        assertEquals("result", targetAtCaret()?.name)
        assertFalse("Ctrl+B keeps its behaviour on a register: value (D-FU4)", gtdu(offset) == GTDUOutcome.SU)
        assertTrue(describeUsages(findUsagesViaAction()).contains("runtime/site.yml:13:result R"))
    }

    companion object {
        const val ALPHA_TASKS = "runtime/roles/alpha/tasks/main.yml"
        const val BETA_TASKS = "runtime/roles/beta/tasks/main.yml"
        const val ALPHA_TEMPLATE = "runtime/roles/alpha/templates/alpha.conf.j2"
        const val SERVERS_TEMPLATE = "runtime/roles/alpha/templates/servers.j2"
        const val UNBOUND_TEMPLATE = "runtime/roles/alpha/templates/unbound.j2"
        const val DOCKERFILE = "runtime/roles/alpha/molecule/default/Dockerfile.j2"
        const val SITE = "runtime/site.yml"
        const val NESTED_VARS = "nested/environments/prod/group_vars/all/vars.yml"
        const val NESTED_SITE = "nested/site.yml"
    }
}
