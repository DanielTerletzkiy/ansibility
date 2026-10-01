package de.terletzkiy.ansibility.lang.jinja.injection

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.openapi.project.DumbService
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaFileType
import de.terletzkiy.ansibility.lang.jinja.filetype.J2Mappings
import org.jetbrains.yaml.YAMLFileType

/**
 * [AnsibleJinjaYamlInjector] (plan A.5 "Jinja inside YAML", F2.2, M5 acceptance 5 and 7): which scalars get Jinja,
 * in which mode, with which text, and where the gate keeps it out.
 */
class AnsibleJinjaYamlInjectorTest : JinjaInjectionTestCase() {

    fun testIsRegisteredAndDumbAware() {
        val injectors = MultiHostInjector.MULTIHOST_INJECTOR_EP_NAME.getExtensions(project)
        val ours = injectors.filterIsInstance<AnsibleJinjaYamlInjector>().single()
        assertTrue(DumbService.isDumbAware(ours))
    }

    /** Acceptance 5: a double-quoted scalar with `'\n'` and `{% endfor%}` (no space) is one template, decoded. */
    fun testDoubleQuotedTemplateWithEscapesAndTightEndTag() {
        copyInfra("golden/roles/deployment-target")
        val tasks = "golden/roles/deployment-target/tasks/main.yml"
        val fragment = fragmentAt(tasks, offsetAt(tasks, 50, "{% for keyfile"))
        assertEquals(JinjaInjectionMode.TEMPLATE, fragment.mode)
        assertEquals(
            "{% for keyfile in deployment_target_builder_public_key_files %}{{ lookup('file', keyfile) ~ '\n' }}{% endfor%}\n",
            fragment.text,
        )
        assertEmpty(fragment.errors)
        val host = text(tasks).substring(fragment.hostRange.startOffset, fragment.hostRange.endOffset)
        assertTrue("from the value's start (after the quote): $host", host.startsWith("{% for keyfile"))
        assertTrue("the trailing escape is read-only fragment text: $host", host.endsWith("{% endfor%}"))
        assertEquals(fragment.scalar.startOffset + 1, fragment.hostRange.startOffset)
    }

    /** Acceptance 5: a folded `when: >` is one bare expression, wrapped in `{{ ` and ` }}`, across its lines. */
    fun testFoldedWhenIsAnExpression() {
        copyInfra("golden/roles/jenkins-controller")
        val tasks = "golden/roles/jenkins-controller/tasks/jenkins.yml"
        val fragment = fragmentAt(tasks, offsetAt(tasks, 138, "jenkins_default_users_config"))
        assertEquals(JinjaInjectionMode.EXPRESSION, fragment.mode)
        assertTrue(fragment.text, fragment.text.startsWith("{{ (jenkins_default_users_config is defined and jenkins_default_users_config.changed)"))
        assertTrue(fragment.text, fragment.text.endsWith(" }}"))
        assertTrue("the block's indentation is not part of the expression", fragment.text.contains("changed)\nor (jenkins_proxy_config"))
        assertEmpty(fragment.errors)
    }

    /** Acceptance 5: a `{% for item %}` in a literal block scalar is a template; the block's indentation is not in it. */
    fun testLiteralBlockTemplate() {
        copyInfra("golden/roles/system")
        val tasks = "golden/roles/system/tasks/dns.yml"
        val fragment = fragmentAt(tasks, offsetAt(tasks, 27, "{% for item"))
        assertEquals(JinjaInjectionMode.TEMPLATE, fragment.mode)
        assertTrue(fragment.text, fragment.text.startsWith("{% for item in system_hosts_file_entries %}\n{{ item.ip }} {{ item.hostname }}\n{% endfor %}"))
        assertEmpty(fragment.errors)
    }

