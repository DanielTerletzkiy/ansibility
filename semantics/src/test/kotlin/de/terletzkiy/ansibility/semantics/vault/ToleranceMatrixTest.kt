package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Every row of `tol-2.18.json` and `tol-2.21.json`: inline `!vault` scalars through YAML (folded, chomping, CRLF,
 * trailing spaces, quoted escapes, …) and whole-file envelopes (BOM, leading newline, payload damage, padding), each
 * accepted or rejected exactly as ansible-vault does, with the same error class.
 */
class ToleranceMatrixTest {
    private val expected = VaultTestData.vector("v01").plaintext

    @TestFactory
    fun `inline values through YAML`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, VaultTestData.tolerance(version).rows("yaml").map { row ->
            DynamicTest.dynamicTest(row.str("id")) {
                val scalar = VaultTestData.vaultScalar(row.str("yaml"))
                assertEquals(row.strOrNull("scalar"), scalar, "the YAML 1.1 scalar value (SnakeYAML vs PyYAML)")
                assertEquals(row.str("result") to row.strOrNull("detail"), classify(VaultEnvelope.parse(scalar!!)))
            }
        })
    }

    @TestFactory
    fun `whole-file envelopes`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, VaultTestData.tolerance(version).rows("file").map { row ->
            DynamicTest.dynamicTest(row.str("id")) {
                val data = unhex(row.str("hex"))
                assertEquals(row["fileHeaderDetected"], VaultEnvelope.isEncryptedFile(data), "first-14-bytes detection")
                assertEquals(row.str("result") to row.strOrNull("detail"), classify(VaultEnvelope.parse(data)))
                assertEquals(VaultEnvelope.isEncrypted(data), row.str("result") != "NOT_VAULT", "is_encrypted")
            }
        })
    }

    @Test
    fun `both versions agree on every row`() {
        val (old, new) = VaultTestData.VERSIONS.map { VaultTestData.tolerance(it) }
        for (section in listOf("yaml", "file", "multivault")) {
            assertEquals(old.rows(section).map { it - "version" }, new.rows(section).map { it - "version" }, section)
        }
    }

    @Test
    fun `failures carry the reason, the part and a hint for the fix`() {
        val rows = VaultTestData.tolerance("2.21").let { t -> t.rows("yaml") + t.rows("file") }.associateBy { it.str("id") }
        fun parsed(id: String): EnvelopeParse {
            val row = rows.getValue(id)
            return if ("yaml" in row) VaultEnvelope.parse(VaultTestData.vaultScalar(row.str("yaml"))!!) else VaultEnvelope.parse(unhex(row.str("hex")))
        }
        assertEquals(EnvelopeParse.Format(FormatReason.ODD_LENGTH, EnvelopePart.BODY, FormatHint.TRAILING_WHITESPACE),
            parsed("F_trailing_spaces_on_hex_lines"))
        assertEquals(EnvelopeParse.Format(FormatReason.ODD_LENGTH, EnvelopePart.BODY, FormatHint.TAB), parsed("W_tab_inside_hex_line"))
        assertEquals(EnvelopeParse.Format(FormatReason.ODD_LENGTH, EnvelopePart.BODY, null), parsed("V_odd_length_hex"))
        assertEquals(EnvelopeParse.Format(FormatReason.HEADER_FIELDS, EnvelopePart.HEADER, null), parsed("Y_two_header_fields"))
        assertEquals(EnvelopeParse.Format(FormatReason.NON_HEX_DIGIT, EnvelopePart.BODY, FormatHint.LEADING_WHITESPACE),
            parsed("g_leading_spaces_on_hex_lines"))
        assertEquals(EnvelopeParse.Format(FormatReason.ODD_LENGTH, EnvelopePart.BODY, FormatHint.TRAILING_WHITESPACE),
            parsed("h_trailing_spaces_on_hex_lines"))
        assertEquals(EnvelopeParse.Format(FormatReason.PAYLOAD_FIELDS, EnvelopePart.BODY, null), parsed("m_no_body"))
        assertEquals(EnvelopeParse.Format(FormatReason.PAYLOAD_FIELDS, EnvelopePart.BODY, null), parsed("n_payload_without_separators"))
        assertEquals(EnvelopeParse.Format(FormatReason.NON_HEX_DIGIT, EnvelopePart.SALT, null), parsed("p_salt_not_hex"))
        assertEquals(EnvelopeParse.Format(FormatReason.NON_HEX_DIGIT, EnvelopePart.HMAC, null), parsed("q_hmac_not_hex"))
        assertEquals(EnvelopeParse.Format(FormatReason.NON_HEX_DIGIT, EnvelopePart.CIPHERTEXT, null), parsed("r_ciphertext_not_hex"))
        assertEquals(EnvelopeParse.Format(FormatReason.ODD_LENGTH, EnvelopePart.CIPHERTEXT, null), parsed("s_ciphertext_odd_hex"))

        val folded = parsed("K_folded_>") as EnvelopeParse.UnknownCipher
        assertEquals("AES256", folded.name)
        assertEquals(FormatHint.FOLDED_BLOCK, folded.hint)
        assertEquals(false, folded.toString().contains("3237"), "the folded payload never reaches toString")
        assertEquals("aes256", (parsed("R_lowercase_cipher_aes256") as EnvelopeParse.UnknownCipher).name)

        assertEquals(EnvelopeParse.NotVault(NotVaultReason.LEADING_WHITESPACE), parsed("N_quoted_leading_space_before_header"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.LEADING_WHITESPACE), parsed("d_leading_newline"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.BYTE_ORDER_MARK), parsed("e_utf8_BOM"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.LEADING_WHITESPACE), parsed("i_header_leading_space"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NON_ASCII), parsed("k_non_ascii_after_body"))
        assertEquals(EnvelopeParse.NotVault(NotVaultReason.NO_MAGIC), parsed("x_lowercase_magic"))

        val emptyLabel = (parsed("Z_empty_label_field") as EnvelopeParse.Ok).envelope
        assertEquals("", emptyLabel.label)
        assertEquals("9.9", (parsed("Q_version_9.9") as EnvelopeParse.Ok).envelope.version)
        assertEquals("dev", (parsed("O_1.1_header_with_label") as EnvelopeParse.Ok).envelope.label)
        assertEquals(null, (parsed("P_1.2_header_without_label") as EnvelopeParse.Ok).envelope.label)
    }

    /** The oracle's result class and detail for what this codec does with a scalar or file. */
    private fun classify(parse: EnvelopeParse): Pair<String, String?> = when (parse) {
        is EnvelopeParse.NotVault -> "NOT_VAULT" to null
        is EnvelopeParse.UnknownCipher -> "UNKNOWN_CIPHER" to null
        is EnvelopeParse.Format -> "FORMAT" to when (parse.reason) {
            FormatReason.HEADER_FIELDS -> "header"
            FormatReason.ODD_LENGTH -> "odd-length"
            FormatReason.NON_HEX_DIGIT -> "non-hex"
            FormatReason.PAYLOAD_FIELDS -> "payload"
            FormatReason.PADDING -> "padding"
        }
        is EnvelopeParse.Ok -> {
            // The oracle's loader held default, dev and prod; v01 needs default.
            val secrets = listOf(VaultTestData.labelled("default", "pw1"), VaultTestData.labelled("dev", "dev"),
                VaultTestData.labelled("prod", "prod"))
            when (val outcome = VaultMatcher.decrypt(parse.envelope, secrets, idMatch = false)) {
                is DecryptOutcome.Decrypted -> outcome.use { d ->
                    (if (d.read { it.contentEquals(expected) }) "OK" else "WRONG_PLAINTEXT") to null
                }
                is DecryptOutcome.NoSecretWorked -> "NO_SECRET" to null
                is DecryptOutcome.FormatError -> "PADDING" to null
            }
        }
    }
}
