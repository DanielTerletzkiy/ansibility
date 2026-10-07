package de.terletzkiy.ansibility.run

import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.JumpHost
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Running a playbook: the context the dialog starts from, the prepared Docker run with the vault secrets, a role run
 * with the runner settings, the gutter icons.
 */
class PlaybookRunTest : VaultTestCase() {
    private lateinit var falcon: String
    private val playbook: String get() = "$falcon/playbook-site.yml"

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        for (env in listOf("dev", "prod")) {
            write("$falcon/environments/$env/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1.$env:\n")
        }
        write(playbook, "---\n- hosts: web\n  tasks:\n    - name: Install nginx\n      ansible.builtin.debug: {}\n      tags: [nginx]\n")
        write("$falcon/vault.yml", VaultVectors.raw("v03"))
        write(
            "repos/falcon/docker-compose.ansible-playbook.yaml",
            "services:\n  ansible-lint:\n    volumes:\n      - ./:/ansible\n" +
                "  ansible-playbook:\n    environment:\n      - ANSIBLE_VAULT_PASSWORD_FILE=/.vaultpass\n      - SSH_AUTH_SOCK=/root/.ssh/agent\n" +
                "    volumes:\n      - ./:/ansible\n      - \$LOCAL_VAULT_FILE:/.vaultpass\n      - \${AGENT_SOCK-\${SSH_AUTH_SOCK}}:/root/.ssh/agent\n",
        )
    }

    override fun tearDown() {
        try {
            PlaybookPreparation.dockerForTests = null
            PlaybookPreparation.gitForTests = null
            RunnerSettings.getInstance(project).loadState(RunnerSettings.StateBean())
            BecomePasswords.getInstance(project).forgetSession()
        } finally {
            super.tearDown()
        }
    }

    fun testTheContextListsTheEnvironmentsAndTheComposeService() {
        root(falcon)
        val context = await { PlaybookRunContext.collect(project, vf(playbook)) }!!
        assertEquals(listOf("dev", "prod"), context.environments.map { it.id })
        assertEquals(listOf(base.resolve("$falcon/environments/dev/hosts.yml")), context.environment("dev")!!.inventories)
        assertEquals(listOf("web1.dev"), context.environment("dev")!!.hosts)
        assertEquals(listOf("web"), context.environment("dev")!!.groups)
        assertTrue(context.tags.contains("nginx"))

        assertNull("two environments and no selection: the user chooses", context.preselectedEnvironment())
        assertTrue(context.lacksEnvironment(context.initialSpec()))

        assertEquals(listOf("ansible-playbook", "ansible-lint"), context.dockerTargets.map { it.service })
        val target = context.dockerTargets.first()
        assertEquals("LOCAL_VAULT_FILE", target.vaultFileVariable)
        assertEquals("AGENT_SOCK", target.sshSocketVariable)
        assertEquals("/ansible/ansible", target.containerPath(context.rootPath))
        assertEquals(PlaybookExecutor.DOCKER, context.executor(PlaybookRunSpec(playbook = "")))
    }

    fun testTheContextSelectionPreselectsTheEnvironmentAndTheLimit() {
        val root = root(falcon)
        AnsibleContextService.getInstance(project).setSelection(root, RootContext(EnvironmentChoice.Named("prod"), host = "web1.prod"))
        val context = await { PlaybookRunContext.collect(project, vf(playbook)) }!!
        val spec = context.initialSpec()
        assertEquals("prod", spec.environment)
        assertEquals("web1.prod", spec.limit)
        assertFalse(context.lacksEnvironment(spec))
    }

    fun testADockerRunGetsTheUnlockedVaultIdsThroughTheClientScript() {
        val root = root(falcon)
        secrets.storePassword(root, "prod", VaultVectors.PROD.toCharArray())
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        val spec = PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", tags = "nginx", executor = PlaybookExecutor.DOCKER)

        val prepared = await { PlaybookPreparation.prepare(project, spec) }!!
        try {
            val command = prepared.process.command
            assertEquals(listOf("/usr/local/bin/docker", "compose", "-f"), command.take(3))
            assertTrue(command.containsAll(listOf("-w", "/ansible/ansible", "ansible-playbook", "playbook-site.yml", "environments/dev/hosts.yml", "nginx")))
            assertTrue(prepared.header.any { "prod" in it })
            assertEmpty(prompter.passwordRequests)

            val client = prepared.process.environment.getValue("LOCAL_VAULT_FILE")
            val process = ProcessBuilder(client, "--vault-id", "prod").also { it.environment().putAll(prepared.process.environment) }.start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(VaultVectors.PROD + "\n", out)
        } finally {
            prepared.secrets.close()
        }
        assertNull(prepared.secrets.dir?.takeIf { it.toFile().exists() })
    }

    fun testTheGutterRunsPlaybooksOnly() {
        root(falcon)
        myFixture.configureFromExistingVirtualFile(vf(playbook))
        assertTrue(myFixture.findAllGutters().any { it.tooltipText == AnsibilityRunBundle.message("run.gutter.tooltip") })

        myFixture.configureFromExistingVirtualFile(vf("$falcon/environments/dev/hosts.yml"))
        assertFalse(myFixture.findAllGutters().any { it.tooltipText == AnsibilityRunBundle.message("run.gutter.tooltip") })
    }

    fun testTheGutterRunsPlaysAndRoles() {
        root(falcon)
        write("$falcon/playbook-web.yml", "---\n- name: Web\n  hosts: web\n  roles:\n    - common\n    - { role: nginx, tags: [nginx] }\n- hosts: db\n  tasks: []\n")
        myFixture.configureFromExistingVirtualFile(vf("$falcon/playbook-web.yml"))
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }
        for (expected in listOf(
            AnsibilityRunBundle.message("run.gutter.tooltip"),
            AnsibilityRunBundle.message("run.gutter.tooltip.play", "Web"),
            AnsibilityRunBundle.message("run.gutter.tooltip.role", "common"),
            AnsibilityRunBundle.message("run.gutter.tooltip.role", "nginx"),
            AnsibilityRunBundle.message("run.gutter.tooltip.play", "hosts: db"),
        )) {
            assertTrue("$expected in $tooltips", expected in tooltips)
        }
    }

    fun testARoleRunsThePlaybookWithItsTagsAndTheRunnerSettings() {
        val root = root(falcon)
        write("$falcon/playbook-web.yml", "---\n- name: Web\n  hosts: web\n  become: true\n  roles:\n    - common\n    - { role: nginx, tags: [nginx] }\n")
        RunnerSettings.getInstance(project).update(RootKeys.keyOf(project, root.dir)) {
            RunnerRootSettings(
                remoteUser = "jane",
                jumpHost = JumpHost(enabled = true, host = "proxy.example.test", port = "2222", forwardAgent = true),
                become = mapOf(RunnerRootSettings.ALL_ENVIRONMENTS to BecomeSource(VaultSourceKind.PASSWORD_SAFE)),
                environmentVariables = mapOf("ANSIBLE_FORKS" to "3"),
            )
        }
        val rootPath = base.resolve(falcon)
        BecomePasswords.getInstance(project).store(BecomeRoot(rootPath, "falcon"), RunnerRootSettings.ALL_ENVIRONMENTS, "sudo!".toCharArray())
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        PlaybookPreparation.gitForTests = { null }

        val context = await { PlaybookRunContext.collect(project, vf("$falcon/playbook-web.yml")) }!!
        val target = PlaybookTarget.role(0, "Web", 1, "nginx")
        val spec = context.withTarget(PlaybookRunSpec(playbook = vf("$falcon/playbook-web.yml").path, environment = "dev", become = true, executor = PlaybookExecutor.DOCKER), target)
        assertEquals("nginx", spec.tags)
        assertEquals("common", context.withTarget(spec, PlaybookTarget.role(0, "Web", 0, "common")).tags)
        assertEquals("the play: its roles' tags and the one to add", "nginx,common", context.withTarget(spec, PlaybookTarget.play(0, "Web")).tags)
        assertEquals("the whole playbook drops a role's tags", "", context.withTarget(spec, PlaybookTarget.PLAYBOOK).tags)

        val prepared = await { PlaybookPreparation.prepare(project, spec) }!!
        try {
            val command = prepared.process.command
            assertTrue(command.toString(), command.containsAll(listOf("playbook-web.yml", "--tags", "nginx")))
            assertEmpty("no temporary playbook", Files.list(rootPath).use { files -> files.filter { it.fileName.toString().startsWith(".ansibility") }.toList() })
            val connection = command.single { it.startsWith("{\"ansible_user\":\"jane\"") }
            assertTrue(connection, connection.contains("ProxyCommand=\\\"ssh -A -p 2222 -W %h:%p jane@proxy.example.test\\\""))
            assertTrue(command.containsAll(listOf("--become-password-file", "/ansibility-run/ansibility-become-pass", "-e", "ANSIBLE_FORKS", "PROVISION_USER")))
            assertEquals("3", prepared.process.environment["ANSIBLE_FORKS"])
            assertEquals("jane", prepared.process.environment["PROVISION_USER"])
            assertEquals("no vault id unlocked: the vault file mount gets the placeholder",
                prepared.secrets.vaultPlaceholder.toString(), prepared.process.environment["LOCAL_VAULT_FILE"])

            val header = prepared.header.joinToString("\n")
            assertTrue(header, header.contains(AnsibilityRunBundle.message("run.header.target", TargetChoice.describe(target), "nginx")))
            assertTrue(header, header.contains(AnsibilityRunBundle.message("run.header.become", AnsibilityRunBundle.message("become.origin.store"))))
            assertTrue(header, header.contains("via jump host jane@proxy.example.test:2222"))

            val script = prepared.secrets.becomeScript!!
            val process = ProcessBuilder(script.toString()).also { it.environment().putAll(prepared.process.environment) }.start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals("sudo!\n", process.inputStream.readBytes().toString(Charsets.UTF_8))
        } finally {
            prepared.close()
        }
    }

    fun testTheBecomePasswordIsOnWhenASourceIsSetOrThePlayBecomes() {
        val root = root(falcon)
        write("$falcon/playbook-web.yml", "---\n- name: Web\n  hosts: web\n  become: true\n  roles:\n    - common\n")
        val site = await { PlaybookRunContext.collect(project, vf(playbook)) }!!
        assertFalse("no source and no become", site.becomeByDefault(project, "dev", PlaybookTarget.PLAYBOOK))
        val web = await { PlaybookRunContext.collect(project, vf("$falcon/playbook-web.yml")) }!!
        assertTrue("the play becomes", web.becomeByDefault(project, "dev", PlaybookTarget.play(0, "Web")))

        RunnerSettings.getInstance(project).update(RootKeys.keyOf(project, root.dir)) {
            RunnerRootSettings(become = mapOf("prod" to BecomeSource(VaultSourceKind.ONE_PASSWORD, "op://Private/sudo/password")))
        }
        assertTrue("a source for the environment", site.becomeByDefault(project, "prod", PlaybookTarget.PLAYBOOK))
        assertFalse(site.becomeByDefault(project, "dev", PlaybookTarget.PLAYBOOK))
    }

    fun testOlderConfigurationsBecomeAutomatic() {
        fun read(xml: String): AnsiblePlaybookConfiguration =
            (AnsiblePlaybookConfigurationType.getInstance().factory.createTemplateConfiguration(project) as AnsiblePlaybookConfiguration)
                .also { it.readExternal(com.intellij.openapi.util.JDOMUtil.load(xml)) }
        assertNull("an old configuration that stored nothing (off) is automatic now",
            read("<configuration><option name=\"playbook\" value=\"/p.yml\" /></configuration>").spec.become)
        assertEquals(true, read("<configuration><option name=\"become\" value=\"true\" /></configuration>").spec.become)

        val configuration = read("<configuration />")
        configuration.spec = PlaybookRunSpec(playbook = "/p.yml", become = false)
        val element = org.jdom.Element("configuration").also(configuration::writeExternal)
        assertEquals(false, read(com.intellij.openapi.util.JDOMUtil.write(element)).spec.become)
    }

    fun testARunReportsItsEventsBesideTheConfiguredCallbacks() {
        val root = root(falcon)
        write("$falcon/ansible.cfg", "[defaults]\ncallback_plugins = ./team/cb\n")
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        PlaybookPreparation.gitForTests = { null }
        val docker = await { PlaybookPreparation.prepare(project, PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER)) }!!
        try {
            val events = docker.events!!
            assertEquals(32, events.token.length)
            assertEquals(events.token, docker.process.environment["ANSIBILITY_EVENTS_TOKEN"])
            assertEquals("./team/cb:/ansibility-run/callbacks", docker.process.environment["ANSIBLE_CALLBACK_PLUGINS"])
            assertTrue(docker.process.command.containsAll(listOf("-e", "ANSIBILITY_EVENTS_TOKEN", "ANSIBLE_CALLBACK_PLUGINS")))
            assertTrue(Files.readString(docker.secrets.callbackDir!!.resolve("ansibility_events.py")).contains("ansibility_events"))
            assertEquals(base.resolve("$falcon/roles/web/tasks/main.yml"), events.hostPath("/ansible/ansible/roles/web/tasks/main.yml"))
            assertNull(events.hostPath("/elsewhere/x.yml"))
        } finally {
            docker.close()
        }

        PlaybookPreparation.dockerForTests = null
        val native = await {
            PlaybookPreparation.configuredCallbackPlugins(PlaybookRunContext.collect(project, vf(playbook))!!, local = true)
        }
        assertEquals("relative paths of ansible.cfg resolve against its directory for a local run", base.resolve("$falcon/team/cb").toString(), native)

        RunnerSettings.getInstance(project).update(RootKeys.keyOf(project, root.dir)) { RunnerRootSettings(runView = false) }
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        val plain = await { PlaybookPreparation.prepare(project, PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER)) }!!
        try {
            assertNull(plain.events)
            assertNull(plain.process.environment["ANSIBILITY_EVENTS_TOKEN"])
            assertNull(plain.secrets.callbackDir)
        } finally {
            plain.close()
        }
    }
}
