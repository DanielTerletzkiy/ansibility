package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.TestActionEvent
import de.terletzkiy.ansibility.run.AnsibilityRunBundle
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PlaybookPreparation
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.runtime.AnsibleRuntimeOptions
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.settings.RootKeys
import de.terletzkiy.ansibility.vault.VaultTestCase
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path

/**
 * Molecule on a role: its scenarios and Compose service, the gutter, the prepared run, the role action, the run
 * configuration and what each button runs.
 */
class MoleculeRunTest : VaultTestCase() {
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
        val prepared = await { MoleculePreparation.prepare(project, MoleculeSpec(vf(role).path, "default", MoleculeCommand.CONVERGE)) }!!
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
        val own = await { MoleculePreparation.prepare(project, MoleculeSpec(vf(role).path, "")) }!!
        try {
            assertEquals("mine", own.process.environment[MoleculePreparation.RUN_ID])
            assertTrue(own.process.command.containsAll(listOf("test", "--all")))
        } finally {
            own.close()
        }
        val error = runCatching { await { MoleculePreparation.prepare(project, MoleculeSpec(vf(role).path, "nope")) } }.exceptionOrNull()
        assertTrue(error.toString(), error?.message.orEmpty().contains("nope"))
        val gone = runCatching { await { MoleculePreparation.prepare(project, MoleculeSpec(base.resolve("$falcon/roles/gone").toString())) } }.exceptionOrNull()
        assertTrue(gone.toString(), gone?.message.orEmpty().contains("does not exist"))
        PlaybookPreparation.dockerForTests = { null }
        val noDocker = runCatching { await { MoleculePreparation.prepare(project, MoleculeSpec(vf(role).path, "default")) } }.exceptionOrNull()
        assertTrue(noDocker.toString(), noDocker?.message.orEmpty().contains("No docker executable"))
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
        val prepared = await { MoleculePreparation.prepare(project, MoleculeSpec(vf("library/roles/web").path)) }!!
        try {
            val command = prepared.process.command
            assertTrue(command.toString(), command.containsAll(listOf("-w", "/ansible/roles/web", "ansible-molecule", "test", "--all")))
            assertEquals(base.resolve("docker-compose.ansible-molecule.yaml").toString(), command[command.indexOf("-f") + 1])
        } finally {
            prepared.close()
        }
    }

    fun testAMoleculeRunNeverAsksForAVaultPassword() {
        // A root whose only vault password comes from a prompt (a role library has none at all).
        val vaulted = projectRoot("heron", "[defaults]\nvault_identity_list = default@prompt\n")
        write("$vaulted/roles/app/tasks/main.yml", "- name: One\n  ansible.builtin.debug: {}\n")
        write("$vaulted/roles/app/molecule/default/molecule.yml", "---\ndriver:\n  name: default\n")
        root(vaulted)
        MoleculeRunContext.moleculeForTests = { Path.of("/venv/bin/molecule") }
        val prepared = await { MoleculePreparation.prepare(project, MoleculeSpec(vf("$vaulted/roles/app").path, "default", executor = PlaybookExecutor.NATIVE)) }!!
        try {
            assertTrue("no prompt: ${prompter.passwordRequests}", prompter.passwordRequests.isEmpty())
            assertTrue(prepared.header.toString(), prepared.header.any { "Molecule runs do not ask" in it })
        } finally {
            prepared.close()
        }
    }

    fun testALocalRunUsesTheMoleculeBesideAnsible() {
        root(falcon)
        MoleculeRunContext.moleculeForTests = { Path.of("/venv/bin/molecule") }
        val prepared = await { MoleculePreparation.prepare(project, MoleculeSpec(vf(role).path, "default", executor = PlaybookExecutor.NATIVE)) }!!
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
