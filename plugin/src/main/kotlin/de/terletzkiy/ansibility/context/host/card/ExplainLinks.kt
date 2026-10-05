package de.terletzkiy.ansibility.context.host.card

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import de.terletzkiy.ansibility.api.EvalTarget
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.PlayGraph
import de.terletzkiy.ansibility.api.VarSourceRef
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The "Explain precedence ›" links of the host-aware card (plan amendment R7/R8, F8.2, ex-X14):
 * `psi_element://ansibility-host/explain?name=…&root=…&env=…&host=…&play=<playbook url>&index=<play index>&dir=<playbook dir url>&role=…&local=<task file url>&at=<key offset>`.
 * The `psi_element://` scheme keeps the documentation popup from handing a link to a browser; [HostCardLinkHandler]
 * resolves it to an [ExplainDocumentationTarget]. A link names one evaluation context and the variable, never a value.
 */
internal object ExplainLinks {
    private const val PREFIX = "psi_element://ansibility-host/explain?"

    /** One explained context: variable [name] for [host], in the play at [playIndex] of [playUrl] (none: inventory only). */
    data class Request(
        val name: String,
        val host: HostKey,
        val playUrl: String?,
        val playIndex: Int,
        val playbookDirUrl: String?,
        /** The role whose task asks (its defaults and vars are applied last), or null for a play-level task. */
        val runningRole: String?,
        /** The file of the block or task var in force where the card was opened ([TaskVars.applied]), or null. */
        val taskVarUrl: String? = null,
        /** The key offset of that block or task var in [taskVarUrl], or -1. */
        val taskVarOffset: Int = -1,
    ) {
        /** The context this request names, or null when its play or playbook dir no longer exists. Needs a read action. */
        fun target(project: Project): EvalTarget? {
            val manager = VirtualFileManager.getInstance()
            val play = playUrl?.let { url ->
                val file = manager.findFileByUrl(url)?.takeIf { it.isValid && !it.isDirectory } ?: return null
                PlayGraph.getInstance(project).playsOf(file).getOrNull(playIndex)?.ref ?: return null
            }
            val playbookDir = playbookDirUrl?.let { url -> manager.findFileByUrl(url)?.takeIf { it.isValid && it.isDirectory } ?: return null }
            return EvalTarget(host, play, playbookDir)
        }
    }

    /** The link that explains [name] in [target] for a task of [runningRole], where block or task var [taskVar] is in force. */
    fun of(name: String, target: EvalTarget, runningRole: String?, taskVar: VarSourceRef? = null): String =
        of(request(name, target, runningRole, taskVar))

    fun request(name: String, target: EvalTarget, runningRole: String?, taskVar: VarSourceRef? = null): Request = Request(
        name = name,
        host = target.host,
        playUrl = target.play?.file?.url,
        playIndex = target.play?.playIndex ?: -1,
        playbookDirUrl = target.playbookDir?.url,
        runningRole = runningRole,
        taskVarUrl = taskVar?.file?.url,
        taskVarOffset = taskVar?.offset ?: -1,
    )

    fun of(request: Request): String {
        val parameters = listOfNotNull(
            NAME to request.name,
            ROOT to request.host.root,
            ENVIRONMENT to request.host.environment,
            HOST to request.host.host,
            request.playUrl?.let { PLAY to it },
            request.playUrl?.let { INDEX to request.playIndex.toString() },
            request.playbookDirUrl?.let { DIR to it },
            request.runningRole?.let { ROLE to it },
            request.taskVarUrl?.let { LOCAL to it },
            request.taskVarUrl?.let { AT to request.taskVarOffset.toString() },
        )
        return PREFIX + parameters.joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8) }
    }

    /** The request of an explain link, or null for any other URL (or a malformed one). */
    fun parse(url: String): Request? {
        if (!url.startsWith(PREFIX)) return null
        val parameters = url.removePrefix(PREFIX).split('&').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to (decode(part.substring(at + 1)) ?: return null)
        }.toMap()
        val name = parameters[NAME]?.takeIf { it.isNotEmpty() } ?: return null
        val host = HostKey(parameters[ROOT] ?: return null, parameters[ENVIRONMENT] ?: return null, parameters[HOST] ?: return null)
        val playUrl = parameters[PLAY]
        val index = if (playUrl == null) -1 else parameters[INDEX]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val taskVarUrl = parameters[LOCAL]
        val taskVarOffset = if (taskVarUrl == null) -1 else parameters[AT]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return Request(name, host, playUrl, index, parameters[DIR], parameters[ROLE], taskVarUrl, taskVarOffset)
    }

    /** [value] URL-decoded, or null when it is not a valid encoding (a `%` without two hex digits). */
    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    private const val NAME = "name"
    private const val ROOT = "root"
    private const val ENVIRONMENT = "env"
    private const val HOST = "host"
    private const val PLAY = "play"
    private const val INDEX = "index"
    private const val DIR = "dir"
    private const val ROLE = "role"
    private const val LOCAL = "local"
    private const val AT = "at"
}
