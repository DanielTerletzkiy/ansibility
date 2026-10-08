package de.terletzkiy.ansibility.completion.jinja

import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.CONVERGE
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.ROOT
import de.terletzkiy.ansibility.context.MoleculeNavigationFixture.TASKS
import de.terletzkiy.ansibility.settings.SettingsTestSupport

/**
 * Plan amendment R20, D153/D154: Jinja completion outside a `molecule/` folder offers no Molecule inventory variable,
 * Molecule play variable or Molecule group while "Show Molecule in navigation and search" is off (the default); inside
 * a Molecule file it offers them whatever the setting.
 */
class MoleculeCompletionVisibilityTest : JinjaCompletionTestCase() {
    override fun setUp() {
        super.setUp()
        MoleculeNavigationFixture.create { path, text -> myFixture.tempDirFixture.createFile(path, text) }
        refreshRoots()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
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

    private fun show(show: Boolean) = MoleculeNavigationFixture.showInNavigation(project, show)

    /** The plugin's Jinja candidates for [typed] (with `<caret>`) written in place of `{{ shared_value }}`. */
    private fun complete(path: String, line: Int, typed: String): List<String> =
        try {
            strings(completeAfterEdit(path, line, "{{ shared_value }}", typed))
        } finally {
            reset(path)
        }

    fun testMoleculeVariablesOnlyWhereMoleculeIsShown() {
        assertDoesntContain("off, in the role's tasks", complete(TASKS, 4, "{{ only_<caret> }}"), "only_in_tests")
        assertContainsElements("off, in converge.yml", complete(CONVERGE, 15, "{{ only_<caret> }}"), "only_in_tests")
        show(true)
        assertContainsElements("on, in the role's tasks", complete(TASKS, 4, "{{ only_<caret> }}"), "only_in_tests")
    }

    /**
     * Member shapes (`{{ web_cfg.| }}`) come from the values the request sees: outside Molecule, while Molecule is
     * hidden, a Molecule play's fixture value (which carries the role's name and so would rank first) never types it.
     */
    fun testMemberShapesFollowTheMoleculeView() {
        createFile("$ROOT/environments/prod/group_vars/web.yml", "---\nweb_cfg:\n  listen: 80\n  tls: true\n")
        createFile(PREPARE, PREPARE_TEXT)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val off = complete(TASKS, 4, "{{ web_cfg.<caret> }}")
        assertContainsElements("off, in the role's tasks: the production value", off, "listen", "tls")
        assertDoesntContain("off, in the role's tasks", off, "test_only")
        assertContainsElements("off, in prepare.yml", complete(PREPARE, 15, "{{ web_cfg.<caret> }}"), "test_only")
        show(true)
        assertContainsElements("on, in the role's tasks", complete(TASKS, 4, "{{ web_cfg.<caret> }}"), "test_only")
    }

    /**
     * Loop items are typed as analysis types them (plan amendment R20, D157): from production values in production
     * files whatever the setting, so `item.` never offers a fixture's keys there.
     */
    fun testLoopItemShapesOutsideMoleculeComeFromProductionValues() {
        createFile("$ROOT/environments/prod/group_vars/web.yml", "---\nweb_vhosts:\n  - name: a\n    port: 80\n")
        createFile(PREPARE, PREPARE_TEXT)
        createFile(LOOP_TASKS, "---\n- name: Vhosts\n  ansible.builtin.debug:\n    msg: \"{{ shared_value }}\"\n  loop: \"{{ web_vhosts }}\"\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        for (show in listOf(false, true)) {
            show(show)
            val items = complete(LOOP_TASKS, 4, "{{ item.<caret> }}")
            assertContainsElements("show=$show", items, "name", "port")
            assertDoesntContain("show=$show", items, "test_only")
        }
    }

    /**
     * A converge task that renders a role template is a render context of it only for requests that see Molecule: its
     * task vars are no completion candidates in the production template while Molecule is hidden.
     */
    fun testTemplateCompletionLeavesConvergeRenderContextsOut() {
        createFile("$ROOT/roles/web/templates/web.conf.j2", "port {{ shared_value }}\n")
        createFile("$ROOT/roles/web/tasks/conf.yml", "---\n- name: Conf\n  ansible.builtin.template:\n    src: web.conf.j2\n    dest: /etc/web.conf\n  vars:\n    web_mode: prod\n")
        createFile(
            "$ROOT/roles/web/molecule/default/verify_conf.yml",
            """
            ---
            - name: Verify conf
              hosts: all
              tasks:
                - name: Expected
                  ansible.builtin.template:
                    src: web.conf.j2
                    dest: /tmp/expected
                  vars:
                    web_test_banner: x
            """,
        )
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val off = complete("$ROOT/roles/web/templates/web.conf.j2", 1, "{{ web_<caret> }}")
        assertContainsElements("off: the production task's vars", off, "web_mode")
        assertDoesntContain("off: no verify task vars", off, "web_test_banner")
        show(true)
        assertContainsElements("on", complete("$ROOT/roles/web/templates/web.conf.j2", 1, "{{ web_<caret> }}"), "web_mode", "web_test_banner")
    }

    fun testMoleculeGroupsOnlyWhereMoleculeIsShown() {
        assertDoesntContain("off, in the role's tasks", complete(TASKS, 4, "{{ groups['mol<caret>'] }}"), "molecule_testers")
        assertContainsElements("off, in converge.yml", complete(CONVERGE, 15, "{{ groups['mol<caret>'] }}"), "molecule_testers")
        show(true)
        assertContainsElements("on, in the role's tasks", complete(TASKS, 4, "{{ groups['mol<caret>'] }}"), "molecule_testers")
    }

    private companion object {
        const val PREPARE = "$ROOT/roles/web/molecule/default/prepare.yml"
        const val LOOP_TASKS = "$ROOT/roles/web/tasks/vhosts.yml"

        /** Play vars of a Molecule playbook; line 15 holds `{{ shared_value }}`. */
        val PREPARE_TEXT = """
            ---
            - name: Prepare
              hosts: all
              vars:
                web_cfg:
                  listen: 8080
                  test_only: x
                web_vhosts:
                  - name: t
                    port: 1
                    test_only: x
              tasks:
                - name: Read
                  ansible.builtin.debug:
                    msg: "{{ shared_value }}"
            """
    }
}