    fun testImplicitExpressionsAndTemplates() {
        createFile("site/ansible.cfg", "[defaults]")
        val tasks = "site/roles/web/tasks/main.yml"
        createFile(
            tasks,
            """
            - name: Check
              ansible.builtin.stat:
                path: "/etc/{{ web_dir }}/x"
              register: web_stat
              changed_when: false
              failed_when: web_stat.failed and not web_ok
              until: web_stat is succeeded
            - name: Show
              ansible.builtin.debug:
                var: web_stat.stat.exists
              when:
                - web_stat.stat.exists
                - "{{ web_enabled }}"
            - name: Assert
              ansible.builtin.assert:
                that:
                  - web_port | int > 0
            - name: Plain
              ansible.builtin.debug:
                msg: no jinja here
            """,
        )
        val all = fragments(tasks)
        val fragments = all.associateBy { it.text }
        assertEquals(JinjaInjectionMode.TEMPLATE, fragments["/etc/{{ web_dir }}/x"]?.mode)
        assertEquals(JinjaInjectionMode.EXPRESSION, fragments["{{ web_stat.failed and not web_ok }}"]?.mode)
        assertEquals(JinjaInjectionMode.EXPRESSION, fragments["{{ web_stat is succeeded }}"]?.mode)
        assertEquals(JinjaInjectionMode.EXPRESSION, fragments["{{ web_stat.stat.exists }}"]?.mode)
        assertEquals("debug.var and the when list item", 2, all.count { it.text == "{{ web_stat.stat.exists }}" })
        assertEquals("a when item with braces is a template, not wrapped (X30)", JinjaInjectionMode.TEMPLATE, fragments["{{ web_enabled }}"]?.mode)
        assertEquals(JinjaInjectionMode.EXPRESSION, fragments["{{ web_port | int > 0 }}"]?.mode)
        assertNull("booleans are not templated", fragments.keys.firstOrNull { "false" in it })
        assertNull(fragments.keys.firstOrNull { "no jinja" in it })
        assertEquals(7, all.size)
        assertTrue(all.all { it.errors.isEmpty() })
    }

    /**
     * Jinja reads the decoded value: YAML escapes and doubled quotes never reach the Jinja lexer, nor does a block
     * scalar's indentation (its final line break is not part of the host's literal text, as in the YAML plugin).
     */
    fun testFragmentsReadTheDecodedValue() {
        createFile("site/ansible.cfg", "[defaults]")
        val tasks = "site/roles/web/tasks/main.yml"
        createFile(
            tasks,
            """
            - ansible.builtin.debug:
                msg: "{{ web_x | default(\"a\") }}\t{{ web_y }}"
            - ansible.builtin.debug:
                msg: '{{ web_x | default(''b'') }}'
            - ansible.builtin.debug:
                msg: |
                  {% if web_x %}
                    {{ web_y }}
                  {% endif %}
              when: web_x == "it's"
            """,
        )
        val fragments = fragments(tasks)
        assertEquals(
            listOf(
                "{{ web_x | default(\"a\") }}\t{{ web_y }}",
                "{{ web_x | default('b') }}",
                "{% if web_x %}\n  {{ web_y }}\n{% endif %}",
                "{{ web_x == \"it's\" }}",
            ),
            fragments.map { it.text },
        )
        assertTrue(fragments.joinToString { it.errors.toString() }, fragments.all { it.errors.isEmpty() })
    }

    fun testExpressionOffsetsMapThroughThePrefix() {
        createFile("site/ansible.cfg", "[defaults]")
        val tasks = "site/roles/web/tasks/main.yml"
        createFile(tasks, "- name: Show\n  ansible.builtin.debug:\n    msg: x\n  when: web_port > 0\n")
        val fragment = fragmentAt(tasks, offsetAt(tasks, 4, "web_port"))
        assertEquals("{{ web_port > 0 }}", fragment.text)
        assertEquals("web_port > 0", text(tasks).substring(fragment.hostRange.startOffset, fragment.hostRange.endOffset))
    }

