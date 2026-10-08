package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ANS-V107's classifier (plan amendment R21, D159) on synthetic text around vector v01's envelope (`encrypt_string`,
 * password pw1 of `tools/vault/SYNTHETIC.md`), and against the wrapped-shape oracle that `tools/vault/shapes.sh`
 * recorded with ansible-core 2.21.4 and 2.18.8.
 */
class VaultFileShapeTest {
    private val envelope: String = VaultTestData.vector("v01").envelope
    private val lines: List<String> = envelope.trimEnd('\n').split('\n')
    private val header: String = lines[0]
    private val payload: List<String> = lines.drop(1)

    private val raw = ShapeContext(yamlInput = false, readRaw = true)
    private val other = ShapeContext(yamlInput = false, readRaw = false)
    private val vars = ShapeContext(yamlInput = true, readRaw = false)

    /** The envelope's lines at [indent] spaces, LF-terminated. */
    private fun block(indent: Int = 0, separator: String = "\n"): String = lines.joinToString("") { " ".repeat(indent) + it + separator }

    private fun kind(text: String, context: ShapeContext = raw): VaultShapeKind? = VaultFileShape.classify(text, context)?.kind

    /** assertEquals on JSON values, which are typed `Any?`. */
    private fun eq(expected: Any?, actual: Any?, message: String) = assertEquals(expected, actual, message)

    // ------------------------------------------------------------------------------------------------ oracle

    @Test
    fun `every shape of the oracle table classifies and unwraps as ansible-core requires`() {
        val table = VaultTestData.json("shapes/shapes.json")
        @Suppress("UNCHECKED_CAST")
        val shapes = table["shapes"] as List<Map<String, Any?>>
        assertEquals(16, shapes.size)
        for (row in shapes) {
            val id = row.str("id")
            val bytes = VaultTestData.bytes("shapes/${row.str("file")}")
            val context = ShapeContext(yamlInput = row["yamlInput"] as Boolean, readRaw = row["readRaw"] as Boolean)
            val shape = VaultFileShape.classify(bytes, context)
            assertNotNull(shape, id)
            assertEquals(row.str("kind"), shape!!.kind.name, id)
            @Suppress("UNCHECKED_CAST")
            val oracles = row["oracle"] as Map<String, Map<String, Any?>>
            assertEquals(setOf("2.21.4", "2.18.8"), oracles.keys, id)
            for ((version, oracle) in oracles) {
                val vault = shape.kind == VaultShapeKind.VAULT
                eq(vault, oracle["isEncryptedFile"], "$id $version: Ansible sees a vault only at byte 0")
                eq(if (vault) "plaintext" else "asIs", oracle["copyDelivers"], "$id $version: copy")
                eq(if (vault) "plaintext" else "asIs", oracle["textDelivers"], "$id $version: template, lookup('file')")
                eq(!vault, oracle["encryptWraps"], "$id $version: ansible-vault encrypt would wrap it a second time")
            }
            val expected = row.strOrNull("unwrapped")?.let { VaultTestData.bytes("shapes/$it") }
            if (expected == null) {
                assertNull(shape.unwrapped, id)
                assertFalse(shape.unwrapsToVault, id)
                continue
            }
            assertEquals(String(expected, Charsets.ISO_8859_1), shape.unwrapped, "$id: what the Convert fix writes")
            for ((version, oracle) in oracles) {
                eq(true, oracle["unwrappedIsEncryptedFile"], "$id $version")
                eq(oracle["unwrappedDecrypts"], shape.unwrapsToVault, "$id $version: fix offered exactly when it decrypts")
                if (shape.unwrapsToVault) eq("plaintext", oracle["unwrappedCopyDelivers"], "$id $version")
            }
        }
    }

