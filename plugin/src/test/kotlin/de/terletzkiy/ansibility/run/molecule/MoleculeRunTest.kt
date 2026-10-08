package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings as PlatformRunnerSettings
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.api.VaultSourceKind
import de.terletzkiy.ansibility.api.VaultUnlockResult
import de.terletzkiy.ansibility.run.AnsibilityRunBundle
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PlaybookPreparation
import de.terletzkiy.ansibility.run.RunSecrets
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.runtime.AnsibleRuntimeOptions
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import de.terletzkiy.ansibility.vault.VaultVectors
import de.terletzkiy.ansibility.vault.identity.ExplicitIdentity
import de.terletzkiy.ansibility.vault.identity.VaultProjectSettings
import de.terletzkiy.ansibility.vault.identity.VaultRootSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Molecule on a role: its scenarios and Compose service, the gutter, the prepared run, the role action, the run
 * configuration and what each button runs.
 */
class MoleculeRunTest : VaultTestCase() {
    private object TestRunner : ProgramRunner<PlatformRunnerSettings> {
        override fun getRunnerId(): String = "AnsibilityMoleculeRunTest"
        override fun canRun(executorId: String, profile: RunProfile): Boolean = true
        override fun execute(environment: ExecutionEnvironment) = Unit
    }

    private lateinit var falcon: String
    private val role: String get() = "$falcon/roles/web"

    override fun setUp() {
        super.setUp()
        falcon = projectRoot("falcon")
        write("$role/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$role/molecule/default/molecule.yml", "---\ndriver:\n  name: docker\nplatforms:\n  - name: web-\${MOLECULE_RUN_ID:-local}\n")
        write("$role/molecule/default/converge.yml", "- name: Converge\n  hosts: all\n  roles: [web]\n")
        write("$role/molecule/default/verify.yml", "- name: Verify\n  hosts: all\n  tasks: []\n")
        write("$role/molecule/notes/README.md", "no scenario here\n")
        write(
            "repos/falcon/docker-compose.ansible-molecule.yaml",
            "services:\n  ansible-molecule:\n    volumes:\n      - ./ansible/roles:/ansible/roles\n      - /var/run/docker.sock:/var/run/docker.sock\n",
        )
        write("repos/falcon/docker-compose.ansible-lint.yaml", "services:\n  ansible-lint:\n    volumes:\n      - ./:/ansible\n")
    }

    override fun tearDown() {
        try {
            PlaybookPreparation.dockerForTests = null
            MoleculeRunContext.moleculeForTests = null
            MoleculeLauncher.executeForTests = null
            MoleculeResults.getInstance(project).resetForTests()
            MoleculeCleanup.getInstance(project).resetForTests()
            RunnerSettings.getInstance(project).loadState(RunnerSettings.StateBean())
            RunManager.getInstance(project).let { manager ->
                manager.getConfigurationSettingsList(MoleculeConfigurationType.getInstance()).forEach(manager::removeConfiguration)
            }
        } finally {
            super.tearDown()
        }
    }

    /** Records what the buttons run, instead of running it. */
    private fun recordRuns(): List<MoleculeSpec> {
        val runs = ArrayList<MoleculeSpec>()
        MoleculeLauncher.executeForTests = { settings, _ -> runs += (settings.configuration as MoleculeConfiguration).spec }
        return runs
    }

