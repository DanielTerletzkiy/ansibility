package de.terletzkiy.ansibility.vault.actions

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.semantics.vault.VaultLayout
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.yaml.YamlScalarDecoder
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar

/** The text the vault actions write and the PSI ranges they write into. */
class VaultValueTextTest : BasePlatformTestCase() {
    private fun scalar(text: String, key: String): YAMLScalar = runReadActionBlocking {
        val file = myFixture.configureByText("vault.yml", text)
        PsiTreeUtil.findChildrenOfType(file, YAMLKeyValue::class.java).single { it.keyText == key }.value as YAMLScalar
    }

    fun testDoubleQuotedRoundTripsThroughPyYamlsRules() {
        val values = listOf(
            "hello world", "with \"quotes\" and \\ backslash", "line one\nline two\n", "tab\there", "\u0000\u0007\u001B\u007F\u0085",
            "nbsp\u00A0ls\u2028ps\u2029bom\uFEFF", "unicode äöü 🔒", "  leading and trailing  ", "#not a comment", "",
        )
        for (value in values) {
            val quoted = VaultValueText.doubleQuoted(value)
            assertFalse("one line: $quoted", quoted.contains('\n'))
            assertEquals(value, YamlScalarDecoder.decodeFlow(quoted))
        }
    }

    fun testJinjaValuesAreMarkedUnsafe() {
        assertTrue(VaultValueText.isTemplated("a {{ b }}"))
        assertTrue(VaultValueText.isTemplated("{% if %}"))
        assertTrue(VaultValueText.isTemplated("{# c #}"))
        assertFalse(VaultValueText.isTemplated("{ not jinja }"))
    }

    fun testDecodeRejectsBinaryValues() {
        assertEquals("ok ä", VaultValueText.decode("ok ä".toByteArray()))
        assertNull(VaultValueText.decode(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    fun testLiteralBodyRangesKeepTheKeyLine() {
        val envelope = VaultVectors.encrypt("x", VaultVectors.PW1)
        val text = "a: 1\nkey: !vault |-  # comment\n" + envelope.formatLines().joinToString("") { "    $it\n" } + "\nb: 2\n"
        val value = scalar(text, "key")
        val body = runReadActionBlocking { VaultValuePsi.literalBody(value, text) }!!
        assertEquals(text.indexOf("# comment") + "# comment".length, body.headerLineEnd)
        assertEquals(body.headerLineEnd + 1, body.start)
        assertEquals(4, body.indent)
        assertEquals(envelope.formatLines().size, body.lines)
        assertEquals(envelope.formatLines().joinToString("\n") { "    $it" }, text.substring(body.start, body.end))
        val replacement = runReadActionBlocking { VaultValueText.envelopeReplacement(value, text, envelope) }
        assertEquals(body.range, replacement.range)
        assertEquals(VaultValueText.body(envelope, 4), replacement.text)
    }

    fun testPlainValuesBecomeABlockAtTheFilesIndentWithTheCommentOnTheTagLine() {
        val envelope = VaultVectors.encrypt("s3cret", VaultVectors.PW1)
        val text = "outer:\n  password: s3cret   # rotate\n  other: 1\n"
        val value = scalar(text, "password")
        val replacement = runReadActionBlocking { VaultValueText.envelopeReplacement(value, text, envelope) }
        val result = text.replaceRange(replacement.range.startOffset, replacement.range.endOffset, replacement.text)
        assertTrue(result, result.startsWith("outer:\n  password: !vault | # rotate\n    \$ANSIBLE_VAULT;1.1;AES256\n"))
        assertTrue(result.endsWith("\n  other: 1\n"))
        assertEquals(2, VaultLayout.blocks(result).single().relativeIndent)
    }

    fun testPlainReplacementCoversTagAndBody() {
        val envelope = VaultVectors.encrypt("x", VaultVectors.PW1)
        val text = VaultVectors.inline("key", envelope) + "next: 1\n"
        val value = scalar(text, "key")
        val replacement = runReadActionBlocking { VaultValueText.plainReplacement(value, text, "pre {{ x }}") }
        val result = text.replaceRange(replacement.range.startOffset, replacement.range.endOffset, replacement.text)
        assertEquals("key: !unsafe \"pre {{ x }}\"\nnext: 1\n", result)
    }

    fun testVaultScalarsAreFoundFromKeyTagAndBody() {
        val envelope = VaultVectors.encrypt("x", VaultVectors.PW1)
        val text = VaultVectors.inline("key", envelope) + "plain: 1\nlist:\n  - !vault |\n" + envelope.formatLines().joinToString("") { "    $it\n" }
        val file = myFixture.configureByText("vault.yml", text)
        runReadActionBlocking {
            for (offset in listOf(0, text.indexOf("!vault"), text.indexOf("\$ANSIBLE") + 3)) assertNotNull(VaultValuePsi.vaultScalarAt(file.findElementAt(offset)!!))
            assertNull(VaultValuePsi.vaultScalarAt(file.findElementAt(text.indexOf("plain"))!!))
            assertNull(VaultValuePsi.keyName(VaultValuePsi.vaultScalars(file).last()))
            assertEquals(2, VaultValuePsi.vaultScalars(file).size)
            assertEquals("1.1", VaultValuePsi.header(VaultValuePsi.vaultScalars(file).first())?.version)
        }
    }
}
