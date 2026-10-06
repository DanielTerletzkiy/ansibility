package de.terletzkiy.ansibility.context.host.witness

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.context.host.HostContextTestCase
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * [DefinitionWitnesses] on the falcon root of the fixture (with golden's `alloy` role copied into it, as the real repo has
 * it): which reachable (env, host, play) define a name, lack it (witnesses), or may get it at runtime.
 */
@RequiresInfraFixture
class DefinitionWitnessesTest : HostContextTestCase() {
    override fun addFixtureFiles() {
        copyTree("infra/golden/roles/alloy", "$FALCON/roles/alloy")
    }

    private val witnesses: DefinitionWitnesses get() = DefinitionWitnesses.getInstance(project)

    private fun labels(targets: List<EvalTarget>): List<String> = targets.map { "${label(it.host)}@${it.play?.name}" }

    private fun edit(path: String, old: String, new: String) {
        val file = vf(path)
        val text = VfsUtil.loadText(file)
        assertTrue("'$old' not in $path", old in text)
        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(file, text.replaceFirst(old, new))
            FileDocumentManager.getInstance().getDocument(file)?.let { FileDocumentManager.getInstance().reloadFromDisk(it) }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun testEveryReachableContextOfTheRoleDefinesTheTenantKey() {
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_tenant_api_key") }
        assertEquals(
            listOf("ops/ops-ops1@Observability", "ops/ops-ops1@Monitoring Client", "prod/prod-prod1@Monitoring Client", "prod/prod-prod2@Monitoring Client", "test/test-test1@Monitoring Client"),
            labels(report.evaluated),
        )
        assertEquals(report.evaluated, report.definedOn)
        assertEquals("the value comes from group_vars/all of each environment", report.definedOn, report.inventoryDefinedOn)
        assertEquals("nothing but the inventory defines it", report.definedOn, report.inventoryOnlyOn)
        assertEmpty(report.missingOn)
        assertNull(report.witness)
        assertTrue(report.isReached)
    }

    fun testWithoutTheProdDefinitionTheProdHostsAreWitnesses() {
        edit(FALCON_PROD_ALL, "alloy_tenant_api_key: \"{{ vault_alloy_tenant_api_key_prod }}\"", "")
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_tenant_api_key") }
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), report.missingHosts.map(::label))
        assertEquals(mapOf("prod" to listOf("prod-prod1", "prod-prod2")), report.missingByEnvironment())
        assertEquals("prod/prod-prod1@Monitoring Client", labels(listOfNotNull(report.witness)).single())
    }

    fun testInventoryOnlyLeavesOutWhatAnotherRoleOfThePlayDefaults() {
        add("$FALCON/roles/alloy-extra/defaults/main.yml", "---\nalloy_tenant_api_key: ''\n")
        add("$FALCON/roles/alloy-extra/tasks/main.yml", "---\n- ansible.builtin.debug:\n    msg: extra\n")
        edit("$FALCON/playbook-setup-system.yml", "- { role: alloy, tags: ['alloy'] }", "- { role: alloy-extra }\n    - { role: alloy, tags: ['alloy'] }")
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_tenant_api_key") }
        assertEquals("group_vars/all still wins everywhere", report.evaluated, report.inventoryDefinedOn)
        assertEquals(
            "only the ops play without alloy-extra depends on the inventory alone",
            listOf("ops/ops-ops1@Observability"),
            labels(report.inventoryOnlyOn),
        )
    }

    fun testRoleDefaultsArePlayDefinitionsNotInventory() {
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_config_templates") }
        assertEquals(report.evaluated, report.definedOn)
        assertEmpty("role defaults are no inventory layer", report.inventoryDefinedOn)
    }

    fun testANameSetAtRuntimeSomewhereInTheRootIsNoWitness() {
        add("$FALCON/roles/alloy/tasks/facts.yml", "- ansible.builtin.set_fact:\n    alloy_runtime_only: 1")
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_runtime_only") }
        assertEmpty(report.missingOn)
        assertEquals(report.evaluated, report.runtimeOn)
        val unknown = runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_nowhere") }
        assertEquals(report.evaluated, unknown.missingOn)
    }

    fun testUnappliedRoleHasAReason() {
        val report = runReadActionBlocking { witnesses.ofRole(root(FALCON), "totp-token").report("anything") }
        assertFalse(report.isReached)
        assertEquals("No play of falcon applies totp-token", report.emptyReason)
    }

    fun testExplicitTargetsForAPlayLevelTask() {
        val playbook = vf("$FALCON/playbook-setup-system.yml")
        val targets = runReadActionBlocking { context.allHostsScope(playbook).targets.filter { it.play?.name == "Monitoring Client" } }
        val report = runReadActionBlocking { witnesses.of(root(FALCON), targets, null).report("alloy_tenant_api_key") }
        assertEquals(listOf("ops/ops-ops1", "prod/prod-prod1", "prod/prod-prod2", "test/test-test1"), report.definedOn.map { label(it.host) })
    }

    fun testTheSelectionChangesNothing() {
        edit(FALCON_PROD_ALL, "alloy_tenant_api_key: \"{{ vault_alloy_tenant_api_key_prod }}\"", "")
        val service = AnsibleContextService.getInstance(project)
        val results = listOf(RootContext(), RootContext(EnvironmentChoice.Named("test"), "test-test1", null)).map { selection ->
            service.setSelection(root(FALCON), selection)
            runReadActionBlocking { witnesses.ofRole(root(FALCON), "alloy").report("alloy_tenant_api_key").toString() }
        }
        assertEquals(results.first(), results.last())
    }
}
