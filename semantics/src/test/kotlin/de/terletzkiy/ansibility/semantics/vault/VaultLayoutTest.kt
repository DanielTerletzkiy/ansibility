package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Rendering inline vault blocks and detecting a file's indentation style. */
class VaultLayoutTest {
    private val v01 = VaultTestData.envelope(VaultTestData.vector("v01").envelope)

    @Test
    fun `encrypt_string output re-indented to the repo's key + 2 style`() {
        val raw = VaultTestData.text("raw/v01.yml")
        val reindented = raw.lines().mapIndexed { i, line -> if (i == 0) line else line.removePrefix("        ") }.joinToString("\n")
        assertEquals(reindented, VaultLayout.inlineBlock("greeting", 0, 2, v01))
        assertEquals("    greeting: !vault |\n      \$ANSIBLE_VAULT;1.1;AES256\n",
            VaultLayout.inlineBlock("greeting", 4, 6, v01).lineSequence().take(2).joinToString("\n", postfix = "\n"))
    }

    @Test
    fun `an inline value has no final line break and survives a YAML round trip`() {
        val value = VaultLayout.inlineValue(v01, 2)
        assertFalse(value.endsWith("\n"))
        assertEquals(v01.formatLines().size + 1, value.lines().size)
        assertEquals(v01.format(), VaultTestData.vaultScalar("x: $value\n"))
        val crlf = VaultLayout.inlineBlock("x", 0, 2, v01, newline = "\r\n")
        assertEquals(crlf.count { it == '\n' }, crlf.split("\r\n").size - 1, "every line ends with CRLF")
        assertEquals(v01, VaultTestData.envelope(VaultTestData.vaultScalar(crlf)!!))
        assertThrows(IllegalArgumentException::class.java) { VaultLayout.inlineBlock("x", 2, 2, v01) }
        assertThrows(IllegalArgumentException::class.java) { VaultLayout.inlineValue(v01, 0) }
    }

    @Test
    fun `the multi-vault fixture is in the repo's style`() {
        val text = VaultTestData.text("raw/multivault-fixture.yml")
        val blocks = VaultLayout.blocks(text)
        assertEquals(7, blocks.size)
        assertEquals(setOf(2), blocks.map { it.relativeIndent }.toSet())
        assertEquals("vault_greeting", blocks.first().key)
        assertEquals(2, VaultLayout.detectRelativeIndent(text))
        assertEquals(6, VaultLayout.bodyIndentFor(text, keyColumn = 4))
    }

    @Test
    fun `the most frequent indent wins, ties keep the first`() {
        val two = VaultLayout.inlineBlock("a", 0, 2, v01) + VaultLayout.inlineBlock("b", 0, 2, v01)
        val ten = VaultLayout.inlineBlock("c", 0, 10, v01)
        assertEquals(2, VaultLayout.detectRelativeIndent(two + ten))
        assertEquals(10, VaultLayout.detectRelativeIndent(ten + VaultLayout.inlineBlock("d", 0, 2, v01)))
        assertEquals(10, VaultLayout.detectRelativeIndent("x: 1\n" + ten))
        assertNull(VaultLayout.detectRelativeIndent("plain: value\nother: !vault \"\$ANSIBLE_VAULT;1.1;AES256\\n00\"\n"))
        assertEquals(6, VaultLayout.bodyIndentFor("plain: value\n", keyColumn = 4), "no vault yet: key + 2")
    }

    @Test
    fun `nested keys, sequence items, styles and comments`() {
        val text = """
            |top:
            |  nested: !vault |
            |      ${'$'}ANSIBLE_VAULT;1.1;AES256
            |      3030
            |  list:
            |    - !vault-encrypted |-
            |      ${'$'}ANSIBLE_VAULT;1.1;AES256
            |    - key: !vault >  # folded, still a block
            |        ${'$'}ANSIBLE_VAULT;1.1;AES256
            |  "quoted key": !vault |+
            |
            |    ${'$'}ANSIBLE_VAULT;1.2;AES256;dev
            |  'single': !vault |
            |    not an envelope
            |""".trimMargin().replace("\n", "\r\n")
        val blocks = VaultLayout.blocks(text)
        assertEquals(listOf("nested", null, "key", "\"quoted key\"", "'single'"), blocks.map { it.key })
        assertEquals(listOf(2, 4, 6, 2, 2), blocks.map { it.keyColumn })
        assertEquals(listOf(4, 2, 2, 2, 2), blocks.map { it.relativeIndent })
        assertEquals(listOf("|", "|-", ">", "|+", "|"), blocks.map { it.indicator })
        assertEquals(listOf(true, true, true, true, false), blocks.map { it.hasHeader })
        assertEquals(listOf(1, 5, 7, 9, 12), blocks.map { it.keyLine })
        assertEquals(11, blocks[3].firstLine, "blank lines before the header are skipped")
        assertEquals(2, VaultLayout.detectRelativeIndent(text), "the block without a header does not count")
    }

    @Test
    fun `lines that only look like vault tags are ignored`() {
        val text = "# key: !vault |\nkey: \"!vault |\"\nother: !vaulted |\n  x\nlast: !vault |\nnext: 1\n"
        assertEquals(emptyList<InlineBlock>(), VaultLayout.blocks(text))
    }
}
