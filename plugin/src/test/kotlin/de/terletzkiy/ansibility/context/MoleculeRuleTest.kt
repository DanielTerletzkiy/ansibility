package de.terletzkiy.ansibility.context

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.VarDefKind
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * The shared Molecule rule (plan amendment R20, D153–D157, [MoleculeVisibility]): which files and definitions are
 * Molecule content, judged by path below the role (or root) and, for `include_vars`, by cause; and who sees them.
 */
class MoleculeRuleTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        add("ansible.cfg", "[defaults]\n")
        add("environments/prod/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1:\n")
        add(
            "site.yml",
            """
            - hosts: web
              roles: [web]
              tasks:
                - name: Shared vars
                  ansible.builtin.include_vars: shared/both.yml
            """,
        )
        add("roles/web/defaults/main.yml", "web_port: 80\n")
        add("roles/web/tasks/main.yml", "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ web_port }}\"\n")
        add("roles/web/files/molecule/notes.yml", "file_in_files: 1\n")
        add("roles/web/tasks/molecule/setup.yml", "- name: Own tasks\n  ansible.builtin.debug: {}\n")
        add("roles/web/templates/molecule/molecule.yml.j2", "driver: {{ web_port }}\n")
        add(
            "roles/web/molecule/default/molecule.yml",
            """
            ---
            driver:
              name: default
            provisioner:
              inventory:
                group_vars:
                  all:
                    web_port: 8080
            """,
        )
        add(
            "roles/web/molecule/default/converge.yml",
            """
            ---
            - name: Converge
              hosts: all
              vars:
                web_port: 9090
              tasks:
                - name: Test vars
                  ansible.builtin.include_vars: ../../../../shared/extra.yml
                - name: Shared vars
                  ansible.builtin.include_vars: ../../../../shared/both.yml
              roles: [web]
            """,
        )
        add("roles/web/molecule/requirements.yml", "---\ncollections: []\n")
        add("roles/web/molecule/default/Dockerfile.j2", "FROM {{ item.image }}\n")
        add("roles/web/molecule/notes.txt", "notes\n")
        add("roles/molecule/tasks/main.yml", "- name: A role named molecule\n  ansible.builtin.debug: {}\n")
        add("roles/molecule/defaults/main.yml", "web_port: 1\n")
        add("molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        add("shared/extra.yml", "extra_only_in_tests: 1\n")
        add("shared/both.yml", "both_loaded: 1\n")
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

    private fun isMoleculeFile(path: String): Boolean = runReadActionBlocking { MoleculeVisibility.isMoleculeFile(project, vf(path)) }

    private fun definitions(name: String): List<VarDefinition> = runReadActionBlocking { VarService.getInstance(project).symbol(root(), name).definitions }

    private fun relative(definition: VarDefinition): String = definition.location.file.path.substringAfter("/src/")

    /** The definitions of [name] as `path kind molecule?`. */
    private fun judged(name: String): List<String> = runReadActionBlocking {
        definitions(name).map { "${relative(it)} ${it.kind} ${MoleculeVisibility.isMolecule(project, root(), it)}" }
    }

    fun testMoleculeFilesAreJudgedByTheirPathBelowTheRoleOrRoot() {
        for (path in listOf(
            "roles/web/molecule/default/molecule.yml",
            "roles/web/molecule/default/converge.yml",
            "roles/web/molecule/requirements.yml",
            "roles/web/molecule/default/Dockerfile.j2",
            "roles/web/molecule/notes.txt",
            "molecule/default/molecule.yml",
        )) {
            assertTrue(path, isMoleculeFile(path))
        }
        for (path in listOf("roles/web/tasks/main.yml", "roles/web/defaults/main.yml", "site.yml", "shared/extra.yml", "environments/prod/hosts.yml")) {
            assertFalse(path, isMoleculeFile(path))
        }
    }

    /** As the classifier: inside a role only `roles/<role>/molecule/…` is Molecule; deeper `molecule` dirs hold role files. */
    fun testAMoleculeDirectoryInsideARoleSubdirectoryHoldsProductionFiles() {
        for (path in listOf("roles/web/tasks/molecule/setup.yml", "roles/web/templates/molecule/molecule.yml.j2", "roles/web/files/molecule/notes.yml")) {
            assertFalse(path, isMoleculeFile(path))
        }
        val kinds = runReadActionBlocking {
            listOf("roles/web/tasks/molecule/setup.yml", "roles/web/templates/molecule/molecule.yml.j2")
                .map { AnsibleWorkspace.getInstance(project).contextOf(vf(it))!!.kind.name }
        }
        assertEquals("the classifier agrees", listOf("ROLE_TASKS", "ROLE_TEMPLATE"), kinds)
    }

    fun testARoleNamedMoleculeIsAProductionRole() {
        assertFalse(isMoleculeFile("roles/molecule/tasks/main.yml"))
        assertFalse(isMoleculeFile("roles/molecule/defaults/main.yml"))
        assertTrue("its definitions are production ones", judged("web_port").contains("roles/molecule/defaults/main.yml ROLE_DEFAULT false"))
    }

    fun testMoleculeDefinitionsByKindLocationAndCause() {
        assertEquals(
            listOf(
                "roles/molecule/defaults/main.yml ROLE_DEFAULT false",
                "roles/web/defaults/main.yml ROLE_DEFAULT false",
                "roles/web/molecule/default/converge.yml PLAY_VARS true",
                "roles/web/molecule/default/molecule.yml MOLECULE_INVENTORY true",
            ),
            judged("web_port"),
        )
        assertEquals("a production file only a converge play loads", listOf("shared/extra.yml INCLUDE_VARS true"), judged("extra_only_in_tests"))
        assertEquals("a production play loads it too", listOf("shared/both.yml INCLUDE_VARS false"), judged("both_loaded"))
    }

    fun testNavigationShowsMoleculeOnlyFromMoleculeFilesUnlessTheSettingIsOn() {
        val tasks = vf("roles/web/tasks/main.yml")
        val converge = vf("roles/web/molecule/default/converge.yml")
        runReadActionBlocking {
            assertFalse("off by default", MoleculeVisibility.showInNavigation(project))
            assertFalse(MoleculeVisibility.shows(project, tasks))
            assertFalse("no origin counts as production", MoleculeVisibility.shows(project, null))
            assertTrue(MoleculeVisibility.shows(project, converge))
            val symbol = VarService.getInstance(project).symbol(root(), "web_port")
            assertEquals(
                listOf("roles/molecule/defaults/main.yml", "roles/web/defaults/main.yml"),
                MoleculeVisibility.visible(project, root(), tasks, symbol).definitions.map(::relative),
            )
            assertEquals(4, MoleculeVisibility.visible(project, root(), converge, symbol).definitions.size)
        }
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings(showInNavigation = true)) }
        runReadActionBlocking {
            assertTrue(MoleculeVisibility.shows(project, tasks))
            assertTrue(MoleculeVisibility.shows(project, null))
            assertEquals(4, MoleculeVisibility.visible(project, root(), tasks, VarService.getInstance(project).symbol(root(), "web_port")).definitions.size)
        }
    }

    fun testAnalysisIgnoresMoleculeFromProductionFilesWhateverTheSetting() {
        val tasks = vf("roles/web/tasks/main.yml")
        val converge = vf("roles/web/molecule/default/converge.yml")
        for (show in listOf(false, true)) {
            AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings(showInNavigation = show)) }
            runReadActionBlocking {
                val symbol = VarService.getInstance(project).symbol(root(), "web_port")
                assertEquals("show=$show", listOf(VarDefKind.ROLE_DEFAULT, VarDefKind.ROLE_DEFAULT), MoleculeVisibility.forAnalysis(project, root(), tasks, symbol).definitions.map { it.kind })
                assertEquals("show=$show", 2, MoleculeVisibility.forAnalysis(project, root(), null, symbol).definitions.size)
                assertSame("show=$show", symbol, MoleculeVisibility.forAnalysis(project, root(), converge, symbol))
                assertEquals("spec bindings are kept", symbol.specBindings, MoleculeVisibility.withoutMolecule(project, root(), symbol).specBindings)
            }
        }
    }
}
