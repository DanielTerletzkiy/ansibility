package de.terletzkiy.ansibility.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.MoleculeSettings

/**
 * One synthetic root `m` for the plan amendment R20 navigation tests (D153–D156): role `web` with a Molecule scenario
 * whose `molecule.yml` (inventory vars, a platform group), `converge.yml` (play vars, a handler, a read, roles `web` and
 * `db`) and `verify.yml` (a read) are test fixtures; production is `site.yml` applying `web` to the `prod` inventory.
 *
 * - `shared_value`: production `group_vars/all.yml` and the converge play's `vars:`;
 * - `only_in_tests`: only Molecule (converge play vars and molecule.yml inventory vars);
 * - `web_port`: web's default, molecule.yml's inventory var, read in web's tasks, converge and verify;
 * - `shared_port`: declared by the defaults of `alpha` and `db`; only converge applies `db` together with `web`;
 * - `db_result`: registered by `db`'s tasks, read in `web`'s tasks (visible only through the converge play);
 * - handler `restart web`: only in converge's `handlers:`, notified by web's task.
 */
object MoleculeNavigationFixture {
    const val ROOT = "m"
    const val CFG = "$ROOT/ansible.cfg"
    const val GROUP_VARS = "$ROOT/environments/prod/group_vars/all.yml"
    const val DEFAULTS = "$ROOT/roles/web/defaults/main.yml"
    const val TASKS = "$ROOT/roles/web/tasks/main.yml"
    const val MOLECULE = "$ROOT/roles/web/molecule/default/molecule.yml"
    const val CONVERGE = "$ROOT/roles/web/molecule/default/converge.yml"
    const val VERIFY = "$ROOT/roles/web/molecule/default/verify.yml"
    const val DB_DEFAULTS = "$ROOT/roles/db/defaults/main.yml"
    const val DB_TASKS = "$ROOT/roles/db/tasks/main.yml"
    const val ALPHA_DEFAULTS = "$ROOT/roles/alpha/defaults/main.yml"

    /** `path → text` of every file, `ansible.cfg` first. */
    val FILES: Map<String, String> = linkedMapOf(
        CFG to "[defaults]\nroles_path = roles\n",
        "$ROOT/environments/prod/hosts.yml" to "---\nall:\n  children:\n    web:\n      hosts:\n        web1:\n",
        GROUP_VARS to "---\nshared_value: 1\n",
        "$ROOT/site.yml" to "---\n- hosts: web\n  roles: [web]\n",
        DEFAULTS to "---\nweb_port: 80\n",
        // line 4: the reads; line 5: the notify; line 8: the registered result of db.
        TASKS to """
            ---
            - name: Use
              ansible.builtin.debug:
                msg: "{{ web_port }} {{ shared_value }} {{ only_in_tests }} {{ shared_port }}"
              notify: restart web
            - name: Result
              ansible.builtin.debug:
                msg: "{{ db_result }}"
            """.trimIndent() + "\n",
        // line 13: web_port, line 14: only_in_tests.
        MOLECULE to """
            ---
            driver:
              name: default
            platforms:
              - name: instance
                groups:
                  - molecule_testers
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    web_port: 8080
                    only_in_tests: 2
            """.trimIndent() + "\n",
        // line 5: shared_value, line 6: only_in_tests, line 9: the handler, line 15: the read.
        CONVERGE to """
            ---
            - name: Converge
              hosts: all
              vars:
                shared_value: 2
                only_in_tests: 1
              roles: [web, db]
              handlers:
                - name: restart web
                  ansible.builtin.debug:
                    msg: restarted
              tasks:
                - name: Read
                  ansible.builtin.debug:
                    msg: "{{ web_port }} {{ shared_value }}"
            """.trimIndent() + "\n",
        // line 7: the read.
        VERIFY to """
            ---
            - name: Verify
              hosts: all
              tasks:
                - name: Check
                  ansible.builtin.assert:
                    that: web_port == 8080
            """.trimIndent() + "\n",
        DB_DEFAULTS to "---\nshared_port: 5432\n",
        DB_TASKS to "---\n- name: Probe\n  ansible.builtin.command: \"true\"\n  register: db_result\n",
        ALPHA_DEFAULTS to "---\nshared_port: 1\n",
    )

    /** Writes every file with [create]. */
    fun create(create: (String, String) -> Unit) {
        for ((path, text) in FILES) create(path, text)
    }

    /** Sets "Show Molecule in navigation and search" (Run Molecule tests stays on). */
    fun showInNavigation(project: Project, show: Boolean) {
        AnsibilityProjectSettings.getInstance(project).update { it.copy(molecule = MoleculeSettings(showInNavigation = show)) }
    }

    /**
     * Turns "Show Molecule in navigation and search" on until [disposable] (a test's root disposable) is disposed, for
     * tests that check what Molecule adds from production files (the light project outlives the test).
     */
    fun showInNavigationUntil(project: Project, disposable: Disposable) {
        showInNavigation(project, true)
        Disposer.register(disposable) { showInNavigation(project, false) }
    }
}
