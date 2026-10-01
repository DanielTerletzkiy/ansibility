package de.terletzkiy.ansibility.vault.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.dispatch.AnsibleDocumentationTargetProvider
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vars.VarsTestCase
import java.util.concurrent.TimeUnit

/**
 * F7.14: the card's Vault row on definition and reference cards (locked, unlocked, verified), its Reveal link, and
 * that building the card never decrypts and never puts the value or the ciphertext into the HTML.
 */
class VaultCardSectionTest : VaultUiTestCase() {
    private lateinit var root: String
    private val vaultPath get() = "$root/environments/prod/group_vars/all/vault.yml"
    private val varsPath get() = "$root/environments/prod/group_vars/all/vars.yml"
    private lateinit var hexLine: String

    override fun setUp() {
        super.setUp()
        root = defaultRoot()
        val envelope = VaultVectors.encrypt("ANSIBILITY-SENTINEL-31", VaultVectors.PW1)
        hexLine = envelope.formatLines()[1]
        write("$root/environments/prod/hosts.yml", "all:\n  hosts:\n    prod-1:\n")
        write(vaultPath, "# secrets\n" + VaultVectors.inline("vault_db_password", envelope))
        write(varsPath, "db_password: \"{{ vault_db_password }}\"\n")
        root(root)
    }

    private fun card(path: String, marker: String, delta: Int = 0): Pair<DocumentationTarget, String> {
        val file = vf(path)
        val target = runReadActionBlocking {
            val psi = PsiManager.getInstance(project).findFile(file)!!
            val offset = psi.text.indexOf(marker) + delta
            AnsibleDocumentationTargetProvider().documentationTargets(psi, offset).single()
        }
        val html = ApplicationManager.getApplication().executeOnPooledThread<String> {
            runReadActionBlocking { (target.computeDocumentation() as DocumentationData).html }
        }.get(60, TimeUnit.SECONDS)
        return target to html
    }

    private fun assertNoSecret(html: String) {
        assertFalse("no plaintext", html.contains("ANSIBILITY-SENTINEL-31"))
        assertFalse("no ciphertext body", html.contains(hexLine) || html.contains(hexLine.take(40)))
    }

    fun testTheSectionIsRegistered() {
        assertTrue(CardSection.EP_NAME.extensionList.any { it is VaultCardSection })
    }

    fun testALockedDefinitionCardShowsHeaderIdStateLengthAndTheUnlockLink() {
        val attempts = crypto.decryptAttempts
        val (_, html) = card(vaultPath, "vault_db_password")
        val text = VarsTestCase.plain(html)
        val row = text.substringAfter("Vault ")
        assertTrue(text, row.contains("AES256 · 1.1 · id default (from .vault-pass) · 🔒 locked · plaintext 16–31 bytes · Unlock and reveal"))
        assertTrue(text, row.contains("Alt+Enter › Unlock and reveal"))
        assertTrue(html.contains("psi_element://ansibility-vault/reveal/"))
        assertNoSecret(html)
        assertEquals("building the card never decrypts", attempts, crypto.decryptAttempts)
        assertEmpty("nor asks for consent", prompter.consentRequests)
        assertEmpty(access.secretReads)
    }

    fun testAVerifiedValueSaysWhichIdDecryptsIt() {
        await { secrets.unlock(root(root)) }
        await { VaultOperations.getInstance(project).decrypt(location(vaultPath, "vault_db_password"), VaultPurpose.REVEAL) }.let {
            (it as? de.terletzkiy.ansibility.api.VaultDecryptResult.Decrypted)?.plaintext?.close()
        }
        val (_, html) = card(vaultPath, "vault_db_password")
        val text = VarsTestCase.plain(html)
        assertTrue(text, text.contains("🔓 decrypts with id default · plaintext 16–31 bytes · Reveal"))
        assertNoSecret(html)
    }

