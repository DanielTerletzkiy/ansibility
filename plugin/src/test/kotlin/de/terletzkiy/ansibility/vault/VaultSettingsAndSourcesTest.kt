package de.terletzkiy.ansibility.vault

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.util.xmlb.XmlSerializer
import de.terletzkiy.ansibility.api.VaultDecryptResult
import de.terletzkiy.ansibility.api.VaultOperations
import de.terletzkiy.ansibility.api.VaultPurpose
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import de.terletzkiy.ansibility.vault.secrets.ConsentItem
import de.terletzkiy.ansibility.vault.secrets.ConsentRecord
import de.terletzkiy.ansibility.vault.secrets.PasswordSafeCredentialStore
import de.terletzkiy.ansibility.vault.secrets.VaultConsentDialog
import de.terletzkiy.ansibility.vault.secrets.VaultUserState

/**
 * The persisted vault state (shared project settings, per-user application state), the explicit source kinds of the
 * settings, and the consent dialog's texts.
 */
class VaultSettingsAndSourcesTest : VaultTestCase() {
    private val ops: VaultOperations get() = VaultOperations.getInstance(project)

    fun testProjectSettingsRoundTripThroughXml() {
        val settings = VaultProjectSettings.getInstance(project)
        val root = VaultRootSettings(
            identities = listOf(
                ExplicitIdentity("prod", VaultSourceKind.PASSWORD_FILE, "secrets/prod.pw"),
                ExplicitIdentity("ops", VaultSourceKind.PROMPT),
            ),
            environmentIdentities = mapOf("prod" to "prod", VaultRootSettings.ANY_ENVIRONMENT to "default"),
            analyzeDecryptedValues = true,
        )
        val tracker = settings.modificationTracker.modificationCount
        settings.update("repos/falcon/ansible") { root }
        assertTrue(settings.modificationTracker.modificationCount > tracker)

        val xml = XmlSerializer.serialize(settings.state)
        val reloaded = VaultProjectSettings()
        reloaded.loadState(XmlSerializer.deserialize(xml, VaultProjectSettings.StateBean::class.java))
        assertEquals(root, reloaded.rootSettings("repos/falcon/ansible"))
        assertEquals("prod", root.identityForEnvironment("prod"))
        assertEquals("default", root.identityForEnvironment("test"))

        settings.update("repos/falcon/ansible") { VaultRootSettings.DEFAULT }
        assertEmpty("defaults are not stored", settings.state.roots)
    }

    fun testUserStateSavesConsentsAndPersistentLabelsOnly() {
        val state = VaultUserState.getInstance()
        state.grant(ConsentRecord("/r/.vault-pass", 12, "pbkdf2-hmac-sha256:00:11"))
        state.remember("/r", "default", persistent = true)
        state.remember("/r", "tmp", persistent = false)
        state.autoLockMinutes = 45
        assertEquals(listOf("default", "tmp"), state.rememberedLabels("/r"))

        val xml = XmlSerializer.serialize(state.state)
        val reloaded = VaultUserState()
        reloaded.loadState(XmlSerializer.deserialize(xml, VaultUserState.StateBean::class.java))
        assertEquals(ConsentRecord("/r/.vault-pass", 12, "pbkdf2-hmac-sha256:00:11"), reloaded.consent("/r/.vault-pass"))
        assertEquals("session labels are not saved", listOf("default"), reloaded.rememberedLabels("/r"))
        assertEquals(45, reloaded.autoLockMinutes)

        state.forget("/r", "default")
        state.revoke("/r/.vault-pass")
        assertEquals(listOf("tmp"), state.rememberedLabels("/r"))
        assertNull(state.consent("/r/.vault-pass"))
    }

    fun testAnEnvironmentVariableSourceNeedsAConsentToo() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v02"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("dev", VaultSourceKind.ENVIRONMENT, "VAULT_DEV_PASSWORD")))
        }
        access.environment = mapOf("HOME" to home.toString(), "VAULT_DEV_PASSWORD" to "${VaultVectors.DEV}\n")
        val identity = VaultStatusService.getInstance(project).config(root).identities.single()
        assertEquals(VaultSourceKind.ENVIRONMENT, identity.source.kind)
        assertEquals(VaultSourceOrigin.SETTINGS, identity.source.origin)

        assertEquals(VaultUnlockResult.Unlocked(listOf("dev")), await { ops.unlock(root) })
        val item = prompter.consentRequests.single().roots.single().items.single()
        assertEquals(ConsentItem.Kind.ENVIRONMENT, item.kind)
        assertEquals("VAULT_DEV_PASSWORD", item.display)
        val result = await { ops.decrypt(location("$falcon/vault.yml", "db_password"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("dev only secret", result.plaintext.use { p -> p.read { String(it) } })
    }

    fun testAConfiguredPasswordSafeEntryIsReadWithoutAPrompt() {
        val falcon = projectRoot("falcon")
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        val root = root(falcon)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("prod", VaultSourceKind.PASSWORD_SAFE, "team#prod")))
        }
        secrets.setCredentialsForTests(PasswordSafeCredentialStore)
        val service = VaultPasswordSafeKeys.ownServiceName("team#prod")
        assertEquals(service, VaultStatusService.getInstance(project).config(root).identities.single().source.location)
        passwordSafe.set(CredentialAttributes(service, "prod"), Credentials("prod", VaultVectors.PROD))
        // Another plugin's entry under the same key text is never what an explicit id names.
        passwordSafe.set(CredentialAttributes("team#prod", "prod"), Credentials("prod", "foreign"))

        assertEquals(VaultUnlockResult.Unlocked(listOf("prod")), await { ops.unlock(root) })
        assertEmpty(prompter.consentRequests)
        assertEmpty(prompter.passwordRequests)
        val result = await { ops.decrypt(location("$falcon/vault.yml", "db_password"), VaultPurpose.REVEAL) } as VaultDecryptResult.Decrypted
        assertEquals("prod", result.identity)
        result.plaintext.close()
    }

    fun testConsentDialogTexts() {
        assertEquals(
            "Id default: the ANSIBLE_LOCAL_VAULT_PASSWORD_FILE line of .env.local, then the password file it names (.vault-pass)",
            VaultConsentDialog.itemText(ConsentItem("default", ConsentItem.Kind.ENV_LOCAL_KEY, ".env.local", ".vault-pass")),
        )
        assertEquals(
            "Id default: the ANSIBLE_LOCAL_VAULT_PASSWORD_FILE line of .env.local, then the password file it names",
            VaultConsentDialog.itemText(ConsentItem("default", ConsentItem.Kind.ENV_LOCAL_KEY, ".env.local")),
        )
        assertEquals(
            "Id prod: the password file secrets/prod.pw (changed since you allowed it)",
            VaultConsentDialog.itemText(ConsentItem("prod", ConsentItem.Kind.PASSWORD_FILE, "secrets/prod.pw", changed = true)),
        )
        assertEquals(
            "Id dev: the environment variable VAULT_DEV_PASSWORD",
            VaultConsentDialog.itemText(ConsentItem("dev", ConsentItem.Kind.ENVIRONMENT, "VAULT_DEV_PASSWORD")),
        )
    }
}