    fun testNeverInjected() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile(
            "site/roles/web/meta/argument_specs.yml",
            """
            argument_specs:
              main:
                options:
                  web_dir:
                    type: str
                    description: Used as {{ web_dir }}/x.
                    default: "{{ web_base }}/x"
            """,
        )
        createFile(
            "site/roles/web/defaults/main.yml",
            """
            web_secret: !vault |
              ${'$'}ANSIBLE_VAULT;1.1;AES256
              64756d6d79
            web_raw: !unsafe "{{ not_jinja }}"
            web_dir: "{{ web_base }}/x"
            """,
        )
        createFile(
            "site/roles/web/molecule/default/molecule.yml",
            """
            driver:
              name: docker
            platforms:
              - name: web-${'$'}{MOLECULE_RUN_ID:-local}
                image: "{{ molecule_image }}"
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    web_dir: "{{ web_base }}/molecule"
            """,
        )
        createFile("site/docker-compose.yml", "services:\n  web:\n    image: \"{{ not_ansible }}\"\n")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: \"{{ web_dir }}\"\n")

        assertEmpty("argument specs are documentation", fragments("site/roles/web/meta/argument_specs.yml"))
        assertEquals("vault and !unsafe scalars are skipped", listOf("{{ web_base }}/x"), fragments("site/roles/web/defaults/main.yml").map { it.text })
        assertEquals(
            "molecule configuration outside provisioner.inventory is not Ansible's",
            listOf("{{ web_base }}/molecule"),
            fragments("site/roles/web/molecule/default/molecule.yml").map { it.text },
        )
        assertEmpty("compose files use \${VAR}", fragments("site/docker-compose.yml"))
    }

    /** Acceptance 7: GitHub Actions `${{ }}` outside every Ansible root, and in a non-Ansible file of a root. */
    fun testNoInjectionOutsideAnsibleFiles() {
        val workflow = """
            on: push
            jobs:
              build:
                if: ${'$'}{{ github.event_name == 'push' }}
                runs-on: ubuntu-latest
                steps:
                  - run: echo "${'$'}{{ github.ref }}"
                    when: ${'$'}{{ github.ref }}
            """
        createFile(".github/workflows/ci.yml", workflow)
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/.github/workflows/ci.yml", workflow)
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: \"{{ web_dir }}\"\n")
        assertEmpty(fragments(".github/workflows/ci.yml"))
        assertEmpty(fragments("site/.github/workflows/ci.yml"))
        assertEquals("the root's own task file is injected", 1, fragments("site/roles/web/tasks/main.yml").size)
    }

    /** Template files are Jinja as a whole: their YAML is never injected into, whatever the `.j2` type. */
    fun testTemplateFilesAreNotInjectedInto() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/roles/web/templates/config.yml.j2", "key: \"{{ web_dir }}\"\n")
        createFile("site/roles/web/templates/plain.yml", "key: \"{{ web_dir }}\"\n")
        assertEquals(AnsibleJinjaFileType, vf("site/roles/web/templates/config.yml.j2").fileType)
        assertEmpty(fragments("site/roles/web/templates/config.yml.j2"))
        assertEmpty("a Jinja-bearing file under templates is a template file", fragments("site/roles/web/templates/plain.yml"))
        J2Mappings.withJ2As(YAMLFileType.YML) {
            assertEquals(YAMLFileType.YML, vf("site/roles/web/templates/config.yml.j2").fileType)
            assertEmpty("a .j2 kept as YAML is still a template", fragments("site/roles/web/templates/config.yml.j2"))
        }
    }

    fun testPlaybookLevelTaskFilesAndInventories() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: x\n")
        createFile("site/tasks/common.yml", "- ansible.builtin.debug:\n    msg: x\n  when: site_flag | bool\n")
        createFile("site/vars/common.yml", "site_dir: \"{{ site_base }}/x\"\n")
        createFile("site/environments/dev/hosts.yml", "all:\n  vars:\n    site_url: \"https://{{ inventory_hostname }}\"\n  hosts:\n    web1:\n")
        createFile("site/notes/other.yml", "x: \"{{ not_ansible }}\"\n")
        assertEquals(listOf("{{ site_flag | bool }}"), fragments("site/tasks/common.yml").map { it.text })
        assertEquals(listOf("{{ site_base }}/x"), fragments("site/vars/common.yml").map { it.text })
        assertEquals(listOf("https://{{ inventory_hostname }}"), fragments("site/environments/dev/hosts.yml").map { it.text })
        assertEmpty("a YAML file of no Ansible kind", fragments("site/notes/other.yml"))
    }

    /** Injected syntax errors are real: Ansible fails on them too. */
    fun testBrokenJinjaReportsErrors() {
        createFile("site/ansible.cfg", "[defaults]")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: \"{{ web_dir \"\n")
        val fragment = fragments("site/roles/web/tasks/main.yml").single()
        assertEquals("{{ web_dir ", fragment.text)
        assertFalse(fragment.errors.isEmpty())
    }

    fun testHighlightingShowsNoErrorsOnTheFixture() {
        copyInfra("golden/roles/deployment-target", "golden/roles/jenkins-controller", "golden/roles/system")
        for (path in listOf(
            "golden/roles/deployment-target/tasks/main.yml",
            "golden/roles/jenkins-controller/tasks/jenkins.yml",
            "golden/roles/system/tasks/dns.yml",
        )) {
            myFixture.configureFromExistingVirtualFile(vf(path))
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            assertEmpty("$path: ${errors.map { it.description }}", errors)
        }
    }
}
