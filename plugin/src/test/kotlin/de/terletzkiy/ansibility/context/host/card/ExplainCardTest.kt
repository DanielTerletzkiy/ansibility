package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import de.terletzkiy.ansibility.api.CardPlacement
import de.terletzkiy.ansibility.api.CardSection
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vars.VarDocumentationTarget

/**
 * F8.2 part 2 (HA4b, ex-X14): the Explain precedence card for one (env, host, play) context, reached from the Effective
 * section's link, and its link handler; plus the registrations of the card parts (ids and documented order).
 */
class ExplainCardTest : HostCardTestCase() {
    override fun addFixtureFiles() {
        super.addFixtureFiles()
        // A runtime marker: a set_fact in postfix's tasks may replace the value while the play runs.
        add(
            "$FALCON/roles/postfix/tasks/relay.yml",
            """
            ---
            - name: Pick the relay at runtime
              ansible.builtin.set_fact:
                postfix_relayhost: "{{ groups['all'] | first }}"
            """,
        )
    }

    private val handler = HostCardLinkHandler()

    private fun explainLinks(html: String): List<String> =
        Regex("href=\"(psi_element://ansibility-host/explain[^\"]*)\"").findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.toList()

    private fun resolve(target: DocumentationTarget, url: String): DocumentationTarget =
        inBackgroundReadAction { handler.resolveTarget(target, url) } ?: error("$url does not resolve from $target")

    /** Acceptance 2 (host prod-prod1): L2 → L4 (struck through) → L5 (winner), with the runtime marker. */
    fun testExplainPrecedenceForOneHost() {
        withSelection(FALCON, RootContext(EnvironmentChoice.Named("prod"), "prod-prod1")) {
            val card = hover(POSTFIX_TEMPLATE, offsetAt(POSTFIX_TEMPLATE, 9, "postfix_relayhost", 1))
            val links = explainLinks(html(card))
            assertEquals("host mode: one Explain link", 1, links.size)
            val explain = resolve(card, links.single())
            assertTrue(explain is ExplainDocumentationTarget)
            val html = html(explain)
            val text = text(html)
            assertTrue(text, text.startsWith("postfix_relayhost Explain precedence · prod › prod-prod1"))
            assertTrue(text, "Play playbook-setup-system.yml › System · as a task of role postfix · playbook dir repos/falcon/ansible" in text)
            val l2 = text.indexOf("L2 role defaults · roles/postfix/defaults/main.yml:2 · = \"\" · shadowed")
            val l4 = text.indexOf("L4 inventory group_vars/all · environments/prod/group_vars/all/vars.yml:471 · = $prodRelayHost · shadowed")
            val l5 = text.indexOf("L5 playbook group_vars/all · group_vars/all/vars.yml:156 · = $relayHost · ✓ wins")
            assertTrue("lowest precedence first: $text", l2 in 0 until l4 && l4 < l5)
            assertTrue("shadowed steps are struck through", Regex("<s>inventory group_vars/all · <a [^>]*>environments/prod/group_vars/all/vars.yml:471</a>").containsMatchIn(html))
            assertTrue(text, "Runtime may be replaced at runtime by set_fact at roles/postfix/tasks/relay.yml:4" in text)
            assertFalse("host mode has no other hosts", "Other hosts" in text)
        }
    }

