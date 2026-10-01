package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The envelope reader's header and detection rules, and the writer's exact layout. */
class VaultEnvelopeTest {
    private val v01Text = VaultTestData.vector("v01").envelope
    private val v01 = VaultTestData.envelope(v01Text)
    private val body = v01Text.substringAfter('\n')

    private fun ok(text: String): VaultEnvelope = (VaultEnvelope.parse(text) as EnvelopeParse.Ok).envelope

    @Test
    fun `header fields are stripped, the version is not checked, a fourth field is the label`() {
        assertEquals(null, ok("\$ANSIBLE_VAULT ; 1.1 ; AES256 \n$body").label)
        assertEquals("dev", ok("\$ANSIBLE_VAULT;1.1;AES256;dev\n$body").label)
        assertEquals("dev", ok("\$ANSIBLE_VAULT;1.2;AES256; dev ;extra\n$body").label)
        assertEquals(null, ok("\$ANSIBLE_VAULT;1.2;AES256\n$body").label)
        assertEquals("team", ok("\$ANSIBLE_VAULT;1.2;AES256\n$body").labelOrDefault("team"))
        assertEquals("", ok("\$ANSIBLE_VAULT;1.2;AES256;\n$body").labelOrDefault("team"))
        assertEquals("9.9", ok("\$ANSIBLE_VAULT;9.9;AES256\n$body").version)
        assertEquals(v01, ok("\$ANSIBLE_VAULT;1.1;AES256\n\n${body.uppercase()}\n\n"))
        assertEquals(v01, ok("\$ANSIBLE_VAULT;1.1;AES256\r\n" + body.replace("\n", "\r\n")))
        assertEquals(v01, ok("\$ANSIBLE_VAULT;1.1;AES256\n" + body.replace("\n", "")))
        assertEquals(EnvelopeParse.Format(FormatReason.HEADER_FIELDS, EnvelopePart.HEADER, null), VaultEnvelope.parse("\$ANSIBLE_VAULT\n$body"))
        assertEquals("aes256", (VaultEnvelope.parse("\$ANSIBLE_VAULT;1.1;aes256\n$body") as EnvelopeParse.UnknownCipher).name)
        assertEquals("", (VaultEnvelope.parse("\$ANSIBLE_VAULT;1.1;\n$body") as EnvelopeParse.UnknownCipher).cipher)
    }