    @Test
    fun `the oracle pins how Ansible fails to load a vars file that is one vault value`() {
        @Suppress("UNCHECKED_CAST")
        val shapes = (VaultTestData.json("shapes/shapes.json")["shapes"] as List<Map<String, Any?>>).associateBy { it.str("id") }
        @Suppress("UNCHECKED_CAST")
        fun load(id: String, version: String) = ((shapes.getValue(id)["oracle"] as Map<String, Map<String, Any?>>).getValue(version))["varsLoad"] as Map<*, *>
        eq("AnsibleYAMLParserError", load("vars-document", "2.21.4")["error"], "2.21.4 vars file")
        assertTrue((load("vars-document", "2.21.4")["message"] as String).contains("<document start>"))
        eq("EncryptedString", load("vars-document-indented", "2.21.4")["type"], "not a mapping")
    }

    // ------------------------------------------------------------------------------------------------ tag lines

    @Test
    fun `tag lines in every form wrap the envelope, unindented or indented`() {
        val tags = listOf("!vault |", "!vault |-", "!vault |+", "!vault |2", "!vault >", "!vault >-", "! vault |", "!vault-encrypted |",
            "--- !vault |", "!vault | # pasted", "!vault", "  !vault |")
        for (tag in tags) {
            for (indent in listOf(0, 2, 10)) {
                val text = "$tag\n" + block(indent)
                val shape = VaultFileShape.classify(text, raw)
                assertEquals(VaultShapeKind.TAG_LINE, shape?.kind, "$tag / $indent")
                assertEquals(0, shape!!.tagLine, tag)
                assertEquals(1, shape.headerLine, tag)
                assertEquals(indent, shape.indent, tag)
                assertEquals(envelope, shape.unwrapped, "$tag / $indent")
                assertTrue(shape.unwrapsToVault, tag)
                assertEquals(VaultShapeKind.TAG_LINE, kind(text, other), "any text file: $tag")
                assertEquals(VaultShapeKind.VARS_DOCUMENT, kind(text, vars), "YAML Ansible loads: $tag")
            }
        }
    }

    @Test
    fun `a key with a vault value is the normal inline form in YAML and a wrapped vault in a raw file`() {
        for (keyLine in listOf("greeting: !vault |", "\"odd: key\": !vault |", "'single': !vault >-", "- !vault |", "- db: !vault |")) {
            val text = "$keyLine\n" + block(10)
            assertEquals(VaultShapeKind.YAML_VALUE, kind(text), keyLine)
            assertNull(kind(text, other), "a file Ansible does not read as it is may be a vars file of one variable: $keyLine")
            assertNull(kind(text, vars), "ANS-V101–V103 judge inline values: $keyLine")
            assertEquals(envelope, VaultFileShape.classify(text, raw)!!.unwrapped, keyLine)
        }
        // YAML below files/ or templates/ (rendered into a vars file, ansible-pull): inline values stay the inline checks'.
        val yamlCopied = ShapeContext(yamlInput = true, readRaw = true)
        assertNull(kind("greeting: !vault |\n" + block(2), yamlCopied))
        assertNull(kind("greeting: |\n" + block(2), yamlCopied), "an untagged value is ANS-V114's")
        assertEquals(VaultShapeKind.VARS_DOCUMENT, kind("!vault |\n" + block(2), yamlCopied))
    }

    @Test
    fun `comments, blank lines, document starts and directives before the envelope are a preamble`() {
        val cases = mapOf(
            "# from the password manager\n" to 1,
            "\n" to 1,
            "\n\n# note\n\n" to 4,
            "---\n" to 1,
            "%YAML 1.1\n---\n" to 2,
            "# a comment that names \$ANSIBLE_VAULT\n" to 1,
        )
        for ((preamble, before) in cases) {
            val shape = VaultFileShape.classify(preamble + envelope, raw)
            assertEquals(VaultShapeKind.PREAMBLE, shape?.kind, preamble)
            assertEquals(before, shape!!.linesBefore, preamble)
            assertEquals(before, shape.headerLine, preamble)
            assertEquals(envelope, shape.unwrapped, preamble)
            assertEquals(VaultShapeKind.PREAMBLE, kind(preamble + envelope, vars), "a vars file with a comment first is a string too")
        }
        assertEquals(VaultShapeKind.TAG_LINE, kind("# pasted\n!vault |\n" + envelope), "the tag wins over a preamble")
    }

