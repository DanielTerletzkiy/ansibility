package de.terletzkiy.ansibility.run

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.JumpHost
import de.terletzkiy.ansibility.run.settings.RunnerConfigurable
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase

/** The Runner settings page: it lists the roots, imports a provisioning `.env.local` and stores the result on Apply. */
class RunnerConfigurableTest : VaultTestCase() {
    override fun tearDown() {
        try {
            RunnerSettings.getInstance(project).loadState(RunnerSettings.StateBean())
        } finally {
            super.tearDown()
        }
    }

    fun testImportFromEnvLocalAndApply() {
        val falcon = projectRoot("falcon")
        write("$falcon/environments/dev/hosts.yml", "all:\n  hosts:\n    web1:\n")
        write(
            "repos/falcon/docker-compose.ansible-playbook.yaml",
            "services:\n  ansible-playbook:\n    environment:\n      - ANSIBLE_VAULT_PASSWORD_FILE=/.vaultpass\n" +
                "    volumes:\n      - ./:/ansible\n      - \$KNOWN_HOSTS:/root/.ssh/known_hosts\n      - \$LOCAL_VAULT_FILE:/.vaultpass\n",
        )
        write(
            "$falcon/.env.local",
            "ANSIBLE_USER=jane\nSSH_JUMP_HOST_ENABLED=true\nSSH_JUMP_HOST_USER=\"\$ANSIBLE_USER\"\nSSH_JUMP_HOST_HOSTNAME=proxy.example.test\n" +
                "SSH_JUMP_HOST_PORT=2222\nSSH_JUMP_HOST_FORWARD_AGENT=yes\nKNOWN_HOSTS=/home/jane/.ssh/known_hosts\nLOCAL_VAULT_FILE=.vault-pass\n",
        )
        val root = root(falcon)
        val key = RootKeys.keyOf(project, root.dir)
        val configurable = RunnerConfigurable(project, key)
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            PlatformTestUtil.waitWithEventsDispatching("the roots load", { configurable.shownRootKey() != null }, 20)
            assertEquals(key, configurable.shownRootKey())
            assertFalse(configurable.isModified)

            configurable.importForTests()
            assertTrue(configurable.isModified)
            configurable.apply()
            val stored = RunnerSettings.getInstance(project).rootSettings(key)
            assertEquals("jane", stored.remoteUser)
            assertEquals(JumpHost(true, "", "proxy.example.test", "2222", true, ""), stored.jumpHost)
            assertTrue(stored.skipHostKeyChecking)
            assertEquals("the vault file is the run's own", mapOf("KNOWN_HOSTS" to "/home/jane/.ssh/known_hosts"), stored.composeVariables)
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testTheBecomePasswordComesFromAPasswordManagerOrTheStore() {
        val falcon = projectRoot("falcon")
        write("$falcon/environments/dev/hosts.yml", "all:\n  hosts:\n    web1:\n")
        val root = root(falcon)
        val key = RootKeys.keyOf(project, root.dir)
        val configurable = RunnerConfigurable(project, key)
        try {
            configurable.createComponent()
            configurable.reset()
            PlatformTestUtil.waitWithEventsDispatching("the roots load", { configurable.shownRootKey() != null }, 20)
            val fields = configurable.becomeFieldsForTests()
            assertEquals("ask is the default", VaultSourceKind.PROMPT, fields.selected())

            fields.kind.selectedItem = VaultSourceKind.ONE_PASSWORD
            fields.location.text = "not a reference"
            assertTrue(configurable.isModified)
            assertThrows(ConfigurationException::class.java) { configurable.apply() }

            fields.location.text = "op://Private/sudo/password"
            configurable.apply()
            assertEquals(
                mapOf(RunnerRootSettings.ALL_ENVIRONMENTS to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/sudo/password")),
                RunnerSettings.getInstance(project).rootSettings(key).become,
            )
            assertFalse(configurable.isModified)

            fields.kind.selectedItem = VaultSourceKind.PASSWORD_SAFE
            assertThrows(ConfigurationException::class.java) { configurable.apply() } // the store needs a password
            fields.password.text = "sudo!"
            configurable.apply()
            assertEquals(BecomeSource(VaultSourceKind.PASSWORD_SAFE), RunnerSettings.getInstance(project).rootSettings(key).become.getValue("*"))
            val becomeRoot = BecomeRoot(base.resolve(falcon), "falcon")
            PlatformTestUtil.waitWithEventsDispatching("the password is stored", { BecomePasswords.getInstance(project).hasStored(becomeRoot, "*") }, 20)
            assertEquals("", String(fields.password.password))
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
