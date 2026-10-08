package de.terletzkiy.ansibility.resolve.include

/**
 * Synthetic roles in the falcon copy of the fixture for what include tasks give the files they include (the
 * include-variable report: a looping `include_tasks` with `loop_var`, or two `include_tasks` with `vars:`, and the
 * included file renders a template from them). `playbook-fw.yml` applies them to `monitoring_client` (ops, prod and test
 * hosts). Nothing in the inventory sets the include variables.
 *
 * - `fw_loop` (case A): `rules.yml:8` includes `ruleset.yml` in a loop of two literal dicts with `loop_var: fwl_ruleset`;
 *   `ruleset.yml` renders `rules.j2`.
 * - `fw_vars` (case B): `rules.yml:2` and `rules.yml:10` include `ruleset.yml` with `vars: { fwv_ruleset: … }`.
 * - `fw_entry`: the play's `include_role` with `tasks_from: apply`, a loop (`loop_var: fwe_set`) and `vars:`.
 * - `fw_two`: `main.yml:2` includes `part.yml` with `vars: { fwt_mode: strict }`, `main.yml:7` without.
 * - `fw_mol`: production `main.yml` includes `apply.yml` plainly; only the Molecule converge play's `include_role`
 *   gives it `fwm_flag`.
 * - `fw_imp`: `import_tasks` with `vars:` (they apply) and `import_tasks` with a loop (it binds nothing).
 * - `fw_dir`: `playbook-fw-direct.yml` applies it directly to `database` (prod-prod1, test-test1) and runs it through an
 *   `include_role` with `vars: { fwd_mode: strict }` on `monitoring_client`: its `main.yml` also runs without the include.
 * - `fw_tpl`: only an `include_role` with a templated `tasks_from` (and `vars:`) runs it: no file is known to be included.
 * - `fw_prec`: `include_tasks` with `vars: { fwr_policy: DROP }` of a task whose own `vars:` say `ACCEPT` (the include
 *   param wins), and a looping `include_tasks` (`loop_var: fwr_item`) of a template task whose own `vars:` set `fwr_item`.
 * - `fw_scope`: a looping `include_tasks` over `fws_sets` (defaults `[a, b]`) of a task whose own `vars:` set
 *   `fws_sets: [x]`: the include's loop is evaluated where the include runs.
 * - `fw_lab`: `main.yml:2` and `main.yml:7` include `rules.yml` with `fwb_mode` a / b, whose one include runs
 *   `ruleset.yml`: two paths that share their direct includer.
 * - `fw_cap`: an include looping 11 times runs a template task looping 100 times (preview choices are capped).
 * - `fw_inv`: two includes of `part.yml`, one with `vars: { fwq_mode: strict }`; the spec declares `fwq_mode` (optional,
 *   no default) and the root's `group_vars/all` set it for every host.
 */
object IncludeFixture {
    const val ROOT = "repos/falcon/ansible"
    const val PLAYBOOK = "$ROOT/playbook-fw.yml"

    const val LOOP = "$ROOT/roles/fw_loop"
    const val LOOP_RULES = "$LOOP/tasks/rules.yml"
    const val LOOP_RULESET = "$LOOP/tasks/ruleset.yml"
    const val LOOP_TEMPLATE = "$LOOP/templates/rules.j2"

    const val VARS = "$ROOT/roles/fw_vars"
    const val VARS_RULES = "$VARS/tasks/rules.yml"
    const val VARS_RULESET = "$VARS/tasks/ruleset.yml"
    const val VARS_TEMPLATE = "$VARS/templates/rules.j2"

    const val ENTRY = "$ROOT/roles/fw_entry"
    const val ENTRY_APPLY = "$ENTRY/tasks/apply.yml"

    const val TWO = "$ROOT/roles/fw_two"
    const val TWO_MAIN = "$TWO/tasks/main.yml"
    const val TWO_PART = "$TWO/tasks/part.yml"

    const val MOL = "$ROOT/roles/fw_mol"
    const val MOL_APPLY = "$MOL/tasks/apply.yml"
    const val MOL_CONVERGE = "$MOL/molecule/default/converge.yml"

