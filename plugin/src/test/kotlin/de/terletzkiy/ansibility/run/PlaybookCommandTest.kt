package de.terletzkiy.ansibility.run

import de.terletzkiy.ansibility.semantics.vault.LabelledSecret
import de.terletzkiy.ansibility.semantics.vault.SecretBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class PlaybookCommandTest {
    private val root = Path.of("/work/repos/falcon/ansible")
    private val playbook = root.resolve("playbook-site.yml")
    private val inventory = root.resolve("environments/dev/hosts.yml")

    @Test
    fun `the ansible arguments follow the spec`() {
        val spec = PlaybookRunSpec(
            playbook = playbook.toString(),
            limit = " web,db:!old ",
            tags = "nginx, certs ,",
            skipTags = "slow",
            extraVars = "ansible_user=deploy\n\n# comment\n{\"a\": 1}\n",
            check = true,
            diff = true,
            verbosity = 9,
            additionalArgs = "--forks 5 --start-at-task 'Install packages'",
        )
        assertEquals(
            listOf(
                "playbook-site.yml", "-i", "environments/dev/hosts.yml", "--limit", "web,db:!old", "--tags", "nginx,certs",
                "--skip-tags", "slow", "-e", "ansible_user=deploy", "-e", "{\"a\": 1}", "--check", "--diff", "-vvvv",
                "--become-password-file", "/s/become", "--forks", "5", "--start-at-task", "Install packages",
            ),
            PlaybookCommand.ansibleArguments(spec, "playbook-site.yml", listOf("environments/dev/hosts.yml"), "/s/become"),
        )
        assertEquals(listOf("p.yml"), PlaybookCommand.ansibleArguments(PlaybookRunSpec(playbook = "p.yml"), "p.yml", emptyList(), null))
    }

    @Test
    fun `a native run starts in the root with relative paths and the tool first on PATH`() {
        val process = PlaybookCommand.native(
            Path.of("/opt/ansible/bin/ansible-playbook"),
            PlaybookRunSpec(playbook = playbook.toString()),
            root,
            playbook,
            listOf(inventory, Path.of("/elsewhere/hosts.ini")),
            RunSecrets.NONE,
            "/usr/bin:/bin",
        )
        assertEquals(
            listOf("/opt/ansible/bin/ansible-playbook", "playbook-site.yml", "-i", "environments/dev/hosts.yml", "-i", "/elsewhere/hosts.ini"),
            process.command,
        )
        assertEquals(root, process.workDir)
        assertEquals("/opt/ansible/bin:/usr/bin:/bin", process.environment["PATH"])
        assertEquals("1", process.environment["ANSIBLE_FORCE_COLOR"])
        assertNull("no vault override without secrets", process.environment["ANSIBLE_VAULT_IDENTITY_LIST"])
    }

    @Test
    fun `a docker run passes the secrets by name, mounts the scripts and points the compose variables at them`() {
        val base = Files.createTempDirectory("ansibility-run-test")
        try {
            val secrets = RunSecrets.create(base, listOf(secret("prod", "p"), secret("dev", "d")), "sudo".toCharArray())
            val compose = Path.of("/work/repos/falcon/docker-compose.ansible-playbook.yaml")
            val target = DockerTarget(
                composeFile = compose,
                service = "ansible-playbook",
                mounts = listOf(DockerTarget.Mount(Path.of("/work/repos/falcon"), "/ansible")),
                vaultFileVariable = "LOCAL_VAULT_FILE",
                sshSocketVariable = "AGENT_SOCK",
            )
            val process = PlaybookCommand.docker(
                Path.of("/usr/local/bin/docker"), target, PlaybookRunSpec(playbook = playbook.toString(), check = true),
                root, playbook, listOf(inventory), secrets,
                composeEnvironment = mapOf("KNOWN_HOSTS" to "/home/u/.ssh/known_hosts"), inherited = emptyMap(), macOs = true,
            )!!
            val dir = secrets.dir!!
            assertEquals(
                listOf(
                    "/usr/local/bin/docker", "compose", "-f", compose.toString(), "run", "--rm", "-T",
                    "--entrypoint", "ansible-playbook", "-w", "/ansible/ansible", "-v", "$dir:/ansibility-run:ro",
                    "-e", "ANSIBILITY_VAULT_prod", "-e", "ANSIBILITY_VAULT_dev", "-e", "ANSIBILITY_VAULT_FALLBACK",
                    "-e", "ANSIBILITY_BECOME_PASSWORD", "-e", "ANSIBLE_VAULT_IDENTITY_LIST", "-e", "ANSIBLE_VAULT_PASSWORD_FILE",
                    "-e", "ANSIBLE_FORCE_COLOR", "-e", "PYTHONUNBUFFERED",
                    "ansible-playbook", "playbook-site.yml", "-i", "environments/dev/hosts.yml", "--check",
                    "--become-password-file", "/ansibility-run/ansibility-become-pass",
                ),
                process.command,
            )
            assertEquals(compose.parent, process.workDir)
            val env = process.environment
            assertEquals("p", env["ANSIBILITY_VAULT_prod"])
            assertEquals("prod@/ansibility-run/ansibility-vault-client,dev@/ansibility-run/ansibility-vault-client", env["ANSIBLE_VAULT_IDENTITY_LIST"])
            assertEquals("/ansibility-run/ansibility-vault-client", env["ANSIBLE_VAULT_PASSWORD_FILE"])
            assertEquals(dir.resolve("ansibility-vault-client").toString(), env["LOCAL_VAULT_FILE"])
            assertEquals(PlaybookCommand.MAC_SSH_AGENT, env["AGENT_SOCK"])
            assertEquals("/home/u/.ssh/known_hosts", env["KNOWN_HOSTS"])

            val linux = PlaybookCommand.docker(
                Path.of("docker"), target, PlaybookRunSpec(playbook = playbook.toString()), root, playbook, emptyList(), secrets,
                emptyMap(), mapOf("AGENT_SOCK" to "/tmp/agent"), macOs = false,
            )!!
            assertNull("the agent variable is only set for Docker Desktop on macOS", linux.environment["AGENT_SOCK"])

            val unmounted = target.copy(mounts = listOf(DockerTarget.Mount(Path.of("/other"), "/ansible")))
            assertNull(PlaybookCommand.docker(Path.of("docker"), unmounted, PlaybookRunSpec(playbook = ""), root, playbook, emptyList(), secrets, emptyMap(), emptyMap(), false))
            secrets.close()
            assertFalse(Files.exists(dir))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the client script prints the password of the id it is asked for`() {
        val base = Files.createTempDirectory("ansibility-run-test")
        try {
            val secrets = RunSecrets.create(base, listOf(secret("prod", "prod pass"), secret("team-x", "x'\"\$y")), "become!".toCharArray())
            val client = secrets.vaultClient!!
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(secrets.dir!!))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(client))
            assertFalse("no secret is written to disk", Files.readString(client).contains("prod pass"))

            assertEquals("prod pass", run(client, secrets.environment, "--vault-id", "prod"))
            assertEquals("x'\"\$y", run(client, secrets.environment, "--vault-id=team-x"))
            assertEquals("unknown and missing ids fall back to the first secret", "prod pass", run(client, secrets.environment, "--vault-id", "other"))
            assertEquals("prod pass", run(client, secrets.environment))
            assertEquals("become!", run(secrets.becomeScript!!, secrets.environment))
            assertEquals("ANSIBILITY_VAULT_team_x", RunSecrets.variableOf("team-x"))
            secrets.close()
        } finally {
            base.toFile().deleteRecursively()
        }
        assertTrue(RunSecrets.create(base, emptyList(), null) === RunSecrets.NONE)
    }

    @Test
    fun `env files are read like a sourced shell file`() {
        val text = """
            |# comment
            |export A=one
            |B="${'$'}A two"
            |C='${'$'}A literal'
            |D=~/known_hosts # trailing
            |E=${'$'}{HOME_DIR}/x
            |not a line
            |""".trimMargin()
        assertEquals(
            mapOf("A" to "one", "B" to "one two", "C" to "\$A literal", "D" to "/home/u/known_hosts", "E" to "/h/x"),
            EnvFiles.parse(text, mapOf("HOME_DIR" to "/h"), "/home/u"),
        )
    }

    @Test
    fun `production-like environments are recognised`() {
        listOf("prod", "production", "eu-prod", "PRD", "live").forEach { assertTrue(it, PlaybookPreparation.isProductionLike(it)) }
        listOf("dev", "staging", "preprod", "product", "delivery").forEach { assertFalse(it, PlaybookPreparation.isProductionLike(it)) }
    }

    @Test
    fun `services are ranked playbook first and other ansible tools last`() {
        assertEquals(0, DockerTargets.rank("ansible-playbook"))
        assertEquals(1, DockerTargets.rank("ansible"))
        assertEquals(2, DockerTargets.rank("tools"))
        assertEquals(3, DockerTargets.rank("ansible-lint"))
        assertEquals(3, DockerTargets.rank("ansible-molecule"))
    }

    private fun secret(label: String, value: String) = LabelledSecret(label, SecretBytes.of(value.toByteArray()))

    private fun run(script: Path, environment: Map<String, String>, vararg args: String): String {
        val builder = ProcessBuilder(listOf(script.toString()) + args).redirectErrorStream(false)
        builder.environment().putAll(environment)
        val process = builder.start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        return out.removeSuffix("\n")
    }
}
