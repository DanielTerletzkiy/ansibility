package de.terletzkiy.ansibility.run.settings

/**
 * Turns the variables of a provisioning `.env.local` into runner settings, so the file is no longer needed: the
 * connection (`ANSIBLE_USER`, `SSH_JUMP_HOST_*`; such scripts always turn host key checks off) and the values of the
 * Compose variables in [composeVariables] (those the root's Compose services interpolate). The vault password file is
 * left out: the run hands Ansible the vault ids itself. Pure.
 */
object EnvLocalImport {
    const val USER = "ANSIBLE_USER"
    private const val JUMP = "SSH_JUMP_HOST_"

    /** Whether [values] holds anything [apply] would take. */
    fun hasConnection(values: Map<String, String>): Boolean = USER in values || values.keys.any { it.startsWith(JUMP) }

    fun apply(base: RunnerRootSettings, values: Map<String, String>, composeVariables: Collection<String>, ignored: Collection<String> = emptyList()): RunnerRootSettings {
        var result = base
        if (hasConnection(values)) {
            val user = values[USER]?.trim().orEmpty()
            val jumpUser = values["${JUMP}USER"]?.trim().orEmpty()
            result = result.copy(
                remoteUser = user.ifEmpty { base.remoteUser },
                jumpHost = JumpHost(
                    enabled = values["${JUMP}ENABLED"]?.trim()?.lowercase() in TRUE,
                    user = if (jumpUser == user) "" else jumpUser,
                    host = values["${JUMP}HOSTNAME"]?.trim().orEmpty(),
                    port = values["${JUMP}PORT"]?.trim().orEmpty(),
                    forwardAgent = values["${JUMP}FORWARD_AGENT"]?.trim()?.lowercase() in TRUE,
                    extraArgs = values["${JUMP}EXTRA_ARGS"]?.trim().orEmpty(),
                ),
                skipHostKeyChecking = true,
            )
        }
        val compose = LinkedHashMap(base.composeVariables)
        for (name in composeVariables) {
            if (name in ignored) continue
            values[name]?.let { compose[name] = it }
        }
        return result.copy(composeVariables = compose)
    }

    private val TRUE = setOf("true", "yes", "1", "on")
}