    const val IMP = "$ROOT/roles/fw_imp"
    const val IMP_PART = "$IMP/tasks/part.yml"
    const val IMP_LOOPED = "$IMP/tasks/looped.yml"

    const val DIRECT_PLAYBOOK = "$ROOT/playbook-fw-direct.yml"
    const val DIR = "$ROOT/roles/fw_dir"
    const val DIR_MAIN = "$DIR/tasks/main.yml"

    const val TPL = "$ROOT/roles/fw_tpl"
    const val TPL_MAIN = "$TPL/tasks/main.yml"

    const val PREC = "$ROOT/roles/fw_prec"
    const val PREC_MAIN = "$PREC/tasks/main.yml"
    const val PREC_PART = "$PREC/tasks/part.yml"
    const val PREC_TEMPLATE = "$PREC/templates/prec.j2"

    const val SCOPE = "$ROOT/roles/fw_scope"
    const val SCOPE_PART = "$SCOPE/tasks/part.yml"

    const val LAB = "$ROOT/roles/fw_lab"
    const val LAB_RULESET = "$LAB/tasks/ruleset.yml"

    const val CAP = "$ROOT/roles/fw_cap"
    const val CAP_TEMPLATE = "$CAP/templates/cap.j2"

    const val INV = "$ROOT/roles/fw_inv"
    const val INV_PART = "$INV/tasks/part.yml"

    private const val TEMPLATE = """
        # {{ %s.ip_version }}
        {%% for rule in %s.chains %%}
        -A {{ rule.chain }} {{ rule.rule }}
        {%% endfor %%}
        COMMIT
    """

    private fun ruleset(name: String) = """
        ---
        - name: "Render the {{ $name.ip_version }} rules"
          ansible.builtin.template:
            src: rules.j2
            dest: "{{ $name.dest }}"
            mode: "0644"

        - name: Report each chain
          ansible.builtin.debug:
            msg: "{{ item.chain }} in {{ $name.dest }}"
          loop: "{{ $name.chains }}"
    """

    private fun defaults(prefix: String) = """
        ---
        ${prefix}_rules:
          - chain: INPUT
            rule: "-p tcp --dport 22 -j ACCEPT"
        ${prefix}_rules_extra: []
        ${prefix}_rules_ipv6:
          - chain: INPUT
            rule: "-j DROP"
        ${prefix}_rules_extra_ipv6: []
    """

    private const val INCLUDE_RULES = """
        ---
        - name: Include the rules
          ansible.builtin.include_tasks: rules.yml
    """

