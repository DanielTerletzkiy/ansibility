package de.terletzkiy.ansibility.vars

import com.intellij.codeInsight.navigation.targetPresentation
import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * Ctrl+B on a reference inside a file that defines variables ([SeenDefinitions], [VarNavigation] rule 3): the
 * definition the reference sees there, per ansible-core precedence and per-host templating, on synthetic roots.
 */
class VarNavigationVarsFileTest : VarsTestCase() {

    private fun targets(path: String, line: Int, marker: String, delta: Int = 3): List<String> =
        gotoTargets(path, offsetAt(path, line, marker, delta)).map(::describe)

    /** The effective winners over the caret's host scope, as [AnsibleContextService] reports them. */
    private fun winners(path: String, line: Int, marker: String, name: String): Set<String> = inBackgroundReadAction {
        val service = AnsibleContextService.getInstance(project)
        val breakdown = service.effective(service.hostScope(vf(path), offsetAt(path, line, marker, 3)), name)
        (breakdown.groups + breakdown.molecule).mapNotNull { group -> group.winner?.let { describe(SourceLocation(it.file, it.offset)) } }.toSet()
    }

    private fun withSelection(path: String, context: RootContext, action: () -> Unit) {
        val root = runReadActionBlocking { AnsibleWorkspace.getInstance(project).contextOf(vf(path))!!.root }
        val service = AnsibleContextService.getInstance(project)
        service.setSelection(root, context)
        try {
            action()
        } finally {
            service.setSelection(root, RootContext.DEFAULT)
        }
    }

    private fun base() {
        createFile("r/ansible.cfg", "[defaults]\nroles_path = roles\n")
        createFile(
            HOSTS,
            """
            ---
            all:
              vars:
                inline_x: inline
                inline_y: "{{ inline_x }}"
              hosts:
                h1:
                  hv: one
                  hu: "{{ hv }}"
                h2:
                  hv: two
            web:
              hosts:
                h1:
            """.trimIndent() + "\n",
        )
    }

    fun testHostVarsOverrideASameFileDefinitionForTheirHost() {
        base()
        createFile(ALL, "---\nx: base\ny: \"{{ x }}\"\n")
        createFile("r/environments/e/host_vars/h1/vars.yml", "---\nx: special\n")
        assertEquals(
            "h2 sees group_vars/all (same file, first), h1 its host_vars",
            listOf("$ALL:2", "r/environments/e/host_vars/h1/vars.yml:2"),
            targets(ALL, 3, "{{ x"),
        )
        withSelection(ALL, RootContext(environment = EnvironmentChoice.Named("e"), host = "h1")) {
            assertEquals("a selected host narrows to what it sees", listOf("r/environments/e/host_vars/h1/vars.yml:2"), targets(ALL, 3, "{{ x"))
        }
    }

    fun testPlaybookGroupVarsBeatInventoryGroupVars() {
        base()
        createFile("r/group_vars/all/vars.yml", "---\nx: pb\n")
        createFile(ALL, "---\nx: inv\ny: \"{{ x }}\"\n")
        assertEquals("the same-file key never wins (level 5 beats 4)", listOf("r/group_vars/all/vars.yml:2"), targets(ALL, 3, "{{ x"))
    }

    fun testHostsYmlInlineGroupVarsLoseToGroupVars() {
        base()
        createFile(ALL, "---\ninline_x: gv\n")
        assertEquals("level 4 beats the inline level 3", listOf("$ALL:2"), targets(HOSTS, 5, "{{ inline_x"))
    }

    fun testHostsYmlHostEntrySeesOnlyItsOwnInlineValue() {
        base()
        assertEquals("h2's hv never applies to h1", listOf("$HOSTS:8"), targets(HOSTS, 9, "{{ hv"))
    }

    fun testMoreSpecificGroupVarsWinForTheirHosts() {
        base()
        createFile(ALL, "---\nx: a\ny: \"{{ x }}\"\n")
        createFile("r/environments/e/group_vars/web/vars.yml", "---\nx: w\n")
        assertEquals(listOf("$ALL:2", "r/environments/e/group_vars/web/vars.yml:2"), targets(ALL, 3, "{{ x"))
    }

    fun testSplitGroupVarsReachTheVaultFile() {
        base()
        createFile(ALL, "---\nsecret: \"{{ vault_secret }}\"\n")
        createFile("r/environments/e/group_vars/all/vault.yml", "---\nvault_secret: dummy\n")
        assertEquals(listOf("r/environments/e/group_vars/all/vault.yml:2"), targets(ALL, 2, "{{ vault_secret"))
    }