    /** All mode: one link per outcome; the Explain card links the same explanation for the scope's other hosts. */
    fun testOtherHostsAndDefinitionLinks() {
        val card = hover(POSTFIX_TEMPLATE, offsetAt(POSTFIX_TEMPLATE, 9, "postfix_relayhost", 1)) as VarDocumentationTarget
        val explain = resolve(card, explainLinks(html(card)).single()) as ExplainDocumentationTarget
        assertSame("the origin card is kept", card, explain.origin)
        val html = html(explain)
        val text = text(html)
        assertTrue(text, text.startsWith("postfix_relayhost Explain precedence · ops › ops-ops1"))
        assertTrue(text, "Other hosts prod › prod-prod1 · prod › prod-prod2 · test › test-test1" in text)
        val others = explainLinks(html)
        assertEquals(3, others.size)
        val prod2 = resolve(explain, others[1]) as ExplainDocumentationTarget
        assertSame(card, prod2.origin)
        assertTrue(text(html(prod2)).startsWith("postfix_relayhost Explain precedence · prod › prod-prod2"))

        val definition = Regex("href=\"(psi_element://ansibility-var/def/[^\"]*)\">group_vars/all/vars.yml:156<").find(html)!!.groupValues[1]
        val opened = resolve(explain, definition)
        assertTrue("a definition link opens the definition's card, as on the origin card", opened is VarDocumentationTarget)
        assertTrue(text(html(opened)), text(html(opened)).contains("This definition playbook group_vars/all · level 5"))

        val pointer = explain.createPointer().dereference() as ExplainDocumentationTarget
        assertEquals(explain.request, pointer.request)
        assertEquals(text, text(html(pointer)))
    }

    /** The runtime-default chain of the explained context. */
    fun testResolvesToTheEndOfABareReferenceChain() {
        val card = hover(HAPROXY_TEMPLATE, offsetAt(HAPROXY_TEMPLATE, 37, "haproxy_bind_ip", 2))
        val explain = resolve(card, explainLinks(html(card)).first())
        val text = text(html(explain))
        assertTrue(text, "L4 inventory group_vars/all · environments/prod/group_vars/all/vars.yml:524 · = \"{{ system_ip_floating }}\" · ✓ wins" in text)
        assertTrue(text, "Resolves to → str 192.0.2.33 via system_ip_floating · environments/prod/group_vars/all/vars.yml:582" in text)
    }

    fun testLinksRoundTripAndAContextThatIsGone() {
        val target = context.hostScope(vf(POSTFIX_TEMPLATE)).targets.first()
        val request = ExplainLinks.request("postfix_relayhost", target, "postfix")
        assertEquals(request, ExplainLinks.parse(ExplainLinks.of(request)))
        val inventoryOnly = ExplainLinks.request("x y&z=", target.copy(play = null, playbookDir = null), null)
        assertEquals(inventoryOnly, ExplainLinks.parse(ExplainLinks.of(inventoryOnly)))
        assertNull(ExplainLinks.parse("psi_element://ansibility-var/def/1/x"))
        assertNull("no host", ExplainLinks.parse("psi_element://ansibility-host/explain?name=x&root=r&env=e"))
        assertNull("a play without its index", ExplainLinks.parse("psi_element://ansibility-host/explain?name=x&root=r&env=e&host=h&play=file:///p"))
        // A malformed escape used to throw IllegalArgumentException out of the link handler.
        assertNull("a malformed escape", ExplainLinks.parse("psi_element://ansibility-host/explain?name=%zz&root=r&env=e&host=h"))
        assertNull("a malformed escape", ExplainLinks.parse("psi_element://ansibility-host/explain?name=x&root=r&env=e&host=h%2"))
        val withTaskVar = request.copy(taskVarUrl = vf(POSTFIX_DEFAULTS).url, taskVarOffset = 4)
        assertEquals(withTaskVar, ExplainLinks.parse(ExplainLinks.of(withTaskVar)))
        assertNull("a task var without its offset", ExplainLinks.parse(ExplainLinks.of(withTaskVar).substringBefore("&at=")))

        val card = hover(POSTFIX_TEMPLATE, offsetAt(POSTFIX_TEMPLATE, 9, "postfix_relayhost", 1))
        val gone = ExplainLinks.of(request.copy(host = HostKey(request.host.root, "prod", "prod-db9")))
        assertTrue(text(html(resolve(card, gone))).endsWith("This host or play no longer exists"))
        val missingPlay = ExplainLinks.of(request.copy(playIndex = 99))
        assertTrue(text(html(resolve(card, missingPlay))).endsWith("This host or play no longer exists"))
    }