    private fun event(action: AnAction, path: String) = TestActionEvent.createTestEvent(
        action, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, vf(path)).build(),
    )

    fun testTheContextFindsTheScenariosAndTheMoleculeService() {
        root(falcon)
        MoleculeRunContext.moleculeForTests = { null }
        val context = await { MoleculeRunContext.collect(project, vf(role)) }!!
        assertEquals(listOf("default"), context.scenarios)
        assertEquals("only services with Molecule in their name", listOf("ansible-molecule"), context.dockerTargets.map { it.service })
        assertEquals(PlaybookExecutor.DOCKER, context.executor(MoleculeSpec(vf(role).path)))
        assertEquals("ansible-molecule", context.dockerTarget(MoleculeSpec(vf(role).path, composeService = "ansible-molecule"))?.service)
        assertNull("a service that does not run Molecule", context.dockerTarget(MoleculeSpec(vf(role).path, composeService = "ansible-lint")))
        assertEquals(vf(role), MoleculeRunContext.roleDirOf(vf("$role/molecule/default/converge.yml")))
        assertEquals("default", MoleculeRunContext.scenarioOf(vf("$role/molecule/default/converge.yml")))
        assertNull(MoleculeRunContext.roleDirOf(vf("$falcon/ansible.cfg")))
        assertNull("a role without scenarios", await { MoleculeRunContext.collect(project, vf("$falcon")) })
    }

    fun testADockerRunGetsTheEventsAndTheRolesRunId() {
        val root = root(falcon)
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        MoleculeRunContext.moleculeForTests = { null }
        val prepared = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE)) }
        try {
            val command = prepared.process.command
            assertTrue(command.toString(), command.containsAll(listOf("--entrypoint", "molecule", "-w", "/ansible/roles/web", "ansible-molecule", "converge", "--scenario-name", "default")))
            val env = prepared.process.environment
            assertEquals("the same for each run of the role", MoleculePreparation.runId(base.resolve(role)), env[MoleculePreparation.RUN_ID])
            assertTrue(env.getValue(MoleculePreparation.RUN_ID).matches(Regex("ansibility-[0-9a-f]{8}")))
            assertFalse("another for another role", MoleculePreparation.runId(base.resolve("$falcon/roles/db")) == env[MoleculePreparation.RUN_ID])
            assertEquals("1", env["PYTHONUNBUFFERED"])
            assertEquals(prepared.events!!.token, env["ANSIBILITY_EVENTS_TOKEN"])
            assertTrue(env.getValue("ANSIBLE_CALLBACK_PLUGINS").endsWith(":/ansibility-run/callbacks"))
            assertTrue(Files.exists(prepared.secrets.callbackDir!!.resolve("ansibility_events.py")))
            assertEquals(base.resolve("$role/tasks/main.yml"), prepared.events!!.hostPath("/ansible/roles/web/tasks/main.yml"))
            assertTrue(prepared.header.any { "role web" in it })
        } finally {
            prepared.close()
        }

        RunnerSettings.getInstance(project).update(RootKeys.keyOf(project, root.dir)) { RunnerRootSettings(environmentVariables = mapOf("MOLECULE_RUN_ID" to "mine")) }
        val own = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "")) }
        try {
            assertEquals("mine", own.process.environment[MoleculePreparation.RUN_ID])
            assertTrue(own.process.command.containsAll(listOf("test", "--all")))
        } finally {
            own.close()
        }
        val error = runCatching { await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "nope")) } }.exceptionOrNull()
        assertTrue(error.toString(), error?.message.orEmpty().contains("nope"))
        val gone = runCatching { await { MoleculePreparation.prepareAsync(project, MoleculeSpec(base.resolve("$falcon/roles/gone").toString())) } }.exceptionOrNull()
        assertTrue(gone.toString(), gone?.message.orEmpty().contains("does not exist"))
        PlaybookPreparation.dockerForTests = { null }
        val noDocker = runCatching { await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "default")) } }.exceptionOrNull()
        assertTrue(noDocker.toString(), noDocker?.message.orEmpty().contains("No docker executable"))
    }

    fun testStopWhileTheScriptsAreWrittenDeletesThem() {
        root(falcon)
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        MoleculeRunContext.moleculeForTests = { null }
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
            val job = scope.launch { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE)) }
            PlatformTestUtil.waitWithEventsDispatching("the scripts were not written", { written.isNotEmpty() }, 30)
            // Stop comes while the scripts are written.
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

    fun testALibraryRoleRunsInTheMoleculeServiceOfTheRepoAboveIt() {
        // A role library (no ansible.cfg) whose roles a Compose file one level up mounts, like a shared roles repo.
        write("library/roles/web/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("library/roles/web/molecule/default/molecule.yml", "---\ndriver:\n  name: docker\n")
        write(
            "docker-compose.ansible-molecule.yaml",
            "services:\n  ansible-molecule:\n    container_name: ansible-molecule-\${MOLECULE_RUN_ID:-local}\n    environment:\n" +
                "      MOLECULE_RUN_ID: \${MOLECULE_RUN_ID:-local}\n    volumes:\n      - ./library/roles:/ansible/roles\n" +
                "      - /var/run/docker.sock:/var/run/docker.sock:z\n",
        )
        root(falcon)
        root("library")
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        MoleculeRunContext.moleculeForTests = { null }
        val prepared = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf("library/roles/web").path)) }
        try {
            val command = prepared.process.command
            assertTrue(command.toString(), command.containsAll(listOf("-w", "/ansible/roles/web", "ansible-molecule", "test", "--all")))
            assertEquals(base.resolve("docker-compose.ansible-molecule.yaml").toString(), command[command.indexOf("-f") + 1])
        } finally {
            prepared.close()
        }
    }

    /** A root with a vault password file and an id in a password manager, its `default` unlocked earlier in the session. */
    private fun vaultedRoot(): String {
        val vaulted = projectRoot("heron")
        write("$vaulted/.vault-pass", "${VaultVectors.PW1}\n")
        write("$vaulted/vars/v01.yml", VaultVectors.raw("v01"))
        write("$vaulted/roles/app/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$vaulted/roles/app/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        val root = root(vaulted)
        VaultProjectSettings.getInstance(project).update(registry.rootKey(root)) {
            VaultRootSettings(identities = listOf(ExplicitIdentity("ops", VaultSourceKind.ONE_PASSWORD, "op://Infra/ops/password")))
        }
        registry.invalidate()
        managerReads.set(0)
        secrets.setPasswordManagersForTests { _, _, _ ->
            managerReads.incrementAndGet()
            null
        }
        assertEquals("unlocked earlier in the session", listOf("default"), (await { secrets.unlock(root, "default") } as VaultUnlockResult.Unlocked).identities)
        access.secretReads.clear()
        access.nonSecretReads.clear()
        prompter.consentRequests.clear()
        prompter.passwordRequests.clear()
        managerReads.set(0)
        return vaulted
    }

    private val managerReads = java.util.concurrent.atomic.AtomicInteger()

    /** Nothing of the vault was asked, read or run: no source, no prompt or consent, no password manager (D136). */
    private fun assertNoVaultCall() {
        assertEmpty("no vault source read", access.secretReads)
        assertEmpty("no prompt", prompter.passwordRequests)
        assertEmpty("no consent", prompter.consentRequests)
        assertEmpty("no master password", prompter.masterRequests)
        assertEquals("no password manager", 0, managerReads.get())
    }

    /** No vault secret, client or identity list in what the run passes. */
    private fun assertNoVaultEnvironment(environment: Map<String, String>, command: List<String>) {
        assertFalse(environment.keys.toString(), environment.keys.any { it.startsWith("ANSIBILITY_VAULT_") || it.startsWith("ANSIBLE_VAULT_") })
        assertFalse(command.toString(), command.any { "ANSIBLE_VAULT" in it || "ANSIBILITY_VAULT" in it || RunSecrets.VAULT_CLIENT in it })
    }

    fun testAMoleculeRunUsesNoVaultAtAll() {
        // R19/D136 replaces R17/D126 ("unlock what needs no question"): even an id unlocked earlier stays out.
        val vaulted = vaultedRoot()
        MoleculeRunContext.moleculeForTests = { Path.of("/venv/bin/molecule") }
        val native = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf("$vaulted/roles/app").path, "default", executor = PlaybookExecutor.NATIVE)) }
        try {
            assertNoVaultCall()
            assertNoVaultEnvironment(native.process.environment, native.process.command)
            assertNull("no vault client is written", native.secrets.vaultClient)
            assertEmpty(native.secrets.vaultLabels)
            assertTrue(native.header.toString(), native.header.contains(AnsibilityRunBundle.message("molecule.header.vault.none")))
            assertEquals("Vault: none \u2014 Molecule runs pass no vault secrets; vaulted values fail to decrypt.", AnsibilityRunBundle.message("molecule.header.vault.none"))
        } finally {
            native.close()
        }

        // In its Compose service: no vault variable passed by name, no vault client mounted.
        write(
            "repos/heron/docker-compose.ansible-molecule.yaml",
            "services:\n  ansible-molecule:\n    volumes:\n      - ./ansible/roles:/ansible/roles\n",
        )
        refresh()
        PlaybookPreparation.dockerForTests = { Path.of("/usr/local/bin/docker") }
        val docker = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf("$vaulted/roles/app").path, "default", MoleculeCommand.DESTROY)) }
        try {
            assertTrue(docker.process.command.toString(), "ansible-molecule" in docker.process.command && "destroy" in docker.process.command)
            assertNoVaultCall()
            assertNoVaultEnvironment(docker.process.environment, docker.process.command)
        } finally {
            docker.close()
        }
        assertTrue("the earlier unlock stays as it was", secrets.unlockedLabels(registry.discovery(root(vaulted))).contains("default"))
    }

    fun testADestroyAndABulkRunOfAVaultedRootAskNothingEither() {
        val vaulted = vaultedRoot()
        val molecule = base.resolve("venv/bin/molecule")
        Files.createDirectories(molecule.parent)
        // Each run leaves the names of its environment variables behind (names only, never values).
        Files.writeString(molecule, "#!/bin/sh\nenv | cut -d= -f1 > \"${'$'}PWD/.env-names-${'$'}1\"\n")
        molecule.toFile().setExecutable(true)
        MoleculeRunContext.moleculeForTests = { molecule }
        write("$vaulted/roles/db/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$vaulted/roles/db/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        refresh()
        val app = MoleculeSpec(vf("$vaulted/roles/app").path, "default", MoleculeCommand.DESTROY, PlaybookExecutor.NATIVE)
        val db = MoleculeSpec(vf("$vaulted/roles/db").path, executor = PlaybookExecutor.NATIVE)
        val executor = DefaultRunExecutor.getRunExecutorInstance()

        // The destroy run, as the countdown starts it: through the configuration MoleculeLauncher runs.
        val launched = ArrayList<RunnerAndConfigurationSettings>()
        MoleculeLauncher.executeForTests = { settings, _ -> launched += settings }
        MoleculeCleanup.destroy(project, app.copy(command = MoleculeCommand.TEST))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val settings = launched.single()
        val destroy = ExecutionEnvironment(executor, TestRunner, settings, project)
        val state = settings.configuration.getState(executor, destroy)!!
        val result = state.execute(executor, TestRunner)!!
        Disposer.register(testRootDisposable, result.executionConsole)
        result.processHandler.startNotify()
        assertTrue(result.processHandler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        assertEquals(0, result.processHandler.exitCode)

        // A bulk run of the root's two roles.
        val targets = listOf(MoleculeTarget(app.copy(command = MoleculeCommand.TEST), "app", "heron"), MoleculeTarget(db, "db", "heron"))
        val bulk = ExecutionEnvironment(executor, TestRunner, settings, project)
        val batch = MoleculeBatchProfile(project, targets).getState(executor, bulk).execute(executor, TestRunner)!!
        Disposer.register(testRootDisposable, batch.executionConsole)
        batch.processHandler.startNotify()
        // The batch begins each role on the EDT (and waits for the destroy's end): wait dispatching events.
        PlatformTestUtil.waitWithEventsDispatching("the batch did not end", { batch.processHandler.isProcessTerminated }, 60)
        assertEquals(0, batch.processHandler.exitCode)

        assertNoVaultCall()
        for (dump in listOf("app/.env-names-destroy", "app/.env-names-test", "db/.env-names-test")) {
            val names = Files.readAllLines(base.resolve("$vaulted/roles/$dump"))
            assertFalse("$dump: $names", names.any { it.startsWith("ANSIBILITY_VAULT_") })
        }
    }

    fun testALocalRunUsesTheMoleculeBesideAnsible() {
        root(falcon)
        MoleculeRunContext.moleculeForTests = { Path.of("/venv/bin/molecule") }
        val prepared = await { MoleculePreparation.prepareAsync(project, MoleculeSpec(vf(role).path, "default", executor = PlaybookExecutor.NATIVE)) }
        try {
            assertEquals(listOf("/venv/bin/molecule", "test", "--scenario-name", "default"), prepared.process.command)
            assertEquals(base.resolve(role), prepared.process.workDir)
        } finally {
            prepared.close()
        }
    }

    fun testTheGutterRunsScenarios() {
        root(falcon)
        val tooltip = AnsibilityRunBundle.message("molecule.gutter.tooltip", "default")
        for (file in listOf("molecule.yml", "converge.yml", "verify.yml")) {
            myFixture.configureFromExistingVirtualFile(vf("$role/molecule/default/$file"))
            assertTrue(file, myFixture.findAllGutters().any { it.tooltipText == tooltip })
        }
        myFixture.configureFromExistingVirtualFile(vf("$role/tasks/main.yml"))
        assertFalse(myFixture.findAllGutters().any { it.tooltipText == tooltip })
    }

    fun testTheRoleActionNamesAndTestsTheRoleOrScenario() {
        root(falcon)
        val runs = recordRuns()
        val action = ActionManager.getInstance().getAction("Ansibility.RunMoleculeTest")
        fun presentation(path: String) = event(action, path).also { action.update(it) }.presentation
        assertEquals("Run Molecule Test on 'web'", presentation(role).text)
        assertEquals("Run Molecule Test on 'web' › default", presentation("$role/molecule/default").text)
        assertFalse(presentation("$falcon").isEnabledAndVisible)
        assertFalse("a file is no role", presentation("$role/tasks/main.yml").isEnabledAndVisible)
        action.actionPerformed(event(action, role))
        action.actionPerformed(event(action, "$role/molecule/default"))
        assertEquals(listOf(MoleculeSpec(vf(role).path), MoleculeSpec(vf(role).path, "default")), runs)
    }

    fun testTheGutterButtonsRunTheirCommand() {
        root(falcon)
        val runs = recordRuns()
        val contributor = MoleculeRunLineMarkerContributor()
        fun info(file: String): RunLineMarkerContributor.Info {
            myFixture.configureFromExistingVirtualFile(vf("$role/molecule/default/$file"))
            return SyntaxTraverser.psiTraverser(myFixture.file).filter { it.firstChild == null }.toList().mapNotNull(contributor::getInfo).single()
        }
        val config = info("molecule.yml")
        assertEquals(
            listOf("Test", "Converge", "Verify", "Idempotence", "Destroy", "Test, Keep Instances").map { "Molecule $it (default)" },
            config.actions.map { it.templateText },
        )
        assertEquals(listOf("Molecule Converge (default)", "Molecule Test (default)"), info("converge.yml").actions.map { it.templateText })
        val verify = info("verify.yml").actions.first()
        for (action in listOf(config.actions.last(), verify)) action.actionPerformed(event(action, "$role/molecule/default/molecule.yml"))
        assertEquals(listOf(MoleculeSpec(vf(role).path, "default", MoleculeCommand.TEST_KEEP), MoleculeSpec(vf(role).path, "default", MoleculeCommand.VERIFY)), runs)
    }

    fun testTheLauncherReusesTheConfigurationOfTheSameRun() {
        val runs = recordRuns()
        val converge = MoleculeSpec("/work/roles/web", "default", MoleculeCommand.CONVERGE)
        val first = MoleculeLauncher.run(project, converge)
        assertTrue(first.isTemporary)
        assertEquals("Molecule web › default (converge)", first.name)
        assertSame(first, RunManager.getInstance(project).selectedConfiguration)
        assertSame("the same role, scenario and command", first, MoleculeLauncher.run(project, converge))
        val verify = MoleculeLauncher.run(project, converge.copy(command = MoleculeCommand.VERIFY))
        assertNotSame(first, verify)
        assertEquals(listOf(verify), MoleculeLauncher.configurations(project, converge.copy(command = MoleculeCommand.VERIFY)))
        assertEquals(listOf(converge, converge, converge.copy(command = MoleculeCommand.VERIFY)), runs)
    }

    fun testAStageOfThePlaysTabRunsAgainAlone() {
        val runs = recordRuns()
        val spec = MoleculeSpec("/work/roles/web", "", MoleculeCommand.TEST, PlaybookExecutor.DOCKER, additionalArgs = "--debug")
        val actions = MoleculeViewActions(project, spec)
        assertFalse(actions.playbookActions)
        assertTrue(actions.stageActions)
        actions.runStage("volatile", "idempotence", null)
        actions.runStage("volatile", "prepare", null)
        actions.rerunHosts(listOf("instance"))
        actions.startAt("Converge")
        actions.runPart("Converge", null)
        assertEquals("only actions Molecule runs alone; the rest of the spec stays", listOf(spec.copy(scenario = "volatile", command = MoleculeCommand.IDEMPOTENCE)), runs)
    }

    fun testTheConfigurationStoresItsSpecAndChecksIt() {
        val factory = MoleculeConfigurationType.getInstance().factory
        fun create() = factory.createTemplateConfiguration(project) as MoleculeConfiguration
        val blank = create()
        assertNull(blank.suggestedName())
        assertTrue(runCatching { blank.checkConfiguration() }.exceptionOrNull() is RuntimeConfigurationError)
        val spec = MoleculeSpec("/work/roles/web", "default", MoleculeCommand.IDEMPOTENCE, PlaybookExecutor.DOCKER, "/work/compose.yaml", "ansible-molecule", "--debug")
        val configuration = create().also { it.spec = spec }
        configuration.checkConfiguration()
        assertEquals("Molecule web › default (idempotence)", configuration.suggestedName())
        val element = Element("configuration")
        configuration.writeExternal(element)
        assertEquals(spec, create().also { it.readExternal(element) }.spec)
        assertEquals(MoleculeConfigurationType.ID, factory.id)
    }

    fun testTheEditorShowsAndAppliesTheSpec() {
        val factory = MoleculeConfigurationType.getInstance().factory
        val spec = MoleculeSpec("/work/roles/web", "default", MoleculeCommand.VERIFY, PlaybookExecutor.NATIVE, "/work/compose.yaml", "ansible-molecule", "--debug")
        val configuration = (factory.createTemplateConfiguration(project) as MoleculeConfiguration).also { it.spec = spec }
        val editor = configuration.configurationEditor as MoleculeSettingsEditor
        Disposer.register(testRootDisposable, editor)
        assertNotNull(editor.component)
        editor.resetFrom(configuration)
        val applied = factory.createTemplateConfiguration(project) as MoleculeConfiguration
        editor.applyTo(applied)
        assertEquals(spec, applied.spec)
    }

    fun testMoleculeIsFoundBesideAnsiblePlaybook() {
        val options = AnsibleRuntimeOptions.getInstance()
        val saved = options.explicitExecutable
        try {
            val bin = Files.createDirectories(base.resolve("venv/bin"))
            for (tool in listOf("ansible-playbook", "molecule")) Files.writeString(bin.resolve(tool), "#!/bin/sh\n").toFile().setExecutable(true)
            options.explicitExecutable = { tool -> bin.resolve("ansible-playbook").toString().takeIf { tool == AnsibleTool.ANSIBLE_PLAYBOOK } }
            assertEquals("the same virtualenv", bin.resolve("molecule"), MoleculeRunContext.locate(project))
        } finally {
            options.explicitExecutable = saved
        }
    }
}
