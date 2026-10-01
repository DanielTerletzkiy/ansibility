package de.terletzkiy.ansibility.context.host

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.api.VarsLayer
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext

/**
 * The host-context caches (file scopes, reach, play hits, addresses, definition statuses) follow typed edits of exactly
 * the files they were computed from (plan amendment R7/R8, A.9), with no structure change and no save.
 */
class ContextCachingTest : HostContextTestCase() {
    /** Appends [text] to [path] and commits it, as typing does (never saved). */
    private fun type(path: String, text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = FileDocumentManager.getInstance().getDocument(vf(path))!!
            document.insertString(document.textLength, text)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    fun testDefinitionStatusFollowsTheVarsFilesThatShadowIt() {
        val falcon = root(FALCON)
        type(FALCON_PROD_ALL, "\nha2_status_key: 1\n")
        val definition = VarService.getInstance(project).symbol(falcon, "ha2_status_key").definitions.single()
        val before = context.definitionStatus(definition)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), before.winsOn.map(::label))
        assertSame("cached", before, context.definitionStatus(definition))

        type("$FALCON/roles/postfix/tasks/main.yml", "# typed\n")
        assertSame("a task file is no input of the status", before, context.definitionStatus(definition))

        type(FALCON_PLAYBOOK_ALL, "\nha2_status_key: 2\n")
        val after = context.definitionStatus(definition)
        assertEquals(emptyList<HostKey>(), after.winsOn)
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), after.shadowedOn.keys.map(::label))
        assertTrue(after.shadowedOn.values.all { it.layer == VarsLayer.PLAYBOOK_GROUP_VARS_ALL && rel(it.file) == FALCON_PLAYBOOK_ALL })
    }

    fun testReachFollowsATypedPlay() {
        val falcon = root(FALCON)
        val before = context.reach(falcon, "haproxy")
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), before.targets.map { label(it.host) }.distinct())
        type("$FALCON/playbook-setup-system.yml", "\n- name: Typed proxy\n  hosts: test-test1\n  roles:\n    - haproxy\n")
        val after = context.reach(falcon, "haproxy")
        assertNotSame(before, after)
        assertTrue(after.targets.any { it.host.environment == "test" && it.play?.name == "Typed proxy" })
        assertTrue(after.plays.any { it.name == "Typed proxy" })
    }

    fun testHostScopesOfHostsYmlFollowItsContent() {
        val path = "$FALCON/environments/prod/hosts.yml"
        val before = context.hostScope(vf(path))
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2"), hosts(before))
        type(path, "\nha2_group:\n  hosts:\n    prod-prod3:\n")
        val after = context.hostScope(vf(path))
        assertEquals(listOf("prod/prod-prod1", "prod/prod-prod2", "prod/prod-prod3"), hosts(after))
        val atNewGroup = context.hostScope(vf(path), offsetOf(path, "ha2_group:"))
        assertEquals(listOf("prod/prod-prod3"), hosts(atNewGroup))
    }

    fun testAddressesFollowAnsibleHostEdits() {
        val prodScope = { context.selectionScope(root(FALCON), RootContext(EnvironmentChoice.Named("prod"))) }
        val facts = context.inventoryFacts(prodScope())
        assertEquals("192.0.2.29", facts.host(HostKey(FALCON, "prod", "prod-prod1"))!!.address)
        assertEquals(emptyList<HostKey>(), facts.host(HostKey(FALCON, "prod", "prod-prod1"))!!.sharesAddressWith)
        type("$FALCON/environments/prod/host_vars/prod-prod2/vars.yml", "\nansible_host: 192.0.2.29\n")
        val after = context.inventoryFacts(prodScope())
        assertEquals("192.0.2.29", after.host(HostKey(FALCON, "prod", "prod-prod2"))!!.address)
        assertEquals(listOf(HostKey(FALCON, "prod", "prod-prod2")), after.host(HostKey(FALCON, "prod", "prod-prod1"))!!.sharesAddressWith)
    }

    fun testTemplateScopesKeepWhileTheirRenderersAreUnchanged() {
        val scope = context.hostScope(vf(POSTFIX_TEMPLATE))
        type("$FALCON/roles/postfix/tasks/main.yml", "# a comment changes no render context\n")
        assertSame(scope, context.hostScope(vf(POSTFIX_TEMPLATE)))
        type(FALCON_PROD_ALL, "\nha2_unrelated: 1\n")
        assertSame("vars files are no input of a scope", scope, context.hostScope(vf(POSTFIX_TEMPLATE)))
    }
}
