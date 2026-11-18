package de.terletzkiy.ansibility.vars.hints

/**
 * One synthetic root `s` for plan amendment R31 (every definition from a usage, inline variable sources): role `app`
 * declares `app_version` (spec, default 1.2.0), which every environment overrides; `site.yml` applies `app` to the hosts
 * of group `web`.
 *
 * - `app_version`: app's default 1.2.0; `prod` group_vars all 1.4.0, group_vars web 1.4.1, host_vars prod-web2
 *   1.4.2-hotfix (prod-web1 and prod-web3 get 1.4.1); `test` 1.3.0 (the playbook-level 2.0.0 wins there); `stage`
 *   1.3.5; `dev` a long value; playbook `group_vars/all.yml` 2.0.0; role `other` declares it too (default 9.9.9); the
 *   Molecule scenario sets 0.0.1-mol;
 * - `app_port`: default 80, prod 8443; a loop of the tasks binds it as its `loop_var`, a template binds it as a local;
 * - `app_token`: vault values in prod and test; `app_password`: a `no_log` spec option, default and prod value;
 * - `app_only_default`: only the role default; `app_status`: prod and test, but registered by the role's tasks;
 *   `ansible_user`: prod and test (a magic variable); `undefined_thing`: nothing.
 */
object VarSourcesFixture {
    const val ROOT = "s"
    const val TASKS = "$ROOT/roles/app/tasks/main.yml"
    const val TEMPLATE = "$ROOT/roles/app/templates/app.conf.j2"
    const val SPEC = "$ROOT/roles/app/meta/argument_specs.yml"
    const val DEFAULTS = "$ROOT/roles/app/defaults/main.yml"
    const val OTHER_SPEC = "$ROOT/roles/other/meta/argument_specs.yml"
    const val OTHER_DEFAULTS = "$ROOT/roles/other/defaults/main.yml"
    const val PROD_ALL = "$ROOT/environments/prod/group_vars/all.yml"
    const val PROD_WEB = "$ROOT/environments/prod/group_vars/web.yml"
    const val PROD_WEB2 = "$ROOT/environments/prod/host_vars/prod-web2.yml"
    const val TEST_ALL = "$ROOT/environments/test/group_vars/all.yml"
    const val STAGE_ALL = "$ROOT/environments/stage/group_vars/all.yml"
    const val DEV_ALL = "$ROOT/environments/dev/group_vars/all.yml"
    const val PLAYBOOK_ALL = "$ROOT/group_vars/all.yml"
    const val MOLECULE = "$ROOT/roles/app/molecule/default/molecule.yml"
    const val SITE = "$ROOT/site.yml"

    /** A `!vault` value with a dummy body (never decrypted, never a real envelope). */
    private const val VAULT = "!vault \"\$ANSIBLE_VAULT;1.1;AES256\\n6162\""

    private fun hosts(vararg hosts: String) =
        "---\nall:\n  children:\n    web:\n      hosts:\n" + hosts.joinToString("") { "        $it:\n" }

    /** `path → text` of every file, `ansible.cfg` first. Line numbers are in the comments. */
    val FILES: Map<String, String> = linkedMapOf(
        "$ROOT/ansible.cfg" to "[defaults]\nroles_path = roles\n",
        "$ROOT/environments/prod/hosts.yml" to hosts("prod-web1", "prod-web2", "prod-web3"),
        "$ROOT/environments/test/hosts.yml" to hosts("test-web1"),
        "$ROOT/environments/stage/hosts.yml" to hosts("stage-web1"),
        "$ROOT/environments/dev/hosts.yml" to hosts("dev-web1"),
        // 2 app_version, 3 app_port, 4 app_token, 5 app_password, 6 app_status, 7 ansible_user, 8 the reference (rule 3)
        PROD_ALL to """
            ---
            app_version: 1.4.0
            app_port: 8443
            app_token: $VAULT
            app_password: also-secret-never-shown
            app_status: prod
            ansible_user: deploy
            app_url: "https://example.com/{{ app_version }}"
            """.trimIndent() + "\n",
        PROD_WEB to "---\napp_version: 1.4.1\n",
        PROD_WEB2 to "---\napp_version: 1.4.2-hotfix\n",
        // 2 app_version, 3 app_token, 4 app_status, 5 ansible_user
        TEST_ALL to "---\napp_version: 1.3.0\napp_token: $VAULT\napp_status: test\nansible_user: tester\n",
        STAGE_ALL to "---\napp_version: 1.3.5\n",
        DEV_ALL to "---\napp_version: 1.3.9-dev-build-with-a-long-name\n",
        PLAYBOOK_ALL to "---\napp_version: 2.0.0\n",
        SITE to "---\n- name: Web\n  hosts: web\n  roles:\n    - app\n",
        // 5 app_version, 8 app_port, 10 app_password, 13 app_only_default
        SPEC to """
            ---
            argument_specs:
              main:
                options:
                  app_version:
                    type: str
                    description: The version to install.
                  app_port:
                    type: int
                  app_password:
                    type: str
                    no_log: true
                  app_only_default:
                    type: str
            """.trimIndent() + "\n",
        // 2 app_version, 3 app_port, 4 app_password, 5 app_only_default, 6 app_items
        DEFAULTS to "---\napp_version: 1.2.0\napp_port: 80\napp_password: hunter2-never-shown\napp_only_default: lonely\napp_items: [a, b]\n",
        // 4 two references in a value; 5 a bare condition with two; 8 several on one line; 11 the loop variable app_port;
        // 17 register; 20 a registered result, a fact, a magic variable, an undefined name
        TASKS to """
            ---
            - name: Install
              ansible.builtin.package:
                name: "app-{{ app_version }}-{{ app_port }}"
              when: app_version is version('1.0', '>=') and app_port > 0
            - name: Several on one line
              ansible.builtin.debug:
                msg: "{{ app_version }} {{ app_port }} {{ app_only_default }} {{ app_token }} {{ app_password }}"
            - name: Loop
              ansible.builtin.debug:
                msg: "{{ app_port }}"
              loop: "{{ app_items }}"
              loop_control:
                loop_var: app_port
            - name: Probe
              ansible.builtin.command: "true"
              register: app_status
            - name: Others
              ansible.builtin.debug:
                msg: "{{ app_status.rc }} {{ ansible_user }} {{ inventory_hostname }} {{ undefined_thing }}"
            """.trimIndent() + "\n",
        // 1 app_version; 2 a local named app_port; 3 app_port
        TEMPLATE to "version={{ app_version }}\n{% for app_port in [1] %}{{ app_port }}{% endfor %}\nport={{ app_port }}\n",
        OTHER_SPEC to "---\nargument_specs:\n  main:\n    options:\n      app_version:\n        type: str\n",
        OTHER_DEFAULTS to "---\napp_version: 9.9.9\n",
        // 13 app_version
        MOLECULE to """
            ---
            driver:
              name: default
            platforms:
              - name: instance
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    mol_only: 1
                    other_mol: 2
                    app_version: 0.0.1-mol
            """.trimIndent() + "\n",
    )

    /** Writes every file with [create]. */
    fun create(create: (String, String) -> Unit) {
        for ((path, text) in FILES) create(path, text)
    }
}
