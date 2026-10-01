package de.terletzkiy.ansibility.completion.keys

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.CompletionSource
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.psi.util.PsiTreeUtil

/** Rules of this package that need no Ansible root: quoting, inventory owners, value types, registration. */
class KeyCompletionUnitTest : BasePlatformTestCase() {

    fun testPlainSafeStrings() {
        for (text in listOf("bookworm", "rdp-cookie", "main.site.combined.conf.j2", "a b", "x:y", "1.2.3", "v1")) {
            assertTrue(text, VarsValueCompletionSource.isPlainSafe(text))
        }
    }

    fun testStringsYamlWouldRetypeOrCannotLoadPlainAreNotSafe() {
        val unsafe = listOf(
            "yes", "No", "on", "OFF", "true", "null", "~", "", "42", "0644", "1.10", "1_000", "2024-01-01", "1:20",
            "-x", "*ref", "&a", "!tag", "{a}", "[a]", "#c", "@x", "`x`", "%x", "|", ">", "'q", "\"q", "a: b", "a #b",
            "a:", " lead", "trail ", "=",
        )
        for (text in unsafe) assertFalse(text, VarsValueCompletionSource.isPlainSafe(text))
    }

    fun testDoubleQuotingEscapes() {
        assertEquals("\"yes\"", VarsValueCompletionSource.doubleQuoted("yes"))
        assertEquals("\"a\\\"b\\\\c\"", VarsValueCompletionSource.doubleQuoted("a\"b\\c"))
    }

    fun testInventoryOwners() {
        assertEquals("all", InventoryVarOwners.inventory(listOf("all", "vars"))?.group)
        assertEquals("web1", InventoryVarOwners.inventory(listOf("all", "hosts", "web1"))?.host)
        assertEquals("analytics", InventoryVarOwners.inventory(listOf("contracting", "children", "analytics", "vars"))?.group)
        assertEquals("h", InventoryVarOwners.inventory(listOf("a", "children", "b", "hosts", "h"))?.host)
        assertTrue(InventoryVarOwners.inventory(listOf("all", "vars"))!!.varPath.isEmpty())
        assertNull("a host name, not a variable", InventoryVarOwners.inventory(listOf("all", "hosts")))
        assertNull("a group name", InventoryVarOwners.inventory(listOf("all")))
        assertNull("below a variable: the variables classifier decides", InventoryVarOwners.inventory(listOf("all", "vars", "x")))
        assertNull(InventoryVarOwners.inventory(listOf("all", "other")))
    }

    fun testMoleculeOwners() {
        assertEquals("all", InventoryVarOwners.molecule(listOf("provisioner", "inventory", "group_vars", "all"))?.group)
        assertEquals("instance", InventoryVarOwners.molecule(listOf("provisioner", "inventory", "host_vars", "instance"))?.host)
        assertEquals("keepalived_master", InventoryVarOwners.molecule(listOf("provisioner", "inventory", "hosts", "keepalived_master", "vars"))?.group)
        assertNull(InventoryVarOwners.molecule(listOf("provisioner", "inventory", "group_vars")))
        assertNull(InventoryVarOwners.molecule(listOf("provisioner", "env")))
        assertNull(InventoryVarOwners.molecule(listOf("platforms")))
    }

    fun testValueTypeNames() {
        val file = myFixture.configureByText(
            YAMLFileType.YML,
            "a: 1\nb: yes\nc: 1.5\nd: text\ne:\nf: {x: 1}\ng: [1]\nh: \"42\"\ni: ~\nj: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  6464\n",
        ) as YAMLFile
        val types = PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java)
            .filter { it.parent?.parent is org.jetbrains.yaml.psi.YAMLDocument }
            .associate { it.keyText to KeyCatalogs.typeName(it) }
        assertEquals(
            mapOf(
                "a" to "int", "b" to "bool", "c" to "float", "d" to "str", "e" to "null", "f" to "dict", "g" to "list",
                "h" to "str", "i" to "null", "j" to "str",
            ),
            types,
        )
    }

    fun testSourcesAreRegistered() {
        val sources = CompletionSource.EP_NAME.extensionList
        assertTrue(sources.any { it is VarsKeyCompletionSource })
        assertTrue(sources.any { it is VarsValueCompletionSource })
    }

    fun testKeyCharacters() {
        assertTrue(VarsCompletionRequest.isKeyChar('a'))
        assertTrue(VarsCompletionRequest.isKeyChar('_'))
        assertTrue(VarsCompletionRequest.isKeyChar('-'))
        assertTrue(VarsCompletionRequest.isKeyChar('9'))
        assertFalse(VarsCompletionRequest.isKeyChar(' '))
        assertFalse(VarsCompletionRequest.isKeyChar(':'))
        assertFalse(VarsCompletionRequest.isKeyChar('{'))
    }
}
