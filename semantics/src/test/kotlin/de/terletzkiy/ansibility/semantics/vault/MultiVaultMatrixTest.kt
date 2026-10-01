package de.terletzkiy.ansibility.semantics.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Ansible's multi-vault matching (`decrypt_and_get_vault_id`): `vault_id_match` off and on, secret lists in both
 * orders, labelled, mislabelled, unlabelled, empty-label and renamed-default envelopes. Each row records the result,
 * the id that decrypted and the exact order of tries as ansible-core 2.18.8 and 2.21.4 performed them.
 */
class MultiVaultMatrixTest {
    @TestFactory
    fun `the multi-vault matrix row by row`(): List<DynamicContainer> = VaultTestData.VERSIONS.map { version ->
        DynamicContainer.dynamicContainer(version, VaultTestData.tolerance(version).rows("multivault").map { row ->
            val name = "${row["defaultIdentity"]} match=${row["match"]} ${row["secretsName"]} ${row["envelope"]}"
            DynamicTest.dynamicTest(name) {
                @Suppress("UNCHECKED_CAST")
                val secrets = (row["secrets"] as List<List<String>>).map { (label, pid) -> VaultTestData.labelled(label, pid) }
                val envelope = VaultTestData.envelope(row.str("envelopeText"))
                val outcome = VaultMatcher.decrypt(envelope, secrets, row["match"] as Boolean, row.str("defaultIdentity"))
                when (outcome) {
                    is DecryptOutcome.Decrypted -> outcome.use {
                        assertEquals("OK", row["result"])
                        assertEquals(row["via"], it.label)
                        assertEquals(row.strings("tried"), it.tried)
                    }
                    is DecryptOutcome.NoSecretWorked -> {
                        assertEquals("NO_SECRET", row["result"])
                        assertEquals(row.strings("tried"), outcome.tried)
                    }
                    is DecryptOutcome.FormatError -> error("unexpected $outcome")
                }
                val labels = secrets.map { it.label }
                val candidates = VaultMatcher.candidates(envelope, labels, row["match"] as Boolean, row.str("defaultIdentity"))
                assertEquals(row.strings("tried"), candidates.map { labels[it] }.take(row.strings("tried").size))
            }
        })
    }

    @Test
    fun `the multi-vault fixture decrypts to its expected plaintexts`() {
        val expected = VaultTestData.json("multivault-fixture.expected.json")
        @Suppress("UNCHECKED_CAST")
        val secrets = (expected["secrets"] as List<List<String>>).map { (label, pid) -> VaultTestData.labelled(label, pid) }
        val scalars = VaultTestData.vaultScalars(VaultTestData.text("raw/multivault-fixture.yml"))
        val cases = expected.obj("expected")
        assertEquals(cases.keys, scalars.keys)
        for ((key, case) in cases) {
            @Suppress("UNCHECKED_CAST")
            case as Map<String, Any?>
            val envelope = VaultTestData.envelope(scalars.getValue(key))
            assertEquals(case.str("header"), envelope.headerLine())
            val outcome = VaultMatcher.decrypt(envelope, secrets, idMatch = false) as DecryptOutcome.Decrypted
            outcome.use { assertEquals(case.str("plaintext"), it.read { bytes -> String(bytes, Charsets.UTF_8) }, key) }
        }
        for (version in VaultTestData.VERSIONS) {
            assertEquals(cases.keys.associateWith { "OK" }, VaultTestData.tolerance(version).obj("fixture"), version)
        }
    }

    @Test
    fun `strict matching fails the mislabelled and unlabelled values and reports the mismatch otherwise`() {
        val secrets = listOf(VaultTestData.labelled("default", "pw1"), VaultTestData.labelled("dev", "dev"),
            VaultTestData.labelled("prod", "prod"))
        for (id in listOf("v13", "v14")) {
            val envelope = VaultTestData.envelope(VaultTestData.vector(id).envelope)
            val lenient = VaultMatcher.decrypt(envelope, secrets, idMatch = false) as DecryptOutcome.Decrypted
            lenient.use {
                assertEquals("prod", it.label)
                assertEquals(false, it.labelMatches, "$id would fail under vault_id_match")
            }
            assertEquals(DecryptOutcome.NoSecretWorked(listOf(envelope.labelOrDefault())),
                VaultMatcher.decrypt(envelope, secrets, idMatch = true))
        }
        val v02 = VaultMatcher.decrypt(VaultTestData.envelope(VaultTestData.vector("v02").envelope), secrets, idMatch = true)
        (v02 as DecryptOutcome.Decrypted).use { assertEquals(true, it.labelMatches) }
    }

