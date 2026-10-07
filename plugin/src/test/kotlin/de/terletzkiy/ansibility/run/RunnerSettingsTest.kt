package de.terletzkiy.ansibility.run

import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.EnvLocalImport
import de.terletzkiy.ansibility.run.settings.JumpHost
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The runner settings of a root and what they add to a run: connection, variables, the vault placeholder, targets. */
class RunnerSettingsTest {
    private val jump = RunnerRootSettings(
        remoteUser = "jane",
        jumpHost = JumpHost(enabled = true, host = "proxy.example.test", port = "42022", forwardAgent = true, extraArgs = "-o StrictHostKeyChecking=no"),
        skipHostKeyChecking = true,
    )

    @Test
    fun `the jump host becomes a ProxyCommand in ansible_ssh_common_args, like the provisioning scripts build it`() {
        assertEquals("ssh -A -p 42022 -o StrictHostKeyChecking=no -W %h:%p jane@proxy.example.test", RunConnection.proxyCommand(jump))
        assertEquals(
            "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null " +
                "-o ProxyCommand=\"ssh -A -p 42022 -o StrictHostKeyChecking=no -W %h:%p jane@proxy.example.test\"",
            RunConnection.sshCommonArgs(jump),
        )
        assertEquals(
            "{\"ansible_user\":\"jane\",\"ansible_ssh_common_args\":\"-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null " +
                "-o ProxyCommand=\\\"ssh -A -p 42022 -o StrictHostKeyChecking=no -W %h:%p jane@proxy.example.test\\\"\"}",
            RunConnection.extraVarsArgument(jump),
        )

        val ownJumpUser = jump.copy(jumpHost = jump.jumpHost.copy(user = "gate", port = "", forwardAgent = false, extraArgs = ""))
        assertEquals("ssh -W %h:%p gate@proxy.example.test", RunConnection.proxyCommand(ownJumpUser))
        assertEquals("ssh -W %h:%p proxy.example.test", RunConnection.proxyCommand(RunnerRootSettings(jumpHost = JumpHost(enabled = true, host = "proxy.example.test"))))
        assertNull("a disabled jump host adds nothing", RunConnection.proxyCommand(jump.copy(jumpHost = jump.jumpHost.copy(enabled = false))))
        assertNull("default settings change nothing", RunConnection.extraVarsArgument(RunnerRootSettings()))
        assertEquals("{\"ansible_user\":\"jane\"}", RunConnection.extraVarsArgument(RunnerRootSettings(remoteUser = " jane ")))

        val quotes = RunnerRootSettings(jumpHost = JumpHost(enabled = true, host = "h", extraArgs = "-o \"ProxyJump=a\\b\""))
        assertEquals("-o ProxyCommand=\"ssh -o \\\"ProxyJump=a\\\\b\\\" -W %h:%p h\"", RunConnection.sshCommonArgs(quotes))
    }

    @Test
    fun `the connection goes before the run's own extra vars, which may override it`() {
        val args = PlaybookCommand.ansibleArguments(PlaybookRunSpec(playbook = "p.yml", extraVars = "ansible_user=root"), "p.yml", emptyList(), null, "{\"ansible_user\":\"jane\"}")
        assertEquals(listOf("p.yml", "-e", "{\"ansible_user\":\"jane\"}", "-e", "ansible_user=root"), args)
    }

    @Test
    fun `a provisioning env file is imported as the connection and the compose variables the services use`() {
        val values = mapOf(
            "ANSIBLE_USER" to "jane",
            "ANSIBLE_PORT" to "42022",
            "SSH_JUMP_HOST_ENABLED" to "true",
            "SSH_JUMP_HOST_USER" to "jane",
            "SSH_JUMP_HOST_HOSTNAME" to "proxy.example.test",
            "SSH_JUMP_HOST_PORT" to "42022",
            "SSH_JUMP_HOST_FORWARD_AGENT" to "yes",
            "SSH_JUMP_HOST_EXTRA_ARGS" to "-o StrictHostKeyChecking=no",
            "LOCAL_VAULT_FILE" to ".vault-pass",
            "KNOWN_HOSTS" to "/home/jane/.ssh/known_hosts",
        )
        val imported = EnvLocalImport.apply(RunnerRootSettings(), values, listOf("KNOWN_HOSTS", "LOCAL_VAULT_FILE", "AGENT_SOCK"), ignored = listOf("LOCAL_VAULT_FILE", "AGENT_SOCK"))
        assertEquals("jane", imported.remoteUser)
        assertEquals(JumpHost(true, "", "proxy.example.test", "42022", true, "-o StrictHostKeyChecking=no"), imported.jumpHost)
        assertTrue("the scripts always skip host key checks", imported.skipHostKeyChecking)
        assertEquals(mapOf("KNOWN_HOSTS" to "/home/jane/.ssh/known_hosts"), imported.composeVariables)
        assertEquals(RunConnection.proxyCommand(jump), RunConnection.proxyCommand(imported))

        val none = EnvLocalImport.apply(RunnerRootSettings(remoteUser = "kept"), mapOf("OTHER" to "x"), emptyList())
        assertEquals("kept", none.remoteUser)
        assertFalse(EnvLocalImport.hasConnection(mapOf("OTHER" to "x")))
    }

