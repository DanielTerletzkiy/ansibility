package de.terletzkiy.ansibility.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection

class VarUseIndexerTest : BasePlatformTestCase() {
    private fun uses(path: String, text: String): Map<String, List<UseEntry>> = VarUseIndexer.index(IndexInput.of(path, text, project))

    /** `name@container` of each use, checking that every offset points at the name in [text]. */
    private fun describe(text: String, map: Map<String, List<UseEntry>>): Set<String> = map.flatMap { (name, entries) ->
        entries.map { entry ->
            assertEquals("offset of $name", name, text.substring(entry.offset, entry.offset + name.length))
            "$name@${entry.container}"
        }
    }.toSet()

    fun testTemplateFileReferencesWithFlagsAndPaths() {
        val text = """
            {% for server in haproxy_servers %}
            server {{ server.name }} {{ server.ip }}:{{ server.port | default(80) }}
            {% endfor %}
            {{ item.floating.ssl.cert_file }} {{ maybe | default('') }}
            {% if opt is defined %}{{ opt.x }}{% endif %}
            {{ lookup('env', 'HOME') }}
        """.trimIndent()
        val map = uses("golden/roles/haproxy/templates/haproxy.cfg.j2", text)
        assertEquals(
            setOf("haproxy_servers@TEMPLATE_FILE", "item@TEMPLATE_FILE", "maybe@TEMPLATE_FILE", "opt@TEMPLATE_FILE", "lookup@TEMPLATE_FILE"),
            describe(text, map),
        )
        assertNull("for targets are locals", map["server"])
        assertEquals(listOf("floating", "ssl", "cert_file"), map.getValue("item").single().attrPath)
        assertTrue(map.getValue("maybe").single().guarded)
        val opt = map.getValue("opt")
        assertTrue(opt.any { it.guarded } && opt.any { it.guardedByCondition })
        assertTrue(map.getValue("lookup").single().called)
    }

    fun testNonJ2FilesUnderTemplatesAreWholeTemplates() {
        val text = "services:\n  app:\n    image: \"{{ app_image }}\"\n"
        val map = uses("repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/docker-compose.yml", text)
        assertEquals(setOf("app_image@TEMPLATE_FILE"), describe(text, map))
    }

    fun testYamlScalarsInTemplateModeWithExactOffsets() {
        val text = """
            - name: "Deploy {{ app_name }}"
              ansible.builtin.template:
                src: templates/x.j2
                dest: '/etc/{{ app_dir }}/x'
              notify: Reload
            - name: Double quoted with escapes
              ansible.builtin.debug:
                msg: "a\tb \"{{ quoted_var }}\" {{ second | default('\n') }}"
            - name: Block scalar
              ansible.builtin.shell: |
                echo {{ block_one }}
                  {{ block_two.attr }}
            - name: Folded plain
              ansible.builtin.debug:
                msg: first line
                  {{ folded_var }}
        """.trimIndent() + "\n"
        val map = uses("golden/roles/app/tasks/main.yml", text)
        assertEquals(
            setOf(
                "app_name@YAML_TEMPLATE", "app_dir@YAML_TEMPLATE", "quoted_var@YAML_TEMPLATE", "second@YAML_TEMPLATE",
                "block_one@YAML_TEMPLATE", "block_two@YAML_TEMPLATE", "folded_var@YAML_TEMPLATE",
            ),
            describe(text, map),
        )
        assertEquals(listOf("attr"), map.getValue("block_two").single().attrPath)
        assertTrue(map.getValue("second").single().guarded)
    }

    fun testImplicitExpressionsInTaskContext() {
        val text = """
            - name: Conditions
              ansible.builtin.command: echo
              when: cond_a is defined and cond_a.enabled
              changed_when: false
              failed_when:
                - result_a.rc != 0
                - "'x' in result_a.stdout"
              until: retries_left > 0
            - name: Assert
              ansible.builtin.assert:
                that:
                  - asserted | length > 0
                fail_msg: "{{ failure_text }}"
            - name: Debug var
              ansible.builtin.debug:
                var: debugged
            - name: Hazard
              ansible.builtin.command: echo
              when: "{{ templated_when }}"
            - name: Block
              when: block_cond
              block:
                - ansible.builtin.command: echo
                  when: nested_cond
            - name: Not an expression
              ansible.builtin.lineinfile:
                line: when_not_expression
        """.trimIndent() + "\n"
        val map = uses("golden/roles/app/tasks/main.yml", text)
        assertEquals(
            setOf(
                "cond_a@YAML_EXPRESSION", "result_a@YAML_EXPRESSION", "retries_left@YAML_EXPRESSION",
                "asserted@YAML_EXPRESSION", "failure_text@YAML_TEMPLATE", "debugged@YAML_EXPRESSION",
                "templated_when@YAML_TEMPLATE", "block_cond@YAML_EXPRESSION", "nested_cond@YAML_EXPRESSION",
            ),
            describe(text, map),
        )
        assertTrue(map.getValue("cond_a").any { it.guardedByCondition })
        assertNull("booleans are not expressions", map["false"])
        assertNull(map["when_not_expression"])
    }

