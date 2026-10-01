package de.terletzkiy.ansibility.inspections.modules

import com.intellij.lang.annotation.HighlightSeverity
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.diagnostics.Preset
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.DocsMismatchSeverity

/**
 * ANS-M001, ANS-M002 and the module option value checks through the highlighting pass (plan F3.3, F5.6; M4
 * acceptance 5, 6 and 7): typing a bad value into a real task file and undoing it, the cases that must stay clean,
 * the exemptions, the D5 coercion toggle, the docs-mismatch cap and the quick fixes.
 */
class ModuleOptionInspectionTest : ModuleChecksTestCase() {

    // ------------------------------------------------------------------------------------------------ M4 acceptance 7

    fun testAssertVarsIsRedAndMovesToTaskLevel() {
        copyInfra("repos/platform")
        completeMysqlDatabasesRole()
        pinTarget()
        val infos = run {
            myFixture.configureFromTempProjectFile(VERIFY)
            infos()
        }
        assertEquals(
            listOf(
                "34: ERROR AnsibleUnknownModuleOption: Unsupported option `vars` for ansible.builtin.assert: `vars` is a task " +
                    "keyword, which belongs at task level; ansible-core 2.18.8 fails the task (Invalid options for ansible.builtin.assert: vars)",
            ),
            infos.map { "${line(it.startOffset)}: ${it.severity.name} ${it.inspectionToolId}: ${it.description}" },
        )
        assertEquals("vars", text(infos.single()))
        myFixture.editor.caretModel.moveToOffset(infos.single().startOffset)
        applyFix("Move 'vars' to task level")
        assertEquals(
            """
                - name: Assert molecule_db_one has the requested encoding and collation
                  ansible.builtin.assert:
                    that:
                      - db_one | length == 1
                      - db_one[0].encoding == 'utf8mb4'
                      - db_one[0].collation == 'utf8mb4_unicode_ci'
                    fail_msg: "molecule_db_one has wrong encoding/collation: {{ db_one }}"
                  vars:
                    db_one: "{{ schemata.query_result[0] | selectattr('name', 'equalto', 'molecule_db_one') | list }}"
            """.trimIndent(),
            (28..36).joinToString("\n") { lineText(it) }.trimIndent(),
        )
        assertEmpty(infos())
    }

    // ------------------------------------------------------------------------------------------------ M4 acceptance 5

    fun testTypingAbsentxUnderFileIsRedT004AndUndoingIsClean() {
        copyInfra("golden/roles/haproxy")
        myFixture.configureFromTempProjectFile(CONFIGURE)
        assertEmpty(infos())
        type(14, "state: directory", "state: absentx")
        val info = infos().single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals("AnsibleModuleOptionValue", info.inspectionToolId)
        assertEquals("absentx", text(info))
        assertTrue(info.description, info.description.startsWith("ansible-core 2.18.8 would reject `state` for module `ansible.builtin.file`: value of state must be one of:"))
        assertTrue(info.description, info.description.endsWith("got: absentx"))
        type(14, "state: absentx", "state: directory")
        assertEmpty(infos())
    }

    fun testNearestChoiceFix() {
        copyInfra("golden/roles/haproxy")
        myFixture.configureFromTempProjectFile(CONFIGURE)
        type(14, "state: directory", "state: absentx")
        myFixture.editor.caretModel.moveToOffset(infos().single().startOffset)
        applyFix("Replace with 'absent'")
        assertEquals("    state: absent", lineText(14))
        assertEmpty(infos())
    }

    fun testForceMaybeIsRedT001() {
        copyInfra("golden/roles/haproxy")
        myFixture.configureFromTempProjectFile(CONFIGURE)
        type(17, "group: root", "group: root\n    force: maybe")
        val info = infos().single()
        assertEquals(HighlightSeverity.ERROR, info.severity)
        assertEquals("AnsibleModuleOptionValue", info.inspectionToolId)
        assertEquals(18, line(info.startOffset))
        assertTrue(info.description, info.description.startsWith("ansible-core 2.18.8 would reject `force` for module `ansible.builtin.file`:"))
        assertTrue(info.description, "'maybe' is not a valid boolean" in info.description)
    }