    val FILES: Map<String, String> = linkedMapOf(
        PLAYBOOK to """
            ---
            - name: Firewall
              hosts: monitoring_client
              gather_facts: false
              roles:
                - fw_loop
                - fw_vars
                - fw_two
                - fw_mol
                - fw_imp
                - fw_prec
                - fw_scope
                - fw_lab
                - fw_cap
                - fw_inv
              tasks:
                - name: Apply the entry rules
                  ansible.builtin.include_role:
                    name: fw_entry
                    tasks_from: apply
                  vars:
                    fwe_mode: fast
                  loop:
                    - dest: /etc/fw/entry.v4
                    - dest: /etc/fw/entry.v6
                  loop_control:
                    loop_var: fwe_set
        """,
        // case A
        "$LOOP/defaults/main.yml" to defaults("fwl"),
        "$LOOP/tasks/main.yml" to INCLUDE_RULES,
        LOOP_RULES to """
            ---
            - name: Ensure the directory exists
              ansible.builtin.file:
                path: /etc/fw
                state: directory
                mode: "0755"

            - name: Apply and persist rulesets
              ansible.builtin.include_tasks: ruleset.yml
              loop:
                - ip_version: ipv4
                  dest: /etc/fw/rules.v4
                  chains: "{{ fwl_rules + fwl_rules_extra }}"
                - ip_version: ipv6
                  dest: /etc/fw/rules.v6
                  chains: "{{ fwl_rules_ipv6 + fwl_rules_extra_ipv6 }}"
              loop_control:
                loop_var: fwl_ruleset
                label: "{{ fwl_ruleset.dest }}"
        """,
        LOOP_RULESET to ruleset("fwl_ruleset"),
        LOOP_TEMPLATE to TEMPLATE.format("fwl_ruleset", "fwl_ruleset"),
        // case B
        "$VARS/defaults/main.yml" to defaults("fwv"),
        "$VARS/tasks/main.yml" to INCLUDE_RULES,
        VARS_RULES to """
            ---
            - name: Apply and persist ruleset (ipv4)
              ansible.builtin.include_tasks: ruleset.yml
              vars:
                fwv_ruleset:
                  ip_version: ipv4
                  dest: /etc/fw/rules.v4
                  chains: "{{ fwv_rules + fwv_rules_extra }}"

            - name: Apply and persist ruleset (ipv6)
              ansible.builtin.include_tasks: ruleset.yml
              vars:
                fwv_ruleset:
                  ip_version: ipv6
                  dest: /etc/fw/rules.v6
                  chains: "{{ fwv_rules_ipv6 + fwv_rules_extra_ipv6 }}"
        """,
        VARS_RULESET to ruleset("fwv_ruleset"),
        VARS_TEMPLATE to TEMPLATE.format("fwv_ruleset", "fwv_ruleset"),
        // include_role with tasks_from and a loop
        "$ENTRY/tasks/main.yml" to """
            ---
            - name: Nothing by default
              ansible.builtin.debug:
                msg: entry
        """,
        ENTRY_APPLY to """
            ---
            - name: Write the entry rules
              ansible.builtin.copy:
                content: "{{ fwe_mode }}"
                dest: "{{ fwe_set.dest }}"
                mode: "0644"
        """,
        // two includers, only one sets the name
        TWO_MAIN to """
            ---
            - name: Include strictly
              ansible.builtin.include_tasks: part.yml
              vars:
                fwt_mode: strict

            - name: Include plainly
              ansible.builtin.include_tasks: part.yml
        """,
        TWO_PART to """
            ---
            - name: Use the mode
              ansible.builtin.command:
                cmd: "echo mode={{ fwt_mode }}"
              changed_when: false
        """,
        // only a Molecule includer sets the name
        "$MOL/tasks/main.yml" to """
            ---
            - name: Include the apply tasks
              ansible.builtin.include_tasks: apply.yml
        """,
        MOL_APPLY to """
            ---
            - name: Use the flag
              ansible.builtin.command:
                cmd: "echo {{ fwm_flag }}"
              changed_when: false
        """,
        "$MOL/molecule/default/molecule.yml" to """
            ---
            driver:
              name: docker
            platforms:
              - name: instance
                image: debian:12
        """,
        MOL_CONVERGE to """
            ---
            - name: Converge
              hosts: all
              tasks:
                - name: Apply with the flag
                  ansible.builtin.include_role:
                    name: fw_mol
                    tasks_from: apply
                  vars:
                    fwm_flag: true
        """,
        // import_tasks: vars apply, a loop binds nothing
        "$IMP/tasks/main.yml" to """
            ---
            - name: Import with vars
              ansible.builtin.import_tasks: part.yml
              vars:
                fwi_mode: imported

            - name: Import in a loop
              ansible.builtin.import_tasks: looped.yml
              loop: [a, b]
              loop_control:
                loop_var: fwi_each
        """,
        IMP_PART to """
            ---
            - name: Use the imported mode
              ansible.builtin.command:
                cmd: "echo {{ fwi_mode }}"
              changed_when: false
        """,
        IMP_LOOPED to """
            ---
            - name: Use the import's loop name
              ansible.builtin.debug:
                msg: "{{ fwi_each }}"
        """,
        // a role entry file a play applies directly and an include_role runs with vars
        DIRECT_PLAYBOOK to """
            ---
            - name: Direct
              hosts: database
              gather_facts: false
              roles:
                - fw_dir

            - name: Included
              hosts: monitoring_client
              gather_facts: false
              tasks:
                - name: Apply the direct role with its mode
                  ansible.builtin.include_role:
                    name: fw_dir
                  vars:
                    fwd_mode: strict

                - name: Apply a templated entry
                  ansible.builtin.include_role:
                    name: fw_tpl
                    tasks_from: "{{ fwp_entry | default('apply') }}"
                  vars:
                    fwp_mode: strict
        """,
        DIR_MAIN to """
            ---
            - name: Use the direct mode
              ansible.builtin.command:
                cmd: "echo {{ fwd_mode }}"
              changed_when: false
        """,
        TPL_MAIN to """
            ---
            - name: Use the templated mode
              ansible.builtin.command:
                cmd: "echo {{ fwp_mode }}"
              changed_when: false
        """,
        // include params beat the included task's own vars
        PREC_MAIN to """
            ---
            - name: Include with the policy
              ansible.builtin.include_tasks: part.yml
              vars:
                fwr_policy: DROP

            - name: Include the render in a loop
              ansible.builtin.include_tasks: render.yml
              loop: [a, b]
              loop_control:
                loop_var: fwr_item
        """,
        PREC_PART to """
            ---
            - name: Report the policy
              ansible.builtin.debug:
                msg: "policy {{ fwr_policy }}"
              vars:
                fwr_policy: ACCEPT
        """,
        "$PREC/tasks/render.yml" to """
            ---
            - name: Render with an own value
              ansible.builtin.template:
                src: prec.j2
                dest: /etc/fw/prec
                mode: "0644"
              vars:
                fwr_item: own
        """,
        PREC_TEMPLATE to "{{ fwr_item }}",
        // an include's loop is evaluated where the include runs
        "$SCOPE/defaults/main.yml" to """
            ---
            fws_sets: [a, b]
        """,
        "$SCOPE/tasks/main.yml" to """
            ---
            - name: Include per set
              ansible.builtin.include_tasks: part.yml
              loop: "{{ fws_sets }}"
              loop_control:
                loop_var: fws_set
        """,
        SCOPE_PART to """
            ---
            - name: Report the set
              ansible.builtin.debug:
                msg: "set {{ fws_set }}"
              vars:
                fws_sets: [x]
        """,
        // two paths through one direct includer
        "$LAB/tasks/main.yml" to """
            ---
            - name: Include as a
              ansible.builtin.include_tasks: rules.yml
              vars:
                fwb_mode: a

            - name: Include as b
              ansible.builtin.include_tasks: rules.yml
              vars:
                fwb_mode: b
        """,
        "$LAB/tasks/rules.yml" to """
            ---
            - name: Include the ruleset
              ansible.builtin.include_tasks: ruleset.yml
        """,
        LAB_RULESET to """
            ---
            - name: Report the mode
              ansible.builtin.debug:
                msg: "mode {{ fwb_mode }}"
        """,
        // include runs × own items
        "$CAP/tasks/main.yml" to """
            ---
            - name: Include eleven times
              ansible.builtin.include_tasks: part.yml
              loop: "{{ range(11) | list }}"
              loop_control:
                loop_var: fwk_run
        """,
        "$CAP/tasks/part.yml" to """
            ---
            - name: Render a hundred times
              ansible.builtin.template:
                src: cap.j2
                dest: "/etc/fw/cap-{{ fwk_run }}-{{ item }}"
                mode: "0644"
              loop: "{{ range(100) | list }}"
        """,
        CAP_TEMPLATE to "{{ fwk_run }}-{{ item }}",
        // an inventory value everywhere, an optional spec option, one of two includes sets it
        "$INV/meta/argument_specs.yml" to """
            ---
            argument_specs:
              main:
                short_description: Firewall with an inventory mode
                options:
                  fwq_mode:
                    type: str
                    description: The mode.
        """,
        "$INV/tasks/main.yml" to """
            ---
            - name: Include strictly
              ansible.builtin.include_tasks: part.yml
              vars:
                fwq_mode: strict

            - name: Include plainly
              ansible.builtin.include_tasks: part.yml
        """,
        INV_PART to """
            ---
            - name: Use the inventory mode
              ansible.builtin.command:
                cmd: "echo mode={{ fwq_mode }}"
              changed_when: false
        """,
        "$ROOT/group_vars/all/fw.yml" to """
            ---
            fwq_mode: relaxed
        """,
    )
}