    fun testPlayAndRoleEntryConditions() {
        val text = """
            - name: Play
              hosts: all
              roles:
                - { role: x, when: role_cond }
              tasks:
                - ansible.builtin.command: echo
                  when: play_task_cond
            - ansible.builtin.import_playbook: other.yml
              when: import_cond
        """.trimIndent() + "\n"
        val map = uses("repos/falcon/ansible/playbook-site.yml", text)
        assertEquals(setOf("role_cond@YAML_EXPRESSION", "play_task_cond@YAML_EXPRESSION", "import_cond@YAML_EXPRESSION"), describe(text, map))
    }

    fun testVarsFilesHaveNoImplicitExpressions() {
        val text = "when: not_an_expression\nvalue: \"{{ templated }}\"\n"
        val map = uses("repos/falcon/ansible/group_vars/all/vars.yml", text)
        assertEquals(setOf("templated@YAML_TEMPLATE"), describe(text, map))
    }

    fun testArgumentSpecsVaultUnsafeAndMoleculePlatformsAreNeverIndexed() {
        val spec = """
            argument_specs:
              main:
                options:
                  haproxy_backports_version:
                    type: str
                    description: HAProxy version. Repo URL uses {{ haproxy_backports_version }}-backports.
                    default: "{{ spec_default }}"
        """.trimIndent()
        assertTrue(uses("golden/roles/haproxy/meta/argument_specs.yml", spec).isEmpty())

        val vars = "a: !vault |\n  {{ vault_body }}\nb: !unsafe '{{ unsafe_var }}'\nc: '{{ real_var }}'\n"
        assertEquals(setOf("real_var@YAML_TEMPLATE"), describe(vars, uses("golden/roles/x/defaults/main.yml", vars)))

        val molecule = """
            platforms:
              - name: "instance-${'$'}{MOLECULE_RUN_ID:-local}-{{ not_jinja }}"
            provisioner:
              inventory:
                group_vars:
                  all:
                    inv_var: "{{ molecule_inventory_ref }}"
        """.trimIndent()
        assertEquals(
            setOf("molecule_inventory_ref@YAML_TEMPLATE"),
            describe(molecule, uses("golden/roles/x/molecule/default/molecule.yml", molecule)),
        )
    }

    fun testFilesAreNotIndexed() {
        assertTrue(uses("golden/roles/x/files/script.yml", "a: '{{ x }}'").isEmpty())
    }

    // ------------------------------------------------------------------------------------------------ FU2

    /** `name@container` of each use, with `/HOSTVARS` or `/VARS` for indirect reads; offsets are checked as in [describe]. */
    private fun describeAll(text: String, map: Map<String, List<UseEntry>>): Set<String> = map.flatMap { (name, entries) ->
        entries.map { entry ->
            assertEquals("offset of $name", name, text.substring(entry.offset, entry.offset + name.length))
            "$name@${entry.container}" + (entry.indirect?.let { "/$it" } ?: "")
        }
    }.toSet()

    fun testMembersReadByNameAreIndirectEntries() {
        val text = """
            - name: Members
              ansible.builtin.debug:
                msg: "{{ hostvars[percona_donor_host].ansible_host }} {{ hostvars[groups['kb'][0]]['system_ip_floating'] | default('') }}"
            - name: Single quoted extract
              ansible.builtin.set_fact:
                _buckets: '{{ groups[''app''] | map(''extract'', hostvars, ''app_s3_bucket'') | list }}'
            - name: Vars
              ansible.builtin.debug:
                msg: "{{ vars['by_name'].attr }} {{ lookup('vars', 'looked_up', default='') }} {{ vars[dynamic_name] }}"
              when: hostvars[h].cond_member is defined and vars.cond_vars
        """.trimIndent() + "\n"
        val map = uses("repos/pelican/ansible/danger_zone/database/roles/clone/tasks/main.yml", text)
        assertEquals(
            setOf(
                "hostvars@YAML_TEMPLATE", "percona_donor_host@YAML_TEMPLATE", "ansible_host@YAML_TEMPLATE/HOSTVARS",
                "groups@YAML_TEMPLATE", "system_ip_floating@YAML_TEMPLATE/HOSTVARS", "app_s3_bucket@YAML_TEMPLATE/HOSTVARS",
                "vars@YAML_TEMPLATE", "by_name@YAML_TEMPLATE/VARS", "lookup@YAML_TEMPLATE", "looked_up@YAML_TEMPLATE/VARS",
                "dynamic_name@YAML_TEMPLATE", "hostvars@YAML_EXPRESSION", "h@YAML_EXPRESSION", "cond_member@YAML_EXPRESSION/HOSTVARS",
                "vars@YAML_EXPRESSION", "cond_vars@YAML_EXPRESSION/VARS",
            ),
            describeAll(text, map),
        )
        assertTrue(map.getValue("system_ip_floating").single().guarded)
        assertTrue(map.getValue("looked_up").single().guarded)
        assertTrue(map.getValue("cond_member").single().guarded)
        assertEquals(listOf("attr"), map.getValue("by_name").single().attrPath)
        assertTrue(map.values.flatten().filter { it.isIndirect }.none { it.called })
        assertNull("dynamic names stay out", map["_buckets"])
    }