    fun testListForIntIsRedAndUnknownSubKeyIsRedT002() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/broken.yml"
        createFile(
            path,
            """
            - name: Wait
              ansible.builtin.wait_for:
                port: [1, 2]
                timeout: 5
            - name: Container
              community.docker.docker_container:
                name: web
                healthcheck:
                  test: ["CMD", "true"]
                  intervall: 30s
            """.trimIndent() + "\n",
        )
        val lines = highlights(path)
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("3: ERROR AnsibleModuleOptionValue: ansible-core 2.18.8 would reject `port` for module `ansible.builtin.wait_for`:"))
        assertTrue(lines[1], lines[1].startsWith("10: ERROR AnsibleModuleOptionValue: Unsupported key `intervall` in `healthcheck` for module `community.docker.docker_container`"))
        assertContainsElements(myFixture.getAllQuickFixes().map { it.text }, "Rename to 'interval'")
    }

    // ------------------------------------------------------------------------------------------------ M4 acceptance 6

    fun testRealCasesStayClean() {
        copyInfra("golden/roles/jenkins-controller", "golden/roles/chronod", "golden/roles/haproxy", "repos/falcon")
        val jenkins = "golden/roles/jenkins-controller/tasks/jenkins.yml"
        myFixture.configureFromTempProjectFile(jenkins)
        assertEquals("    follow_redirects: false", lineText(156))
        assertEmpty(infos())
        myFixture.configureFromTempProjectFile("golden/roles/chronod/tasks/install.yml")
        assertEquals("        follow_redirects: none", lineText(25))
        assertEmpty(infos())
        myFixture.configureFromTempProjectFile(JENKINS_AGENT)
        assertEquals("    owner: 1000", lineText(6))
        assertEmpty(infos())
        myFixture.configureFromTempProjectFile(CONFIGURE)
        assertEquals("    mode: \"0644\"", lineText(8))
        assertEmpty(infos())
    }

    fun testOwner1000IsACoercionShownOnlyWithTheToggleOrStrict() {
        copyInfra("repos/falcon")
        myFixture.configureFromTempProjectFile(JENKINS_AGENT)
        assertEmpty(infos())
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(moduleOptionCoercions = true) }
        val toggled = infos().filter { line(it.startOffset) == 6 }
        assertEquals(listOf("AnsibleModuleOptionCoercion"), toggled.map { it.inspectionToolId })
        assertEquals(HighlightSeverity.ERROR, toggled.single().severity)
        assertEquals("1000", text(toggled.single()))
        assertTrue(toggled.single().description, "would coerce `1000` → `'1000'`" in toggled.single().description)
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(moduleOptionCoercions = false, preset = Preset.RUNTIME_FAITHFUL) }
        assertEmpty(infos())
        AnsibilityProjectSettings.getInstance(project).updateRoot(FALCON) { it.copy(preset = Preset.STRICT) }
        assertEquals(HighlightSeverity.ERROR, infos().first { line(it.startOffset) == 6 }.severity)
    }

    // ------------------------------------------------------------------------------------------------ exemptions

    fun testExemptionsOfUnknownOptions() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/exempt.yml"
        createFile(
            path,
            """
            - ansible.builtin.command: echo hi chdir=/tmp
            - ansible.builtin.shell:
                cmd: echo hi
                executable: /bin/bash
            - ansible.builtin.set_fact:
                anything: 1
                cacheable: true
            - ansible.builtin.copy: "{{ copy_args }}"
            - ansible.builtin.copy:
                dest: /tmp/x
              args: "{{ more_args }}"
            - community.general.not_a_module_anywhere:
                whatever: 1
            - ansible.builtin.fetch:
                src: /etc/hosts
                dest: /tmp/hosts
                bogus: 1
            - ansible.builtin.package:
                name: vim
                update_cache: true
            - ansible.builtin.template:
                src: templates/haproxy.cfg.j2
                dest: /etc/x
                remote_src: false
            - ansible.builtin.include_role:
                role: haproxy
            - ansible.builtin.debug:
                msg: ~
            """.trimIndent() + "\n",
        )
        assertEmpty(highlights(path))
    }

    fun testArgsAndKeyValueOptionsAreCheckedLikeTheMapping() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/args.yml"
        createFile(
            path,
            """
            - ansible.builtin.file:
                path: /tmp/x
              args:
                stat: directory
            - ansible.builtin.file: path=/tmp/y sate=touch
            - ansible.builtin.file: path=/tmp/z state=absentx
            """.trimIndent() + "\n",
        )
        val lines = highlights(path)
        assertEquals(3, lines.size)
        assertTrue(lines[0], lines[0].startsWith("4: ERROR AnsibleUnknownModuleOption: Unsupported option `stat` for ansible.builtin.file — did you mean `state`?"))
        assertTrue(lines[0], lines[0].endsWith("(Unsupported parameters for (ansible.builtin.file) module: stat)"))
        assertTrue(lines[1], lines[1].startsWith("5: ERROR AnsibleUnknownModuleOption: Unsupported option `sate` for ansible.builtin.file"))
        assertTrue(lines[2], lines[2].startsWith("6: ERROR AnsibleModuleOptionValue:"))
    }

    // ------------------------------------------------------------------------------------------------ ANS-M002

    fun testMissingRequiredOptionAndAddFix() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/missing.yml"
        createFile(
            path,
            """
            - name: Render
              ansible.builtin.template:
                src: templates/haproxy.cfg.j2
            - name: Alias counts
              ansible.builtin.file:
                dest: /tmp/x
            - name: Free form
              ansible.builtin.raw: uptime
            - name: Meta
              ansible.builtin.meta: flush_handlers
            - name: Set
              ansible.builtin.set_fact:
                a: 1
            - name: Templated
              ansible.builtin.template: "{{ template_args }}"
            """.trimIndent() + "\n",
        )
        val lines = highlights(path)
        assertEquals(
            listOf(
                "2: ERROR AnsibleMissingModuleOption: Missing required option `dest` for ansible.builtin.template: ansible-core 2.18.8 " +
                    "fails the task (src and dest are required)",
            ),
            lines,
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("ansible.builtin.template"))
        applyFix("Add option 'dest'")
        assertEquals("    dest: \"\"", lineText(4))
        assertEmpty(highlights(path))
    }

    fun testModuleDefaultsAnywhereSilencesMissingOptions() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/missing.yml"
        createFile(path, "- ansible.builtin.template:\n    src: templates/haproxy.cfg.j2\n")
        assertEquals(1, highlights(path).size)
        createFile("golden/playbooks/defaults.yml", "- hosts: all\n  module_defaults:\n    ansible.builtin.template:\n      dest: /tmp/x\n  roles:\n    - haproxy\n")
        assertEmpty(highlights(path))
    }

    // ------------------------------------------------------------------------------------------------ docs vs target

    fun testDocsOfAnotherLineCapAtWarningOrHide() {
        copyInfra("repos/platform")
        completeMysqlDatabasesRole()
        TargetVersionDetector.getInstance(project).overrideFor = { CoreVersion(2, 20, 0) }
        myFixture.configureFromTempProjectFile(VERIFY)
        val info = infos().single()
        assertEquals(HighlightSeverity.WARNING, info.severity)
        assertTrue(info.description, info.description.endsWith("(documentation from ansible-core 2.21.4 bundled + latest collections; the target is ansible-core 2.20.0)"))
        AnsibilityProjectSettings.getInstance(project).updateRoot(PLATFORM) { it.copy(unknownModuleOptionWhenDocsDiffer = DocsMismatchSeverity.OFF) }
        assertEmpty(infos())
    }

    private companion object {
        const val CONFIGURE = "golden/roles/haproxy/tasks/configure.yml"
        const val JENKINS_AGENT = "repos/falcon/ansible/roles/jenkins-agent-docker/tasks/main.yml"
        const val FALCON = "repos/falcon/ansible"
        const val PLATFORM = "repos/platform/ansible"
    }
}
