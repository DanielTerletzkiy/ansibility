package de.terletzkiy.ansibility.run

import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.nio.file.Path

/** A process to start: [command] in [workDir], with [environment] added to the inherited one. */
data class PlaybookProcess(val command: List<String>, val workDir: Path, val environment: Map<String, String>)

/**
 * What the root's runner settings add to a run: [connectionVars] (the JSON `-e` of the connection, before the run's
 * own extra vars), [environment] for the `ansible-playbook` process (passed into the container by name) and
 * [composeVariables] for Compose's interpolation only.
 */
data class RunAdditions(
    val connectionVars: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val composeVariables: Map<String, String> = emptyMap(),
)

/**
 * Builds the `ansible-playbook` invocation of a [PlaybookRunSpec], run locally or through `docker compose run`. Pure:
 * every path and environment value comes in as an argument.
 */
object PlaybookCommand {
    /** Where the secrets directory is mounted in a container. */
    const val CONTAINER_SECRETS = "/ansibility-run"

    /** Docker Desktop's (and OrbStack's) forwarded host SSH agent; a macOS host socket cannot be mounted directly. */
    const val MAC_SSH_AGENT = "/run/host-services/ssh-auth.sock"

    private val COLOR = mapOf("ANSIBLE_FORCE_COLOR" to "1", "PYTHONUNBUFFERED" to "1")

    /** The arguments after `ansible-playbook`: [playbook] and [inventories] as the process sees them. */
    fun ansibleArguments(spec: PlaybookRunSpec, playbook: String, inventories: List<String>, becomeScript: String?, connectionVars: String? = null): List<String> {
        val args = ArrayList<String>()
        args += playbook
        inventories.forEach { args += listOf("-i", it) }
        spec.limit.trim().takeIf { it.isNotEmpty() }?.let { args += listOf("--limit", it) }
        list(spec.tags)?.let { args += listOf("--tags", it) }
        list(spec.skipTags)?.let { args += listOf("--skip-tags", it) }
        connectionVars?.let { args += listOf("-e", it) }
        extraVars(spec.extraVars).forEach { args += listOf("-e", it) }
        if (spec.check) args += "--check"
        if (spec.diff) args += "--diff"
        spec.verbosity.coerceIn(0, PlaybookRunSpec.MAX_VERBOSITY).takeIf { it > 0 }?.let { args += "-" + "v".repeat(it) }
        becomeScript?.let { args += listOf("--become-password-file", it) }
        args += ParametersListUtil.parse(spec.additionalArgs.trim(), false, true)
        return args
    }

    /** `ANSIBLE_VAULT_IDENTITY_LIST`/`ANSIBLE_VAULT_PASSWORD_FILE` pointing every unlocked id at [client] (they replace `ansible.cfg`'s). */
    fun vaultEnvironment(labels: List<String>, client: String?): Map<String, String> {
        if (client == null || labels.isEmpty()) return emptyMap()
        return mapOf(
            "ANSIBLE_VAULT_IDENTITY_LIST" to labels.joinToString(",") { "$it@$client" },
            "ANSIBLE_VAULT_PASSWORD_FILE" to client,
        )
    }

    /** A local run in [workDir] (the root holding `ansible.cfg`) with [executable] first on `PATH`. */
    fun native(
        executable: Path,
        spec: PlaybookRunSpec,
        workDir: Path,
        playbook: Path,
        inventories: List<Path>,
        secrets: RunSecrets,
        inheritedPath: String,
        additions: RunAdditions = RunAdditions(),
    ): PlaybookProcess {
        val args = ansibleArguments(
            spec, relative(workDir, playbook), inventories.map { relative(workDir, it) }, secrets.becomeScript?.toString(), additions.connectionVars,
        )
        val environment = LinkedHashMap<String, String>()
        environment += additions.environment
        environment += secrets.environment
        environment += vaultEnvironment(secrets.vaultLabels, secrets.vaultClient?.toString())
        environment += COLOR
        val toolDir = executable.parent?.toString()
        environment["PATH"] = listOfNotNull(toolDir, inheritedPath.takeIf { it.isNotEmpty() }).joinToString(File.pathSeparator)
        return PlaybookProcess(listOf(executable.toString()) + args, workDir, environment)
    }