    @Test
    fun `an indented envelope with spaces or TABs is indented`() {
        val spaces = VaultFileShape.classify(block(4), other)
        assertEquals(VaultShapeKind.INDENTED, spaces?.kind)
        assertEquals(4, spaces!!.indent)
        assertEquals(envelope, spaces.unwrapped)
        val tabs = VaultFileShape.classify(lines.joinToString("") { "\t$it\n" }, other)
        assertEquals(VaultShapeKind.INDENTED, tabs?.kind)
        assertEquals(envelope, tabs!!.unwrapped)
    }

    @Test
    fun `a byte order mark before the magic is its own shape, from the bytes or from the document's flag`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + envelope.toByteArray(Charsets.US_ASCII)
        val fromBytes = VaultFileShape.classify(bytes, other)
        assertEquals(VaultShapeKind.BYTE_ORDER_MARK, fromBytes?.kind)
        assertEquals(envelope, fromBytes!!.unwrapped)
        assertTrue(fromBytes.unwrapsToVault)
        assertEquals(VaultShapeKind.BYTE_ORDER_MARK, kind(envelope, other.copy(byteOrderMark = true)))
        val vault = VaultFileShape.classify(envelope, other)
        assertEquals(VaultShapeKind.VAULT, vault?.kind)
        assertTrue(vault!!.inner is EnvelopeParse.Ok)
        assertNull(vault.unwrapped)
        assertFalse(vault.unwrapsToVault)
    }

    @Test
    fun `quoted and mixed envelopes are reported only in files Ansible reads as they are`() {
        val quoted = "\"" + lines.joinToString("\\n") + "\\n\"\n"
        assertEquals(VaultShapeKind.QUOTED, kind(quoted))
        assertEquals(VaultShapeKind.QUOTED, kind("secret: '$header'\n"))
        assertNull(kind(quoted, other))
        val mixed = "[db]\npassword =\n" + envelope + "port = 5432\n"
        assertEquals(VaultShapeKind.MIXED, kind(mixed))
        assertEquals(VaultShapeKind.MIXED, kind("password = $header\n"))
        assertEquals(VaultShapeKind.MIXED, kind("!vault |\n" + envelope + "next: 1\n"))
        assertEquals(VaultShapeKind.MIXED, kind("!vault |\n!vault |\n" + envelope), "two tag lines")
        val readme = "# Vault files\n\nAn encrypted file starts like this:\n\n    $header\n    ${payload[0]}\n\nThat is all.\n"
        assertNull(kind(readme, other), "docs that show an envelope stay silent")
        assertNull(kind("val sample = \"$header\"\n", other), "code that holds an envelope stays silent")
        assertNull(kind(mixed, vars), "an untagged value in a vars file is ANS-V114's")
        for (shape in listOf(VaultFileShape.classify(quoted, raw)!!, VaultFileShape.classify(mixed, raw)!!)) {
            assertNull(shape.unwrapped)
            assertNull(shape.inner)
            assertFalse(shape.unwrapsToVault)
        }
    }

    @Test
    fun `Ansible's own environment variables and partial headers are no envelope`() {
        val script = "#!/bin/sh\nansible-playbook --vault-password-file \"\$ANSIBLE_VAULT_PASSWORD_FILE\" site.yml\n"
        assertNull(kind(script), "quoted variable")
        assertNull(kind("export ANSIBLE_VAULT_IDENTITY_LIST=dev@x\necho \$ANSIBLE_VAULT_IDENTITY_LIST\n"), "mixed variable")
        assertNull(kind("#!/bin/sh\n\$ANSIBLE_VAULT_PASSWORD_FILE --print\n"), "a line that starts with a variable is no header")
        assertNull(kind("head -c 15 \"\$f\" | grep -q '^\$ANSIBLE_VAULT;' && echo vaulted\n"), "a grep for the magic")
        assertNull(kind("\$ANSIBLE_VAULT_ID_MATCH=1\n", other.copy(byteOrderMark = true)), "a byte order mark before a variable")
        assertFalse(VaultFileShape.mayHoldEnvelope("echo \$ANSIBLE_VAULT_PASSWORD_FILE"))
        assertEquals(VaultShapeKind.QUOTED, kind("pw=\"$header\"\n"), "a complete header still counts")
        assertEquals(VaultShapeKind.VAULT, kind("\$ANSIBLE_VAULT_PASSWORD_FILE\n"), "Ansible's own rule at byte 0 (its envelope check judges it)")
    }

    @Test
    fun `a byte order mark before a wrapped envelope is the shape to fix first, without a conversion`() {
        val bom = raw.copy(byteOrderMark = true)
        for (text in listOf("!vault |\n" + block(2), "# pasted\n" + envelope, block(4), "secret: !vault |\n" + block(2))) {
            val shape = VaultFileShape.classify(text, bom)!!
            assertEquals(VaultShapeKind.BYTE_ORDER_MARK, shape.kind, text.lines().first())
            assertNull(shape.unwrapped, "the mark has to go first")
            assertFalse(shape.unwrapsToVault)
        }
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + ("!vault |\n" + block(2)).toByteArray(Charsets.US_ASCII)
        assertEquals(VaultShapeKind.BYTE_ORDER_MARK, VaultFileShape.classify(bytes, raw)?.kind)
        assertEquals(1, VaultFileShape.classify(bytes, raw)!!.headerLine)
    }

    @Test
    fun `documentation that shows an envelope stays silent unless Ansible copies it`() {
        val docs = other.copy(documentation = true)
        val ending = "# Encrypted file example\n\n" + block(4)
        assertEquals(VaultShapeKind.PREAMBLE, kind(ending, other), "without the documentation flag a heading is a comment")
        assertNull(kind(ending, docs))
        assertNull(kind("!vault |\n" + block(2), docs))
        assertEquals(VaultShapeKind.TAG_LINE, kind("!vault |\n" + block(2), raw.copy(documentation = true)), "a doc below files/ is copied as it is")
    }

    @Test
    fun `nothing is reported without an envelope near the top`() {
        assertNull(kind(""))
        assertNull(kind("!vault |\nhello\n"))
        assertNull(kind("plain text\n"))
        val late = "#\n".repeat(VaultFileShape.HEAD_CHARS / 2) + "!vault |\n" + envelope
        assertFalse(VaultFileShape.mayHoldEnvelope(late))
        assertNull(kind(late))
        assertTrue(VaultFileShape.mayHoldEnvelope("x".repeat(VaultFileShape.HEAD_CHARS - 1) + header))
        assertFalse(VaultFileShape.mayHoldEnvelope("x".repeat(VaultFileShape.HEAD_CHARS) + header))
    }

    // ------------------------------------------------------------------------------------------------ unwrapping

    @Test
    fun `unwrapping keeps the line separator and the payload digits and drops blanks`() {
        val crlf = VaultFileShape.classify("!vault |\r\n" + block(2, "\r\n"), raw)!!
        assertEquals(envelope.replace("\n", "\r\n"), crlf.unwrapped)
        assertTrue(crlf.unwrapsToVault, "Ansible accepts CRLF")
        val cr = VaultFileShape.classify("!vault |\r" + block(0, "\r"), raw)!!
        assertEquals(envelope.replace("\n", "\r"), cr.unwrapped)
        val padded = "!vault |\n" + lines.joinToString("") { "  $it \t\n" } + "\n\n"
        assertEquals(envelope, VaultFileShape.classify(padded, raw)!!.unwrapped)
        val gaps = "!vault |\n  $header\n\n" + payload.joinToString("") { "  $it\n\n" }
        assertEquals(envelope, VaultFileShape.classify(gaps, raw)!!.unwrapped)
        val upper = "!vault |\n$header\n" + payload.joinToString("") { it.uppercase() + "\n" }
        assertEquals("$header\n" + payload.joinToString("") { it.uppercase() + "\n" }, VaultFileShape.classify(upper, raw)!!.unwrapped)
        assertEquals(VaultFileShape.classify(upper, raw)!!.inner.let { (it as EnvelopeParse.Ok).envelope },
            (VaultEnvelope.parse(envelope) as EnvelopeParse.Ok).envelope)
    }

    @Test
    fun `a malformed envelope behind the wrapper is still the shape, without a fix`() {
        val odd = VaultFileShape.classify("!vault |\n" + header + "\n" + payload.dropLast(1).joinToString("") { "$it\n" } + payload.last().dropLast(1) + "\n", raw)!!
        assertEquals(VaultShapeKind.TAG_LINE, odd.kind)
        assertEquals(FormatReason.ODD_LENGTH, (odd.inner as EnvelopeParse.Format).reason)
        assertFalse(odd.unwrapsToVault)
        val cipher = VaultFileShape.classify("!vault |\n" + envelope.replace(";AES256", ";aes256"), raw)!!
        assertTrue(cipher.inner is EnvelopeParse.UnknownCipher)
        assertFalse(cipher.unwrapsToVault)
        // Payload lines indented deeper than the header keep the extra blanks: the codec reports them, nothing is guessed.
        val deeper = VaultFileShape.classify("!vault |\n  $header\n" + payload.joinToString("") { "    $it\n" }, raw)!!
        assertEquals(FormatHint.LEADING_WHITESPACE, (deeper.inner as EnvelopeParse.Format).hint)
        assertFalse(deeper.unwrapsToVault)
        val headerOnly = VaultFileShape.classify("!vault |\n$header\n", raw)!!
        assertEquals(VaultShapeKind.TAG_LINE, headerOnly.kind)
        assertFalse(headerOnly.unwrapsToVault)
    }

    @Test
    fun `a head of a file and a verdict-only call compute no envelope`() {
        val text = "!vault |\n" + block(0)
        val head = text.substring(0, text.length - 7)
        val shape = VaultFileShape.classify(head, raw, complete = false)!!
        assertEquals(VaultShapeKind.TAG_LINE, shape.kind)
        assertNull(shape.unwrapped)
        assertNull(shape.inner)
        val verdict = VaultFileShape.classify(text, raw, withEnvelope = false)!!
        assertEquals(VaultShapeKind.TAG_LINE, verdict.kind)
        assertNull(verdict.unwrapped)
        assertNull(VaultFileShape.classify(envelope, raw, complete = false)!!.inner)
        assertNull(VaultFileShape.classify("!vault |\n$header", raw, complete = false), "the cut last line is ignored")
    }

    @Test
    fun `positions are reported and toString carries no payload`() {
        val text = "# pasted\n!vault |\n" + block(2)
        val shape = VaultFileShape.classify(text, raw)!!
        assertEquals(1, shape.tagLine)
        assertEquals(2, shape.headerLine)
        assertEquals(text.indexOf(header), shape.headerOffset)
        assertFalse(payload.any { it.take(16) in shape.toString() }, shape.toString())
    }

    // ------------------------------------------------------------------------------------------------ ANS-V114

    @Test
    fun `an untagged value is an envelope only with its header and a hex payload`() {
        assertTrue(VaultFileShape.isUntaggedEnvelope(envelope))
        assertTrue(VaultFileShape.isUntaggedEnvelope(header + " " + payload.joinToString(" ")), "a plain value joins the lines")
        assertTrue(VaultFileShape.isUntaggedEnvelope("\n  $header\n" + payload.joinToString("\n")))
        assertTrue(VaultFileShape.isUntaggedEnvelope(header.replace("AES256", "aes256") + "\n" + payload.joinToString("\n")), "malformed is still an envelope")
        assertFalse(VaultFileShape.isUntaggedEnvelope("$header not tagged, not a vault"))
        assertFalse(VaultFileShape.isUntaggedEnvelope(header))
        assertFalse(VaultFileShape.isUntaggedEnvelope("$header\n6162"), "too short")
        assertFalse(VaultFileShape.isUntaggedEnvelope("\$ANSIBLE_VAULT\n" + payload.joinToString("\n")), "no header fields")
        assertFalse(VaultFileShape.isUntaggedEnvelope("hello $header\n" + payload.joinToString("\n")))
        assertFalse(VaultFileShape.isUntaggedEnvelope(""))
    }
}
