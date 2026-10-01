package de.terletzkiy.ansibility.inspections.undefined

/**
 * A synthetic role `probe` in the falcon copy of the fixture, applied by `playbook-probe.yml` to `monitoring_client` (ops,
 * prod and test hosts), for the guard-form and exclusion tables of ANS-V003. Each template line ends with
 * `{# expect: a, b #}`: the names reported on that line (none for a clean line); task lines end with `# M-…` (a
 * certain failure, MISSING), `# W-…` (MISSING_WHEN_RUN) or `# C-…` (clean).
 *
 * - `probe_opt` is optional in the spec, has no default and no environment sets it: every unguarded use is a witness.
 * - `probe_env` is optional and every environment sets it in `group_vars/all/probe.yml`; `probe_partial` only prod.
 * - `probe_req` is required (ANS-P003's), `probe_defaulted` has a role default, `probe_param` is a role param and
 *   `probe_play_var` a play var of the applying play; `probe_from_file` comes from the role's `include_vars` file.
 * - The role `gated` is applied with `when: gated_var is defined`: `gated_var` is guarded by the `roles:` entry, and
 *   every other use of the role runs only when that holds.
 */
object ProbeRole {
    const val ROOT = "repos/falcon/ansible"
    const val ROLE = "$ROOT/roles/probe"
    const val TEMPLATE = "$ROLE/templates/probe.conf.j2"
    const val LOOPED = "$ROLE/templates/looped.conf.j2"
    const val VERSIONS = "$ROLE/templates/versions.conf.j2"
    const val OPEN_TASKS = "$ROLE/tasks/open.yml"
    const val GUARDED_TASKS = "$ROLE/tasks/guarded.yml"
    const val DEFAULTS = "$ROLE/defaults/main.yml"
    const val SPEC = "$ROLE/meta/argument_specs.yml"
    const val PLAYBOOK = "$ROOT/playbook-probe.yml"
    const val HANDLERS = "$ROLE/handlers/main.yml"
    const val VARS = "$ROLE/vars/main.yml"
    const val GATED = "$ROOT/roles/gated"
    const val GATED_SPEC = "$GATED/meta/argument_specs.yml"
    const val GATED_TEMPLATE = "$GATED/templates/gated.conf.j2"

    /** The witness hosts of `probe_opt` (the `monitoring_client` hosts of the three environments). */
    val ALL_HOSTS: List<String> = listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1")

