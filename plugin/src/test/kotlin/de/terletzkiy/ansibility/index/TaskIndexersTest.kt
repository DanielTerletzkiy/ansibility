package de.terletzkiy.ansibility.index

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The template, handler, module and play indexers on synthetic files. */
class TaskIndexersTest : BasePlatformTestCase() {
    private fun input(path: String, text: String) = IndexInput.of(path, text, project)

    private fun String.at(offset: Int, length: Int) = substring(offset, offset + length)

    fun testTemplateRenderKinds() {
        val text = """
            - name: Static
              ansible.builtin.template:
                src: templates/haproxy.cfg.j2
                dest: /etc/haproxy/haproxy.cfg
            - name: Dynamic prefix
              ansible.builtin.template:
                src: "templates/nginx/{{ item.floating.template }}"
                dest: /etc/nginx/x
              loop: "{{ loki_nginx_sites }}"
            - name: Whole var
              template:
                src: "{{ iptables_rules_file_ipv4 }}"
                dest: /etc/iptables/rules.v4
            - name: Fileglob
              ansible.builtin.template:
                src: "{{ item }}"
                dest: /etc/alloy/x
              loop: "{{ lookup('fileglob', role_path ~ '/templates/config-*.alloy.j2', wantlist=True) | sort }}"
            - name: With fileglob and loop var
              ansible.builtin.template:
                src: "{{ conf }}"
                dest: /etc/x
              with_fileglob:
                - templates/conf.d/*.j2
              loop_control:
                loop_var: conf
            - name: Free form
              ansible.legacy.template: src=free.j2 dest=/tmp/free
            - name: Lookup in YAML
              ansible.builtin.copy:
                content: "{{ lookup('ansible.builtin.template', 'rules.yml.j2', template_vars={'a': 1}) }}"
                dest: /tmp/rules
            - name: Not a template
              ansible.builtin.copy:
                src: files/x
                dest: /tmp/x
        """.trimIndent() + "\n"
        val map = TemplateUseIndexer.index(input("golden/roles/loki/tasks/nginx.yml", text))
        assertEquals(
            setOf(
                "templates/haproxy.cfg.j2", "templates/nginx/{{ item.floating.template }}", "{{ iptables_rules_file_ipv4 }}",
                "{{ item }}", "{{ conf }}", "free.j2", "rules.yml.j2",
            ),
            map.keys,
        )
        val static = map.getValue("templates/haproxy.cfg.j2").single()
        assertEquals(SrcKind.STATIC, static.srcKind)
        assertNull(static.loopVar)
        assertEquals("templates/haproxy.cfg.j2", text.at(static.srcOffset, "templates/haproxy.cfg.j2".length))
        assertEquals("- name: Static", text.at(static.taskOffset - 2, "- name: Static".length))

        val prefix = map.getValue("templates/nginx/{{ item.floating.template }}").single()
        assertEquals(SrcKind.DYNAMIC_PREFIX, prefix.srcKind)
        assertEquals(listOf("item", "floating", "template"), prefix.dynamicVarPath)
        assertEquals("{{ loki_nginx_sites }}", prefix.loopExprText)
        assertEquals("item", prefix.loopVar)

        val whole = map.getValue("{{ iptables_rules_file_ipv4 }}").single()
        assertEquals(SrcKind.WHOLE_VAR, whole.srcKind)
        assertEquals(listOf("iptables_rules_file_ipv4"), whole.dynamicVarPath)

        val glob = map.getValue("{{ item }}").single()
        assertEquals(SrcKind.FILEGLOB, glob.srcKind)
        assertEquals("/templates/config-*.alloy.j2", glob.globPattern)
        val withGlob = map.getValue("{{ conf }}").single()
        assertEquals(SrcKind.FILEGLOB, withGlob.srcKind)
        assertEquals("conf", withGlob.loopVar)

        assertEquals(SrcKind.STATIC, map.getValue("free.j2").single().srcKind)
        val lookup = map.getValue("rules.yml.j2").single()
        assertEquals(SrcKind.LOOKUP, lookup.srcKind)
        assertEquals("rules.yml.j2", text.at(lookup.srcOffset, "rules.yml.j2".length))
    }

    fun testTemplateIncludesAndLookupsInTemplates() {
        val text = "{% include 'deployment/part.yml.j2' %}\n{%- import \"macros.j2\" as m -%}\n" +
            "{% from 'm2.j2' import x %}{% extends 'base.j2' %}\n{{ lookup('template', 'nested.j2') }}\n"
        val map = TemplateUseIndexer.index(input("repos/thrush/ansible/roles/app/templates/deployment/docker-compose.yml.j2", text))
        assertEquals(setOf("deployment/part.yml.j2", "macros.j2", "m2.j2", "base.j2", "nested.j2"), map.keys)
        assertEquals(SrcKind.INCLUDE, map.getValue("macros.j2").single().srcKind)
        assertEquals(SrcKind.LOOKUP, map.getValue("nested.j2").single().srcKind)
        val part = map.getValue("deployment/part.yml.j2").single()
        assertEquals("deployment/part.yml.j2", text.at(part.srcOffset, "deployment/part.yml.j2".length))
    }