    @Test
    fun `the become source of an environment falls back to the one for all environments`() {
        val settings = RunnerRootSettings(
            become = mapOf(
                RunnerRootSettings.ALL_ENVIRONMENTS to BecomeSource(VaultSourceKind.PROMPT),
                "prod" to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/sudo/password"),
            ),
        )
        assertEquals(VaultSourceKind.ONE_PASSWORD, settings.becomeSource("prod")!!.kind)
        assertEquals("prod", settings.becomeScope("prod"))
        assertEquals(VaultSourceKind.PROMPT, settings.becomeSource("dev")!!.kind)
        assertEquals(RunnerRootSettings.ALL_ENVIRONMENTS, settings.becomeScope("dev"))
        assertNull(RunnerRootSettings().becomeSource(null))
    }

    @Test
    fun `runner settings survive a save and a load`() {
        val settings = jump.copy(
            become = mapOf("*" to BecomeSource(VaultSourceKind.PASSWORD_SAFE), "prod" to BecomeSource(VaultSourceKind.ENVIRONMENT, "SUDO_PROD")),
            composeVariables = mapOf("KNOWN_HOSTS" to "~/.ssh/known_hosts"),
            environmentVariables = mapOf("ANSIBLE_FORKS" to "20", "EMPTY" to ""),
            checkFreshness = false,
            freshnessBranch = "origin/develop",
            runMetadata = false,
        )
        val stored = RunnerSettings()
        stored.update("repos/falcon/ansible") { settings }
        stored.update("other") { RunnerRootSettings(remoteUser = "x") }
        stored.update("other") { RunnerRootSettings.DEFAULT }
        val loaded = RunnerSettings()
        loaded.loadState(stored.state)
        assertEquals(settings, loaded.rootSettings("repos/falcon/ansible"))
        assertEquals("default settings are not stored", 1, stored.state.roots.size)
    }

    @Test
    fun `targets match by name, and by index only where the names are empty`() {
        val play = PlaybookTarget.play(2, "System")
        assertTrue(play.sameAs(PlaybookTarget.play(5, "System")))
        assertFalse(play.sameAs(PlaybookTarget.play(2, "Proxy")))
        assertTrue(PlaybookTarget.play(1, "").sameAs(PlaybookTarget.play(1, "")))
        assertFalse(PlaybookTarget.play(1, "").sameAs(PlaybookTarget.play(2, "")))
        assertFalse(play.sameAs(PlaybookTarget.PLAYBOOK))
        assertTrue(PlaybookTarget.role(2, "System", 0, "nginx").sameAs(PlaybookTarget.role(2, "System", 3, "nginx")))
        assertFalse(PlaybookTarget.role(2, "System", 0, "nginx").sameAs(PlaybookTarget.role(2, "Web", 0, "nginx")))

        assertEquals("playbook-site.yml", AnsiblePlaybookConfiguration.nameFor(PlaybookRunSpec(playbook = "/r/playbook-site.yml")))
        assertEquals("playbook-site.yml › System [prod]", AnsiblePlaybookConfiguration.nameFor(PlaybookRunSpec("/r/playbook-site.yml", play, environment = "prod")))
        assertEquals("playbook-site.yml › #3", AnsiblePlaybookConfiguration.nameFor(PlaybookRunSpec("/r/playbook-site.yml", PlaybookTarget.play(2, ""))))
        assertEquals(
            "playbook-site.yml › nginx",
            AnsiblePlaybookConfiguration.nameFor(PlaybookRunSpec("/r/playbook-site.yml", PlaybookTarget.role(0, "Web", 1, "nginx"))),
        )
    }

