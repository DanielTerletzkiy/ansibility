package de.terletzkiy.ansibility.vault.regenerate

import com.intellij.configurationStore.serialize
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.semantics.vault.GenerationRule
import de.terletzkiy.ansibility.semantics.vault.GeneratorKind
import de.terletzkiy.ansibility.semantics.vault.SecretGenerator
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.ui.settings.VaultConfigurable
import org.jdom.Element
import javax.swing.JButton

/**
 * Plan amendment R29 (D218, X131, D217): the generation rules in the shared vault settings (XML round trip, older
 * files without rules), Import from Script… on the Vault settings page, and the selection dialog's tree (groups
 * check together, texts without values, split).
 */
class RegenerateSettingsTest : RegenerateTestCase() {
    private val settings: VaultProjectSettings get() = VaultProjectSettings.getInstance(project)

    private val script = """
        #!/usr/bin/env bash
        PASSWORD_LENGTH="${'$'}{PASSWORD_LENGTH:-32}"
        generate_password() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${'$'}{1:-${'$'}PASSWORD_LENGTH}"; }
        value_for() {
          case "${'$'}1" in
            vault_*_cookie_secret) openssl rand -hex 16 ;;
            vault_vrrp_password) generate_password 8 ;;
            vault_proxy_hmac_secret) openssl rand -base64 32 ;;
            vault_legacy_*) echo keep ;;
            *) generate_password "${'$'}PASSWORD_LENGTH" ;;
          esac
        }
    """.trimIndent()

    private val rules = listOf(
        GenerationRule("vault_*_cookie_secret", GeneratorKind.HEX, 32),
        GenerationRule("vault_vrrp_password", GeneratorKind.ALPHANUMERIC, 8),
        GenerationRule("vault_proxy_hmac_secret", GeneratorKind.BASE64, 32),
        GenerationRule("*", GeneratorKind.ALPHANUMERIC, 32),
    )

    fun testRulesRoundTripThroughTheSharedXmlAndOlderFilesReadAsNone() {
        settings.update("repos/falcon/ansible") { it.copy(generationRules = rules) }
        val element = serialize(settings.state)!!
        val xml = com.intellij.openapi.util.JDOMUtil.write(element)
        assertTrue(xml, xml.contains("<rule generator=\"HEX\" length=\"32\" pattern=\"vault_*_cookie_secret\" />"))
        val loaded = VaultProjectSettings()
        loaded.loadState(XmlSerializer.deserialize(element, VaultProjectSettings.StateBean::class.java))
        assertEquals(rules, loaded.rootSettings("repos/falcon/ansible").generationRules)

        // A file of an older version has no rules; a rule this version cannot read is dropped, the others stay.
        val old = Element("AnsibilityVault").addContent(
            Element("roots").addContent(
                Element("root").setAttribute("key", "repos/heron/ansible").addContent(
                    Element("generationRules")
                        .addContent(Element("rule").setAttribute("pattern", "vault_x").setAttribute("generator", "ROT13").setAttribute("length", "8"))
                        .addContent(Element("rule").setAttribute("pattern", "vault_y").setAttribute("generator", "HEX").setAttribute("length", "16")),
                ).addContent(Element("identities").addContent(Element("identity").setAttribute("label", "dev").setAttribute("kind", "PROMPT"))),
            ),
        )
        val older = VaultProjectSettings()
        older.loadState(XmlSerializer.deserialize(old, VaultProjectSettings.StateBean::class.java))
        assertEquals(listOf(GenerationRule("vault_y", GeneratorKind.HEX, 16)), older.rootSettings("repos/heron/ansible").generationRules)
        assertEquals("dev", older.rootSettings("repos/heron/ansible").identities.single().label)
        assertEmpty(older.rootSettings("repos/falcon/ansible").generationRules)
    }

    fun testImportFromScriptPreselectsTheRepositorysScriptAndAppendsItsRules() {
        write("repos/falcon/bin/rotate-secrets.sh", script)
        ready()
        regeneratePrompts.script = vf("repos/falcon/bin/rotate-secrets.sh")
        val page = VaultConfigurable(project)
        try {
            val component = page.createComponent()!!
            val roots = UIUtil.findComponentsOfType(component, ComboBox::class.java).first()
            PlatformTestUtil.waitWithEventsDispatching("the roots did not load", { roots.itemCount > 0 }, 60)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val button = UIUtil.findComponentsOfType(component, JButton::class.java).single { it.name == VaultConfigurable.IMPORT_RULES_COMPONENT }

            button.doClick()

            assertEquals("the repository's rotation script is preselected", vf("repos/falcon/bin/rotate-secrets.sh"), regeneratePrompts.scriptRequests.single())
            assertEquals(4 to listOf("vault_legacy_*"), regeneratePrompts.importResults.single())
            assertTrue(page.isModified)
            page.apply()
            val rootKey = registry.discovery(root(root)).rootKey
            assertEquals(rules, settings.rootSettings(rootKey).generationRules)
        } finally {
            page.disposeUIResources()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    fun testTheDialogChecksAGroupTogetherShowsNoValueAndSplitsIt() {
        val same = "Same0Secret0Value0123456789"
        write("$root/group_vars/all/vault.yml", VaultVectors.inline("vault_a_password", VaultVectors.encrypt(same, VaultVectors.PW1)) +
            VaultVectors.inline("vault_b_password", VaultVectors.encrypt(same, VaultVectors.PW1)) +
            VaultVectors.inline("vault_cert", VaultVectors.encrypt("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n", VaultVectors.PW1)) +
            VaultVectors.inline("vault_vrrp_password", VaultVectors.encrypt("Ab3dEf7h", VaultVectors.PW1)))
        ready()
        val texts = ArrayList<String>()
        regeneratePrompts.onValues = { request ->
            val dialog = RegenerateDialog(project, request)
            try {
                val a = request.rows.single { it.keyPath == "vault_a_password" }
                val b = request.rows.single { it.keyPath == "vault_b_password" }
                val cert = request.rows.single { it.keyPath == "vault_cert" }
                val vrrp = request.rows.single { it.keyPath == "vault_vrrp_password" }
                for (row in request.rows) texts += dialog.textOf(row)
                assertTrue(dialog.isShownChecked(a) && dialog.isShownChecked(b) && !dialog.isShownChecked(cert))
                dialog.click(a)
                assertFalse("unchecking one value of a group unchecks the group", dialog.isShownChecked(b) || b.checked)
                dialog.click(a)
                assertTrue(b.checked && dialog.isShownChecked(b))
                assertTrue(dialog.textOf(a), dialog.textOf(a).contains("equal values (group 1, 2 values)"))
                assertTrue(dialog.textOf(vrrp), dialog.textOf(vrrp).contains("short"))
                assertTrue(dialog.textOf(cert), dialog.textOf(cert).contains("not a password: a PEM block"))
                request.split(a.group!!)
                assertFalse(dialog.textOf(a).contains("equal values"))
                request.setGenerator(listOf(vrrp), SecretGenerator(GeneratorKind.ALPHANUMERIC, 12))
                assertTrue(dialog.textOf(vrrp), dialog.textOf(vrrp).contains("12 alphanumeric"))
            } finally {
                dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            }
            false
        }

        regenerate(vf("$root/group_vars/all/vault.yml"))

        assertEquals(4, texts.size)
        assertTrue(texts[0], texts[0].startsWith("vault_a_password · default · same as now: 27 alphanumeric"))
        for (text in texts) assertFalse(text, text.contains(same) || text.contains("Ab3dEf7h") || text.contains("AAAA"))
    }
}
