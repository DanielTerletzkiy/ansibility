package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiDocumentManager
import de.terletzkiy.ansibility.api.VaultFailure
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.actions.VaultValueActions
import de.terletzkiy.ansibility.vault.actions.VaultValuePsi
import de.terletzkiy.ansibility.vault.actions.VaultValueRef
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDecision
import de.terletzkiy.ansibility.vault.tab.DecryptedVaultTabs
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan amendment R19, D139: "Not now" leaves the root's files and password managers unread for the session, until you
 * ask for an unlock yourself: the gutter's Unlock vault ids… and the whole-file banner's Unlock… ask for the consent
 * again and read the root's password managers.
 */
class VaultExplicitUnlockTest : VaultUiTestCase() {
    private val reference = "op://Infra/falcon dev/password"
    private val reads = CopyOnWriteArrayList<String>()
    private lateinit var root: String
    private val valuesPath get() = "$root/group_vars/all/vault.yml"
    private val wholePath get() = "$root/group_vars/web/vault.yml"

    override fun setUp() {
        super.setUp()
        root = defaultRoot()
        write(valuesPath, VaultVectors.inline("vault_db_password", VaultVectors.encrypt("synthetic-wren", VaultVectors.PW1)))
        write(wholePath, VaultVectors.encrypt("tern: 1\n", VaultVectors.PW1).formatBytes())
        val ansibleRoot = root(root)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(ansibleRoot)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("dev", VaultSourceKind.ONE_PASSWORD, reference)))
        }
        secrets.setPasswordManagersForTests { _, ref, _ ->
            reads += ref
            VaultVectors.DEV.toByteArray()
        }
    }

    /** "Not now" in an unlock you did not ask for yourself (a playbook run, say); later ones do not ask again. */
    private fun declineOnce() {
        prompter.onConsent = { VaultConsentDecision.NotNow }
        repeat(2) { assertEquals(VaultUnlockResult.Failed(VaultFailure.CANCELLED), await { VaultOperations.getInstance(project).unlock(root(root)) }) }
        assertEquals(1, prompter.consentRequests.size)
        assertEmpty(reads)
        prompter.onConsent = { VaultConsentDecision.UseAll }
    }

    private fun assertUnlockedAgain() {
        assertEquals("asked again", 2, prompter.consentRequests.size)
        assertEquals(listOf(reference), reads)
        assertEquals(listOf("dev", "default"), secrets.unlockedLabels(registry.discovery(root(root))))
    }

    fun testUnlockVaultIdsAsksAgainAfterNotNow() {
        declineOnce()
        myFixture.configureFromExistingVirtualFile(vf(valuesPath))
        val ref = runReadActionBlocking {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val offset = myFixture.editor.document.text.indexOf("vault_db_password:")
            val scalar = (myFixture.file.findElementAt(offset)!!.parent as YAMLKeyValue).value as YAMLScalar
            VaultValueRef.of(VaultValuePsi.vaultScalarAt(scalar)!!)!!
        }
        VaultValueActions.getInstance(project).unlock(ref, myFixture.editor)
        awaitAction()
        assertUnlockedAgain()
    }

    fun testTheBannersUnlockAsksAgainAfterNotNow() {
        declineOnce()
        val tabs = DecryptedVaultTabs.getInstance(project)
        tabs.unlock(vf(wholePath))
        val job = tabs.lastAction!!
        waitFor("the banner's unlock timed out") { job.isCompleted }
        assertUnlockedAgain()
    }
}
