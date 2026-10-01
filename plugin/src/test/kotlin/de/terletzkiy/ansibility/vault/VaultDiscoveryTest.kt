package de.terletzkiy.ansibility.vault

import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultSourceOrigin
import de.terletzkiy.ansibility.api.VaultStatusService
import de.terletzkiy.ansibility.vault.identity.EnvLocalFile
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.VaultDiscovery
import de.terletzkiy.ansibility.vault.identity.VaultPasswordSafeKeys
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import de.terletzkiy.ansibility.vault.secrets.VaultUserState
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The discovery chain of [de.terletzkiy.ansibility.vault.identity.VaultIdentityRegistry] (F7.9): its order,
 * Ansible's configuration rules, the repository's conventions, de-duplication, nested roots, and that building it
 * opens no secret source.
 */
class VaultDiscoveryTest : VaultTestCase() {
    private fun rows(discovery: VaultDiscovery): List<String> = discovery.identities.map {
        "${it.label} ${it.source.kind} ${it.source.origin} ${it.source.location}"
    }

    fun testChainOrderIsSettingsThenAnsibleThenConventionsThenPasswordSafe() {
        val falcon = projectRoot(
            "falcon",
            "[defaults]\nvault_identity_list = dev@secrets/dev.pw, ops@prompt\nvault_password_file = secrets/default.pw\n",
        )
        write("$falcon/secrets/dev.pw", "${VaultVectors.DEV}\n")
        write("$falcon/secrets/default.pw", "${VaultVectors.PW1}\n")
        write("$falcon/.env.local", "ANSIBLE_USER=someone\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$falcon/.env.local.skel", "ANSIBLE_USER=\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n")
        write("$falcon/.vault-pass", "${VaultVectors.PW1}\n")
        write("$falcon/.vault_pass", "${VaultVectors.PW1}\n")
        write("repos/falcon/.git", "gitdir: ../../.git/modules/repos/falcon\n")
        write("repos/falcon/.vault-pass", "${VaultVectors.PW1}\n")
        val root = root(falcon)
        val key = registry.rootKey(root)
        VaultProjectSettings.getInstance(project).update(key) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("prod", VaultSourceKind.PASSWORD_FILE, "secrets/prod.pw")))
        }
        val canonicalRoot = base.resolve(falcon).toRealPath().toString()
        VaultUserState.getInstance().remember(canonicalRoot, "tmp", persistent = true)

        val discovery = registry.discovery(root)
        assertEquals(
            listOf(
                "prod PASSWORD_FILE SETTINGS secrets/prod.pw",
                "dev PASSWORD_FILE ANSIBLE_CFG secrets/dev.pw",
                "ops PROMPT ANSIBLE_CFG null",
                "default PASSWORD_FILE ANSIBLE_CFG secrets/default.pw",
                "default PASSWORD_FILE ENV_LOCAL .vault-pass",
                "default PASSWORD_FILE CONVENTIONAL_NAME .vault_pass",
                "default PASSWORD_FILE CONVENTIONAL_NAME ${base.resolve("repos/falcon/.vault-pass")}",
                "tmp PASSWORD_SAFE PASSWORD_SAFE ${VaultPasswordSafeKeys.serviceName(canonicalRoot, "tmp")}",
            ),
            rows(discovery),
        )
        assertEmpty("discovery opens no secret source", access.secretReads)
        assertEquals("only the template is read", listOf(".env.local.skel"), access.nonSecretReads.map { it.fileName.toString() })
    }

    fun testEnvironmentBeatsAnsibleCfgAndMissingFilesAreSkipped() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_password_file = missing.pw\n")
        val fromEnv = write("elsewhere/env.pw", "${VaultVectors.PW1}\n")
        access.environment = mapOf(
            "HOME" to home.toString(),
            "ANSIBLE_VAULT_PASSWORD_FILE" to fromEnv.toString(),
            "ANSIBLE_VAULT_IDENTITY_LIST" to "ci@/.ansible-vaultpassword",
        )
        val discovery = registry.discovery(root(falcon))
        assertEquals(listOf("default PASSWORD_FILE ENVIRONMENT $fromEnv"), rows(discovery))
    }

    fun testAskVaultPassAddsAPromptAfterTheConfiguredSecrets() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@dev.pw\nask_vault_pass = True\n")
        write("$falcon/dev.pw", "${VaultVectors.DEV}\n")
        val root = root(falcon)
        assertEquals(listOf("dev PASSWORD_FILE ANSIBLE_CFG dev.pw", "default PROMPT ANSIBLE_CFG null"), rows(registry.discovery(root)))

        access.environment = mapOf("HOME" to home.toString(), "ANSIBLE_ASK_VAULT_PASS" to "no")
        registry.invalidate()
        assertEquals("the environment wins", listOf("dev PASSWORD_FILE ANSIBLE_CFG dev.pw"), rows(registry.discovery(root)))
    }

    fun testEnvLocalSkelDefaultIsUsedWithoutEnvLocal() {
        val heron = projectRoot("heron")
        write("$heron/.env.local.skel", "export ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=\".vault-pass\"\n")
        write("$heron/.vault-pass", "${VaultVectors.PW1}\n")
        assertEquals(listOf("default PASSWORD_FILE ENV_LOCAL_SKEL .vault-pass"), rows(registry.discovery(root(heron))))
        assertEmpty(access.secretReads)
    }

    fun testNothingFoundPromptsForTheDefaultIdentity() {
        val thrush = projectRoot("thrush")
        assertEquals(listOf("default PROMPT PROMPT null"), rows(registry.discovery(root(thrush))))

        val team = projectRoot("team", "[defaults]\nvault_identity = team\n")
        assertEquals(listOf("team PROMPT PROMPT null"), rows(registry.discovery(root(team))))
    }

    fun testEnvLocalWithoutAnyExpectedFileStillCountsAsSource() {
        val pelican = projectRoot("pelican")
        write("$pelican/.env.local", "ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=secrets/pw\n")
        assertEquals(listOf("default PASSWORD_FILE ENV_LOCAL .env.local"), rows(registry.discovery(root(pelican))))
    }

    fun testNestedPlaybookRootInheritsItsParentsIds() {
        val pelican = projectRoot("pelican")
        write("$pelican/.vault-pass", "${VaultVectors.PW1}\n")
        write("$pelican/danger_zone/database/playbook-clone.yml", "- hosts: all\n  roles: [db]\n")
        write("$pelican/danger_zone/database/roles/db/tasks/main.yml", "- ansible.builtin.ping:\n")
        val parent = root(pelican)
        val nested = root("$pelican/danger_zone/database")
        assertSame(parent.dir, registry.discovery(nested).root.dir)

        val config = VaultStatusService.getInstance(project).config(nested)
        assertEquals(listOf("default"), config.identities.map { it.label })
        assertEquals(registry.rootKey(parent), config.identities.single().inheritedFrom)
        assertNull(VaultStatusService.getInstance(project).config(parent).identities.single().inheritedFrom)
    }

    fun testExecutablePasswordFilesAreScriptsAndNeverRun() {
        val falcon = projectRoot("falcon", "[defaults]\nvault_identity_list = dev@bin/vault-client.sh\nvault_password_file = bin/pw.sh\n")
        val marker = base.resolve("ran")
        for (script in listOf("bin/vault-client.sh", "bin/pw.sh")) {
            val path = write("$falcon/$script", "#!/bin/sh\ntouch '$marker'\necho ${VaultVectors.PW1}\n")
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        val root = root(falcon)
        assertEquals(
            listOf("dev CLIENT_SCRIPT ANSIBLE_CFG bin/vault-client.sh", "default SCRIPT ANSIBLE_CFG bin/pw.sh"),
            rows(registry.discovery(root)),
        )
        val result = await { secrets.unlock(root, interactive = false) }
        assertEquals(de.terletzkiy.ansibility.api.VaultUnlockResult.Failed(de.terletzkiy.ansibility.api.VaultFailure.NOT_TRUSTED), result)
        assertFalse("no script ran", Files.exists(marker))
        assertEmpty(access.secretReads)
    }

    fun testANewConventionalFileInvalidatesTheDiscovery() {
        val thrush = projectRoot("thrush")
        val root = root(thrush)
        assertEquals(VaultSourceKind.PROMPT, registry.discovery(root).identities.single().source.kind)
        val stamp = registry.modificationTracker.modificationCount

        write("$thrush/.vault-pass", "${VaultVectors.PW1}\n")
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(base.resolve(thrush))!!
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)

        assertTrue(registry.modificationTracker.modificationCount != stamp)
        assertEquals(VaultSourceOrigin.CONVENTIONAL_NAME, registry.discovery(root).identities.single().source.origin)
    }

    fun testEnvLocalReaderTakesOnlyTheVaultKeys() {
        fun read(text: String, env: Map<String, String> = emptyMap()) = EnvLocalFile.passwordFile(text.toByteArray(), env)
        assertEquals(".vault-pass", read("A=1\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n"))
        assertEquals("p w", read("export ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=\"p w\" # comment\r\n"))
        assertEquals("x", read("  ANSIBLE_LOCAL_VAULT_PASSWORD_FILE='x'\n"))
        assertEquals("second", read("ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=first\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=second\n"))
        assertEquals("fallback", read("ANSIBLE_VAULT_PASSWORD_FILE=fallback\n"))
        assertEquals("local", read("ANSIBLE_VAULT_PASSWORD_FILE=other\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=local\n"))
        assertEquals("/h/pw", read("ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=\$HOME/pw\n", mapOf("HOME" to "/h")))
        assertEquals("\$UNKNOWN/pw", read("ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=\$UNKNOWN/pw\n"))
        assertNull(read("ANSIBLE_LOCAL_VAULT_PASSWORD_FILE = spaced\n"))
        assertNull(read("# ANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\n"))
        assertNull(read("XANSIBLE_LOCAL_VAULT_PASSWORD_FILE=.vault-pass\nANSIBLE_LOCAL_VAULT_PASSWORD_FILE=\n"))
    }
}
