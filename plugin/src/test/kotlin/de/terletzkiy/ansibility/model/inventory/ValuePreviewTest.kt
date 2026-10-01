package de.terletzkiy.ansibility.model.inventory

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.psi.YAMLFile

/** [ValuePreview]: single-line previews that never show secrets. */
class ValuePreviewTest : BasePlatformTestCase() {
    private fun values(text: String): Map<String, YValue> {
        val file = myFixture.configureByText("vars.yml", text) as YAMLFile
        val map = PsiYValueAdapter.documentValue(file) as YMap
        return map.entries.associate { it.key.text to it.value }
    }

    private val plainFile = LightVirtualFile("vars.yml")

    fun testScalars() {
        val v = values(
            """
            plain: relay.example.test
            number: 42
            single: '0.0.0.0'
            double: "{{ host_ips['a'] }}"
            empty:
            tagged: !!str 3.2
            unsafe: !unsafe "{{ raw }}"
            block: |
              first line
              second line
            folded: >
              folded text
            """.trimIndent() + "\n",
        )
        assertEquals("relay.example.test", ValuePreview.render(v.getValue("plain")))
        assertEquals("42", ValuePreview.render(v.getValue("number")))
        assertEquals("quotes show the type", "'0.0.0.0'", ValuePreview.render(v.getValue("single")))
        assertEquals("\"{{ host_ips['a'] }}\"", ValuePreview.render(v.getValue("double")))
        assertEquals("null", ValuePreview.render(v.getValue("empty")))
        assertEquals("!!str 3.2", ValuePreview.render(v.getValue("tagged")))
        assertEquals("!unsafe \"{{ raw }}\"", ValuePreview.render(v.getValue("unsafe")))
        assertEquals("first line …", ValuePreview.render(v.getValue("block")))
        assertEquals("folded text", ValuePreview.render(v.getValue("folded")))
    }

    fun testCollectionsAndTruncation() {
        val v = values(
            """
            servers:
              - name: backend1
                port: 8080
            creds:
              user: admin
              vault_password: plain
              token: !vault |
                ${'$'}ANSIBLE_VAULT;1.1;AES256
                64756d6d79
            long: ${"x".repeat(200)}
            """.trimIndent() + "\n",
        )
        assertEquals("[{name: backend1, port: 8080}]", ValuePreview.render(v.getValue("servers")))
        assertEquals("{user: admin, vault_password: ***, token: !vault}", ValuePreview.render(v.getValue("creds")))
        val long = ValuePreview.render(v.getValue("long"))
        assertEquals(ValuePreview.MAX_LENGTH, long.length)
        assertTrue(long.endsWith("…"))
    }

    fun testSecretsAreNeverPreviewed() {
        val v = values("vault_db: plain\nsecret: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  64756d6d79\nuser: admin\n")
        assertNull(ValuePreview.of("vault_db", v.getValue("vault_db"), plainFile))
        assertNull(ValuePreview.of("secret", v.getValue("secret"), plainFile))
        assertEquals("admin", ValuePreview.of("user", v.getValue("user"), plainFile))
        for (name in listOf("vault.yml", "vault.yaml", "vault", "vault_prod.yml", "Vault.json")) {
            assertNull(name, ValuePreview.of("user", v.getValue("user"), LightVirtualFile(name)))
        }
        assertTrue(ValuePreview.isSecret("vault_db", v.getValue("vault_db")))
        assertFalse(ValuePreview.isSecret("user", v.getValue("user")))
    }

    fun testShapes() {
        val v = values("a: 1\nb: '{{ x }}'\nc: ~\nd:\ne: [1]\nf: !vault |\n  \$ANSIBLE_VAULT;1.1;AES256\n  64756d6d79\ng: '{% if x %}y{% endif %}'\n")
        assertEquals(ValueShape.LITERAL, ValuePreview.shape(v.getValue("a")))
        assertEquals(ValueShape.JINJA, ValuePreview.shape(v.getValue("b")))
        assertEquals(ValueShape.NULL, ValuePreview.shape(v.getValue("c")))
        assertEquals(ValueShape.NULL, ValuePreview.shape(v.getValue("d")))
        assertEquals(ValueShape.CONTAINER, ValuePreview.shape(v.getValue("e")))
        assertEquals(ValueShape.VAULT, ValuePreview.shape(v.getValue("f")))
        assertEquals(ValueShape.JINJA, ValuePreview.shape(v.getValue("g")))
    }
}