    fun testTemplateMembersAndOffsetOrder() {
        val text = "{{ x }} {{ hostvars[groups['monitoring_client'][0]].loki_version }} {{ loki_version }}\n" +
            "{% for h in groups['all'] %}{{ hostvars[h].x }}{% endfor %}{% macro m(vars) %}{{ vars.not_a_member }}{% endmacro %}"
        val map = uses("golden/roles/loki/templates/x.conf.j2", text)
        val loki = map.getValue("loki_version")
        assertEquals("entries stay in offset order", loki.map { it.offset }.sorted(), loki.map { it.offset })
        assertEquals(listOf(JinjaIndirection.HOSTVARS, null), loki.map { it.indirect })
        assertEquals(listOf(null, JinjaIndirection.HOSTVARS), map.getValue("x").map { it.indirect })
        assertNull("for targets and macro parameters are locals", map["h"] ?: map["not_a_member"])
        assertEquals(UseContainer.TEMPLATE_FILE, loki.first().container)
    }

    fun testBracedImplicitExpressionsRecordTheNamesOutsideTheBraces() {
        val text = """
            - name: Braced
              ansible.builtin.assert:
                that:
                  - "'{{ item }}=' in coolify_env_content"
                  - "{{ a_inside }} == b_outside"
              when: "'{{ w_inside }}' in w_outside and prefix_{{ dyn }} is defined"
              changed_when: "{{ c_inside }}"
              until: "{{ u_inside }} > u_outside"
              loop: [1]
            - name: Debug braced
              ansible.builtin.debug:
                var: "{{ d_inside }} == d_outside"
            - name: Not an expression
              ansible.builtin.debug:
                msg: "'{{ m_inside }}' in m_text"
        """.trimIndent() + "\n"
        val map = uses("golden/roles/coolify/molecule/default/verify.yml", text)
        assertEquals(
            setOf(
                "item@YAML_TEMPLATE", "coolify_env_content@YAML_EXPRESSION", "a_inside@YAML_TEMPLATE", "b_outside@YAML_EXPRESSION",
                "w_inside@YAML_TEMPLATE", "w_outside@YAML_EXPRESSION", "dyn@YAML_TEMPLATE", "c_inside@YAML_TEMPLATE",
                "u_inside@YAML_TEMPLATE", "u_outside@YAML_EXPRESSION", "d_inside@YAML_TEMPLATE", "d_outside@YAML_EXPRESSION",
                "m_inside@YAML_TEMPLATE",
            ),
            describe(text, map),
        )
        assertNull("a name assembled with a braced part is dynamic", map["prefix_"])
        assertNull("text of a plain templated value is no expression", map["m_text"])
    }

    /** Member offsets inside quotes and escapes, and braced expressions across the lines of flow and block scalars. */
    fun testIndirectAndBracedOffsetsThroughEveryScalarStyle() {
        val text = """
            - name: Members
              ansible.builtin.debug:
                msg: "{{ hostvars[h][\"dq_member\"] }} {{ hostvars[h]['sq_in_dq'] }}"
            - name: Folded
              ansible.builtin.debug:
                msg: >-
                  {{ hostvars[h]['folded_member'] }}
                  {{ vars['folded_vars'] }}
            - name: Single quoted
              ansible.builtin.debug:
                msg: '{{ hostvars[h][''single_member''] }}'
            - name: Braced across lines
              ansible.builtin.assert:
                that:
                  - "{{ a_in }} ==
                    b_out"
                  - >-
                    '{{ c_in }}' in
                    d_out
                  - '''{{ e_in }}'' in f_out'
              when: "prefix_ {{- p_in }} is defined and g_out"
        """.trimIndent() + "\n"
        val map = uses("golden/roles/r/tasks/main.yml", text)
        assertEquals(
            setOf(
                "hostvars@YAML_TEMPLATE", "h@YAML_TEMPLATE", "vars@YAML_TEMPLATE",
                "dq_member@YAML_TEMPLATE/HOSTVARS", "sq_in_dq@YAML_TEMPLATE/HOSTVARS", "folded_member@YAML_TEMPLATE/HOSTVARS",
                "folded_vars@YAML_TEMPLATE/VARS", "single_member@YAML_TEMPLATE/HOSTVARS",
                "a_in@YAML_TEMPLATE", "b_out@YAML_EXPRESSION", "c_in@YAML_TEMPLATE", "d_out@YAML_EXPRESSION",
                "e_in@YAML_TEMPLATE", "f_out@YAML_EXPRESSION", "p_in@YAML_TEMPLATE", "g_out@YAML_EXPRESSION",
            ),
            describeAll(text, map),
        )
        assertNull("`{{-` strips the space, so `prefix_` is part of a name assembled at run time", map["prefix_"])
    }
}
