package de.terletzkiy.ansibility.vault

import com.intellij.openapi.application.runReadActionBlocking
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultLockState
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult

/**
 * `vault_id_match` at service level (research vault-core §3.2, verified with ansible-vault 2.18.8 and 2.21.4):
 * with matching off every secret is tried in list order; any non-empty value, `false` and `0` included, turns it on,
 * and then only the secrets labelled like the envelope are tried (a `1.1` envelope counts as `default`). Secrets:
 * `dev@dev.pw`, `prod@prod.pw`; vectors v02 (dev, dev), v03 (prod, prod), v13 (label dev, prod password) and v14
 * (`1.1`, prod password).
 */
class VaultIdMatchMatrixTest : VaultTestCase() {
    private val vectors = listOf("v02" to "db_password", "v03" to "db_password", "v13" to "mislabeled", "v14" to "unlabeled_prod")

    /** The outcome per vector: the decrypting id, or the failure and the ids tried. */
    private fun outcomes(name: String, cfgLine: String?, environment: Map<String, String> = emptyMap()): List<String> {
        val relative = projectRoot(name, "[defaults]\nvault_identity_list = dev@dev.pw, prod@prod.pw\n" + cfgLine?.let { "$it\n" }.orEmpty())
        write("$relative/dev.pw", "${VaultVectors.DEV}\n")
        write("$relative/prod.pw", "${VaultVectors.PROD}\n")
        for ((vector, _) in vectors) write("$relative/vars/$vector.yml", VaultVectors.raw(vector))
        access.environment = mapOf("HOME" to home.toString()) + environment
        val root = root(relative)
        assertEquals(VaultUnlockResult.Unlocked(listOf("dev", "prod")), await { VaultOperations.getInstance(project).unlock(root) })
        return vectors.map { (vector, key) ->
            val location = location("$relative/vars/$vector.yml", key)
            when (val result = await { VaultOperations.getInstance(project).decrypt(location, VaultPurpose.TEST) }) {
                is VaultDecryptResult.Decrypted -> result.plaintext.use { "$vector ${result.identity}" }
                is VaultDecryptResult.Failed -> "$vector ${result.failure} ${result.tried}"
            }
        }
    }

    private val off = listOf("v02 dev", "v03 prod", "v13 prod", "v14 prod")
    private val on = listOf("v02 dev", "v03 prod", "v13 WRONG_SECRET [dev]", "v14 NO_IDENTITY []")

    fun testUnsetIsOff() = assertEquals(off, outcomes("unset", null))

    fun testEmptyIniValueIsOff() = assertEquals(off, outcomes("empty", "vault_id_match = \"\""))

    fun testTrueIsOn() = assertEquals(on, outcomes("true", "vault_id_match = True"))

    fun testFalseIsOnBecauseAnyNonEmptyValueTurnsItOn() {
        assertEquals(on, outcomes("false", "vault_id_match = false"))
        val config = VaultStatusService.getInstance(project).config(root("repos/false/ansible"))
        assertEquals("false", config.idMatchRaw)
        assertTrue(config.idMatch)
    }

    fun testEnvironmentZeroIsOnAndBeatsAnsibleCfg() =
        assertEquals(on, outcomes("env", "vault_id_match =", mapOf("ANSIBLE_VAULT_ID_MATCH" to "0")))

    fun testStatusFollowsTheMatchingRule() {
        outcomes("status", "vault_id_match = yes")
        val v14 = location("repos/status/ansible/vars/v14.yml", "unlabeled_prod")
        val v13 = location("repos/status/ansible/vars/v13.yml", "mislabeled")
        val status = VaultStatusService.getInstance(project)
        runReadActionBlocking {
            assertEquals("no id is labelled default", VaultLockState.NO_IDENTITY, status.status(v14)!!.lockState)
            assertNull(status.status(v14)!!.identity)
            assertEquals("dev", status.status(v13)!!.identity!!.label)
            assertEquals(VaultLockState.UNLOCKED, status.status(v13)!!.lockState)
            assertTrue(status.status(v13)!!.verified)
            assertNull("dev alone does not decrypt v13", status.status(v13)!!.decryptsWith)
        }
    }
}
