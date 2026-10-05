package de.terletzkiy.ansibility.vars.usages

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.usages.UsageInfo2UsageAdapter
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * The search target's identity and threading contract, and the vault-safe rows (F1.10), on `testData/usages/runtime`
 * plus a playbook-level `group_vars` file written by the test.
 */
class VarSymbolAndSafetyTest : UsagesTestCase() {

    override fun setUp() {
        super.setUp()
        copyUsagesData("runtime")
        createFile(
            GROUP_VARS,
            """
            ---
            creds: {user: app, vault_pw: hunter2-never-shown}
            plain: "{{ vault_token }}"
            inline: !vault "${'$'}ANSIBLE_VAULT;1.1;AES256\n6162"
            uses: "{{ creds.user }} {{ plain }} {{ inline }}"
            mixed: {user: "{{ plain }}", vault_pw: hunter2-also-hidden}
            """.trimIndent() + "\n",
        )
    }

    fun testOneTargetPerRootVariableButOwnTargetsForLoopsAndLocals() {
        at(ALPHA_TASKS, 4, "result")
        val fromRegister = targetAtCaret()!!
        at(ALPHA_TASKS, 7, "result.rc")
        val fromUse = targetAtCaret()!!
        assertEquals("every position naming the root variable searches the same target", fromRegister, fromUse)
        assertEquals(fromRegister.hashCode(), fromUse.hashCode())
        assertEquals("Variable result · runtime", fromUse.presentableText)

        at(ALPHA_TASKS, 16, "server.name")
        val loop = targetAtCaret()!!
        at(ALPHA_TASKS, 22, "server")
        val outsideTheLoop = targetAtCaret()!!
        assertFalse("the loop variable is not the same-named root variable", loop == outsideTheLoop)
        assertEquals("Loop variable server · runtime", loop.presentableText)
        assertEquals("Variable server · runtime", outsideTheLoop.presentableText)
        assertNull("a target has no text range (smart pointers keep the element itself)", runReadActionBlocking { loop.textRange })
    }

    fun testNothingWhileIndexing() {
        at(ALPHA_TASKS, 7, "result.rc")
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(DumbService.isDumb(project))
            assertNull("no highlighting handler while indexing", runReadActionBlocking {
                VarHighlightUsagesHandlerFactory().createHighlightUsagesHandler(hostEditor(), hostFile())
            })
            assertFalse("the target provider is not asked while indexing", DumbService.isDumbAware(VarUsageTargetProvider()))
        }
    }

    fun testDefinitionRowsWithSecretsOnTheirLineShowOnlyTheName() {
        val decrypts = VaultCrypto.getInstance(project).decryptAttempts
        at(GROUP_VARS, 2, "creds")
        val creds = findUsagesViaAction().associateBy { describeUsages(listOf(it)).single() }
        val definition = creds.getValue("$GROUP_VARS:2:creds W") as UsageInfo2UsageAdapter
        assertEquals("a nested vault_* key on the line", "creds", runReadActionBlocking { definition.plainText }.trim())
        assertTrue("reads keep their line", rowText(creds.getValue("$GROUP_VARS:5:creds R")).contains("creds.user"))

        at(GROUP_VARS, 4, "inline")
        val inline = findUsagesViaAction().single { describeUsages(listOf(it)).single().endsWith(" W") } as UsageInfo2UsageAdapter
        assertEquals("a !vault value", "inline", runReadActionBlocking { inline.plainText }.trim())

        at(GROUP_VARS, 3, "plain")
        val plainUsages = findUsagesViaAction().associateBy { describeUsages(listOf(it)).single() }
        assertTrue("a reference to a vault variable is no secret", rowText(plainUsages.getValue("$GROUP_VARS:3:plain W")).contains("{{ vault_token }}"))
        assertTrue("a read keeps its line", rowText(plainUsages.getValue("$GROUP_VARS:5:plain R")).contains("{{ plain }}"))
        val mixed = plainUsages.getValue("$GROUP_VARS:6:plain R") as UsageInfo2UsageAdapter
        assertEquals("a read next to a nested vault_* value", "plain", runReadActionBlocking { mixed.plainText }.trim())
        assertFalse(rowText(mixed), rowText(mixed).contains("hunter2"))
        assertEquals("nothing was decrypted", decrypts, VaultCrypto.getInstance(project).decryptAttempts)
    }

    fun testPlaybookLevelGroupVarsAreLabelledPlaybook() {
        at(GROUP_VARS, 3, "plain")
        val tree = myFixture.getUsageViewTreeTextRepresentation(targetAtCaret()!!)
        assertTrue(tree, tree.contains("Set: group_vars · playbook (1)"))
        assertTrue(tree, tree.contains("Read: templated value (2)"))
    }

    companion object {
        const val ALPHA_TASKS = VarUsageScopingTest.ALPHA_TASKS
        const val GROUP_VARS = "runtime/group_vars/all/vars.yml"
    }
}