    fun testLabelledValuesShowLabelIdAndWhichIdActuallyDecryptsThem() {
        val labelled = defaultRoot("labelled", cfg = "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n")
        write("$labelled/dev.pw", "${VaultVectors.DEV}\n")
        write("$labelled/prod.pw", "${VaultVectors.PROD}\n")
        val honest = VaultVectors.encrypt("ANSIBILITY-SENTINEL-31", VaultVectors.DEV, "dev")
        val lying = VaultVectors.encrypt("ANSIBILITY-SENTINEL-31", VaultVectors.PROD, "dev")
        val path = "$labelled/environments/dev/group_vars/all/vault.yml"
        write("$labelled/environments/dev/hosts.yml", "all:\n  hosts:\n    dev-1:\n")
        write(path, VaultVectors.inline("vault_honest", honest) + VaultVectors.inline("vault_lying", lying))
        root(labelled)
        // The second root's files are indexed after the rescan; cards need smart mode.
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val locked = VarsTestCase.plain(card(path, "vault_honest").second)
        assertTrue(locked, locked.contains("AES256 · 1.2 · label dev · id dev (from dev.pw via ansible.cfg) · 🔒 locked · plaintext 16–31 bytes"))

        await { secrets.unlock(root(labelled)) }
        await { VaultOperations.getInstance(project).decrypt(location(path, "vault_lying"), VaultPurpose.REVEAL) }.let {
            (it as? de.terletzkiy.ansibility.api.VaultDecryptResult.Decrypted)?.plaintext?.close()
        }
        val unverified = VarsTestCase.plain(card(path, "vault_honest").second)
        assertTrue(unverified, unverified.contains("id dev (from dev.pw via ansible.cfg) · 🔓 unlocked · plaintext"))
        val (_, html) = card(path, "vault_lying")
        val mislabelled = VarsTestCase.plain(html)
        assertTrue(mislabelled, mislabelled.contains("label dev · id dev (from dev.pw via ansible.cfg) · ⚠ decrypts with id prod, not with its label dev"))
        assertFalse("no plaintext", html.contains("ANSIBILITY-SENTINEL-31"))
        assertFalse("no ciphertext body", lying.formatLines().drop(1).any { html.contains(it.take(40)) })
    }

    fun testAReferenceCardListsTheVaultDefinitionsWithTheirLocation() {
        val (_, html) = card(varsPath, "vault_db_password", 2)
        val text = VarsTestCase.plain(html)
        assertTrue(text, text.contains("Vault environments/prod/group_vars/all/vault.yml:2 · AES256 · 1.1 · id default"))
        assertFalse("no Alt+Enter hint away from the value", text.substringAfter("Vault ").contains("Alt+Enter"))
        assertNoSecret(html)
    }

    fun testAReferenceCardShowsAtMostSixValuesAndCountsTheRest() {
        for (env in 1..7) {
            write("$root/environments/e$env/hosts.yml", "all:\n  hosts:\n    e$env-1:\n")
            write("$root/environments/e$env/group_vars/all/vault.yml", VaultVectors.inline("vault_db_password", VaultVectors.encrypt("ANSIBILITY-SENTINEL-31", VaultVectors.PW1)))
        }
        refresh()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val attempts = crypto.decryptAttempts
        val (_, html) = card(varsPath, "vault_db_password", 2)
        val text = VarsTestCase.plain(html)
        assertEquals(text, 6, Regex("group_vars/all/vault\\.yml:\\d+ · AES256").findAll(text).count())
        assertTrue(text, text.contains("2 more vault values"))
        assertNoSecret(html)
        assertEquals(attempts, crypto.decryptAttempts)
    }

    fun testANonVaultDefinitionHasNoVaultRow() {
        val (_, html) = card(varsPath, "db_password")
        assertFalse(VarsTestCase.plain(html), html.contains("ansibility-vault/reveal"))
    }

    fun testTheRevealLinkOpensThePopupAndResolvesToNothing() {
        val (target, html) = card(vaultPath, "vault_db_password")
        val url = Regex("href=\"(psi_element://ansibility-vault/reveal/[^\"]+)\"").find(html)!!.groupValues[1].replace("&amp;", "&")
        myFixture.configureFromExistingVirtualFile(vf(vaultPath))
        val result = ApplicationManager.getApplication().executeOnPooledThread<Any?> {
            runReadActionBlocking { VaultCardLinkHandler().resolveLink(target, url) }
        }.get(60, TimeUnit.SECONDS)
        assertNull("the card never navigates to the value", result)
        waitFor("no reveal popup") { VaultRevealService.getInstance(project).session != null }
        assertEquals("vault_db_password", VaultRevealService.getInstance(project).session!!.facts.keyName)
        assertNull(VaultCardLinks.parse("psi_element://ansibility-var/def/1/x"))
    }
}
