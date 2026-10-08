package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.RuntimeMarkerKind
import de.terletzkiy.ansibility.context.AnsibleWorkspaceImpl
import de.terletzkiy.ansibility.inspections.spec.RoleInputs
import de.terletzkiy.ansibility.model.effective.ExecutionSources
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport
import de.terletzkiy.ansibility.typeflow.ChainResolver

/**
 * Plan amendment R20, D157: analysis of production files never counts what only Molecule scenarios define (a converge
 * play's `set_fact`, `register`, loop variables and play vars never run in a production play), whatever the
 * navigation setting; analysis of a Molecule file still sees them.
 */
class MoleculeOnlyDefinitionsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        add("ansible.cfg", "[defaults]\n")
        add("environments/prod/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1:\n")
        add("site.yml", "- hosts: web\n  roles: [web]\n")
        add("roles/web/defaults/main.yml", "web_port: 80\n")
        add(
            "roles/web/meta/argument_specs.yml",
            """
            ---
            argument_specs:
              main:
                short_description: web
                options:
                  web_port:
                    type: int
            """,
        )
        add(
            TASKS,
            """
            ---
            - name: Use
              ansible.builtin.debug:
                msg: "{{ web_port }} {{ only_in_tests }} {{ test_registered }} {{ test_item }} {{ test_play_var }}"
            """,
        )
        add("roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        add(
            CONVERGE,
            """
            ---
            - name: Converge
              hosts: all
              vars:
                test_play_var: 1
              roles: [web]
              tasks:
                - name: Set
                  ansible.builtin.set_fact:
                    only_in_tests: 1
                - name: Register
                  ansible.builtin.command: "true"
                  register: test_registered
                - name: Loop
                  ansible.builtin.debug:
                    msg: "{{ test_item }} {{ only_in_tests }}"
                  loop: [1]
                  loop_control:
                    loop_var: test_item
            """,
        )
        AnsibleWorkspaceImpl.getInstance(project)!!.structureChanged()
    }

    override fun tearDown() {
        try {
            SettingsTestSupport.resetAll(project)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun add(path: String, text: String) {
        myFixture.addFileToProject(path, text.trimIndent() + "\n")
    }

    private fun vf(path: String): VirtualFile = myFixture.findFileInTempDir(path) ?: error("missing $path")

    private fun root(): AnsibleRoot = runReadActionBlocking { AnsibleWorkspace.getInstance(project).roots().single() }

    /** The ANS-V003 findings of [path] as `KIND name`. */
    private fun findings(path: String): List<String> = runReadActionBlocking {
        val file = PsiManager.getInstance(project).findFile(vf(path))!!
        val context = AnsibleWorkspace.getInstance(project).contextOf(vf(path))!!
        PossiblyUndefined(project, file, context).findings().map { "${it.kind} ${it.name}" }
    }

    private fun showInNavigation(show: Boolean) {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings(showInNavigation = show)) }
    }

    fun testAVariableOnlyAConvergePlaySetsIsPossiblyUndefinedInTheRole() {
        for (show in listOf(false, true)) {
            showInNavigation(show)
            assertEquals(
                "show=$show",
                listOf("MISSING only_in_tests", "MISSING test_registered", "MISSING test_item", "MISSING test_play_var"),
                findings(TASKS),
            )
        }
    }

    fun testInsideConvergeTheMoleculeDefinitionsCount() {
        assertEmpty(findings(CONVERGE))
        runReadActionBlocking {
            val role = RoleRegistry.getInstance(project).role(root(), "web")!!
            val fromConverge = UndefinedRules(project, root(), vf(CONVERGE))
            assertTrue(fromConverge.setByRoleTasks(role, "only_in_tests"))
            assertTrue(fromConverge.setByRoleTasks(role, "test_registered"))
            assertTrue(fromConverge.isLoopVariable("test_item"))
            val fromTasks = UndefinedRules(project, root(), vf(TASKS))
            assertFalse(fromTasks.setByRoleTasks(role, "only_in_tests"))
            assertFalse(fromTasks.setByRoleTasks(role, "test_registered"))
            assertFalse(fromTasks.isLoopVariable("test_item"))
            assertFalse("no file is production", UndefinedRules(project, root(), null).setByRoleTasks(role, "only_in_tests"))
        }
    }

    fun testRuntimeMarkersOfAProductionPlayLeaveTheConvergePlayOut() {
        runReadActionBlocking {
            val plays = PlayGraph.getInstance(project).playsApplying(root(), "web")
            val site = plays.single { it.file == vf("site.yml") }
            val converge = plays.single { it.file == vf(CONVERGE) }
            val sources = ExecutionSources.getInstance(project)
            assertEmpty("the Explain chain of a production host", sources.runtimeMarkers(root(), sources.inputs(root(), site, "web"), "only_in_tests"))
            val inConverge = sources.runtimeMarkers(root(), sources.inputs(root(), converge, "web"), "only_in_tests")
            assertEquals(listOf(RuntimeMarkerKind.SET_FACT), inConverge.map { it.kind })
        }
    }

    fun testTheWitnessOfAProductionHostIgnoresMoleculeTasks() {
        runReadActionBlocking {
            val report = de.terletzkiy.ansibility.context.host.witness.DefinitionWitnesses.getInstance(project).ofRole(root(), "web").report("only_in_tests")
            assertEquals(listOf("web1"), report.missingHosts.map { it.host })
            assertEmpty(report.runtimeOn)
        }
    }

    fun testRoleInputsCountWhatOnlyMoleculeSets() {
        val inputs = runReadActionBlocking {
            val role = RoleRegistry.getInstance(project).role(root(), "web")!!
            RoleInputs(project, root(), role).of(PsiManager.getInstance(project).findFile(vf(TASKS))!!)!!
        }
        assertEquals(listOf("only_in_tests", "test_registered", "test_item", "test_play_var"), inputs.map { it.name })
        assertEquals("the converge value does not type the fix", listOf("type" to "raw"), inputs.single { it.name == "test_play_var" }.fields)
    }

    /** A `molecule` directory inside the role's own `tasks/` holds production tasks (the classifier's rule), not a scenario. */
    fun testASetFactInTheRolesOwnTasksMoleculeDirectoryCounts() {
        add("roles/web/tasks/molecule/setup.yml", "---\n- name: Ready\n  ansible.builtin.set_fact:\n    web_ready: true\n")
        add(
            "roles/web/tasks/check.yml",
            """
            ---
            - name: Setup
              ansible.builtin.include_tasks: molecule/setup.yml
            - name: Use
              ansible.builtin.debug:
                msg: "{{ web_ready }}"
            """,
        )
        runReadActionBlocking {
            val role = RoleRegistry.getInstance(project).role(root(), "web")!!
            assertTrue(UndefinedRules(project, root(), vf(TASKS)).setByRoleTasks(role, "web_ready"))
        }
        assertFalse(findings("roles/web/tasks/check.yml").contains("MISSING web_ready"))
    }

    fun testTypeChainsOfAProductionFileSkipMoleculeValues() {
        runReadActionBlocking {
            val roleDir = vf("roles/web")
            assertNull(ChainResolver(project, root(), roleDir, vf(TASKS)).definitions("test_play_var"))
            assertEquals(1, ChainResolver(project, root(), roleDir, vf(CONVERGE)).definitions("test_play_var")?.size)
            assertEquals("the production default", 1, ChainResolver(project, root(), roleDir, vf(TASKS)).definitions("web_port")?.size)
        }
    }

    private companion object {
        const val TASKS = "roles/web/tasks/main.yml"
        const val CONVERGE = "roles/web/molecule/default/converge.yml"
    }
}
