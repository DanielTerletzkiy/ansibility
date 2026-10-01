package de.terletzkiy.ansibility.vars

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/** The pure helpers of the variable card: value display, vault envelopes, card links, sentences, option paths. */
class VarHelpersTest : BasePlatformTestCase() {
    private fun values(text: String): Map<String, YValue> {
        val file = myFixture.configureByText("vars.yml", text) as YAMLFile
        return (PsiYValueAdapter.documentValue(file) as YMap).entries.associate { it.key.text to it.value }
    }

    fun testValueDisplayWritesValuesLikeAnsibleDoc() {
        val v = values(
            """
            int_string: "65535"
            float_string: "3.2"
            empty: ""
            plain: roundrobin
            bool: false
            float: 3.2
            nothing:
            yes_string: "yes"
            colon: "a: b"
            list: [a, "1", 2]
            map: {k: v}
            multi: "a\nb"
            """.trimIndent() + "\n",
        )
        assertEquals("'65535'", ValueDisplay.dump(v.getValue("int_string")))
        assertEquals("'3.2'", ValueDisplay.dump(v.getValue("float_string")))
        assertEquals("''", ValueDisplay.dump(v.getValue("empty")))
        assertEquals("roundrobin", ValueDisplay.dump(v.getValue("plain")))
        assertEquals("false", ValueDisplay.dump(v.getValue("bool")))
        assertEquals("3.2", ValueDisplay.dump(v.getValue("float")))
        assertEquals("null", ValueDisplay.dump(v.getValue("nothing")))
        assertEquals("'yes'", ValueDisplay.dump(v.getValue("yes_string")))
        assertEquals("'a: b'", ValueDisplay.dump(v.getValue("colon")))
        assertEquals("[a, '1', 2]", ValueDisplay.dump(v.getValue("list")))
        assertEquals("{k: v}", ValueDisplay.dump(v.getValue("map")))
        assertEquals("\"a\\nb\"", ValueDisplay.dump(v.getValue("multi")))
        assertEquals("float", ValueDisplay.typeName(v.getValue("float")))
        assertEquals("str", ValueDisplay.typeName(v.getValue("float_string")))
        assertEquals("list", ValueDisplay.typeName(v.getValue("list")))
    }

    fun testVaultEnvelope() {
        assertEquals(VaultInfo.Header("1.1", "AES256", null), VaultInfo.parse("\$ANSIBLE_VAULT;1.1;AES256"))
        assertEquals("AES256, 1.2, prod", VaultInfo.parse("\$ANSIBLE_VAULT;1.2;AES256;prod")!!.summary)
        assertNull(VaultInfo.parse("not a vault"))
        assertNull(VaultInfo.parse("\$ANSIBLE_VAULT;1.1"))
        assertTrue(VaultInfo.isSecret("vault_x", LightVirtualFile("vars.yml")))
        assertTrue(VaultInfo.isSecret("x", LightVirtualFile("vault.yml")))
        assertFalse(VaultInfo.isSecret("x", LightVirtualFile("vars.yml")))
    }

    fun testLinksRoundTrip() {
        assertEquals(VarLinks.Link.Option(listOf("servers", "a/b")), VarLinks.parse(VarLinks.option(listOf("servers", "a/b"))))
        assertEquals(VarLinks.Link.Variable("web_nested", listOf("inner")), VarLinks.parse(VarLinks.variable(listOf("web_nested", "inner"))))
        val file = LightVirtualFile("vars.yml")
        val definition = VarLinks.parse(VarLinks.definition(SourceLocation(file, 42)))
        assertEquals(VarLinks.Link.Definition(file.url, 42), definition)
        assertNull(VarLinks.parse("psi_element://ansibility-module/ansible.builtin.copy"))
        assertNull(VarLinks.parse("https://docs.ansible.com"))
        assertTrue(VarLinks.option(listOf("x")).startsWith("psi_element://"))
    }

    fun testFirstSentence() {
        assertEquals("Inner value, e.g. 1.", VarCardHtml.firstSentence("Inner value, e.g. 1. Second sentence."))
        assertEquals("One line", VarCardHtml.firstSentence("One line"))
        assertEquals("Uses C(a.b) here.", VarCardHtml.firstSentence("Uses C(a.b) here. More."))
        assertEquals("A folded description.", VarCardHtml.firstSentence("A folded\n  description. Next!"))
    }

    fun testNestedOptionPaths() {
        val port = OptionSpec("port", OptionType.Int, required = true)
        val servers = OptionSpec("servers", OptionType.List, elements = OptionType.Dict, options = mapOf("port" to port))
        val check = OptionSpec("check", OptionType.Dict, options = mapOf("uri" to OptionSpec("uri", aliases = listOf("url"))))
        assertEquals(listOf("port"), SpecOptions.resolve(servers, listOf("0", "port"))!!.names)
        assertEquals(port, SpecOptions.resolve(servers, listOf("port"))!!.option)
        assertEquals("aliases resolve to the option", listOf("uri"), SpecOptions.resolve(check, listOf("url"))!!.names)
        assertNull(SpecOptions.resolve(check, listOf("nope")))
        assertEquals(check, SpecOptions.resolve(check, emptyList())!!.option)
        assertEquals("list[dict]", VarCard.typeText(servers))
        assertEquals("dict", VarCard.typeText(check))
    }
}
