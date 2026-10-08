package de.terletzkiy.ansibility.run.molecule

import com.intellij.execution.ExecutionException
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.EnvironmentUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.host.AnsibleContextServiceImpl
import de.terletzkiy.ansibility.run.AnsibilityRunBundle.message
import de.terletzkiy.ansibility.run.DockerTarget
import de.terletzkiy.ansibility.run.DockerTargets
import de.terletzkiy.ansibility.run.PlaybookCommand
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PlaybookPreparation
import de.terletzkiy.ansibility.run.PreparationReporter
import de.terletzkiy.ansibility.run.PreparedRun
import de.terletzkiy.ansibility.run.RunAdditions
import de.terletzkiy.ansibility.run.RunEventsSetup
import de.terletzkiy.ansibility.run.RunSecrets
import de.terletzkiy.ansibility.run.events.RunCallback
import de.terletzkiy.ansibility.run.settings.RunnerRootSettings
import de.terletzkiy.ansibility.run.settings.RunnerSettings
import de.terletzkiy.ansibility.runtime.AnsibleTool
import de.terletzkiy.ansibility.runtime.AnsibleToolchain
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What a Molecule run of a role needs: the role's directory, its root (for the runner settings), its scenarios
 * (`molecule/<name>/molecule.yml`), the Compose services that mount it and have Molecule in their name, and a local
 * `molecule`.
 */
