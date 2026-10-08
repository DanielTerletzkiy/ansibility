package de.terletzkiy.ansibility.run

import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.JumpHost
import de.terletzkiy.ansibility.run.settings.RunNotificationSettings
import de.terletzkiy.ansibility.run.settings.RunnerConfigurable
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import javax.swing.JEditorPane

/**
 * The Runner settings page: it lists the roots, imports a provisioning `.env.local` and stores the result on Apply; its
 * notification settings hold for every root (plan amendment R19, D147).
 */
class RunnerConfigurableTest : VaultTestCase() {
    override fun tearDown() {
        try {
            RunnerSettings.getInstance(project).loadState(RunnerSettings.StateBean())
            RunNotificationSettings.getInstance().loadState(RunNotificationSettings.Options())
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

    fun testTheMoleculeGroupPointsToTheRunMoleculeTestsSwitch() {
        // Plan amendment R20, D150: whether Molecule runs are offered at all is a project setting, not a root's.
        val falcon = projectRoot("falcon")
        val configurable = RunnerConfigurable(project, RootKeys.keyOf(project, root(falcon).dir))
        try {
            val component = configurable.createComponent()!!
            // The panes hold HTML, wrapped at will: compare with the whitespace collapsed.
            val comments = UIUtil.findComponentsOfType(component, JEditorPane::class.java).map { it.text.replace(Regex("\\s+"), " ") }
            val pointer = message("settings.runner.molecule.switch")
            assertTrue(pointer, pointer.contains("Run Molecule tests"))
            assertEquals(comments.joinToString("\n"), 1, comments.count { it.contains("Run Molecule tests") && it.contains("Ansibility") })
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testTheNotificationSettingsHoldForEveryRootAndApplyOnTheirOwn() {
        val falcon = projectRoot("falcon")
        val tern = projectRoot("tern")
        val falconKey = RootKeys.keyOf(project, root(falcon).dir)
        val ternKey = RootKeys.keyOf(project, root(tern).dir)
        val stored = RunNotificationSettings.getInstance()
        val configurable = RunnerConfigurable(project, falconKey)
        try {
            configurable.createComponent()
            configurable.reset()
            PlatformTestUtil.waitWithEventsDispatching("the roots load", { configurable.shownRootKey() != null }, 20)
            val (whenToNotify, passed) = configurable.notificationFieldsForTests()
            assertEquals("the default", RunNotificationSettings.When.NOT_IN_VIEW, whenToNotify.item)
            assertTrue(passed.isSelected)
            assertFalse(configurable.isModified)

            whenToNotify.item = RunNotificationSettings.When.ALWAYS
            passed.isSelected = false
            assertTrue(configurable.isModified)
            configurable.selectRootForTests(ternKey)
            assertEquals("another root shows the same", RunNotificationSettings.When.ALWAYS, whenToNotify.item)
            configurable.apply()
            assertEquals(RunNotificationSettings.When.ALWAYS, stored.whenToNotify)
            assertFalse(stored.notifyPassed)
            assertEquals("no root changed", RunnerRootSettings.DEFAULT, RunnerSettings.getInstance(project).rootSettings(ternKey))
            assertFalse(configurable.isModified)

            whenToNotify.item = RunNotificationSettings.When.NEVER
            configurable.reset()
            assertEquals("reset shows the stored value", RunNotificationSettings.When.ALWAYS, whenToNotify.item)
            assertFalse(passed.isSelected)

            // A root form that cannot be stored does not hold them back.
            val fields = configurable.becomeFieldsForTests()
            fields.kind.selectedItem = VaultSourceKind.ONE_PASSWORD
            fields.location.text = "not a reference"
            whenToNotify.item = RunNotificationSettings.When.NEVER
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertEquals(RunNotificationSettings.When.NEVER, stored.whenToNotify)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
