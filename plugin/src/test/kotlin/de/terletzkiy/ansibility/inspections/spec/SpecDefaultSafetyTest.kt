package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.model.inventory.ModelCaches

/**
 * ANS-S003–S005 where a value must not be compared, shown or copied (plan amendment R23): aliases and anchors are never
 * copied into another file, a whole-file vault loaded after the winner (or in a dependency) makes the comparison
 * unknown, a dict with a `no_log` sub-option stays secret, a new default never goes into a vault file, and every
 * offered fix edits (flow mappings with commas, multi-line plain and quoted scalars). The analysis is a model cache
 * entry that unrelated edits leave alone.
 */
class SpecDefaultSafetyTest : SpecDefaultTestCase() {
    private val mismatch = AnsibleSpecDefaultMismatchInspection::class.java
    private val notApplied = AnsibleSpecDefaultNotAppliedInspection::class.java
    private val undocumented = AnsibleSpecDefaultUndocumentedInspection::class.java

    override fun addFiles() {
        file("$ROOT/site.yml", "- hosts: all\n  roles: [alias, locked, needy, flow, motd, creds, split]")
        file("$ROOT/group_vars/all.yml", "unrelated: 1")
        file(
            ALIAS_SPEC,
            """
            argument_specs:
              main:
                options:
                  alias_packages:
                    type: list
                    default: [nginx]
                  alias_conf:
                    type: dict
                    default: {port: 8080}
                  alias_doc:
                    type: list
                  alias_port:
                    type: int
                    default: &alias_port 80
                  alias_other:
                    type: int
                    default: *alias_port
            """,
        )
        file(
            ALIAS_DEFAULTS,
            """
            ---
            _alias_base: &alias_base [nginx, curl]
            _alias_conf_base: &alias_conf_base {host: db}
            alias_packages: *alias_base
            alias_conf:
              <<: *alias_conf_base
              port: 80
            alias_doc: *alias_base
            """,
        )
        file(LOCKED_SPEC, "argument_specs:\n  main:\n    options:\n      locked_version:\n        type: str\n        default: \"1.5\"\n      locked_doc:\n        type: str")
        file("$ROOT/roles/locked/defaults/main/10-base.yml", "locked_version: 1.2\nlocked_doc: x")
        file("$ROOT/roles/locked/defaults/main/90-secret.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364")
        file(NEEDY_SPEC, "argument_specs:\n  main:\n    options:\n      needy_version:\n        type: str\n        default: \"1.5\"")
        file("$ROOT/roles/needy/meta/main.yml", "dependencies:\n  - vaultdep")
        file("$ROOT/roles/vaultdep/defaults/main.yml", "\$ANSIBLE_VAULT;1.1;AES256\n61626364")
        file("$ROOT/roles/vaultdep/tasks/main.yml", "- name: Noop\n  ansible.builtin.debug:\n    msg: hi")
        file(FLOW_SPEC, "argument_specs:\n  main:\n    options:\n      flow_ciphers: {type: str, default: HIGH}\n      flow_list: {type: str}")
        file(FLOW_DEFAULTS, "flow_ciphers: \"A,B\"\nflow_list: a,b")
        file(
            MOTD_SPEC,
            """
            argument_specs:
              main:
                options:
                  motd_text:
                    type: str
                    default: hello
                  motd_banner:
                    type: str
                    default: "Welcome to web 2
                      maintained by ops"
            """,
        )
        file(MOTD_DEFAULTS, "motd_text: Welcome to node 1\n  of the cluster")
        file(
            CREDS_SPEC,
            """
            argument_specs:
              main:
                options:
                  creds_conn:
                    type: dict
                    default: {user: app, password: ''}
                    options:
                      user: {type: str}
                      password: {type: str, no_log: true}
                  creds_undoc:
                    type: dict
                    options:
                      token: {type: str, no_log: true}
            """,
        )
        file("$ROOT/roles/creds/defaults/main.yml", "creds_conn: {user: app, password: changeme}\ncreds_undoc: {token: abc123}")
        file(SPLIT_SPEC, "argument_specs:\n  main:\n    options:\n      split_port:\n        type: int\n        default: 80")
        file("$ROOT/roles/split/defaults/main/10-main.yml", "split_a: 1")
        file("$ROOT/roles/split/defaults/main/vault.yml", "vault_split_pw: x")
        file(SPLIT_TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ split_port }}\"")
        file(CHAINPW_SPEC, "argument_specs:\n  main:\n    options:\n      chainpw_password: {type: str, no_log: true}\n      chainpw_dsn: {type: str, default: other}")
        file(CHAINPW_DEFAULTS, "chainpw_password: s3cret-chain\nchainpw_dsn: \"{{ chainpw_password }}\"")
    }

    /** Applies the one fix starting with [prefix] at the highlight on [line] of [path]. */
    private fun applyStarting(path: String, inspection: Class<out LocalInspectionTool>, line: Int, prefix: String) {
        val name = fixNames(path, inspection, line).singleOrNull { it.startsWith(prefix) } ?: error("no '$prefix…' on line $line: ${fixNames(path, inspection, line)}")
        apply(path, inspection, line, name)
    }

    fun testAliasesAndAnchorsAreNeverCopiedIntoAnotherFile() {
        assertEquals(listOf("6 ERROR [nginx]", "9 ERROR {port: 8080}"), highlights(ALIAS_SPEC, mismatch))
        assertEquals("an alias value", listOf("Remove the documented default"), fixNames(ALIAS_SPEC, mismatch, 6))
        assertEquals("a merge key with an alias", listOf("Remove the documented default"), fixNames(ALIAS_SPEC, mismatch, 9))
        assertEquals(listOf("Remove the documented default"), fixNames(ALIAS_DEFAULTS, mismatch, 4))
        assertEquals(listOf("10 INFO alias_doc"), highlights(ALIAS_SPEC, undocumented))
        assertEquals(emptyList<String>(), fixNames(ALIAS_SPEC, undocumented, 10))
        // The spec's own anchor and alias are not copied into defaults/ either.
        assertEquals(listOf(14, 17), highlights(ALIAS_SPEC, notApplied).map { it.substringBefore(' ').toInt() })
        assertEquals(listOf("Remove the documented default"), fixNames(ALIAS_SPEC, notApplied, 14))
        assertEquals(listOf("Remove the documented default"), fixNames(ALIAS_SPEC, notApplied, 17))
    }

    fun testAVaultLoadedAfterTheWinnerMakesTheComparisonUnknown() {
        // 90-secret.yml loads after 10-base.yml and may set locked_version (Ansible then uses its value).
        assertEquals(emptyList<String>(), highlights(LOCKED_SPEC, mismatch))
        assertEquals(emptyList<String>(), highlights(LOCKED_SPEC, undocumented))
    }

    fun testAnUnreadableDependencyDefaultsFileProvesNoAbsence() {
        assertEquals(emptyList<String>(), highlights(NEEDY_SPEC, notApplied))
    }

    fun testADictWithANoLogSubOptionIsNeverComparedOrShown() {
        assertEquals(emptyList<String>(), highlights(CREDS_SPEC, mismatch))
        assertEquals(emptyList<String>(), highlights(CREDS_SPEC, undocumented))
        val messages = listOf(mismatch, notApplied, undocumented).flatMap { infos(CREDS_SPEC, it) }.mapNotNull { it.description }
        assertTrue(messages.toString(), messages.none { "changeme" in it || "abc123" in it })
    }

    /** A `{{ name }}` role default that leads to a `no_log` variable is never compared: the message would show its value. */
    fun testAChainToANoLogVariableIsNeverComparedOrShown() {
        assertEquals(emptyList<String>(), highlights(CHAINPW_SPEC, mismatch))
        assertEquals(emptyList<String>(), highlights(CHAINPW_DEFAULTS, mismatch))
        val messages = listOf(mismatch, notApplied, undocumented).flatMap { infos(CHAINPW_SPEC, it) + infos(CHAINPW_DEFAULTS, it) }.mapNotNull { it.description }
        assertTrue(messages.toString(), messages.none { "s3cret-chain" in it })
    }

    fun testFixesInAFlowMappingQuoteAValueWithAComma() {
        assertEquals(listOf("4 ERROR HIGH"), highlights(FLOW_SPEC, mismatch))
        applyStarting(FLOW_SPEC, mismatch, 4, "Set the documented default to")
        assertEquals("      flow_ciphers: {type: str, default: \"A,B\"}", text(FLOW_SPEC).lines()[3])
        applyStarting(FLOW_SPEC, undocumented, 5, "Document the default")
        assertEquals("      flow_list: {type: str, default: \"a,b\"}", text(FLOW_SPEC).lines()[4])
        assertEmpty(highlights(FLOW_SPEC, mismatch))
        assertEmpty(highlights(FLOW_SPEC, undocumented))
    }

    fun testMultiLinePlainAndQuotedScalarsAreCopied() {
        applyStarting(MOTD_SPEC, mismatch, 6, "Set the documented default to")
        assertTrue(text(MOTD_SPEC), text(MOTD_SPEC).contains("      motd_text:\n        type: str\n        default: Welcome to node 1\n          of the cluster\n      motd_banner:\n"))
        assertEmpty(highlights(MOTD_SPEC, mismatch))
        val banner = highlights(MOTD_SPEC, notApplied).single().substringBefore(' ').toInt()
        applyStarting(MOTD_SPEC, notApplied, banner, "Add 'motd_banner:")
        assertEquals("motd_text: Welcome to node 1\n  of the cluster\nmotd_banner: \"Welcome to web 2\n  maintained by ops\"\n", text(MOTD_DEFAULTS))
    }

    fun testANewDefaultNeverGoesIntoAVaultFile() {
        assertEquals(listOf("Add 'split_port: 80' to defaults/main/10-main.yml", "Remove the documented default"), fixNames(SPLIT_SPEC, notApplied, 6))
        apply(SPLIT_SPEC, notApplied, 6, "Add 'split_port: 80' to defaults/main/10-main.yml")
        assertEquals("split_a: 1\nsplit_port: 80\n", text("$ROOT/roles/split/defaults/main/10-main.yml"))
        assertEquals("vault_split_pw: x\n", text("$ROOT/roles/split/defaults/main/vault.yml"))
    }

    fun testTheAnalysisIsCachedUntilItsOwnFilesChange() {
        val role = runReadActionBlocking { RoleRegistry.getInstance(project).roleOf(vf(FLOW_SPEC))!! }
        fun analysis() = runReadActionBlocking { SpecDefaultChecks.of(project, role) }
        val caches = ModelCaches.getInstance(project)
        val first = analysis()
        assertNotNull(first)
        val before = caches.snapshot()
        edit("$ROOT/group_vars/all.yml", "other: 2\n")
        assertSame("an unrelated vars file", first, analysis())
        assertEquals(0L, caches.snapshot().computationsOf(SpecDefaultChecks.CACHE_NAME, before))
        edit(FLOW_DEFAULTS, "flow_new: 3\n")
        assertNotSame("the role's defaults file", first, analysis())
        assertEquals(1L, caches.snapshot().computationsOf(SpecDefaultChecks.CACHE_NAME, before))
    }

    fun testANeverAppliedFindingFollowsTheRolesTasks() {
        assertEquals(1, highlights(SPLIT_SPEC, notApplied).size)
        edit(SPLIT_TASKS, "- name: Fact\n  ansible.builtin.set_fact:\n    split_port: 81\n")
        assertEquals("the cached analysis sees the new set_fact", emptyList<String>(), highlights(SPLIT_SPEC, notApplied))
    }

    /** Appends [text] to [path] through its document and commits it. */
    private fun edit(path: String, text: String) {
        val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(vf(path))!! }
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private companion object {
        const val ALIAS_SPEC = "$ROOT/roles/alias/meta/argument_specs.yml"
        const val ALIAS_DEFAULTS = "$ROOT/roles/alias/defaults/main.yml"
        const val LOCKED_SPEC = "$ROOT/roles/locked/meta/argument_specs.yml"
        const val NEEDY_SPEC = "$ROOT/roles/needy/meta/argument_specs.yml"
        const val FLOW_SPEC = "$ROOT/roles/flow/meta/argument_specs.yml"
        const val FLOW_DEFAULTS = "$ROOT/roles/flow/defaults/main.yml"
        const val MOTD_SPEC = "$ROOT/roles/motd/meta/argument_specs.yml"
        const val MOTD_DEFAULTS = "$ROOT/roles/motd/defaults/main.yml"
        const val CREDS_SPEC = "$ROOT/roles/creds/meta/argument_specs.yml"
        const val SPLIT_SPEC = "$ROOT/roles/split/meta/argument_specs.yml"
        const val SPLIT_TASKS = "$ROOT/roles/split/tasks/main.yml"
        const val CHAINPW_SPEC = "$ROOT/roles/chainpw/meta/argument_specs.yml"
        const val CHAINPW_DEFAULTS = "$ROOT/roles/chainpw/defaults/main.yml"
    }
}
