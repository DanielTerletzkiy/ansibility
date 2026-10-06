package de.terletzkiy.ansibility.inspections.references

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.text.StringUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.navigation.RefsTestCase
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * ANS-R001 through the highlighting pass (plan A.6, D7; M3 acceptance 6): the broken `tasks_from` of the keycloak
 * playbook, the exemptions (remote_src, templated values, host paths, open search paths) and the quick fix.
 */
@RequiresInfraFixture
class UnresolvedReferenceInspectionTest : RefsTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(AnsibleUnresolvedReferenceInspection())
    }

    /** The ANS-R001 highlights of [path] as `line: description`. */
    private fun problems(path: String): List<String> = highlights(path).map { "${line(it)}: ${it.description}" }

    private fun highlights(path: String): List<HighlightInfo> {
        myFixture.configureFromTempProjectFile(path)
        return myFixture.doHighlighting().filter { it.inspectionToolId == SHORT_NAME }.sortedBy { it.startOffset }
    }

    private fun line(info: HighlightInfo): Int = StringUtil.offsetToLineNumber(myFixture.editor.document.charsSequence, info.startOffset) + 1

    private fun lineText(line: Int): String {
        val document = myFixture.editor.document
        return document.charsSequence.subSequence(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).toString()
    }

    private fun applyFix(name: String) {
        val fix = myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no fix '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")
        myFixture.launchAction(fix)
    }

    // ------------------------------------------------------------------------------------------------ M3 acceptance 6

    fun testKeycloakTasksFromIsRedAndTheFixGivesConfiguration() {
        copyInfra("golden/roles/keycloak", "golden/playbooks")
        val infos = highlights(KEYCLOAK_PLAYBOOK)
        assertEquals(listOf("51: 'configure' does not exist in tasks/ of role 'keycloak'"), infos.map { "${line(it)}: ${it.description}" })
        assertEquals(HighlightSeverity.ERROR, infos.single().severity)
        assertEquals("configure", myFixture.editor.document.charsSequence.subSequence(infos.single().startOffset, infos.single().endOffset).toString())
        myFixture.editor.caretModel.moveToOffset(infos.single().startOffset)
        applyFix("Change to 'configuration'")
        assertEquals("        tasks_from: configuration", lineText(51))
        assertEmpty(problems(KEYCLOAK_PLAYBOOK))
    }

    fun testRealRemoteSourceCopyStaysClean() {
        copyInfra("golden/roles/loki", "golden/roles/docker")
        val prepare = "golden/roles/loki/molecule/default/prepare.yml"
        myFixture.configureFromTempProjectFile(prepare)
        assertEquals("the real remote_src copy (line 79)", "        src: /etc/ssl/molecule.example.test.chain.crt", lineText(80))
        assertEquals("        remote_src: true", lineText(82))
        assertEquals(
            "only the role the sanitised fixture leaves out (golden/roles/nginx) is missing",
            listOf("165: Role '/ansible/roles/nginx' cannot be found"),
            problems(prepare),
        )
    }

    // ------------------------------------------------------------------------------------------------ certain failures

    fun testMissingTargetsInARoleAreReportedWithTheNearestName() {
        copyInfra("golden/roles/haproxy")
        val path = "golden/roles/haproxy/tasks/broken.yml"
        createFile(
            path,
            """
            - ansible.builtin.include_tasks: configur.yml
            - ansible.builtin.template:
                src: templates/haproxy.cfg.j3
                dest: /etc/haproxy/haproxy.cfg
              notify: "Reload haprox"
            - ansible.builtin.copy:
                src: files/overide.conf
                dest: /etc/x
            - ansible.builtin.copy:
                src: files/overide.conf
                remote_src: true
                dest: /etc/x
            - ansible.builtin.template:
                src: "{{ haproxy_template }}"
                dest: /etc/x
            - ansible.builtin.copy:
                src: /etc/haproxy/haproxy.cfg
                dest: /tmp/haproxy.cfg
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "1: Task file 'configur.yml' does not exist",
                "3: Template 'templates/haproxy.cfg.j3' does not exist",
                "5: No handler named 'Reload haprox' (name or listen topic) in the play scope",
                "7: File 'files/overide.conf' does not exist",
            ),
            problems(path),
        )
        val fixes = myFixture.getAllQuickFixes().map { it.text }.filter { it.startsWith("Change to") }.sorted()
        assertEquals(
            listOf("Change to 'Reload haproxy'", "Change to 'configure.yml'", "Change to 'files/override.conf'", "Change to 'templates/haproxy.cfg.j2'"),
            fixes,
        )
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Reload haprox"))
        applyFix("Change to 'Reload haproxy'")
        assertEquals("the quotes stay", "  notify: \"Reload haproxy\"", lineText(5))
    }

    fun testRoleFilesOfIncludes() {
        copyInfra("golden/roles/grafana")
        val path = "golden/roles/grafana/molecule/default/broken.yml"
        createFile(
            path,
            """
            - name: Broken includes
              hosts: all
              tasks:
                - ansible.builtin.include_role:
                    name: /ansible/roles/grafana
                    tasks_from: alertin.yml
                    handlers_from: molecule
                - ansible.builtin.import_role:
                    name: /ansible/roles/grafna
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "6: 'alertin.yml' does not exist in tasks/ of role 'grafana'",
                "9: Role '/ansible/roles/grafna' cannot be found",
            ),
            problems(path),
        )
        assertContainsElements(myFixture.getAllQuickFixes().map { it.text }, "Change to 'alerting.yml'", "Change to '/ansible/roles/grafana'")
    }

    fun testPlayLevelFilesAndHandlers() {
        copyInfra("golden/roles/coolify")
        val path = "golden/roles/coolify/molecule/default/broken.yml"
        createFile(
            path,
            """
            - name: Play
              hosts: all
              vars_files:
                - ../vars/missing.yml
                - [../vars/absent.yml, ../vars/vars.yml]
              tasks:
                - ansible.builtin.debug:
                    msg: hi
                  notify: Unknown handler
              handlers:
                - name: Known handler
                  ansible.builtin.debug:
                    msg: done
            - ansible.builtin.import_playbook: converg.yml
            """.trimIndent() + "\n",
        )
        assertEquals(
            listOf(
                "4: Vars file '../vars/missing.yml' does not exist",
                "9: No handler named 'Unknown handler' (name or listen topic) in the play scope",
                "14: Playbook 'converg.yml' does not exist",
            ),
            problems(path),
        )
        assertContainsElements(myFixture.getAllQuickFixes().map { it.text }, "Change to 'converge.yml'", "Change to 'Known handler'")
    }

    fun testIncludesInTemplates() {
        copyInfra("repos/thrush")
        createFile("repos/thrush/ansible/roles/app-thrush-mono/tasks/main.yml", "---\n")
        val path = "repos/thrush/ansible/roles/app-thrush-mono/templates/deployment/broken.yml.j2"
        createFile(
            path,
            "{% include 'deployment/docker-compose.scheduler.yml.j2' %}\n" +
                "{% include 'deployment/docker-compose.shceduler.yml.j2' %}\n" +
                "{% include 'deployment/optional.yml.j2' ignore missing %}\n" +
                "{% raw %}{% include 'not/a/tag.j2' %}{% endraw %}\n",
        )
        assertEquals(listOf("2: Template 'deployment/docker-compose.shceduler.yml.j2' does not exist"), problems(path))
    }

    // ------------------------------------------------------------------------------------------------ search path

    fun testRoleNamesAreCertainOnlyWithAProjectRolesPath() {
        createFile("site/ansible.cfg", "[defaults]\nroles_path = ./roles\n")
        createFile("site/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: web\n")
        createFile("site/playbook-site.yml", "- hosts: all\n  roles:\n    - web\n    - wbe\n    - community.general.thing\n")
        assertEquals(listOf("4: Role 'wbe' cannot be found"), problems("site/playbook-site.yml"))
        assertContainsElements(myFixture.getAllQuickFixes().map { it.text }, "Change to 'web'")
        createFile("site/roles/web/meta/main.yml", "dependencies:\n  - src: git+https://example.invalid/roles/base.git\n  - role: bse\n  - role: web\n")
        assertEquals("a galaxy source is installed, never looked up", listOf("3: Role 'bse' cannot be found"), problems("site/roles/web/meta/main.yml"))

        createFile("open/ansible.cfg", "[defaults]\nhost_key_checking = False\n")
        createFile("open/roles/web/tasks/main.yml", "- ansible.builtin.debug:\n    msg: web\n")
        createFile("open/playbook-site.yml", "- hosts: all\n  roles:\n    - web\n    - wbe\n")
        assertEmpty("the default roles_path reaches ~/.ansible/roles and /etc/ansible/roles", problems("open/playbook-site.yml"))
    }

    // ------------------------------------------------------------------------------------------------ settings

    fun testSeverityFollowsTheRootSettings() {
        copyInfra("golden/roles/keycloak", "golden/playbooks")
        val golden = AnsibleWorkspace.getInstance(project).rootFor(vf(KEYCLOAK_PLAYBOOK))!!
        AnsibilityProjectSettings.getInstance(project).updateRoot(RootKeys.keyOf(project, golden.dir)) { it.copy(redForClaudeCertainFailures = false) }
        assertEquals(HighlightSeverity.WARNING, highlights(KEYCLOAK_PLAYBOOK).single().severity)
    }

    companion object {
        const val SHORT_NAME = "AnsibleUnresolvedReference"
    }
}
