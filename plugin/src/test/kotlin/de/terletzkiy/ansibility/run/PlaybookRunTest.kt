package de.terletzkiy.ansibility.run

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.testFramework.PlatformTestUtil
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.run.become.BecomePasswords
import de.terletzkiy.ansibility.run.become.BecomeRoot
import de.terletzkiy.ansibility.run.settings.BecomeSource
import de.terletzkiy.ansibility.run.settings.JumpHost
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.run.events.RunModel
import de.terletzkiy.ansibility.run.notify.RecordedNotifications
import de.terletzkiy.ansibility.run.notify.RunNotifier
import de.terletzkiy.ansibility.run.view.AnsibleRunConsole
import de.terletzkiy.ansibility.run.view.RunBanner
import de.terletzkiy.ansibility.run.view.RunViewTexts
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import de.terletzkiy.ansibility.settings.RootContext
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Running a playbook: the context the dialog starts from, the prepared Docker run with the vault secrets, a role run
 * with the runner settings, the gutter icons, and the notification of how it ended (plan amendment R19, D147–D149).
 */
class PlaybookRunTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityPlaybookRunTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String
    private val playbook: String get() = "$falcon/playbook-site.yml"
    private lateinit var notifications: RecordedNotifications

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
        notifications = RecordedNotifications(project, testRootDisposable)
    }

    override fun tearDown() {
        try {
            PlaybookPreparation.dockerForTests = null
            PlaybookPreparation.gitForTests = null
            PlaybookLauncher.runOnceForTests = null
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
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

        val prepared = await { PlaybookPreparation.prepareAsync(project, spec) }!!
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

        val prepared = await { PlaybookPreparation.prepareAsync(project, spec) }!!
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
        val docker = await { PlaybookPreparation.prepareAsync(project, PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER)) }!!
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
        val plain = await { PlaybookPreparation.prepareAsync(project, PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER)) }!!
        try {
            assertNull(plain.events)
            assertNull(plain.process.environment["ANSIBILITY_EVENTS_TOKEN"])
            assertNull(plain.secrets.callbackDir)
        } finally {
            plain.close()
        }
    }

    /** A started run as the platform makes it: the configuration's state, executed (the tab), then start-notified. */
    private class Started(val state: AnsibleRunState, val handler: PreparingProcessHandler, val console: AnsibleRunConsole) {
        val stdout = StringBuffer()
        val stderr = StringBuffer()
        val all get() = "$stdout$stderr"
    }

    private fun start(spec: PlaybookRunSpec): Started {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val settings = com.intellij.execution.RunManager.getInstance(project).createConfiguration(AnsiblePlaybookConfiguration.nameFor(spec), AnsiblePlaybookConfigurationType.getInstance().factory)
        (settings.configuration as AnsiblePlaybookConfiguration).spec = spec
        val environment = ExecutionEnvironment(executor, TestRunner, settings, project)
        val before = System.nanoTime()
        val state = settings.configuration.getState(executor, environment) as AnsibleRunState
        val result = state.execute(executor, TestRunner)
        assertTrue("getState and execute prepare nothing", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(5))
        Disposer.register(testRootDisposable, result.executionConsole)
        val handler = result.processHandler as PreparingProcessHandler
        assertFalse("the platform start-notifies it once the tab shows", handler.isStartNotified)
        val started = Started(state, handler, result.executionConsole as AnsibleRunConsole)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType is ProcessOutputType && outputType.isStderr) started.stderr.append(event.text) else started.stdout.append(event.text)
            }
        })
        handler.startNotify()
        return started
    }

    /** Waits for the end, dispatching events: the preparation asks its questions on the EDT. */
    private fun Started.awaitTermination() = PlatformTestUtil.waitWithEventsDispatching("the run did not end", { handler.isProcessTerminated }, 30)

    private fun Started.awaitEnd() {
        awaitTermination()
        PlatformTestUtil.waitWithEventsDispatching("the run's end was not applied", { console.viewForTests()!!.tree.emptyText.text.startsWith("Not started") }, 30)
    }

    fun testARunThatCannotBePreparedSaysSoInItsTab() {
        val root = root(falcon)
        val missing = base.resolve("$falcon/playbook-gone.yml").toString()
        val run = start(PlaybookRunSpec(playbook = missing, environment = "dev"))
        run.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, run.handler.exitCode)
        assertTrue(run.all, "Preparing: reading the playbook and its environments…" in run.stdout)
        val reason = "The playbook $missing does not exist"
        assertEquals("the console has all of it", "Not started: $reason\n", run.stderr.toString())
        val shown = "Not started: " + RunViewTexts.firstLine(reason)
        assertEquals(shown, run.console.viewForTests()!!.tree.emptyText.text)
        val banner = run.state.bannerForTests()
        assertEquals(shown, banner.messageForTests())
        assertEquals(RunBanner.Status.ERROR, banner.statusForTests())
        assertFalse("nothing ran: the Plays tab stays and says why", run.console.viewDroppedForTests())

        // R19: a failure notifies (the platform no longer does: getState never throws).
        val notification = notifications.runs.single()
        assertEquals("playbook-gone.yml [dev]: did not start", notification.title)
        assertEquals("the first line, at most 160 characters", RunViewTexts.firstLine(reason), notification.content)
        assertEquals(listOf("Open Runner Settings", "Run Again", "Show Run"), RecordedNotifications.texts(notification))
        val opened = ArrayList<String?>()
        RunNotifier.getInstance(project).openSettingsForTests = { opened += it }
        notifications.click(notification, "Open Runner Settings")
        assertEquals("the settings of the playbook's root, though the playbook is gone", listOf(RootKeys.keyOf(project, root.dir)), opened)
    }

    fun testACancelledQuestionKeepsTheTabWithoutStarting() {
        root(falcon)
        PlaybookPreparation.gitForTests = { null }
        // The production confirmation, answered with Cancel.
        TestDialogManager.setTestDialog(TestDialog.NO)
        val run = start(PlaybookRunSpec(playbook = vf(playbook).path, environment = "prod", executor = PlaybookExecutor.DOCKER))
        run.awaitEnd()
        assertEquals(RunModel.NOT_STARTED, run.handler.exitCode)
        assertTrue(run.all, "Not started: cancelled\n" in run.stdout)
        assertEquals("a cancel is no error", "", run.stderr.toString())
        assertEquals("Not started: cancelled", run.state.bannerForTests().messageForTests())
        assertEquals(RunBanner.Status.WARNING, run.state.bannerForTests().statusForTests())
        assertFalse(run.handler.stopped)
        assertTrue("the user cancelled: nothing to tell", notifications.runs.isEmpty())
    }

    fun testStopWhileTheBranchIsFetchedEndsTheRunAndTheFetch() {
        val root = root(falcon)
        RunnerSettings.getInstance(project).update(RootKeys.keyOf(project, root.dir)) { RunnerRootSettings(freshnessBranch = "main") }
        val pid = base.resolve("git.pid")
        val git = write(
            "bin/git",
            "#!/bin/sh\ncase \"$1\" in\n  rev-parse) echo true ;;\n  fetch) echo $$ > '$pid'; exec sleep 30 ;;\n  *) exit 0 ;;\nesac\n",
        )
        git.toFile().setExecutable(true)
        PlaybookPreparation.gitForTests = { git }
        val run = start(PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER))
        PlatformTestUtil.waitWithEventsDispatching("git fetch did not start", { Files.exists(pid) && Files.readString(pid).isNotBlank() }, 30)
        assertTrue(run.all, "Preparing: checking that the checkout is up to date with main…" in run.stdout)
        assertTrue(run.handler.isPreparing)
        assertEquals("Preparing: checking that the checkout is up to date with main…", run.state.bannerForTests().messageForTests())
        assertEquals(RunBanner.Status.INFO, run.state.bannerForTests().statusForTests())

        run.handler.destroyProcess()
        assertTrue("Stop does not wait for the fetch", run.handler.waitFor(TimeUnit.SECONDS.toMillis(5)))
        assertEquals(RunModel.NOT_STARTED, run.handler.exitCode)
        assertTrue(run.handler.stopped)
        assertTrue(run.all, "Not started: stopped while preparing" in run.stdout)
        val fetch = ProcessHandle.of(Files.readString(pid).trim().toLong())
        PlatformTestUtil.waitWithEventsDispatching("git fetch still runs", { fetch.map { !it.isAlive }.orElse(true) }, 10)
        run.awaitEnd()
        assertTrue("the user stopped it before it started: nothing to tell", notifications.runs.isEmpty())
    }

    fun testAFailedRunNotifiesWhichHostsFailedAtWhichTask() {
        root(falcon)
        PlaybookPreparation.gitForTests = { null }
        val sentinel = "SENTINEL-77a1"
        // The failure golden's frames, its messages replaced by a sentinel, written by a stand-in docker with the run's token.
        val golden = Files.readString(Path.of("src/test/testData/run-events/failure/2.21.4.jsonl"))
        val frames = write("frames.jsonl", golden.replace("The command exited with a non-zero return code.", "password=$sentinel"))
        assertTrue(sentinel in Files.readString(frames))
        val docker = write(
            "bin/docker",
            "#!/bin/sh\nwhile IFS= read -r line; do printf '\\036%s %s\\n' \"\$ANSIBILITY_EVENTS_TOKEN\" \"\$line\" >&2; done < '$frames'\nexit 2\n",
        )
        docker.toFile().setExecutable(true)
        PlaybookPreparation.dockerForTests = { docker }
        val spec = PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", extraVars = "api_token=$sentinel", executor = PlaybookExecutor.DOCKER)
        val run = start(spec)
        run.awaitTermination()
        assertEquals(2, run.handler.exitCode)
        PlatformTestUtil.waitWithEventsDispatching("the run did not notify", { notifications.runs.isNotEmpty() }, 30)
        val notification = notifications.runs.single()
        assertEquals("playbook-site.yml [dev]: failed on 3 of 3 hosts", notification.title)
        val lines = notification.content.split("<br>")
        assertEquals(listOf("a1 failed at Fails everywhere", "a2 failed at Fails on a2 only", "down.invalid unreachable at Fails on a2 only"), lines.take(3))
        assertTrue(lines.last(), lines.last().startsWith("no host ok · "))
        assertEquals(listOf("Show Failed Task", "Rerun Failed Hosts", "Run Again"), RecordedNotifications.texts(notification))
        for (text in listOf(notification.title, notification.content) + notifications.system.flatMap { listOf(it.first, it.second) }) {
            assertFalse("no value, message or extra variable: $text", sentinel in text)
        }

        val reruns = ArrayList<PlaybookRunSpec>()
        PlaybookLauncher.runOnceForTests = { rerun, _ -> reruns += rerun }
        notifications.click(notification, "Rerun Failed Hosts")
        assertEquals(listOf(spec.copy(limit = "a1,a2,down.invalid")), reruns)
        notifications.click(notification, "Show Failed Task")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("Fails everywhere", run.console.viewForTests()!!.selectedTask()?.name)
    }

    fun testStopAfterTheScriptsWereWrittenDeletesThem() {
        root(falcon)
        // The run metadata come after the scripts: a git that hangs there.
        val pid = base.resolve("git.pid")
        val git = write("bin/git", "#!/bin/sh\ncase \"$1\" in\n  rev-parse) echo $$ > '$pid'; exec sleep 30 ;;\n  *) exit 0 ;;\nesac\n")
        git.toFile().setExecutable(true)
        PlaybookPreparation.gitForTests = { git }
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        val scripts = PlaybookPreparation.secretsBase()
        Files.createDirectories(scripts)
        val before = Files.list(scripts).use { it.toList() }.toSet()
        val run = start(PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", skipFreshnessCheck = true, executor = PlaybookExecutor.DOCKER))
        PlatformTestUtil.waitWithEventsDispatching("git did not start", { Files.exists(pid) && Files.readString(pid).isNotBlank() }, 30)
        assertTrue(run.all, "Preparing: reading the git facts of the run…" in run.stdout)
        val written = Files.list(scripts).use { it.toList() }.filter { it !in before }
        assertEquals("the run's scripts", 1, written.size)
        run.handler.destroyProcess()
        run.awaitTermination()
        PlatformTestUtil.waitWithEventsDispatching("the scripts of the stopped run stay", { !Files.exists(written.single()) }, 10)
    }

    fun testStopWhileTheVaultScriptsAreWrittenDeletesThem() {
        val root = root(falcon)
        val written = CopyOnWriteArrayList<Path>()
        val release = CountDownLatch(1)
        RunSecrets.scriptsWrittenForTests = { secrets ->
            written.add(secrets.dir!!)
            release.await(30, TimeUnit.SECONDS)
        }
        // A caller on another dispatcher than the scripts' step (IO): withContext drops what that step returns to it
        // once it was cancelled, NonCancellable or not.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch { PlaybookPreparation.secretsFor(project, root, ArrayList(), "synthetic-become".toCharArray(), vaultPlaceholder = true, callback = true) }
            PlatformTestUtil.waitWithEventsDispatching("the scripts were not written", { written.isNotEmpty() }, 30)
            // Stop comes while the vault client, the become script and the callback are written.
            job.cancel()
            release.countDown()
            PlatformTestUtil.waitWithEventsDispatching("the preparation did not end", { job.isCompleted }, 30)
            assertTrue(job.isCancelled)
            assertFalse("the scripts written while Stop came are deleted", Files.exists(written.single()))
        } finally {
            RunSecrets.scriptsWrittenForTests = null
            release.countDown()
            scope.cancel()
        }
    }

    fun testAPreparedRunStartsInItsTabAndForgetsItsScripts() {
        root(falcon)
        PlaybookPreparation.gitForTests = { null }
        // A docker that only says it ran.
        val docker = write("bin/docker", "#!/bin/sh\necho 'the stand-in ran'\n")
        docker.toFile().setExecutable(true)
        PlaybookPreparation.dockerForTests = { docker }
        val run = start(PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER))
        run.awaitTermination()
        assertEquals(0, run.handler.exitCode)
        val text = run.stdout.toString()
        val phase = text.indexOf("Preparing: unlocking the vault ids of ")
        val header = text.indexOf("Environment: dev")
        val command = text.indexOf("$docker compose -f")
        assertTrue(text, phase in 0 until header && header < command && command < text.indexOf("the stand-in ran"))
        PlatformTestUtil.waitWithEventsDispatching("the banner did not go", { run.state.bannerForTests().messageForTests() == null }, 30)
        val scripts = Regex("-v (\\S+):/ansibility-run:ro").find(text)!!.groupValues[1]
        assertFalse("the run's scripts are deleted when it ends", Files.exists(Path.of(scripts)))
    }

    // ------------------------------------------------------------------ R19 integration fixes

    fun testSameNamedPlaybooksOfTwoRootsKeepTheirOwnNotifications() {
        val tern = projectRoot("tern")
        write("$tern/environments/dev/hosts.yml", "all:\n  children:\n    web:\n      hosts:\n        web1.dev:\n")
        write("$tern/playbook-site.yml", "---\n- hosts: web\n  tasks:\n    - name: Install nginx\n      ansible.builtin.debug: {}\n")
        write("repos/tern/docker-compose.ansible-playbook.yaml", Files.readString(base.resolve("repos/falcon/docker-compose.ansible-playbook.yaml")))
        root(falcon)
        root(tern)
        PlaybookPreparation.gitForTests = { null }
        // ansible-playbook fails before its first event (a syntax error: exit code 4), with the run view on.
        val docker = write("bin/docker", "#!/bin/sh\nexit 4\n")
        docker.toFile().setExecutable(true)
        PlaybookPreparation.dockerForTests = { docker }
        fun run(playbook: String) {
            val count = notifications.runs.size
            start(PlaybookRunSpec(playbook = vf(playbook).path, environment = "dev", executor = PlaybookExecutor.DOCKER)).awaitTermination()
            PlatformTestUtil.waitWithEventsDispatching("the run of $playbook did not notify", { notifications.runs.size == count + 1 }, 30)
        }
        run(playbook)
        run("$tern/playbook-site.yml")
        val (falconRun, ternRun) = notifications.runs
        // Both configurations are named "playbook-site.yml [dev]": the root tells them apart.
        assertEquals("playbook-site.yml [dev] (falcon): failed before any play ran", falconRun.title)
        assertEquals("playbook-site.yml [dev] (tern): failed before any play ran", ternRun.title)
        assertTrue(ternRun.content, ternRun.content.startsWith("ansible-playbook exited with code 4; the console shows the error"))
        assertFalse("the other root's run leaves it", falconRun.isExpired)

        // A rerun of the same configuration replaces its own notification only.
        run(playbook)
        assertTrue(falconRun.isExpired)
        assertFalse(ternRun.isExpired)
    }
}