    fun testMoleculeInlineInventory() {
        base()
        createFile(
            MOLECULE,
            """
            ---
            driver:
              name: docker
            platforms:
              - name: i1
              - name: i2
            provisioner:
              name: ansible
              inventory:
                group_vars:
                  all:
                    m: g
                    n: "{{ m }}"
                host_vars:
                  i1:
                    m: h
            """.trimIndent() + "\n",
        )
        createFile("r/roles/rr/tasks/main.yml", "---\n- ansible.builtin.debug:\n    msg: \"{{ n }}\"\n")
        assertEquals("i2 sees the group var, i1 its host var", setOf("$MOLECULE:12", "$MOLECULE:16"), targets(MOLECULE, 13, "{{ m").toSet())
    }

    fun testRoleVarsBeatTheSameFilesDefault() {
        base()
        createFile("r/roles/rr/tasks/main.yml", "---\n- ansible.builtin.debug:\n    msg: \"{{ a }}\"\n")
        createFile("r/roles/rr/defaults/main.yml", "---\nb: d\na: \"{{ b }}\"\n")
        createFile("r/roles/rr/vars/main.yml", "---\nb: v\n")
        assertEquals("vars/ always wins inside the role", listOf("r/roles/rr/vars/main.yml:2"), targets("r/roles/rr/defaults/main.yml", 3, "{{ b"))
    }

    fun testSelfReferenceIsNoTarget() {
        base()
        createFile(ALL, "---\nx: \"{{ x }}\"\n")
        assertEquals("nothing else defines x", emptyList<String>(), targets(ALL, 2, "{{ x"))
        createFile("r/environments/e/group_vars/web/vars.yml", "---\nx: w\n")
        assertEquals("only the other definition", listOf("r/environments/e/group_vars/web/vars.yml:2"), targets(ALL, 2, "{{ x"))
    }

    fun testPlayVarsFilesTargetUsesTheSameFile() {
        base()
        createFile("r/roles/rr/meta/argument_specs.yml", "---\nargument_specs:\n  main:\n    options:\n      p:\n        type: str\n")
        createFile("r/roles/rr/tasks/main.yml", "---\n- ansible.builtin.debug:\n    msg: \"{{ p }}\"\n")
        createFile("r/play.yml", "---\n- hosts: web\n  vars_files:\n    - vars/common.yml\n  roles:\n    - rr\n")
        createFile("r/vars/common.yml", "---\np: 1\nq: \"{{ p }}\"\n")
        assertEquals(listOf("r/vars/common.yml:2"), targets("r/vars/common.yml", 3, "{{ p"))
    }

    fun testAccessorsFollowNestedKeysMergeKeysAndAliases() {
        base()
        createFile(
            ALL,
            "---\nbase: &d\n  host: h\n  port: 1\napp:\n  <<: *d\n  port: 2\napp2: *d\nurl: \"{{ app.host }}\"\nurl2: \"{{ app2.port }}\"\nurl3: \"{{ app.port }}\"\nurl4: \"{{ app.port.missing }}\"\n",
        )
        assertEquals("merged from the anchor", listOf("$ALL:3"), targets(ALL, 9, "host }}", 1))
        assertEquals("through the alias", listOf("$ALL:4"), targets(ALL, 10, "port }}", 1))
        assertEquals("the own key beats the merged one", listOf("$ALL:7"), targets(ALL, 11, "port }}", 1))
        assertEquals("a missing step stops at the deepest key", listOf("$ALL:7"), targets(ALL, 12, "missing", 1))
    }

    fun testWinnerWithoutAnIndexedDefinitionIsReachedAndLabelled() {
        createFile("r/ansible.cfg", "[defaults]\nroles_path = roles\n")
        createFile(HOSTS, "---\nall:\n  hosts:\n    h3:2222:\n")
        createFile(ALL, "---\nport_text: \"{{ ansible_port }}\"\n")
        assertEquals(winners(ALL, 2, "{{ ansible_port", "ansible_port"), targets(ALL, 2, "{{ ansible_port").toSet())
        val texts = runReadActionBlocking { gotoTargets(ALL, offsetAt(ALL, 2, "{{ ansible_port", 3)).map { targetPresentation(it).containerText.orEmpty() } }
        assertTrue(texts.toString(), texts.single().contains("inventory file vars (host h3)") && "<group>" !in texts.single())
    }

    companion object {
        const val HOSTS = "r/environments/e/hosts.yml"
        const val ALL = "r/environments/e/group_vars/all/vars.yml"
        const val MOLECULE = "r/roles/rr/molecule/default/molecule.yml"
    }
}