    /**
     * DEV.md rule 6: an explain link naming a host of another root opens nothing. A falcon card used to explain a
     * platform host (with platform's chain and falcon's other hosts).
     */
    fun testLinksToAnotherRootsHostsResolveToNothing() {
        val card = hover(POSTFIX_TEMPLATE, offsetAt(POSTFIX_TEMPLATE, 9, "postfix_relayhost", 1))
        val platform = HostKey(RootKeys.keyOf(project, root(PLATFORM).dir), "prod", "prod-mlflow1")
        val foreign = ExplainLinks.of(ExplainLinks.Request("percona_config_innodb_buffer_pool_size", platform, null, -1, null, null))
        assertNull(inBackgroundReadAction { handler.resolveTarget(card, foreign) })
        val explain = resolve(card, explainLinks(html(card)).first())
        assertNull("nor from an Explain card", inBackgroundReadAction { handler.resolveTarget(explain, foreign) })
        val detached = ExplainDocumentationTarget(project, null, (explain as ExplainDocumentationTarget).request)
        assertNull("nor from one whose origin card is gone", inBackgroundReadAction { handler.resolveTarget(detached, foreign) })
        assertNotNull("its own root's hosts still resolve", inBackgroundReadAction { handler.resolveTarget(detached, explainLinks(html(card)).first()) })
    }

    fun testHandlerLeavesOtherLinksToTheirOwners() {
        val card = hover(POSTFIX_TEMPLATE, offsetAt(POSTFIX_TEMPLATE, 9, "postfix_relayhost", 1))
        assertNull("variable-card links stay the vars handler's", inBackgroundReadAction { handler.resolveTarget(card, "psi_element://ansibility-var/var/postfix_relayhost") })
        assertNull("Reveal links stay the vault handler's", inBackgroundReadAction { handler.resolveTarget(card, "psi_element://ansibility-vault/reveal/1/x") })
        val foreign = object : DocumentationTarget {
            override fun createPointer() = throw UnsupportedOperationException()
            override fun computePresentation() = throw UnsupportedOperationException()
        }
        val link = explainLinks(html(card)).first()
        assertNull("only cards of this plugin open explanations", handler.resolveTarget(foreign, link))
        // By name: DocumentationLinkHandler.EP_NAME is @ApiStatus.Internal.
        val handlers = ExtensionPointName<DocumentationLinkHandler>("com.intellij.platform.backend.documentation.linkHandler").extensionList
        assertTrue(handlers.any { it is HostCardLinkHandler })
    }

    /** The documented order (DEV.md, wave 6): Effective is the first TOP section; the Effect row precedes the vault row and V003's rows. */
    fun testSectionsAreRegisteredInTheDocumentedOrder() {
        val sections = CardSection.EP_NAME.extensionList
        val effective = sections.indexOfFirst { it is EffectiveCardSection }
        val definition = sections.indexOfFirst { it is DefinitionCardSection }
        val vault = sections.indexOfFirst { it.javaClass.simpleName == "VaultCardSection" }
        val undefined = sections.indexOfFirst { it.javaClass.simpleName == "UndefinedCardSection" }
        assertTrue("$sections", effective >= 0 && definition >= 0 && vault >= 0 && undefined >= 0)
        assertEquals(CardPlacement.TOP, sections[effective].placement)
        assertEquals(CardPlacement.SECTION, sections[definition].placement)
        assertTrue("first TOP section", sections.take(effective).none { it.placement == CardPlacement.TOP })
        assertTrue("$sections", definition < vault && vault < undefined)

        val html = card(FALCON_VAULT, 28, "vault_alloy_tenant_api_key_prod")
        assertTrue("the Effect row comes before the Vault row", html.indexOf("<p>Effect</p>") in 0 until html.indexOf("<p>Vault</p>"))
    }
}
