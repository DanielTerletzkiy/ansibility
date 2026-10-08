package de.terletzkiy.ansibility.vars

import com.intellij.testFramework.IndexingTestUtil
import de.terletzkiy.ansibility.context.host.card.HostCardLinkHandler

/**
 * Values of a variable a spec keeps secret (`no_log`, plan amendment R23) on a root with an inventory: the host-aware
 * parts of the card (Effective section, its rendered value and chain, the Explain card) never show them, like the card's
 * own rows. (The render area's "Rendered" row and the template preview render with real values, as they do for plain
 * `vault_*` values; only encrypted values become placeholders there.)
 */
class NoLogValuesTest : VarsTestCase() {
    override fun setUp() {
        super.setUp()
        write("$ROOT/ansible.cfg", "[defaults]\nroles_path = roles\ninventory = environments/prod/hosts.yml\n")
        write("$ROOT/environments/prod/hosts.yml", "all:\n  hosts:\n    prod1:\n")
        write(
            "$ROOT/environments/prod/group_vars/all.yml",
            "inv_password: role-secret-21\ninv_dsn: \"{{ inv_password }}\"\ninv_token: \"tok-{{ inv_base }}\"\ninv_base: role-secret-23\n" +
                "inv_url: \"https://app:{{ inv_password }}@db\"\n",
        )
        write("$ROOT/site.yml", "- hosts: all\n  roles: [invr]\n")
        write(
            "$ROOT/roles/invr/meta/argument_specs.yml",
            "---\nargument_specs:\n  main:\n    options:\n      inv_password:\n        type: str\n        no_log: true\n" +
                "      inv_dsn:\n        type: str\n      inv_token:\n        type: str\n        no_log: true\n",
        )
        write("$ROOT/roles/invr/defaults/main.yml", "inv_password: role-secret-22\n")
        write(TASKS, "- name: Use\n  ansible.builtin.debug:\n    msg: \"{{ inv_password }} {{ inv_dsn }} {{ inv_token }} {{ inv_url }}\"\n")
        refreshRoots()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testTheEffectiveSectionHidesANoLogValue() {
        val target = hover(TASKS, offsetAt(TASKS, 3, "inv_password", 1))
        val html = html(target)
        val card = ownParts(html)
        assertNoSecret(card)
        assertTrue(card, "Effective on prod › prod1 (play site.yml #0) = 🔒 value hidden (no_log) · environments/prod/group_vars/all.yml:1" in card)
        val links = explainLinks(html)
        assertFalse(card, links.isEmpty())
        for (link in links) {
            val explained = text(html(HostCardLinkHandler().resolveTarget(target, link)!!))
            assertNoSecret(explained)
            assertTrue(explained, "environments/prod/group_vars/all.yml:1 · = 🔒 value hidden (no_log) · ✓ wins" in explained)
        }
    }

    fun testAChainThroughANoLogVariableEndsHidden() {
        val target = hover(TASKS, offsetAt(TASKS, 3, "inv_dsn", 1))
        val html = html(target)
        val card = ownParts(html)
        assertNoSecret(card)
        assertTrue(card, "→ 🔒 value hidden (no_log) via inv_password" in card)
        assertFalse("no rendering of the secret it leads to: $card", "renders:" in card)
        val links = explainLinks(html)
        assertFalse(card, links.isEmpty())
        for (link in links) {
            val explained = text(html(HostCardLinkHandler().resolveTarget(target, link)!!))
            assertNoSecret(explained)
            assertTrue(explained, "→ 🔒 value hidden (no_log) via inv_password" in explained)
        }
    }

    fun testATemplatedNoLogValueIsNotRendered() {
        val card = ownParts(html(hover(TASKS, offsetAt(TASKS, 3, "inv_token", 1))))
        assertNoSecret(card)
        assertTrue(card, "= 🔒 value hidden (no_log) · environments/prod/group_vars/all.yml:3" in card)
        assertFalse(card, "renders:" in card || "tok-" in card)
    }

    /** A template that reads a `no_log` variable is not rendered on the card (it would print that value). */
    fun testAValueThatReadsANoLogVariableIsNotRendered() {
        val card = ownParts(html(hover(TASKS, offsetAt(TASKS, 3, "inv_url", 1))))
        assertNoSecret(card)
        assertTrue(card, "= \"https://app:{{ inv_password }}@db\" · environments/prod/group_vars/all.yml:5" in card)
        assertFalse(card, "renders:" in card)
    }

    /** The card's text without the render area's "Rendered" row (which renders with real values, see the class doc). */
    private fun ownParts(html: String): String = text(html).substringBefore(" Rendered ")

    private fun assertNoSecret(text: String) {
        for (secret in listOf("role-secret-21", "role-secret-22", "role-secret-23")) assertFalse("$secret: $text", secret in text)
    }

    private fun explainLinks(html: String): List<String> =
        Regex("href=\"(psi_element://ansibility-host/explain[^\"]*)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()

    private fun write(path: String, text: String) {
        myFixture.tempDirFixture.createFile(path, text)
    }

    private companion object {
        const val ROOT = "inv"
        const val TASKS = "$ROOT/roles/invr/tasks/main.yml"
    }
}
