package de.terletzkiy.ansibility.vars.usages

import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2.GTDUOutcome
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.usages.UsageInfo2UsageAdapter
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.fixtures.RequiresInfraFixture
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The acceptance list of plan amendment FU on the infra fixture (`repos/falcon` with golden's `alloy` role copied into
 * it as the real repository has it, plus a task file that reads the variable, and golden itself as a second root).
 */
@RequiresInfraFixture
class VarUsagesInfraTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyInfra("repos/falcon", "golden/roles/alloy")
        myFixture.copyDirectoryToProject("${InfraTestData.INFRA}/golden/roles/alloy", ALLOY)
        createFile(
            PROBE,
            """
            ---
            - name: Report the tenant
              ansible.builtin.debug:
                msg: "tenant key {{ alloy_tenant_api_key }}"
              when: alloy_tenant_api_key | length > 0
            """.trimIndent() + "\n",
        )
    }

    /** Acceptance 1: from the template use, the template and task uses and every definition, grouped `Read: …` / `Set: …`. */
    fun testFindUsagesFromTheTemplateListsUsesAndEveryDefinition() {
        at(TEMPLATE, 16, "alloy_tenant_api_key")
        val usages = describeUsages(findUsagesViaAction())
        assertEquals(EXPECTED, usages)
        // The usage view merges the usages of one line into one node.
        val tree = myFixture.getUsageViewTreeTextRepresentation(targetAtCaret()!!)
        for (group in listOf(
            "Read: condition (1)", "Read: task (1)", "Read: template (4)", "Set: group_vars · ops (1)", "Set: group_vars · prod (1)",
            "Set: group_vars · test (1)", "Set: molecule inventory (1)", "Spec: argument spec (1)",
        )) {
            assertTrue("group '$group' in:\n$tree", tree.contains(group))
        }
        assertTrue(tree, tree.contains("Variable alloy_tenant_api_key · falcon"))
    }

    /** Acceptance 2: Ctrl+B on the argument_specs option shows the same usages; Ctrl+B on the template use still goes to the spec. */
    fun testCtrlBOnTheSpecOptionShowsTheSameUsages() {
        val offset = at(SPEC, 91, "alloy_tenant_api_key")
        assertEquals(GTDUOutcome.SU, gtdu(offset))
        val keyValue = runReadActionBlocking { PsiTreeUtil.getParentOfType(hostFile().findElementAt(offset), YAMLKeyValue::class.java) }!!
        assertEquals(EXPECTED.map { it.dropLast(2) }, describeInfos(findUsagesOf(keyValue)))
        assertEquals(GTDUOutcome.GTD, gtdu(at(TEMPLATE, 16, "alloy_tenant_api_key")))
        assertEquals("$SPEC:91", gotoTargets(TEMPLATE, offsetAt(TEMPLATE, 16, "alloy_tenant_api_key", 1)).map(::describe).first())
    }

    /**
     * Ctrl+B on an override key shows its usages, even though `alloy` in falcon declares `environment_group`: the key
     * is a definition itself; Go to Super and the override gutter lead to the declaration.
     */
    fun testCtrlBOnAnOverrideKeyShowsUsages() {
        val ops = "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml"
        assertEquals(GTDUOutcome.SU, gtdu(at(ops, 56, "environment_group")))
        assertEquals(emptyList<String>(), gotoTargets(ops, offsetAt(ops, 56, "environment_group", 1)).map(::describe))
    }

    /** Acceptance 4: highlighting marks every use in the open file, injected uses included. */
    fun testHighlightingMarksEveryUseInTheFile() {
        at(TEMPLATE, 16, "alloy_tenant_api_key")
        val template = highlightHandlerAtCaret()!!
        assertEquals(
            listOf("15:alloy_tenant_api_key", "15:alloy_tenant_api_key", "16:alloy_tenant_api_key", "32:alloy_tenant_api_key", "32:alloy_tenant_api_key", "33:alloy_tenant_api_key"),
            template.readUsages.map { lineAndText(TEMPLATE, it) }.sorted(),
        )

        at(PROBE, 4, "alloy_tenant_api_key")
        val action = HighlightUsagesAction()
        val event = TestActionEvent.createTestEvent(action, EditorUtil.getEditorDataContext(hostEditor()))
        ActionUtil.updateAction(action, event)
        ActionUtil.performAction(action, event)
        val marked = hostEditor().markupModel.allHighlighters.map { lineAndText(PROBE, TextRange(it.startOffset, it.endOffset)) }.sorted()
        assertEquals("the injected msg value and the bare when: expression", listOf("4:alloy_tenant_api_key", "5:alloy_tenant_api_key"), marked)
    }

    /** Acceptance 6: no row of a vault file shows its value; nothing is decrypted. */
    fun testVaultRowsShowNoValueAndNothingIsDecrypted() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        val prod = "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml"
        at(prod, 4, "vault_alloy_tenant_api_key_prod", 3)
        val usages = findUsagesViaAction()
        assertEquals(
            listOf(
                "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml:68:vault_alloy_tenant_api_key_prod R",
                "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml:4:vault_alloy_tenant_api_key_prod R",
                "$VAULT:28:vault_alloy_tenant_api_key_prod W",
            ),
            describeUsages(usages),
        )
        for (usage in usages) {
            val row = rowText(usage)
            val plain = runReadActionBlocking { (usage as UsageInfo2UsageAdapter).plainText }
            assertFalse("no payload in '$row'", InfraTestData.containsVaultPayload(row) || InfraTestData.containsVaultPayload(plain))
            if ((usage as UsageInfo2UsageAdapter).file == vf(VAULT)) {
                assertEquals("only the key", "vault_alloy_tenant_api_key_prod", plain.trim())
                assertFalse(row, row.contains("!vault") || row.contains("ANSIBLE_VAULT"))
            }
        }

        at(VAULT, 4, "vault_alloy_tenant_api_key_build")
        for (usage in findUsagesViaAction().filter { (it as UsageInfo2UsageAdapter).file == vf(VAULT) }) {
            assertEquals("vault_alloy_tenant_api_key_build", runReadActionBlocking { (usage as UsageInfo2UsageAdapter).plainText }.trim())
        }
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    companion object {
        const val ALLOY = "repos/falcon/ansible/roles/alloy"
        const val TEMPLATE = "$ALLOY/templates/config-base.alloy.j2"
        const val SPEC = "$ALLOY/meta/argument_specs.yml"
        const val PROBE = "$ALLOY/tasks/usages.yml"
        const val VAULT = "repos/falcon/ansible/group_vars/all/vault.yml"

        val EXPECTED = listOf(
            "$SPEC:91:alloy_tenant_api_key W",
            "$ALLOY/molecule/default/molecule.yml:57:alloy_tenant_api_key W",
            "$PROBE:4:alloy_tenant_api_key R",
            "$PROBE:5:alloy_tenant_api_key R",
            "$TEMPLATE:15:alloy_tenant_api_key R",
            "$TEMPLATE:15:alloy_tenant_api_key R",
            "$TEMPLATE:16:alloy_tenant_api_key R",
            "$TEMPLATE:32:alloy_tenant_api_key R",
            "$TEMPLATE:32:alloy_tenant_api_key R",
            "$TEMPLATE:33:alloy_tenant_api_key R",
            "repos/falcon/ansible/environments/ops/group_vars/all/vars.yml:4:alloy_tenant_api_key W",
            "repos/falcon/ansible/environments/prod/group_vars/all/vars.yml:4:alloy_tenant_api_key W",
            "repos/falcon/ansible/environments/test/group_vars/all/vars.yml:4:alloy_tenant_api_key W",
        ).sorted()
    }
}