    /**
     * `docker compose run` of [target] with `ansible-playbook` as the entrypoint, in the container path of [workDir].
     * [composeEnvironment] (the env file), the root's Compose variables and [inherited] are what Compose interpolates
     * the file with; the variables of the vault password and SSH agent mounts are pointed at the run's client script
     * (a placeholder when no vault id is unlocked) and, on macOS, Docker Desktop's agent socket when they are unset,
     * and an unset known_hosts mount at [knownHosts]. Null when [workDir] is not inside a mount of [target].
     */
    fun docker(
        docker: Path,
        target: DockerTarget,
        spec: PlaybookRunSpec,
        workDir: Path,
        playbook: Path,
        inventories: List<Path>,
        secrets: RunSecrets,
        composeEnvironment: Map<String, String>,
        inherited: Map<String, String>,
        macOs: Boolean,
        additions: RunAdditions = RunAdditions(),
        knownHosts: String? = null,
    ): PlaybookProcess? {
        val containerRoot = target.containerPath(workDir) ?: return null
        fun inContainer(path: Path): String = if (path.normalize().startsWith(workDir.normalize())) relative(workDir, path) else target.containerPath(path) ?: path.toString()
        fun secret(path: Path?): String? = path?.let { "$CONTAINER_SECRETS/${it.fileName}" }

        val passed = LinkedHashMap<String, String>()
        passed += additions.environment
        passed += secrets.environment
        passed += vaultEnvironment(secrets.vaultLabels, secret(secrets.vaultClient))
        passed += COLOR

        val command = ArrayList<String>()
        command += listOf(docker.toString(), "compose", "-f", target.composeFile.toString(), "run", "--rm", "-T")
        command += listOf("--entrypoint", "ansible-playbook", "-w", containerRoot)
        secrets.dir?.let { command += listOf("-v", "$it:$CONTAINER_SECRETS:ro") }
        passed.keys.forEach { command += listOf("-e", it) }
        command += target.service
        command += ansibleArguments(spec, inContainer(playbook), inventories.map(::inContainer), secret(secrets.becomeScript), additions.connectionVars)

        val environment = LinkedHashMap<String, String>()
        environment += composeEnvironment
        environment += additions.composeVariables
        environment += passed
        val known = inherited + composeEnvironment + additions.composeVariables
        val client = secrets.vaultClient ?: secrets.vaultPlaceholder
        if (target.vaultFileVariable != null && client != null) environment[target.vaultFileVariable] = client.toString()
        if (macOs && target.sshSocketVariable != null && known[target.sshSocketVariable].isNullOrEmpty()) environment[target.sshSocketVariable] = MAC_SSH_AGENT
        if (knownHosts != null) {
            for (variable in target.variables) {
                if (!variable.hasDefault && variable.container.endsWith("/known_hosts") && known[variable.name].isNullOrEmpty()) environment[variable.name] = knownHosts
            }
        }
        return PlaybookProcess(command, target.composeFile.parent ?: workDir, environment)
    }

    /** The volume variables of [target] that neither [process] nor [inherited] sets and Compose has no default for. */
    fun missingVariables(target: DockerTarget, process: PlaybookProcess, inherited: Map<String, String>): List<String> =
        target.variables.filter { !it.hasDefault && process.environment[it.name].isNullOrEmpty() && inherited[it.name].isNullOrEmpty() }.map { it.name }.distinct()

    /** The non-blank, non-comment lines of [text], one `-e` value each. */
    fun extraVars(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** `a, b ,c` as `a,b,c`; null when empty. */
    private fun list(text: String): String? =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(",")

    private fun relative(base: Path, path: Path): String {
        val normalized = path.normalize()
        val root = base.normalize()
        return if (normalized.startsWith(root)) root.relativize(normalized).joinToString("/").ifEmpty { "." } else normalized.toString()
    }
}

/** Reads a shell-style env file (`KEY=value`, `export KEY="value"`) the way sourcing it would: quotes, `~/`, `$VAR`. */
object EnvFiles {
    private val REFERENCE = Regex("""\$(\{([A-Za-z_][A-Za-z0-9_]*)}|([A-Za-z_][A-Za-z0-9_]*))""")
    private val KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String, inherited: Map<String, String>, home: String?): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (!KEY.matches(key)) continue
            result[key] = value(line.substring(eq + 1).trim(), inherited + result, home)
        }
        return result
    }

    private fun value(text: String, known: Map<String, String>, home: String?): String = when {
        text.startsWith("'") -> text.substring(1).substringBefore('\'')
        text.startsWith("\"") -> expand(text.substring(1).substringBefore('"'), known)
        else -> {
            val plain = text.substringBefore(" #").trim()
            val tilde = if (home != null && (plain == "~" || plain.startsWith("~/"))) home + plain.removePrefix("~") else plain
            expand(tilde, known)
        }
    }

    private fun expand(text: String, known: Map<String, String>): String =
        REFERENCE.replace(text) { match -> known[match.groupValues[2].ifEmpty { match.groupValues[3] }].orEmpty() }
}
