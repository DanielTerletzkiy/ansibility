package de.terletzkiy.ansibility.vars

/**
 * A synthetic root `lab` for loop variables across includes (the include-loop report): role `fw` whose `rules.yml:7`
 * includes `ruleset.yml` in a loop of two literal dicts (`loop_var: fw_ruleset`, `index_var: fw_index`, a `label:`).
 * `ruleset.yml` reads `fw_ruleset` in a task name, a module argument, another task's message and `loop:` expression,
 * renders `templates/rules.j2` (which reads `fw_ruleset.ip_version` and `.chains`), and has a task whose own loop is
 * also called `fw_ruleset` (shadowing). `other.yml` reads the name but nothing includes it. `main.yml` also imports
 * `imported.yml` in a loop (an import's loop binds nothing) and includes `twice.yml` from two looping tasks with the
 * same `loop_var: fw_pass`. `site.yml:7` runs role `entry`'s `apply.yml` through `include_role` with `tasks_from` in a
 * loop (`loop_var: fw_target`). Only the Molecule converge play of `fw` includes `probe.yml` in a loop
 * (`loop_var: fw_probe`), which binds nothing for that production file (plan amendment R20).
 *
 * Line numbers are part of the tests; keep them.
 */
object LoopIncludeFixture {
    const val ROOT = "lab"
    const val ROLE = "$ROOT/roles/fw"
    const val MAIN = "$ROLE/tasks/main.yml"
    const val RULES = "$ROLE/tasks/rules.yml"
    const val RULESET = "$ROLE/tasks/ruleset.yml"
    const val TEMPLATE = "$ROLE/templates/rules.j2"
    const val IMPORTED = "$ROLE/tasks/imported.yml"
    const val TWICE = "$ROLE/tasks/twice.yml"
    const val OTHER = "$ROLE/tasks/other.yml"
    const val PROBE = "$ROLE/tasks/probe.yml"
    const val CONVERGE = "$ROLE/molecule/default/converge.yml"
    const val SITE = "$ROOT/site.yml"
    const val ENTRY_APPLY = "$ROOT/roles/entry/tasks/apply.yml"

    val FILES: Map<String, String> = linkedMapOf(
        "$ROOT/ansible.cfg" to "[defaults]\nroles_path = roles\n",
        SITE to """
            ---
            - name: Lab
              hosts: all
              roles:
                - fw
              tasks:
                - name: Apply the entry rules
                  ansible.builtin.include_role:
                    name: entry
                    tasks_from: apply
                  loop:
                    - dest: /etc/entry.v4
                    - dest: /etc/entry.v6
                  loop_control:
                    loop_var: fw_target
        """.trimIndent(),
        ENTRY_APPLY to """
            ---
            - name: Write the entry rules
              ansible.builtin.copy:
                content: "{{ fw_target.dest }}"
                dest: "{{ fw_target.dest }}"
                mode: "0644"
        """.trimIndent(),
        "$ROLE/defaults/main.yml" to """
            ---
            fw_rules:
              - chain: INPUT
                rule: "-j ACCEPT"
            fw_rules_extra: []
            fw_rules_ipv6: []
            fw_rules_extra_ipv6: []
        """.trimIndent(),
        MAIN to """
            ---
            - name: Include the rules
              ansible.builtin.include_tasks: rules.yml

            - name: Import in a loop
              ansible.builtin.import_tasks: imported.yml
              loop: [a, b]
              loop_control:
                loop_var: fw_each

            - name: First pass
              ansible.builtin.include_tasks: twice.yml
              loop: [1, 2]
              loop_control:
                loop_var: fw_pass

            - name: Second pass
              ansible.builtin.include_tasks: twice.yml
              loop: [3]
              loop_control:
                loop_var: fw_pass
        """.trimIndent(),
        RULES to """
            ---
            - name: Ensure the directory exists
              ansible.builtin.file:
                path: /etc/fw
                state: directory

            - name: Apply and persist rulesets
              ansible.builtin.include_tasks: ruleset.yml
              loop:
                - ip_version: ipv4
                  dest: /etc/fw/rules.v4
                  chains: "{{ fw_rules + fw_rules_extra }}"
                - ip_version: ipv6
                  dest: /etc/fw/rules.v6
                  chains: "{{ fw_rules_ipv6 + fw_rules_extra_ipv6 }}"
              loop_control:
                loop_var: fw_ruleset
                index_var: fw_index
                label: "{{ fw_ruleset.dest }}"
        """.trimIndent(),
        RULESET to """
            ---
            - name: "Render the {{ fw_ruleset.ip_version }} rules"
              ansible.builtin.template:
                src: rules.j2
                dest: "{{ fw_ruleset.dest }}"
                mode: "0644"

            - name: Report each chain
              ansible.builtin.debug:
                msg: "{{ item.chain }} in {{ fw_ruleset.dest }} ({{ fw_index }})"
              loop: "{{ fw_ruleset.chains }}"

            - name: Shadowing loop
              ansible.builtin.debug:
                msg: "{{ fw_ruleset }}"
              loop: [x, y]
              loop_control:
                loop_var: fw_ruleset
        """.trimIndent(),
        TEMPLATE to """
            # {{ fw_ruleset.ip_version }}
            {% for rule in fw_ruleset.chains %}
            -A {{ rule.chain }} {{ rule.rule }}
            {% endfor %}
            COMMIT
        """.trimIndent(),
        IMPORTED to """
            ---
            - name: Use the import's loop name
              ansible.builtin.debug:
                msg: "{{ fw_each }}"
        """.trimIndent(),
        TWICE to """
            ---
            - name: Use the pass
              ansible.builtin.debug:
                msg: "{{ fw_pass }}"
        """.trimIndent(),
        OTHER to """
            ---
            - name: Unrelated
              ansible.builtin.debug:
                msg: "{{ fw_ruleset }}"
        """.trimIndent(),
        PROBE to """
            ---
            - name: Probe
              ansible.builtin.debug:
                msg: "{{ fw_probe }}"
        """.trimIndent(),
        "$ROLE/molecule/default/molecule.yml" to """
            ---
            driver:
              name: docker
            platforms:
              - name: instance
                image: debian:12
        """.trimIndent(),
        CONVERGE to """
            ---
            - name: Converge
              hosts: all
              tasks:
                - name: Probe each
                  ansible.builtin.include_role:
                    name: fw
                    tasks_from: probe
                  loop: [1, 2]
                  loop_control:
                    loop_var: fw_probe
        """.trimIndent(),
    )
}