    val FILES: Map<String, String> = linkedMapOf(
        SPEC to """
            ---
            argument_specs:
              main:
                short_description: Probe role of the ANS-V003 tests.
                options:
                  probe_opt:
                    type: str
                    description: Optional, without a default, set nowhere.
                  probe_env:
                    type: str
                    description: Optional, set by every environment.
                  probe_partial:
                    type: str
                    description: Optional, set by prod only.
                  probe_list:
                    type: list
                    elements: str
                    description: Optional list.
                  probe_req:
                    type: str
                    required: true
                    description: Required.
                  probe_param:
                    type: str
                    description: A role param of the play.
                  probe_play_var:
                    type: str
                    description: A play var.
                  probe_from_file:
                    type: str
                    description: Loaded by include_vars.
                  probe_defaulted:
                    type: int
                    description: Has a role default.
        """,
        DEFAULTS to """
            ---
            probe_defaulted: 1
        """,
        "$ROLE/tasks/main.yml" to """
            ---
            - name: Render the probe template
              ansible.builtin.template:
                src: probe.conf.j2
                dest: /tmp/probe.conf
                mode: "0644"
              vars:
                probe_task_var: 1

            - name: Render in a loop
              ansible.builtin.template:
                src: looped.conf.j2
                dest: "/tmp/{{ probe_item }}"
                mode: "0644"
              loop: [a]
              loop_control:
                loop_var: probe_item

            - name: Render the version cases
              ansible.builtin.template:
                src: versions.conf.j2
                dest: /tmp/versions.conf
                mode: "0644"

            - name: Register a result
              ansible.builtin.command: "true"
              register: probe_result
              changed_when: false

            - name: Set a fact
              ansible.builtin.set_fact:
                probe_fact: 1

            - name: Include the guarded tasks
              ansible.builtin.include_tasks: guarded.yml
              when: probe_opt is defined

            - name: Include the open tasks
              ansible.builtin.include_tasks: open.yml

            - name: Load the probe vars file
              ansible.builtin.include_vars: extra/probe-extra.yml
        """,
        VARS to """
            ---
            probe_derived: "{{ probe_opt }}-x" # W-lazy
        """,
        "$ROLE/extra/probe-extra.yml" to """
            ---
            probe_from_file: loaded
        """,
        HANDLERS to """
            ---
            - name: Restart probe
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # W-handler
        """,
        OPEN_TASKS to """
            ---
            - name: "Display only {{ probe_opt }}"
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # M-msg
            - name: Guarded by when
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # C-when
              when: probe_opt is defined
            - name: Debug var never fails
              ansible.builtin.debug:
                var: probe_opt # C-var
            - name: Guarded block
              when: probe_opt is defined
              block:
                - name: In the block
                  ansible.builtin.debug:
                    msg: "{{ probe_opt }}" # C-block
            - name: Truthy when
              ansible.builtin.debug:
                msg: ok
              when: probe_opt # M-truthy
            - name: Ordered when list
              ansible.builtin.debug:
                msg: ok
              when:
                - probe_opt is defined
                - probe_opt # C-ordered
            - name: Wrong order
              ansible.builtin.debug:
                msg: ok
              when:
                - probe_opt # M-order
                - probe_opt is defined
            - name: Single quoted
              ansible.builtin.debug:
                msg: '{{ probe_opt }}' # M-single
            - name: Loop variable
              ansible.builtin.debug:
                msg: "{{ probe_each }}" # C-loop
              loop: [1]
              loop_control:
                loop_var: probe_each
            - name: Task vars
              ansible.builtin.debug:
                msg: "{{ probe_own }}" # C-taskvars
              vars:
                probe_own: 1
            - name: Gated by another condition
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # W-when
              when: probe_flag | default(false)
            - name: Default-falsy when
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # C-default-when
              when: probe_opt | default('') | length > 0
            - name: Default-falsy bool when
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # C-bool-when
              when:
                - probe_opt | default(false) | bool
            - name: Loop over a variable
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # W-loop
              loop: "{{ probe_list | default([]) }}"
            - name: Literal loop
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # M-literal-loop
              loop: [1, 2]
            - name: Run once
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # W-once
              run_once: true
            - name: Rescued
              block:
                - name: Fine
                  ansible.builtin.debug:
                    msg: ok
              rescue:
                - name: Only after a failure
                  ansible.builtin.debug:
                    msg: "{{ probe_opt }}" # W-rescue
              always:
                - name: Always
                  ansible.builtin.debug:
                    msg: "{{ probe_opt }}" # M-always
            - name: From the include_vars file
              ansible.builtin.debug:
                msg: "{{ probe_from_file }}" # C-include-vars
        """,
        GUARDED_TASKS to """
            ---
            - name: Guarded by the include chain
              ansible.builtin.debug:
                msg: "{{ probe_opt }}" # C-include
        """,
        TEMPLATE to """
            {{ probe_opt }}{# expect: probe_opt #}
            {{ probe_opt | default('') }}{# expect: #}
            {{ probe_opt | d('') }}{# expect: #}
            {{ probe_opt.key['x'] | default('') }}{# expect: #}
            {% if probe_opt is defined %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt is defined and probe_opt %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_task_var and probe_opt is defined %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt is not defined %}none{% else %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt is undefined %}none{% elif probe_opt == 'a' %}a{% endif %}{# expect: #}
            {{ probe_opt if probe_opt is defined else 'n' }}{# expect: #}
            {{ 'n' if probe_opt is not defined else probe_opt }}{# expect: #}
            {{ probe_opt is not defined or probe_opt }}{# expect: #}
            {% if not (probe_opt is not defined) %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt is defined %}{% set probe_copy = probe_opt %}{% endif %}{{ probe_copy | default('') }}{# expect: #}
            {% if probe_opt %}x{% endif %}{# expect: probe_opt #}
            {% if probe_opt is not none %}{{ probe_opt }}{% endif %}{# expect: probe_opt #}
            {{ probe_opt | mandatory }}{# expect: probe_opt #}
            {{ probe_task_var | default(probe_opt) }}{# expect: #}
            {% for i in probe_opt %}{{ i }}{% endfor %}{# expect: probe_opt #}
            {% macro m(a) %}{{ a }}{% endmacro %}{{ m(1) }}{# expect: #}
            {% for x in [1] %}{{ loop.index }}{{ x }}{% endfor %}{# expect: #}
            {% set probe_local = 1 %}{{ probe_local }}{# expect: #}
            {{ inventory_hostname }} {{ hostvars[inventory_hostname] }} {{ groups['all'] }} {{ group_names }}{# expect: #}
            {{ ansible_facts.hostname }} {{ ansible_distribution }} {{ ansible_managed }}{# expect: #}
            {{ item }}{# expect: #}
            {{ probe_result.stdout }} {{ probe_fact }}{# expect: #}
            {{ probe_task_var }}{# expect: #}
            {{ probe_param }} {{ probe_play_var }}{# expect: #}
            {{ probe_defaulted }}{# expect: #}
            {{ probe_req }}{# expect: #}
            {{ probe_env }}{# expect: probe_env #}
            {{ probe_partial }}{# expect: probe_partial #}
            {{ lookup('env', 'HOME') }} {{ range(3) | list }}{# expect: #}
            {% raw %}{{ probe_opt }}{% endraw %}{# expect: #}
            {{ probe_undeclared }}{# expect: probe_undeclared #}
            {{ probe_opt.items() | list }}{# expect: probe_opt #}
            {% if probe_opt | default(false) %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt | default(false) | bool %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt | default('') | trim | length > 0 %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt | d([]) | length %}{% for i in probe_opt %}{{ i }}{% endfor %}{% endif %}{# expect: #}
            {% if probe_opt | default('', true) | string | length > 0 %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt | default(true) | bool %}{{ probe_opt }}{% endif %}{# expect: probe_opt #}
            {% if probe_opt | default(true) | bool %}y{% else %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_opt is defined or probe_opt | default(false) %}{{ probe_opt }}{% endif %}{# expect: #}
            {% if probe_task_var %}{% elif probe_opt | default(none) is none %}n{% else %}{{ probe_opt }}{% endif %}{# expect: #}
            {{ probe_opt if probe_opt | default(false) else 'n' }}{# expect: #}
            {{ probe_opt | default(false) and probe_opt.key }}{# expect: #}
            {% if probe_opt | length > 0 %}{{ probe_opt }}{% endif %}{# expect: probe_opt #}
            {% if probe_task_var %}{{ probe_opt }}{% endif %}{# expect: probe_opt #}
            {% if probe_task_var %}{{ probe_opt | mandatory }}{% endif %}{# expect: #}
            {% macro probe_macro() %}{{ probe_opt }}{% endmacro %}{# expect: probe_opt #}
            {% if probe_opt is defined %}{% macro probe_inner() %}{{ probe_opt }}{% endmacro %}{% endif %}{# expect: probe_opt #}
        """,
        LOOPED to """
            {{ probe_item }}{# expect: #}
            {{ probe_opt }}{# expect: probe_opt #}
        """,
        VERSIONS to """
            {{ probe_opt is string }}
            {{ probe_opt | lower | default('') }}
            {{ probe_opt | int | default(0) }}
            {% if probe_opt | int | default(0) > 0 %}{{ probe_opt }}{% endif %}
        """,
        PLAYBOOK to """
            ---
            - name: Probe
              hosts: monitoring_client
              gather_facts: false
              vars:
                probe_play_var: 1
              roles:
                - role: probe
                  probe_param: 1
                - role: gated
                  when: gated_var is defined
              tasks:
                - name: A play's own task
                  ansible.builtin.debug:
                    msg: "{{ probe_play_only }} {{ probe_play_var }}" # M-play
        """,
        GATED_SPEC to """
            ---
            argument_specs:
              main:
                short_description: Applied behind a roles entry condition.
                options:
                  gated_var:
                    type: str
                    description: Guarded by the roles entry.
                  gated_other:
                    type: str
                    description: Used only when the entry condition holds.
        """,
        "$GATED/tasks/main.yml" to """
            ---
            - name: Render the gated template
              ansible.builtin.template:
                src: gated.conf.j2
                dest: /tmp/gated.conf
                mode: "0644"
        """,
        GATED_TEMPLATE to """
            {{ gated_var }}{# expect: #}
            {{ gated_other }}{# expect: gated_other #}
        """,
        "$ROOT/environments/ops/group_vars/all/probe.yml" to "---\nprobe_env: ops\n",
        "$ROOT/environments/prod/group_vars/all/probe.yml" to "---\nprobe_env: prod\nprobe_partial: prod\n",
        "$ROOT/environments/test/group_vars/all/probe.yml" to "---\nprobe_env: test\n",
    )

    private val EXPECT = Regex("""\{# expect: ([^#]*)#}""")

    /** The names each line of [text] expects to be reported, by 1-based line. */
    fun expectations(text: String): Map<Int, Set<String>> = text.lines().withIndex().mapNotNull { (index, line) ->
        val match = EXPECT.find(line) ?: return@mapNotNull null
        (index + 1) to match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.toMap()
}
