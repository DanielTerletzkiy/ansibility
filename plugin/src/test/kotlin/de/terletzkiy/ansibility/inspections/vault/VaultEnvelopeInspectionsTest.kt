package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import de.terletzkiy.ansibility.index.vault.VaultEnvelopeProblem
import de.terletzkiy.ansibility.index.vault.VaultIndexer
import de.terletzkiy.ansibility.semantics.diagnostics.DiagnosticCode
import de.terletzkiy.ansibility.semantics.vault.FormatHint
import de.terletzkiy.ansibility.semantics.vault.VaultEnvelope
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.crypto.VaultCrypto

/**
 * ANS-V101–V103 and their ModCommand fixes on synthetic files (envelopes generated at test time with the synthetic
 * passwords of `tools/vault/SYNTHETIC.md`). Accept and reject cases follow ansible-core's reader rules as the codec's
 * tolerance matrices pin them.
 */
class VaultEnvelopeInspectionsTest : BasePlatformTestCase() {
    private val envelope: VaultEnvelope = VaultVectors.encrypt("a synthetic value long enough for two blocks", VaultVectors.PW1)
    private val lines: List<String> = envelope.formatLines()
    private val header: String = lines[0]
    private val payload: List<String> = lines.drop(1)

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            AnsibleVaultMalformedEnvelopeInspection(),
            AnsibleVaultFoldedValueInspection(),
            AnsibleVaultTrailingWhitespaceInspection(),
        )
    }

    /** A `key: !vault <indicator>` block with [body] lines at [indent] spaces. */
    private fun block(key: String, body: List<String>, indicator: String = "|", indent: Int = 2): String =
        "$key: !vault $indicator\n" + body.joinToString("") { " ".repeat(indent) + it + "\n" }

    private fun vaultInfos(name: String, text: String): List<HighlightInfo> {
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting().filter { info ->
            val description = info.description ?: return@filter false
            MESSAGE_PREFIXES.any { description.startsWith(it) }
        }
    }

    private fun codes(name: String, text: String): List<DiagnosticCode> = vaultInfos(name, text).map { info ->
        val description = info.description!!
        when {
            description.startsWith("Malformed") -> DiagnosticCode.V101_MALFORMED_ENVELOPE
            description.startsWith("Trailing") -> DiagnosticCode.V103_TRAILING_WHITESPACE
            else -> DiagnosticCode.V102_FOLDED_VAULT_VALUE
        }
    }

    private fun fix(name: String) {
        val action = myFixture.getAllQuickFixes().firstOrNull { it.text == name } ?: error("no '$name' in ${myFixture.getAllQuickFixes().map { it.text }}")
        myFixture.checkPreviewAndLaunchAction(action)
    }

    /** Every inline vault of the current file is a well-formed envelope again. */
    private fun assertAllWellFormed() {
        val found = VaultIndexer.inline(myFixture.file, 0, 0)
        assertTrue(found.isNotEmpty())
        assertTrue(found.toString(), found.all { it.wellFormed })
    }

    fun testWellFormedValuesInEveryAcceptedShapeAreClean() {
        val escaped = (listOf(header) + payload).joinToString("\\n")
        val text = buildString {
            append("---\n")
            append(block("literal", lines))
            append(block("strip", lines, "|-"))
            append(block("keep", lines, "|+"))
            append(block("encrypt_string_indent", lines, indent = 10))
            append(block("uppercase", listOf(header) + payload.map { it.uppercase() }))
            append(block("single_line", listOf(header, payload.joinToString(""))))
            append(block("blank_after_header", listOf(header, "") + payload))
            append("legacy: !vault-encrypted |\n").append(lines.joinToString("") { "  $it\n" })
            append("quoted_escapes: !vault \"").append(escaped).append("\"\n")
            append("items:\n  - !vault |\n").append(lines.joinToString("") { "    $it\n" })
            append("plain: \$ANSIBLE_VAULT;1.1;aes256 not tagged, not a vault\n")
        }
        assertEquals(emptyList<DiagnosticCode>(), codes("vault.yml", text))
    }

    fun testMalformedEnvelopesAreV101WithTheReasonAndNoFix() {
        val cases = mapOf(
            "lowercase cipher" to block("v", listOf("\$ANSIBLE_VAULT;1.1;aes256") + payload) to "unknown cipher 'aes256'",
            "two header fields" to block("v", listOf("\$ANSIBLE_VAULT;1.1") + payload) to "fewer than three fields",
            "non-hex digit" to block("v", listOf(header) + payload.dropLast(1) + (payload.last().dropLast(1) + "g")) to "not a hex digit",
            "odd length" to block("v", listOf(header) + payload.dropLast(1) + payload.last().dropLast(1)) to "odd number of hex digits",
            "no separators" to block("v", listOf(header, "616263646566")) to "salt and HMAC separators",
            "tab inside a line" to block("v", listOf(header, payload[0].take(10) + "\t" + payload[0].drop(10)) + payload.drop(1)) to "a TAB",
            "leading space" to "v: !vault \" ${header}\"\n" to "whitespace or a blank line before",
            "empty value" to "v: !vault\n" to "the value is empty",
            "plain non-vault" to "v: !vault hello\n" to "does not start with",
        )
        for ((case, reason) in cases) {
            val (name, text) = case
            val infos = vaultInfos("v.yml", text)
            assertEquals(name, 1, infos.size)
            val info = infos.single()
            assertTrue("$name: ${info.description}", info.description!!.startsWith("Malformed vault envelope: "))
            assertTrue("$name: ${info.description}", reason in info.description!!)
            assertEquals(name, HighlightSeverity.ERROR, info.severity)
            assertFalse("$name: no payload in the message", payload.any { it.take(16) in info.description!! })
            assertEquals("$name: no fix", emptyList<String>(), myFixture.getAllQuickFixes().map { it.text }.filter { it in FIX_NAMES })
        }
    }

    fun testFoldedBlockIsV102AndConvertsToLiteralKeepingChomping() {
        val text = "---\n" + block("folded", lines, ">") + block("folded_strip", lines, ">-") + "after: 1\n"
        val infos = vaultInfos("v.yml", text)
        assertEquals(listOf(DiagnosticCode.V102_FOLDED_VAULT_VALUE, DiagnosticCode.V102_FOLDED_VAULT_VALUE), codes("v.yml", text))
        assertTrue(infos[0].description!!.startsWith("Folded vault value"))
        assertEquals("the tag and the indicator", "!vault >", text.substring(infos[0].startOffset, infos[0].endOffset))
        fix("Convert to literal block")
        assertTrue(myFixture.editor.document.text.contains("folded: !vault |\n"))
        fix("Convert to literal block")
        assertEquals("---\n" + block("folded", lines) + block("folded_strip", lines, "|-") + "after: 1\n", myFixture.editor.document.text)
        assertEquals(emptyList<DiagnosticCode>(), codes("v.yml", myFixture.editor.document.text))
        assertAllWellFormed()
    }

    fun testFlattenedPlainAndQuotedValuesAreV102AndBecomeLiteralBlocks() {
        val plain = "outer:\n  plain: !vault $header\n" + payload.joinToString("") { "    $it\n" } + "  next: 1\n"
        assertEquals(listOf(DiagnosticCode.V102_FOLDED_VAULT_VALUE), codes("v.yml", plain))
        assertTrue(vaultInfos("v.yml", plain).single().description!!.startsWith("Flattened vault value: a plain scalar"))
        fix("Convert to literal block")
        val expected = "outer:\n  plain: !vault |\n" + (listOf(header) + payload.joinToString("").chunked(80)).joinToString("") { "    $it\n" } + "  next: 1\n"
        assertEquals(expected, myFixture.editor.document.text)
        assertAllWellFormed()

        val quoted = "quoted: !vault \"$header\n  " + payload.joinToString("\n  ") + "\"\n"
        assertEquals(listOf(DiagnosticCode.V102_FOLDED_VAULT_VALUE), codes("q.yml", quoted))
        fix("Convert to literal block")
        assertEquals(block("quoted", listOf(header) + payload.joinToString("").chunked(80)), myFixture.editor.document.text)
        assertAllWellFormed()
    }

    fun testFlattenedValueWithATrailingCommentHasNoFix() {
        val text = "v: !vault $header\n  " + payload.joinToString("\n  ") + " # rotated\n"
        assertEquals(listOf(DiagnosticCode.V102_FOLDED_VAULT_VALUE), codes("v.yml", text))
        assertFalse(myFixture.getAllQuickFixes().any { it.text == "Convert to literal block" })
    }

    fun testTrailingWhitespaceIsV103AndIsStripped() {
        val body = listOf(header, payload[0] + "   ") + payload.drop(1).dropLast(1) + (payload.last() + "\t")
        val text = "a: 1\n" + block("v", body) + "b: 2\n"
        val infos = vaultInfos("v.yml", text)
        assertEquals(listOf(DiagnosticCode.V103_TRAILING_WHITESPACE), codes("v.yml", text))
        assertTrue(infos.single().description!!, "line 2 of the vault value" in infos.single().description!!)
        assertEquals(payload[0] + "   ", text.substring(infos.single().startOffset, infos.single().endOffset))
        fix("Strip trailing whitespace")
        assertEquals("a: 1\n" + block("v", lines) + "b: 2\n", myFixture.editor.document.text)
        assertEquals(emptyList<DiagnosticCode>(), codes("v.yml", myFixture.editor.document.text))
        assertAllWellFormed()
    }

    fun testWholeFileVaultsInAnyFileType() {
        assertEquals(emptyList<DiagnosticCode>(), codes("clean.txt", envelope.format()))
        assertEquals(emptyList<DiagnosticCode>(), codes("clean.yml", envelope.format()))

        val broken = codes("broken.txt", "\$ANSIBLE_VAULT;1.1;aes256\n" + payload.joinToString("\n") + "\n")
        assertEquals(listOf(DiagnosticCode.V101_MALFORMED_ENVELOPE), broken)
        assertTrue(vaultInfos("broken.txt", "\$ANSIBLE_VAULT;1.1;aes256\n" + payload.joinToString("\n") + "\n").single().description!!
            .startsWith("Malformed vault file: "))

        val padded = header + "\n" + payload.joinToString("\n") { "$it " } + "\n"
        assertEquals(listOf(DiagnosticCode.V103_TRAILING_WHITESPACE), codes("padded.txt", padded))
        fix("Strip trailing whitespace")
        assertEquals(envelope.format(), myFixture.editor.document.text)
        assertEquals(emptyList<DiagnosticCode>(), codes("padded2.txt", myFixture.editor.document.text))

        assertEquals("a file starting with whitespace is no vault", emptyList<DiagnosticCode>(), codes("lead.txt", " " + envelope.format()))
    }

    fun testOneCodePerEnvelopeAndTheClassification() {
        assertNull(VaultEnvelopeChecks.codeOf(null, null, ScalarStyle.LITERAL))
        val folded = DiagnosticCode.V102_FOLDED_VAULT_VALUE
        assertEquals(folded, VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.UNKNOWN_CIPHER, FormatHint.FOLDED_BLOCK, ScalarStyle.FOLDED))
        assertEquals(folded, VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.UNKNOWN_CIPHER, FormatHint.FOLDED_BLOCK, ScalarStyle.PLAIN))
        assertEquals(folded, VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.UNKNOWN_CIPHER, FormatHint.FOLDED_BLOCK, ScalarStyle.DOUBLE_QUOTED))
        assertEquals("a literal block is never 'folded'", DiagnosticCode.V101_MALFORMED_ENVELOPE,
            VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.UNKNOWN_CIPHER, FormatHint.FOLDED_BLOCK, ScalarStyle.LITERAL))
        assertEquals(DiagnosticCode.V101_MALFORMED_ENVELOPE, VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.NO_MAGIC, null, ScalarStyle.PLAIN))
        assertEquals(DiagnosticCode.V103_TRAILING_WHITESPACE,
            VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.ODD_LENGTH, FormatHint.TRAILING_WHITESPACE, ScalarStyle.LITERAL))
        assertEquals(DiagnosticCode.V103_TRAILING_WHITESPACE, VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.NON_HEX_DIGIT, FormatHint.TRAILING_WHITESPACE, null))
        assertEquals("a bad cipher stays V101 whatever the blanks", DiagnosticCode.V101_MALFORMED_ENVELOPE,
            VaultEnvelopeChecks.codeOf(VaultEnvelopeProblem.UNKNOWN_CIPHER, FormatHint.TRAILING_WHITESPACE, ScalarStyle.LITERAL))
        val both = block("v", listOf("\$ANSIBLE_VAULT;1.1;aes256", payload[0] + " ") + payload.drop(1))
        assertEquals(listOf(DiagnosticCode.V101_MALFORMED_ENVELOPE), codes("v.yml", both))
    }

    fun testChecksNeverDecryptAndSkipTemplates() {
        val before = VaultCrypto.getInstance(project).decryptAttempts
        val text = block("v", listOf(header, payload[0] + " ") + payload.drop(1))
        assertEquals(1, codes("v.yml", text).size)
        assertEquals(before, VaultCrypto.getInstance(project).decryptAttempts)
        myFixture.addFileToProject("roles/r/templates/app.yml", text)
        myFixture.configureFromTempProjectFile("roles/r/templates/app.yml")
        assertEquals("templates are rendered before Ansible loads them", emptyList<VaultFinding>(), VaultEnvelopeChecks.of(myFixture.file))
    }

    fun testRegisteredUnderAnsibilityVaultForEveryLanguageWithDescriptions() {
        val ours = LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { it.shortName?.startsWith("AnsibleVault") == true }.associateBy { it.shortName }
        assertEquals(setOf("AnsibleVaultMalformedEnvelope", "AnsibleVaultFoldedValue", "AnsibleVaultTrailingWhitespace"), ours.keys)
        for (ep in ours.values) {
            assertEquals(ep.shortName, "Ansibility", ep.groupPath)
            assertEquals(ep.shortName, "inspection.group.vault", ep.groupKey)
            assertEquals(ep.shortName, "Vault", AnsibilityVaultChecksBundle.message(ep.groupKey))
            assertNull("whole-file vaults live in files of any type", ep.language)
            assertEquals(ep.shortName, "ERROR", ep.level)
            assertNotNull(ep.shortName, javaClass.getResource("/inspectionDescriptions/${ep.shortName}.html"))
        }
    }

    private companion object {
        val MESSAGE_PREFIXES = listOf("Malformed vault", "Folded vault", "Flattened vault", "Trailing whitespace on line")
        val FIX_NAMES = setOf("Convert to literal block", "Strip trailing whitespace")
    }
}