    @Test
    fun `a compose run gets the runner's variables, a vault placeholder and the known_hosts default, and reports what stays unset`() {
        val root = Path.of("/work/repos/falcon/ansible")
        val playbook = root.resolve("playbook-site.yml")
        val base = Files.createTempDirectory("ansibility-run-test")
        try {
            val secrets = RunSecrets.create(base, emptyList(), null, vaultPlaceholder = true)
            assertNull(secrets.vaultClient)
            val placeholder = secrets.vaultPlaceholder!!
            val target = DockerTarget(
                composeFile = Path.of("/work/repos/falcon/docker-compose.ansible-playbook.yaml"),
                service = "ansible-playbook",
                mounts = listOf(DockerTarget.Mount(Path.of("/work/repos/falcon"), "/ansible")),
                vaultFileVariable = "LOCAL_VAULT_FILE",
                variables = listOf(
                    DockerTarget.VolumeVariable("KNOWN_HOSTS", "/root/.ssh/known_hosts", hasDefault = false),
                    DockerTarget.VolumeVariable("LOCAL_VAULT_FILE", "/.vaultpass", hasDefault = false),
                    DockerTarget.VolumeVariable("AGENT_SOCK", "/root/.ssh/agent", hasDefault = true),
                    DockerTarget.VolumeVariable("CERTS", "/certs", hasDefault = false),
                ),
            )
            val additions = RunAdditions("{\"ansible_user\":\"jane\"}", mapOf("GIT_BRANCH" to "main"), mapOf("CERTS" to "/home/jane/certs"))
            val process = PlaybookCommand.docker(
                Path.of("docker"), target, PlaybookRunSpec(playbook = playbook.toString()), root, playbook, emptyList(), secrets,
                emptyMap(), emptyMap(), macOs = false, additions = additions, knownHosts = "/home/jane/.ssh/known_hosts",
            )!!
            assertEquals(placeholder.toString(), process.environment["LOCAL_VAULT_FILE"])
            assertEquals("/home/jane/.ssh/known_hosts", process.environment["KNOWN_HOSTS"])
            assertEquals("/home/jane/certs", process.environment["CERTS"])
            assertEquals("main", process.environment["GIT_BRANCH"])
            assertTrue(process.command.containsAll(listOf("-e", "GIT_BRANCH", "{\"ansible_user\":\"jane\"}")))
            assertFalse("compose variables are for interpolation only", process.command.contains("CERTS"))
            assertEquals(emptyList<String>(), PlaybookCommand.missingVariables(target, process, emptyMap()))

            val bare = PlaybookCommand.docker(Path.of("docker"), target, PlaybookRunSpec(playbook = ""), root, playbook, emptyList(), secrets, emptyMap(), emptyMap(), false)!!
            assertEquals(listOf("KNOWN_HOSTS", "CERTS"), PlaybookCommand.missingVariables(target, bare, emptyMap()))
            assertEquals(listOf("KNOWN_HOSTS"), PlaybookCommand.missingVariables(target, bare, mapOf("CERTS" to "/c")))

            val run = ProcessBuilder(placeholder.toString()).start()
            assertTrue(run.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, run.exitValue())
            assertEquals("ansibility-no-vault-password\n", run.inputStream.readBytes().toString(Charsets.UTF_8))
            secrets.close()

            val withSecret = RunSecrets.create(base, listOf(LabelledSecret("prod", SecretBytes.of("p".toByteArray()))), null, vaultPlaceholder = true)
            assertNull("an unlocked id needs no placeholder", withSecret.vaultPlaceholder)
            withSecret.close()
            assertTrue(RunSecrets.create(base, emptyList(), null, vaultPlaceholder = false) === RunSecrets.NONE)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `docker targets record the variables of their volumes`() {
        val services = listOf(
            de.terletzkiy.ansibility.model.container.ComposeVolumes.Service(
                "ansible-playbook",
                listOf(
                    de.terletzkiy.ansibility.model.container.ComposeVolumes.Volume("./", "/ansible"),
                    de.terletzkiy.ansibility.model.container.ComposeVolumes.Volume("\$KNOWN_HOSTS", "/root/.ssh/known_hosts"),
                    de.terletzkiy.ansibility.model.container.ComposeVolumes.Volume("\${AGENT_SOCK-\${SSH_AUTH_SOCK}}", "/root/.ssh/agent"),
                    de.terletzkiy.ansibility.model.container.ComposeVolumes.Volume("\${CERTS:-/etc/certs}", "/certs"),
                    de.terletzkiy.ansibility.model.container.ComposeVolumes.Volume("\${VAULT}", "/.vaultpass"),
                ),
                mapOf("ANSIBLE_VAULT_PASSWORD_FILE" to "/.vaultpass", "SSH_AUTH_SOCK" to "/root/.ssh/agent"),
            ),
        )
        val target = DockerTargets.of(Path.of("/w/docker-compose.yaml"), services, Path.of("/w/ansible"), null).single()
        assertEquals(
            listOf(
                DockerTarget.VolumeVariable("KNOWN_HOSTS", "/root/.ssh/known_hosts", false),
                DockerTarget.VolumeVariable("AGENT_SOCK", "/root/.ssh/agent", true),
                DockerTarget.VolumeVariable("CERTS", "/certs", true),
                DockerTarget.VolumeVariable("VAULT", "/.vaultpass", false),
            ),
            target.variables,
        )
        assertEquals("VAULT", target.vaultFileVariable)
        assertEquals("AGENT_SOCK", target.sshSocketVariable)
    }
}
