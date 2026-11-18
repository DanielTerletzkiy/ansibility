package de.terletzkiy.ansibility.vars

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleContextService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.HostKey
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.VarSourceRef
import de.terletzkiy.ansibility.context.host.card.HostCardViews
import de.terletzkiy.ansibility.settings.EnvironmentChoice
import org.jetbrains.annotations.Nls

/**
 * The definition that wins for the selected Ansible context (plan amendment R31, D229 item 2 and D234): the variable
 * card's winner logic ([HostCardViews], the cached single-name evaluation of the Effective section and the ranked "Set
 * in" rows), read at one position. Only with a selected environment or host: in All mode the hosts of every environment
 * get their own winners, and the card already lists them.
 *
 * Call in a read action in smart mode; nothing here decrypts or reads a value beyond the engine's vault-safe previews.
 */
internal class VarContextWinner private constructor(
    /** The winning definition, as the engine knows it (a `hosts.yml` `host:port` entry the symbol may not list). */
    val ref: VarSourceRef,
    /** The environment of the selection's hosts (`prod`). */
    val environment: String?,
    /** The one host of a host-mode selection, else null. */
    val host: HostKey?,
    /** The hosts of the scope this definition wins on, and all hosts of the scope. */
    val wins: Int,
    val of: Int,
) {
    val location: SourceLocation get() = SourceLocation(ref.file, ref.offset)

    /** True when every host of the selection gets this definition. */
    val everywhere: Boolean get() = wins == of

    /** `✓ wins for prod-web1`, `✓ wins for prod (5 hosts)`, `✓ wins on 3 of 5 hosts of prod`: the mark of a Ctrl+B row. */
    @get:Nls
    val mark: String
        get() = when {
            host != null -> AnsibilityVarsBundle.message("source.wins.host", host.host)
            everywhere -> AnsibilityVarsBundle.message("source.wins.all", environment.orEmpty(), of)
            else -> AnsibilityVarsBundle.message("source.wins.some", wins, of, environment.orEmpty())
        }

    companion object {
        /**
         * The winner of [name] for the selection of [root], seen from [offset] of [file] (its play, the task vars in force
         * there); null in All mode, when the file's own scope overrides the selection (no host of the selection runs the
         * file), when no host of it gets a value, in dumb mode or when the model cannot answer.
         */
        fun at(project: Project, root: AnsibleRoot, file: VirtualFile, offset: Int, name: String): VarContextWinner? {
            if (!isSelected(project, root)) return null
            val view = HostCardViews.getInstance(project).view(file, offset, name) ?: return null
            if (view.scope.targets.isEmpty() || view.scope.overriddenSelection) return null
            val hosts = view.outcomes.flatMap { it.group.hosts }.distinct()
            val best = view.outcomes.firstOrNull { it.group.winner != null } ?: return null
            val winner = best.group.winner ?: return null
            val environment = best.group.hosts.map { it.environment }.distinct().singleOrNull()?.let(VarSourceTexts::environmentName)
            return VarContextWinner(winner, environment, hosts.singleOrNull(), best.group.hosts.distinct().size, hosts.size)
        }

        /** True when the selection of [root] names an environment or a host (anything but All). */
        fun isSelected(project: Project, root: AnsibleRoot): Boolean {
            val selection = AnsibleContextService.getInstance(project).selection(root)
            return selection.environment is EnvironmentChoice.Named || selection.host != null
        }
    }
}
