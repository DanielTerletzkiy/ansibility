package de.terletzkiy.ansibility.run.molecule

import com.intellij.util.execution.ParametersListUtil
import de.terletzkiy.ansibility.run.DockerTarget
import de.terletzkiy.ansibility.run.PlaybookCommand
import de.terletzkiy.ansibility.run.PlaybookExecutor
import de.terletzkiy.ansibility.run.PlaybookProcess
import de.terletzkiy.ansibility.run.RunAdditions
import de.terletzkiy.ansibility.run.RunSecrets
import java.io.File
import java.nio.file.Path

/** A Molecule command a run starts: `test`, `test --destroy=never`, `converge`, `verify`, `idempotence`, `destroy`. */
enum class MoleculeCommand(val id: String, val arguments: List<String>) {
    TEST("test", listOf("test")),
    TEST_KEEP("test-keep", listOf("test", "--destroy=never")),
    CONVERGE("converge", listOf("converge")),
    VERIFY("verify", listOf("verify")),
    IDEMPOTENCE("idempotence", listOf("idempotence")),
    DESTROY("destroy", listOf("destroy")),
    ;

    /** Whether the command leaves its instances running (a test destroys them, "keep instances" is asked for). */
    val leavesInstances: Boolean get() = this == CONVERGE || this == VERIFY || this == IDEMPOTENCE

    companion object {
        fun of(id: String?): MoleculeCommand = entries.firstOrNull { it.id == id } ?: TEST

        /** The command that runs Molecule's [action] alone (`converge` of a stage), or null for actions without one. */
        fun forAction(action: String): MoleculeCommand? = entries.firstOrNull { it.id == action && it != TEST_KEEP }
    }
}

/**
 * One Molecule run of a role: [roleDir] (the role's directory), [scenario] (empty: every scenario, `--all`), the
 * [command], where it runs ([executor]; blank [composeFile]/[composeService]: the discovered Molecule service) and
 * further `molecule` arguments.
 */
data class MoleculeSpec(
    val roleDir: String,
    val scenario: String = "",
    val command: MoleculeCommand = MoleculeCommand.TEST,
    val executor: PlaybookExecutor = PlaybookExecutor.AUTO,
    val composeFile: String = "",
    val composeService: String = "",
    val additionalArgs: String = "",
) {
    val roleName: String get() = runCatching { Path.of(roleDir).fileName.toString() }.getOrDefault(roleDir)

    /** "Molecule alloy › default (test)", "Molecule alloy (converge)" for every scenario. */
    fun name(): String = "Molecule $roleName" + (if (scenario.isBlank()) "" else " › $scenario") + " (${command.id})"
}

/** Builds the `molecule` invocation of a [MoleculeSpec], locally or through `docker compose run`. Pure. */
object MoleculeCommandLine {
    /** The arguments after `molecule`. */
    fun arguments(spec: MoleculeSpec): List<String> =
        spec.command.arguments + (if (spec.scenario.isBlank()) listOf("--all") else listOf("--scenario-name", spec.scenario.trim())) +
            ParametersListUtil.parse(spec.additionalArgs.trim(), false, true)

    /** A local run in the role's directory with [executable] first on `PATH`; [environment] holds the run's variables. */
    fun native(executable: Path, spec: MoleculeSpec, roleDir: Path, environment: Map<String, String>, secrets: RunSecrets, inheritedPath: String): PlaybookProcess {
        val env = LinkedHashMap<String, String>()
        env += environment
        env += secrets.environment
        env += PlaybookCommand.vaultEnvironment(secrets.vaultLabels, secrets.vaultClient?.toString())
        env["PATH"] = listOfNotNull(executable.parent?.toString(), inheritedPath.takeIf { it.isNotEmpty() }).joinToString(File.pathSeparator)
        return PlaybookProcess(listOf(executable.toString()) + arguments(spec), roleDir, env)
    }

    /**
     * `docker compose run` of [target] with `molecule` as the entrypoint, in the container path of [roleDir], the run's
     * secrets mounted and [additions] passed by name (Compose interpolates the file with [additions]' Compose variables
     * and the IDE's environment). Null when [roleDir] is not inside a mount of [target].
     */
    fun docker(docker: Path, target: DockerTarget, spec: MoleculeSpec, roleDir: Path, secrets: RunSecrets, additions: RunAdditions): PlaybookProcess? {
        val containerRole = target.containerPath(roleDir) ?: return null
        val passed = LinkedHashMap<String, String>()
        passed += additions.environment
        passed += secrets.environment
        passed += PlaybookCommand.vaultEnvironment(secrets.vaultLabels, secrets.vaultClient?.let { "${PlaybookCommand.CONTAINER_SECRETS}/${it.fileName}" })
        val command = ArrayList<String>()
        command += listOf(docker.toString(), "compose", "-f", target.composeFile.toString(), "run", "--rm", "-T")
        command += listOf("--entrypoint", "molecule", "-w", containerRole)
        secrets.dir?.let { command += listOf("-v", "$it:${PlaybookCommand.CONTAINER_SECRETS}:ro") }
        passed.keys.forEach { command += listOf("-e", it) }
        command += target.service
        command += arguments(spec)
        val environment = LinkedHashMap<String, String>()
        environment += additions.composeVariables
        environment += passed
        return PlaybookProcess(command, target.composeFile.parent ?: roleDir, environment)
    }
}
