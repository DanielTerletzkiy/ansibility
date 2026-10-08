package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.psi.PsiManager
import com.intellij.testFramework.EditorTestUtil
import de.terletzkiy.ansibility.dispatch.AnsibleDocumentationTargetProvider
import de.terletzkiy.ansibility.vault.VaultVectors
import java.util.concurrent.TimeUnit

/**
 * The passive-path rule (F7.13 rule 2): highlighting, folding, the card, the gutter and intention availability over a
 * file with 20 vault values never decrypt (`VaultCrypto.decryptAttempts` stays put), never ask for consent and never
 * read a secret, whether the root is locked or unlocked.
 */
class VaultPassivePathTest : VaultUiTestCase() {
    private lateinit var root: String
    private val path get() = "$root/environments/prod/group_vars/all/vault.yml"
    private val keys = (1..20).map { "vault_secret_%02d".format(it) }

    override fun setUp() {
        super.setUp()
        root = defaultRoot()
        write("$root/environments/prod/hosts.yml", "all:\n  hosts:\n    prod-1:\n")
        write(path, keys.joinToString("") { key -> VaultVectors.inline(key, VaultVectors.encrypt("ANSIBILITY-SENTINEL-$key", VaultVectors.PW1)) })
        write("$root/environments/prod/group_vars/all/vars.yml", keys.joinToString("") { "${it.removePrefix("vault_")}: \"{{ $it }}\"\n" })
        root(root)
    }

    private fun exercisePassivePaths() {
        myFixture.configureFromExistingVirtualFile(vf(path))
        myFixture.doHighlighting()
        EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        val folded = myFixture.editor.foldingModel.allFoldRegions.count { it.placeholderText.startsWith("🔒 vault") }
        assertEquals(20, folded)
        val gutters = myFixture.findAllGutters().count { it.tooltipText?.startsWith("Ansible Vault value") == true }
        assertEquals(20, gutters)
        val text = myFixture.editor.document.text
        for (key in keys) {
            myFixture.editor.caretModel.moveToOffset(text.indexOf("$key:"))
            assertTrue(myFixture.availableIntentions.any { it.familyName == "Ansibility Vault" })
        }
        for (file in listOf(path, "$root/environments/prod/group_vars/all/vars.yml")) {
            val virtualFile = vf(file)
            for (key in keys) {
                val html = ApplicationManager.getApplication().executeOnPooledThread<String> {
                    runReadActionBlocking {
                        val psi = PsiManager.getInstance(project).findFile(virtualFile)!!
                        val offset = psi.text.indexOf(key) + 2
                        (AnsibleDocumentationTargetProvider().documentationTargets(psi, offset).single().computeDocumentation() as DocumentationData).html
                    }
                }.get(60, TimeUnit.SECONDS)
                assertTrue(html.contains("ansibility-vault/reveal"))
                assertFalse(html.contains("ANSIBILITY-SENTINEL"))
            }
        }
    }

    fun testLockedRootNothingIsDecryptedOrRead() {
        val attempts = crypto.decryptAttempts
        exercisePassivePaths()
        assertEquals(attempts, crypto.decryptAttempts)
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
        assertEmpty(access.secretReads)
        assertEquals(0, crypto.plaintextCount)
    }

    fun testUnlockedRootNothingIsDecrypted() {
        await { secrets.unlock(root(root)) }
        val attempts = crypto.decryptAttempts
        val reads = access.secretReads.size
        exercisePassivePaths()
        assertEquals(attempts, crypto.decryptAttempts)
        assertEquals(reads, access.secretReads.size)
        assertEquals(0, crypto.plaintextCount)
        assertEquals(0, crypto.verificationCount)
    }
}
