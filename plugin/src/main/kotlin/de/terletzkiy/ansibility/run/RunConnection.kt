package de.terletzkiy.ansibility.run

import de.terletzkiy.ansibility.run.settings.RunnerRootSettings

/**
 * How a run reaches its hosts, from the root's runner settings: `ansible_user` and `ansible_ssh_common_args` (no host
 * key checks, a `ProxyCommand` through the jump host), passed as one JSON `-e` before the run's own extra vars, so
 * those can still override them. Pure.
 */
object RunConnection {
    const val NO_HOST_KEY_CHECKS = "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null"

    /** `ssh [-A] [-p port] [extra] -W %h:%p [user@]host`, or null without an enabled jump host. */
    fun proxyCommand(settings: RunnerRootSettings): String? {
        val jump = settings.jumpHost
        val host = jump.host.trim()
        if (!jump.enabled || host.isEmpty()) return null
        val parts = arrayListOf("ssh")
        if (jump.forwardAgent) parts += "-A"
        jump.port.trim().takeIf { it.isNotEmpty() }?.let { parts += listOf("-p", it) }
        jump.extraArgs.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += listOf("-W", "%h:%p")
        val user = jump.user.trim().ifEmpty { settings.remoteUser.trim() }
        parts += if (user.isEmpty()) host else "$user@$host"
        return parts.joinToString(" ")
    }

    /** The value of `ansible_ssh_common_args`, or null when the settings add none. Ansible splits it like a shell. */
    fun sshCommonArgs(settings: RunnerRootSettings): String? {
        val parts = ArrayList<String>()
        if (settings.skipHostKeyChecking) parts += NO_HOST_KEY_CHECKS
        proxyCommand(settings)?.let { parts += "-o ProxyCommand=\"${quoted(it)}\"" }
        return parts.joinToString(" ").takeIf { it.isNotEmpty() }
    }

    fun extraVars(settings: RunnerRootSettings): Map<String, String> {
        val vars = LinkedHashMap<String, String>()
        settings.remoteUser.trim().takeIf { it.isNotEmpty() }?.let { vars["ansible_user"] = it }
        sshCommonArgs(settings)?.let { vars["ansible_ssh_common_args"] = it }
        return vars
    }

    /** The `-e` value of [extraVars] as a JSON object, or null when there is nothing to pass. */
    fun extraVarsArgument(settings: RunnerRootSettings): String? = extraVars(settings).takeIf { it.isNotEmpty() }?.let(::json)

    fun json(values: Map<String, String>): String = values.entries.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:${jsonString(value)}" }

    private fun jsonString(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** [text] for the inside of a double-quoted shlex word: only `\` and `"` are escaped there. */
    private fun quoted(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