class MoleculeRunContext(
    val roleDir: VirtualFile,
    val rolePath: Path,
    val root: AnsibleRoot,
    val rootKey: String,
    val scenarios: List<String>,
    val dockerTargets: List<DockerTarget>,
    val molecule: Path?,
) {
    /** The Compose service [spec] names, else the first discovered Molecule service. */
    fun dockerTarget(spec: MoleculeSpec): DockerTarget? {
        if (spec.composeService.isBlank()) return dockerTargets.firstOrNull()
        return dockerTargets.firstOrNull { it.service == spec.composeService && (spec.composeFile.isBlank() || it.composeFile.toString() == spec.composeFile) }
    }

    /** AUTO: a Molecule Compose service, else the local `molecule`. */
    fun executor(spec: MoleculeSpec): PlaybookExecutor = when (spec.executor) {
        PlaybookExecutor.NATIVE, PlaybookExecutor.DOCKER -> spec.executor
        PlaybookExecutor.AUTO -> if (dockerTargets.isNotEmpty()) PlaybookExecutor.DOCKER else PlaybookExecutor.NATIVE
    }

    fun runner(project: Project): RunnerRootSettings = RunnerSettings.getInstance(project).rootSettings(rootKey)

    companion object {
        /** The role directory of [file]: the role itself, its `molecule` directory, a scenario or a file in one. */
        fun roleDirOf(file: VirtualFile): VirtualFile? {
            var dir: VirtualFile? = if (file.isDirectory) file else file.parent
            while (dir != null) {
                if (dir.findChild(MOLECULE)?.isDirectory == true) return dir
                if (dir.name == MOLECULE) return dir.parent
                dir = dir.parent
                if (dir?.name == "roles") return null
            }
            return null
        }

        /** The scenario of [file] (`molecule/<scenario>/…`), or null outside one. */
        fun scenarioOf(file: VirtualFile): String? {
            var dir: VirtualFile? = if (file.isDirectory) file else file.parent
            while (dir != null && dir.parent != null) {
                if (dir.parent.name == MOLECULE) return dir.name
                dir = dir.parent
            }
            return null
        }

        /** The scenarios of the role at [roleDir] (`molecule/<name>/molecule.yml`), sorted. Any thread. */
        fun scenariosOf(roleDir: VirtualFile): List<String> =
            roleDir.findChild(MOLECULE)?.takeIf { it.isDirectory }?.children.orEmpty()
                .filter { it.isDirectory && it.findChild(CONFIG) != null }.map { it.name }.sorted()

        /** Collects the context of [roleDir]; null when it is no role with Molecule scenarios. Off the EDT. */
        suspend fun collect(project: Project, roleDir: VirtualFile): MoleculeRunContext? {
            val home = System.getProperty("user.home")?.let { runCatching { Path.of(it) }.getOrNull() }
            val partial = readAction {
                if (project.isDisposed || !roleDir.isValid) return@readAction null
                val rolePath = roleDir.toNioPathOrNull() ?: return@readAction null
                val ownRoot = AnsibleWorkspace.getInstance(project).rootFor(roleDir) ?: return@readAction null
                val root = AnsibleContextServiceImpl.getInstance(project)?.inventoryRoot(ownRoot) ?: ownRoot
                val scenarios = scenariosOf(roleDir)
                val targets = DockerTargets.find(project, roleDir, home).filter { "molecule" in it.service.lowercase() }
                MoleculeRunContext(roleDir, rolePath, root, RootKeys.keyOf(project, root.dir), scenarios, targets, null)
            } ?: return null
            if (partial.scenarios.isEmpty()) return null
            val molecule = withContext(Dispatchers.IO) { (moleculeForTests ?: { locate(project) })() }
            return MoleculeRunContext(partial.roleDir, partial.rolePath, partial.root, partial.rootKey, partial.scenarios, partial.dockerTargets, molecule)
        }

        /** `molecule` beside the found `ansible-playbook` (the same virtualenv), else on the login shell's `PATH`. */
        fun locate(project: Project): Path? {
            AnsibleToolchain.getInstance().locate(AnsibleTool.ANSIBLE_PLAYBOOK, project)?.resolveSibling("molecule")?.takeIf { Files.isExecutable(it) }?.let { return it }
            val path = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty()
            val home = System.getProperty("user.home").orEmpty()
            return (path.split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin"))
                .asSequence().filter { it.isNotBlank() }.mapNotNull { runCatching { Path.of(it, "molecule") }.getOrNull() }
                .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
        }

        const val MOLECULE = "molecule"
        const val CONFIG = "molecule.yml"

        @TestOnly
        @Volatile
        internal var moleculeForTests: (() -> Path?)? = null
    }
}

/**
 * Turns a [MoleculeSpec] into a [PreparedRun]: adds the events callback, the role's `MOLECULE_RUN_ID` unless the runner
 * settings set one ([runId]), unbuffered Python output (Molecule's stage lines and Ansible's events come as they
 * happen), and builds the local or Compose command. No vault at all (plan amendment R19, D136, replacing R17's D126):
 * nothing is unlocked, no vault secret or client is passed, so no Molecule run (single, bulk, stage rerun, destroy,
 * automatic destroy) can ask for a password or open a password manager; the header says that vaulted values fail to
 * decrypt. It runs in the run's tab and reports its phases there. A run of a role (other than its destroy) first waits
 * for a destroy of the role that [MoleculeCleanup] launched and that has not ended (a stopped test's, a countdown's,
 * Destroy Now), so it never works on instances that destroy removes; Stop ends the wait.
 */
object MoleculePreparation {
    const val RUN_ID = "MOLECULE_RUN_ID"

    /**
     * The `MOLECULE_RUN_ID` of the role at [rolePath]. Scenarios and Compose files name their instances, networks and
     * containers with it (`instance-${'$'}{MOLECULE_RUN_ID:-local}`), so it is the same for every run of a role (a
     * converge's instances are there for the verify run after it) and another for each role or checkout (runs of two
     * roles side by side keep apart).
     */
    fun runId(rolePath: Path): String =
        "ansibility-" + MessageDigest.getInstance("SHA-256").digest(rolePath.toString().toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** The run of [spec], or an [ExecutionException] saying why it cannot start. Off the EDT; [reporter] hears what it does. */
    internal suspend fun prepareAsync(project: Project, spec: MoleculeSpec, reporter: PreparationReporter = PreparationReporter.NONE): PreparedRun {
        reporter.phase(message("molecule.prepare.phase.role"))
        val dir = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByPath(spec.roleDir) }
            ?: throw ExecutionException(message("molecule.error.role.missing", spec.roleDir))
        val context = MoleculeRunContext.collect(project, dir) ?: throw ExecutionException(message("molecule.error.no.scenario", spec.roleDir))
        if (spec.scenario.isNotBlank() && spec.scenario !in context.scenarios) {
            throw ExecutionException(message("molecule.error.scenario.missing", spec.scenario, context.roleDir.name))
        }
        if (spec.command != MoleculeCommand.DESTROY) {
            MoleculeCleanup.getInstance(project).awaitDestroys(spec.roleDir) { destroy ->
                reporter.phase(message("molecule.prepare.phase.destroy", MoleculeCleanup.subject(destroy)))
            }
        }
        val runner = context.runner(project)
        val header = ArrayList<String>()
        header += message("molecule.header", spec.command.id, spec.scenario.ifBlank { message("molecule.all.scenarios") }, context.roleDir.name)
        header += message("molecule.header.vault.none")
        val executor = context.executor(spec)
        val target = if (executor == PlaybookExecutor.DOCKER) {
            context.dockerTarget(spec) ?: throw ExecutionException(message("molecule.error.no.service", context.roleDir.name))
        } else {
            null
        }
        val token = if (runner.runView) RunCallback.newToken() else null
        // Only the callback (D136: no vault secret, no client). Written to the end even when Stop comes meanwhile;
        // deleted then, or below on every later way out.
        val secrets = RunSecrets.writeScripts {
            try {
                RunSecrets.create(PlaybookPreparation.secretsBase(), emptyList(), null, callback = if (token != null) RunCallback.source() else null)
            } catch (e: IOException) {
                throw ExecutionException(message("molecule.error.scripts", e.message.orEmpty()), e)
            }
        }
        try {
            val environment = LinkedHashMap<String, String>()
            environment += runner.environmentVariables
            if (RUN_ID !in environment) environment[RUN_ID] = runId(context.rolePath)
            environment["PYTHONUNBUFFERED"] = "1"
            if (token != null) {
                val ours = if (target != null) "${PlaybookCommand.CONTAINER_SECRETS}/${RunSecrets.CALLBACKS}" else secrets.callbackDir.toString()
                val configured = runner.environmentVariables[RunCallback.PLUGINS_VARIABLE]
                    ?: if (target != null) target.callbackPlugins else EnvironmentUtil.getValue(RunCallback.PLUGINS_VARIABLE)
                environment[RunCallback.TOKEN_VARIABLE] = token
                environment[RunCallback.PLUGINS_VARIABLE] = RunCallback.pluginPath(configured, null, ours)
            }
            header += message("molecule.header.run.id", environment.getValue(RUN_ID))
            val process = if (target != null) {
                val docker = withContext(Dispatchers.IO) { (PlaybookPreparation.dockerForTests ?: PlaybookPreparation::locateDocker)() }
                    ?: throw ExecutionException(message("run.error.docker.missing"))
                header += message("run.header.docker", target.presentable())
                val process = MoleculeCommandLine.docker(docker, target, spec, context.rolePath, secrets, RunAdditions(null, environment, runner.composeVariables))
                    ?: throw ExecutionException(message("run.error.docker.unmounted", target.presentable()))
                val missing = PlaybookCommand.missingVariables(target, process, EnvironmentUtil.getEnvironmentMap())
                if (missing.isNotEmpty()) throw ExecutionException(message("run.error.docker.variables", target.presentable(), missing.joinToString(), context.root.displayName))
                process
            } else {
                val molecule = context.molecule ?: throw ExecutionException(message("molecule.error.native.missing"))
                header += message("molecule.header.native", molecule.toString())
                MoleculeCommandLine.native(molecule, spec, context.rolePath, environment, secrets, EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH").orEmpty())
            }
            val events = token?.let { RunEventsSetup(it) { path -> target?.hostPath(path) ?: runCatching { Path.of(path) }.getOrNull() } }
            return PreparedRun(process, secrets, header, events, runner)
        } catch (e: Throwable) {
            secrets.close()
            throw e
        }
    }
}
