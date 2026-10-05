package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.vfs.VfsUtilCore
import de.terletzkiy.ansibility.fixtures.InfraTestData
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * Vault safety of the host-aware card (plan amendment R7/R8, F8.2 "Vault", F7.13 rule 2): vault winners are masked
 * with header facts and the vault area's Reveal link, values of vault files and `vault_*` names are hidden, chains end
 * at the masked value, and building any of it (Effective section, Effect row, ranked "Set in", Explain card) never
 * decrypts (`VaultCrypto.decryptAttempts` unchanged) and never puts an envelope or plaintext into the HTML.
 */
class HostCardVaultTest : HostCardTestCase() {
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        // A plain value in a vault file: never previewed, whatever it holds; one of them is a bare reference.
        add(
            "$FALCON/environments/prod/group_vars/all/vault_plain.yml",
            "---\nha4_plain_secret: ANSIBILITY-SENTINEL-HA4\nha4_masked_ref: \"{{ ha4_sentinel_target }}\"",
        )
        // A `vault_*` name holding a bare reference, and the visible value both references lead to.
        add(
            "$FALCON/environments/prod/group_vars/all/ha4_chain.yml",
            "---\nha4_sentinel_target: ANSIBILITY-SENTINEL-TARGET\nvault_ha4_named_ref: \"{{ ha4_sentinel_target }}\"",
        )
        // A task var holding one of the fixture's synthetic envelopes, used by its own task.
        val envelope = VfsUtilCore.loadText(vf(FALCON_VAULT)).lines()
            .dropWhile { !it.startsWith("vault_alloy_tenant_api_key_prod:") }.drop(1).takeWhile { it.startsWith("  ") }
        add(
            VAULT_TASKS,
            "---\n- name: Secret task var\n  vars:\n    postfix_relayhost: !vault |\n" +
                envelope.joinToString("") { "      ${it.trim()}\n" } +
                "  ansible.builtin.debug:\n    msg: \"{{ postfix_relayhost }}\"",
        )
    }

    /** A block or task var in force at the card's position is masked like any vault winner, and never decrypted. */
    fun testVaultTaskVarsAreMasked() {
        val attempts = crypto.decryptAttempts
        val line = VfsUtilCore.loadText(vf(VAULT_TASKS)).lines().indexOfFirst { "msg:" in it } + 1
        val html = card(VAULT_TASKS, line, "postfix_relayhost")
        assertTrue(effective(html), "= 🔒 vault-encrypted (AES256, 1.1) · Unlock and reveal · roles/postfix/tasks/ha4_vault.yml:4 · L15 block/task vars" in effective(html))
        assertNoSecret(html)
        val explained = html(HostCardLinkHandler().resolveTarget(hover(VAULT_TASKS, offsetAt(VAULT_TASKS, line, "postfix_relayhost", 1)), explainLinks(html).first())!!)
        assertTrue(text(explained), "L15 block/task vars · roles/postfix/tasks/ha4_vault.yml:4 · = 🔒 vault-encrypted (AES256, 1.1) · Unlock and reveal · ✓ wins" in text(explained))
        assertNoSecret(explained)
        assertEquals("no decryption on any card path", attempts, crypto.decryptAttempts)
    }

    private val crypto: VaultCrypto get() = VaultCrypto.getInstance(project)

    private fun assertNoSecret(html: String) {
        assertFalse("no payload", InfraTestData.containsVaultPayload(html))
        assertFalse("no envelope", "\$ANSIBLE_VAULT" in html)
        assertFalse("no plaintext", "ANSIBILITY-SENTINEL" in html)
    }

    fun testVaultWinnersAreMaskedWithTheRevealLink() {
        val attempts = crypto.decryptAttempts
        val html = card(FALCON_VAULT, 28, "vault_alloy_tenant_api_key_prod")
        val section = effective(html)
        assertTrue(section, "= 🔒 vault-encrypted (AES256, 1.1) · Unlock and reveal · group_vars/all/vault.yml:28 · L5 playbook group_vars/all" in section)
        val top = html.substring(html.indexOf("Effective on"), html.indexOf("</div>", html.indexOf("Effective on")))
        assertTrue("the Reveal link is the vault area's", "psi_element://ansibility-vault/reveal/" in top)
        assertNoSecret(html)
        assertEquals("building the card never decrypts", attempts, crypto.decryptAttempts)
    }

    fun testChainsEndAtTheMaskedValueAndExplainNeverDecrypts() {
        val attempts = crypto.decryptAttempts
        val html = card(ALLOY_TEMPLATE, 16, "alloy_tenant_api_key")
        assertTrue(effective(html), "→ 🔒 vault-encrypted via vault_alloy_tenant_api_key_test" in effective(html))
        assertNoSecret(html)
        withSelection(FALCON, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")) {
            val hostCard = hover(FALCON_VAULT, offsetAt(FALCON_VAULT, 28, "vault_alloy_tenant_api_key_prod", 1))
            val hostHtml = html(hostCard)
            assertNoSecret(hostHtml)
            val explain = HostCardLinkHandler().resolveTarget(hostCard, explainLinks(hostHtml).single())!!
            val explained = html(explain)
            assertTrue(text(explained), "= 🔒 vault-encrypted (AES256, 1.1) · Unlock and reveal · ✓ wins" in text(explained))
            assertNoSecret(explained)
        }
        assertEquals("no decryption on any card path", attempts, crypto.decryptAttempts)
    }

    fun testValuesOfVaultFilesAreHidden() {
        val html = card("$FALCON/environments/prod/group_vars/all/vault_plain.yml", 2, "ha4_plain_secret")
        assertTrue(effective(html), "= 🔒 value hidden · environments/prod/group_vars/all/vault_plain.yml:2" in effective(html))
        assertEquals("✓ effective for prod-prod1, prod-prod2", row(html, "Effect"))
        assertNoSecret(html)
    }

    /**
     * A masked value (a vault file's, a `vault_*` name's) that is a bare reference has no chain: the names and the value it
     * leads to would tell what the hidden value says. Neither the card nor its Explain card follows it.
     */
    fun testMaskedValuesAreNotFollowed() {
        for ((path, line, name) in listOf(
            Triple("$FALCON/environments/prod/group_vars/all/vault_plain.yml", 3, "ha4_masked_ref"),
            Triple("$FALCON/environments/prod/group_vars/all/ha4_chain.yml", 3, "vault_ha4_named_ref"),
        )) {
            val target = hover(path, offsetAt(path, line, name, 1))
            val html = html(target)
            val section = effective(html)
            assertTrue(section, "= 🔒 value hidden" in section)
            assertFalse("no chain from a masked value: $section", "→" in section || "ha4_sentinel_target" in section)
            assertNoSecret(html)
            val links = explainLinks(html)
            assertFalse("the outcome links its Explain card", links.isEmpty())
            for (link in links) {
                val explained = html(HostCardLinkHandler().resolveTarget(target, link)!!)
                assertFalse(text(explained), "Resolves to" in text(explained) || "ha4_sentinel_target" in explained)
                assertNoSecret(explained)
            }
        }
    }

    private fun explainLinks(html: String): List<String> =
        Regex("href=\"(psi_element://ansibility-host/explain[^\"]*)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()

    private companion object {
        const val VAULT_TASKS = "$FALCON/roles/postfix/tasks/ha4_vault.yml"
    }
}
