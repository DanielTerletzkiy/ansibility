package de.terletzkiy.ansibility.vault.exclusion

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * The check of a name typed on the settings page (plan amendment R30 review fixes, decision 1), on Apply: refused when,
 * in an open project, it names a file Ansibility reads as vars or vault content ([KeepOutGuard.Reason.VARS_OR_VAULT]),
 * or more than [MAX_FILES] files the IDE would keep out. Such a name is a slip (an environment's or a role's name, a
 * pattern as broad as `*.yml`); the exact file is kept out from the Vault tab or a suggestion instead. Scripts do not
 * count: the IDE never keeps them out.
 *
 * Looks the files up in the file name index of each project's content (files kept out already are not there: they are
 * out). Paths and names only.
 */
object KeepOutNameCheck {
    /** How many files one name may keep out ("a handful"). */
    const val MAX_FILES: Int = 5

    /** How many files a problem names. */
    private const val SHOWN = 3

    /** Why a name is refused. */
    sealed class Problem(val name: String) {
        /** It names [files] (shown paths), which Ansibility reads as vars or vault content. */
        class VarsOrVault(name: String, val files: List<String>) : Problem(name)

        /** It names [count] files, more than [MAX_FILES]; [files] are the first few (shown paths). */
        class TooMany(name: String, val count: Int, val files: List<String>) : Problem(name)

        /** [project] is indexing, so its files cannot be looked up yet. */
        class Indexing(name: String, val project: String) : Problem(name)
    }

    /** The open projects the check looks at: [first] (the settings page's) and every other open one. */
    fun projects(first: Project?): List<Project> =
        (listOfNotNull(first) + ProjectManager.getInstance().openProjects).filter { !it.isDisposed && !it.isDefault }.distinct()

    /** The first problem of [names] in [projects], or null when every name is fine. EDT or any thread; not in a read action. */
    fun problemOf(names: Collection<String>, projects: List<Project>): Problem? {
        for (name in names) {
            val rules = KeepOutRules(listOf(name), emptyList())
            val named = LinkedHashMap<String, Pair<Project, VirtualFile>>()
            for (project in projects) {
                if (DumbService.isDumb(project)) return Problem.Indexing(name, project.name)
                try {
                    runReadActionBlocking { collect(project, rules, named) }
                } catch (_: IndexNotReadyException) {
                    return Problem.Indexing(name, project.name)
                }
            }
            val protected = named.values.filter { (_, file) -> KeepOutGuard.reasonNotToKeepOut(file) == KeepOutGuard.Reason.VARS_OR_VAULT }
            if (protected.isNotEmpty()) return Problem.VarsOrVault(name, protected.take(SHOWN).map(::shown))
            val kept = named.values.filter { (_, file) -> KeepOutGuard.canKeepOut(file) }
            if (kept.size > MAX_FILES) return Problem.TooMany(name, kept.size, kept.take(SHOWN).map(::shown))
        }
        return null
    }

    /** The files of [project]'s content that [rules] names, by exact path, into [into]. Read action, smart mode. */
    private fun collect(project: Project, rules: KeepOutRules, into: MutableMap<String, Pair<Project, VirtualFile>>) {
        val scope = GlobalSearchScope.projectScope(project)
        val names = ArrayList<String>()
        // Ignoring case first (a superset); each file then follows its own file system's rule.
        FilenameIndex.processAllFileNames({ name ->
            ProgressManager.checkCanceled()
            if (rules.matchesName(name, caseSensitive = false)) names += name
            true
        }, scope, null)
        for (name in names) {
            for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
                if (!file.isDirectory && rules.names(file)) into.putIfAbsent(KeepOutRules.pathOf(file), project to file)
            }
        }
    }

    private fun shown(entry: Pair<Project, VirtualFile>): String = RootKeys.relativePath(entry.first, entry.second) ?: entry.second.path
}