    @Test
    fun `label-first ordering only changes the cost, unless two ids share a password`() {
        val secrets = listOf(VaultTestData.labelled("prod", "prod"), VaultTestData.labelled("default", "pw1"),
            VaultTestData.labelled("dev", "dev"))
        val v02 = VaultTestData.envelope(VaultTestData.vector("v02").envelope)
        val ansible = VaultMatcher.decrypt(v02, secrets, idMatch = false) as DecryptOutcome.Decrypted
        val labelFirst = VaultMatcher.decrypt(v02, secrets, idMatch = false, order = VaultMatcher.TryOrder.LABEL_FIRST)
            as DecryptOutcome.Decrypted
        assertEquals(listOf("prod", "default", "dev"), ansible.tried)
        assertEquals(listOf("dev"), labelFirst.tried)
        assertEquals(ansible.label, labelFirst.label)
        ansible.close()
        labelFirst.close()

        val shared = listOf(VaultTestData.labelled("ops", "dev"), VaultTestData.labelled("dev", "dev"))
        assertEquals("ops", (VaultMatcher.decrypt(v02, shared, idMatch = false) as DecryptOutcome.Decrypted).label)
        assertEquals("dev", (VaultMatcher.decrypt(v02, shared, idMatch = false, order = VaultMatcher.TryOrder.LABEL_FIRST)
            as DecryptOutcome.Decrypted).label)
    }

    @Test
    fun `no secret, or none matching under strict matching, tries nothing`() {
        val v01 = VaultTestData.envelope(VaultTestData.vector("v01").envelope)
        assertEquals(DecryptOutcome.NoSecretWorked(emptyList()), VaultMatcher.decrypt(v01, emptyList(), idMatch = false))
        assertEquals(DecryptOutcome.NoSecretWorked(emptyList()),
            VaultMatcher.decrypt(v01, listOf(VaultTestData.labelled("x", "pw1")), idMatch = true))
        val emptyLabel = v01.withLabel("dev").let { VaultTestData.envelope(it.format().replace(";dev", ";")) }
        assertEquals("", emptyLabel.label)
        assertEquals(DecryptOutcome.NoSecretWorked(emptyList()),
            VaultMatcher.decrypt(emptyLabel, listOf(VaultTestData.labelled("default", "pw1")), idMatch = true))
        // A renamed default identity that is empty excludes secrets labelled '' even with matching off (Ansible's set logic).
        assertEquals(listOf(1), VaultMatcher.candidates(v01, listOf("", "x"), idMatch = false, defaultIdentity = ""))
    }

    @Test
    fun `a padding error after a valid HMAC stops at once`() {
        val v01 = VaultTestData.vector("v01")
        val keys = VaultTestData.secret("pw1").use { VaultAes256.deriveKeys(it, v01.salt) }
        val ciphertext = v01.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val tag = javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(keys.hmacKey(), "HmacSHA256"))
            doFinal(ciphertext)
        }
        val crafted = VaultEnvelope.of(null, v01.salt, tag, ciphertext)
        val secrets = listOf(VaultTestData.labelled("default", "pw1"), VaultTestData.labelled("dev", "dev"))
        assertEquals(DecryptOutcome.FormatError(FormatReason.PADDING, listOf("default")),
            VaultMatcher.decrypt(crafted, secrets, idMatch = false))
        assertThrows(VaultFormatException::class.java) { VaultAes256.decrypt(crafted, keys) }
        keys.close()
    }

    @Test
    fun `a vaulted password file decrypts only with the secrets loaded before it`() {
        val inner = VaultTestData.passwords.getValue("inner")
        val vaulted = VaultTestData.secret("pw1").use { VaultAes256.encrypt(inner.sourceBytes, it, null) }.formatBytes()
        val loaded = mutableListOf<LabelledSecret>()
        val before = SecretBytes.fromFile(vaulted, VaultMatcher.passwordFileDecryptor(loaded, idMatch = false))
        assertEquals(SecretLoad.Failed(SecretLoadFailure.VAULTED_NOT_DECRYPTED), before)
        loaded += VaultTestData.labelled("x", "pw1")
        val after = SecretBytes.fromFile(vaulted, VaultMatcher.passwordFileDecryptor(loaded, idMatch = false)) as SecretLoad.Loaded
        after.secret.read { assertArrayEquals(inner.bytes, it) }
        assertEquals(SecretLoad.Failed(SecretLoadFailure.VAULTED_NOT_DECRYPTED),
            SecretBytes.fromFile(vaulted, VaultMatcher.passwordFileDecryptor(loaded, idMatch = true)), "x is not 'default'")
        assertNull((SecretBytes.fromFile(vaulted, VaultMatcher.passwordFileDecryptor(listOf(VaultTestData.labelled("default", "pw1")),
            idMatch = true)) as? SecretLoad.Failed))
    }
}
