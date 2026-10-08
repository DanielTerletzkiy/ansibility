package de.terletzkiy.ansibility.vars.registered

import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.resolve.register.RegisteredResult
import de.terletzkiy.ansibility.resolve.register.RegisteredResults
import de.terletzkiy.ansibility.semantics.registered.ResultMember
import de.terletzkiy.ansibility.vars.LoopItems

/**
 * A documented member of a registered result (plan amendment FU, F1.12): `result.path…` is [member], written as
 * [display] at the reference.
 */
internal class RegisteredMember(
    val result: RegisteredResult,
    /** Keys below the registered variable, list indices included (`["results", "0", "stdout"]`). */
    val path: List<String>,
    val member: ResultMember,
    val display: String,
) {
    /** The member's own key: the last non-index segment of [path]. */
    val key: String get() = path.lastOrNull { it.toIntOrNull() == null } ?: result.name

    override fun toString(): String = "RegisteredMember($display)"
}

/**
 * Finds the registered member a Jinja reference reads (hover, Ctrl+B): `x.stdout`, `x['stat']['exists']`, and the
 * loop variable of a loop over a registered result (`item.stdout` in a task with `loop: "{{ x.results }}"`, or in the
 * templates it renders), resolved where the loop runs. Template locals and the variable itself (no accessor) are not
 * members. Call in a read action in smart mode.
 */
internal object RegisteredSites {
    /** The member [site] (in host [file]) reads, or null when it is no documented member of a visible register. */
    fun memberAt(file: PsiFile, site: AnsibleSite.VarRef): RegisteredMember? {
        if (site.attrPath.isEmpty() || site.name in site.localNames) return null
        val project = file.project
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val offset = site.range.startOffset
        val results = RegisteredResults.getInstance(project)
        val view = MoleculeView.of(project, virtualFile)
        val display = display(site.name, site.attrPath)
        val binding = LoopItems.bindingAt(project, virtualFile, offset, site.name)
        if (binding != null) {
            val (variable, path) = binding.documented(site.name, site.attrPath) ?: return null
            val task = binding.tasks.firstOrNull()?.task ?: return null
            val result = results.at(task.file, task.offset, variable, view) ?: return null
            val member = result.member(path) ?: return null
            return RegisteredMember(result, path, member, display)
        }
        val result = results.at(virtualFile, offset, site.name, view) ?: return null
        val member = result.member(site.attrPath) ?: return null
        return RegisteredMember(result, site.attrPath, member, display)
    }

    /** `x.results[0].stdout`: the name, attributes after dots and list indices in brackets. */
    fun display(name: String, path: List<String>): String = buildString {
        append(name)
        for (segment in path) if (segment.toIntOrNull() != null) append('[').append(segment).append(']') else append('.').append(segment)
    }
}