    @Test
    fun `detection needs pure ASCII starting with the magic`() {
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.EMPTY), VaultEnvelope.parse(""))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NO_MAGIC), VaultEnvelope.parse("hello"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NO_MAGIC), VaultEnvelope.parse("!vault |\n$v01Text"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.LEADING_WHITESPACE), VaultEnvelope.parse("\n\t $v01Text"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.BYTE_ORDER_MARK), VaultEnvelope.parse("\uFEFF$v01Text"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NON_ASCII), VaultEnvelope.parse("$v01Text# ä"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NON_ASCII), VaultEnvelope.parse(v01Text.toByteArray() + 0xC3.toByte()))
        assertTrue(VaultEnvelope.isEncrypted(v01Text))
        assertTrue(VaultEnvelope.isEncrypted(v01Text.toByteArray()))
        assertFalse(VaultEnvelope.isEncrypted(" $v01Text"))
        assertFalse(VaultEnvelope.isEncrypted("$v01Text€".toByteArray()))
        assertTrue(VaultEnvelope.isEncryptedFile("\$ANSIBLE_VAULT".toByteArray()))
        assertTrue(VaultEnvelope.isEncryptedFile("\$ANSIBLE_VAULT;garbage ÿ".toByteArray()), "only the first 14 bytes count")
        assertFalse(VaultEnvelope.isEncryptedFile("\$ANSIBLE_VAUL".toByteArray()))
        assertFalse(VaultEnvelope.isEncryptedFile(ByteArray(0)))
    }

    @Test
    fun `the writer wraps at 80 lowercase columns with one trailing LF`() {
        val lines = v01.formatLines()
        assertEquals("\$ANSIBLE_VAULT;1.1;AES256", lines.first())
        assertTrue(lines.drop(1).dropLast(1).all { it.length == VaultEnvelope.LINE_WIDTH })
        assertTrue(lines.last().length in 1..VaultEnvelope.LINE_WIDTH)
        assertTrue(lines.drop(1).all { line -> line.all { it in "0123456789abcdef" } })
        assertEquals(v01Text, v01.format())
        assertTrue(v01.format().endsWith("3130\n") && !v01.format().endsWith("\n\n"))
        assertEquals(v01Text, String(v01.formatBytes(), Charsets.US_ASCII))
        assertEquals(v01Text, ok(body.uppercase().let { "\$ANSIBLE_VAULT;1.1;AES256\n$it" }).format(), "re-formatting normalises")
    }

    @Test
    fun `the written version follows the label, not the parsed header`() {
        val labelOn11 = ok("\$ANSIBLE_VAULT;1.1;AES256;dev\n$body")
        assertEquals("1.1", labelOn11.version)
        assertEquals("1.2", labelOn11.formatVersion)
        assertEquals("\$ANSIBLE_VAULT;1.2;AES256;dev", labelOn11.headerLine())
        assertEquals("\$ANSIBLE_VAULT;1.1;AES256", ok("\$ANSIBLE_VAULT;1.2;AES256;default\n$body").headerLine())
        assertEquals("\$ANSIBLE_VAULT;1.1;AES256", ok("\$ANSIBLE_VAULT;9.9;AES256\n$body").headerLine())
    }

    @Test
    fun `relabelling rewrites the header only`() {
        val dev = v01.withLabel("dev")
        assertEquals("\$ANSIBLE_VAULT;1.2;AES256;dev", dev.headerLine())
        assertEquals("dev", dev.label)
        assertEquals(v01Text.substringAfter('\n'), dev.format().substringAfter('\n'))
        assertEquals(v01, dev.withLabel("default"))
        assertEquals(v01, dev.withLabel(""))
        assertEquals(v01, dev.withLabel(null))
        assertEquals(null, dev.withLabel("default").label)
        assertThrows(IllegalArgumentException::class.java) { v01.withLabel("a;b") }
        assertThrows(IllegalArgumentException::class.java) { v01.withLabel("a\nb") }
    }

    @Test
    fun `plaintext lengths, equality and a toString without the payload`() {
        assertEquals(0..15, v01.plaintextLength)
        assertEquals(IntRange.EMPTY, VaultEnvelope.of(null, v01.salt, v01.hmac, ByteArray(15)).plaintextLength)
        assertEquals(IntRange.EMPTY, VaultEnvelope.of(null, v01.salt, v01.hmac, ByteArray(0)).plaintextLength)
        assertEquals(v01, VaultTestData.envelope(v01Text))
        assertEquals(v01.hashCode(), VaultTestData.envelope(v01Text).hashCode())
        assertNotEquals(v01, v01.withLabel("dev"))
        val text = v01.toString()
        assertEquals("VaultEnvelope(1.1;AES256, salt 32 B, ciphertext 16 B)", text)
        assertFalse(VaultTestData.vector("v01").ciphertext.let(::hex) in text)
        assertEquals("Ok(VaultEnvelope(1.1;AES256, salt 32 B, ciphertext 16 B))", VaultEnvelope.parse(v01Text).toString())
        val copy = v01.ciphertext
        copy.fill(0)
        assertNotEquals(hex(copy), hex(v01.ciphertext), "accessors return copies")
    }

    @Test
    fun `lines split at LF, CR and CRLF only`() {
        assertEquals(listOf("a", "b", "c", "", "d"), VaultEnvelope.splitLines("a\nb\rc\r\n\nd"))
        assertEquals(listOf("a\u000Bb\u000Cc"), VaultEnvelope.splitLines("a\u000Bb\u000Cc\n"))
        assertEquals(listOf(""), VaultEnvelope.splitLines(""))
    }
}