    fun testHandlersByNameAndListenTopic() {
        val text = """
            - name: Restart filebeat
              ansible.builtin.systemd:
                name: filebeat
            - name: Reload systemd
              listen: Reload systemd
              ansible.builtin.systemd:
                daemon_reload: true
            - name: Reload haproxy
              listen:
                - haproxy changed
                - config changed
              ansible.builtin.systemd:
                name: haproxy
        """.trimIndent() + "\n"
        val map = HandlerIndexer.index(input("golden/roles/haproxy/handlers/main.yml", text))
        assertEquals(setOf("Restart filebeat", "Reload systemd", "Reload haproxy", "haproxy changed", "config changed"), map.keys)
        assertEquals(listOf(false, true), map.getValue("Reload systemd").map { it.listen })
        val reload = map.getValue("Reload haproxy").single()
        assertEquals("Reload haproxy", text.at(reload.offset, "Reload haproxy".length))
        assertFalse("tasks outside handlers/ are not handlers", HandlerIndexer.index(input("golden/roles/haproxy/tasks/main.yml", text)).isNotEmpty())

        val play = """
            - hosts: all
              tasks:
                - name: Not a handler
                  ansible.builtin.command: echo
              handlers:
                - name: Play handler
                  ansible.builtin.command: echo
        """.trimIndent()
        assertEquals(setOf("Play handler"), HandlerIndexer.index(input("repos/falcon/ansible/playbook-x.yml", play)).keys)
    }

    fun testModuleUses() {
        val text = """
            - name: Configure server
              ansible.builtin.template:
                src: templates/haproxy.cfg.j2
              notify: Reload haproxy
            - name: Short name
              apt: name=x
            - name: Legacy action
              action: shell echo hi
            - name: Block
              block:
                - community.general.jenkins_plugin:
                    name: x
                    with_dependencies: false
            - name: Typo
              become_usr: root
              ansible.builtin.command: echo
        """.trimIndent() + "\n"
        val map = ModuleUseIndexer.index(input("golden/roles/haproxy/tasks/configure.yml", text))
        assertEquals(
            setOf("ansible.builtin.template", "apt", "shell", "community.general.jenkins_plugin", "ansible.builtin.command"),
            map.keys,
        )
        val template = map.getValue("ansible.builtin.template").single()
        assertEquals("ansible.builtin.template", text.at(template, "ansible.builtin.template".length))
        assertTrue(ModuleUseIndexer.index(input("golden/roles/haproxy/defaults/main.yml", "a: 1")).isEmpty())

        val imports = ModuleUseIndexer.index(input("repos/falcon/ansible/site.yml", "- ansible.builtin.import_playbook: a.yml\n"))
        assertEquals(setOf("ansible.builtin.import_playbook"), imports.keys)
    }

    fun testPlays() {
        val text = """
            - name: Ping all hosts serially
              hosts: all
              tasks:
                - name: Include
                  ansible.builtin.include_role:
                    name: keycloak
                    tasks_from: configuration
            - name: System
              hosts:
                - system
                - web
              vars:
                a: 1
              vars_files:
                - vars/common.yml
              roles:
                - { role: system, tags: ['system'] }
                - postfix
            - ansible.builtin.import_playbook: playbook-setup-apps.yml
        """.trimIndent() + "\n"
        val plays = PlayIndexer.index(input("repos/falcon/ansible/playbook-setup-system.yml", text)).getValue(PlayIndex.KEY)
        assertEquals(3, plays.size)
        val (ping, system, import) = plays
        assertEquals("Ping all hosts serially", ping.name)
        assertEquals("all", ping.hosts)
        assertEquals(listOf(PlayRoleUse("keycloak", "configuration", ping.roles.single().offset, RoleUseKind.INCLUDE_ROLE)), ping.roles)
        assertEquals("system,web", system.hosts)
        assertEquals(listOf("system", "postfix"), system.roles.map { it.name })
        assertTrue(system.roles.all { it.kind == RoleUseKind.ROLES && it.entryPoint == null })
        assertEquals("system", text.at(system.roles[0].offset, "system".length))
        assertEquals(listOf("a"), system.varsKeys)
        assertEquals(listOf("vars/common.yml"), system.varsFiles)
        assertEquals("playbook-setup-apps.yml", import.importPlaybook)
        assertNull(import.hosts)
        assertTrue(PlayIndexer.index(input("golden/roles/x/tasks/main.yml", "- ansible.builtin.command: echo\n")).isEmpty())
    }
}
