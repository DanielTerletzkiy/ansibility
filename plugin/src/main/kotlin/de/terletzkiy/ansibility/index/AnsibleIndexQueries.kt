package de.terletzkiy.ansibility.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext

/** A value found in one file together with that file's query-time context. */
data class InContext<T>(val file: VirtualFile, val context: FileContext, val value: T)

/**
 * Root-scoped queries over the Ansible indexes (plan A.7, A.8). Every query runs in one [RootFamily] and drops hits in
 * files that belong to no root or to another root, so a detached worktree or a sibling repo never leaks in.
 *
 * Call inside a read action in smart mode (index access throws `IndexNotReadyException` while indexing).
 */
object AnsibleIndexQueries {
    /** Every value stored under [key] in files of [family], grouped by file in path order. */
    fun <V> values(project: Project, id: ID<String, List<V>>, key: String, family: RootFamily): List<InContext<V>> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val hits = ArrayList<InContext<V>>()
        FileBasedIndex.getInstance().processValues(
            id, key, null,
            FileBasedIndex.ValueProcessor { file, values ->
                ProgressManager.checkCanceled()
                val context = workspace.contextOf(file)
                if (context != null && family.admits(file, context)) values.forEach { hits += InContext(file, context, it) }
                true
            },
            family.scope,
        )
        return hits.sortedWith(compareBy<InContext<V>> { it.file.path })
    }

    /** The keys of [id] that have at least one value in a file of [family]. */
    fun keys(project: Project, id: ID<String, *>, family: RootFamily): Set<String> {
        val workspace = AnsibleWorkspace.getInstance(project)
        val index = FileBasedIndex.getInstance()
        val candidates = ArrayList<String>()
        index.processAllKeys(id, { candidates += it; true }, family.scope, null)
        return candidates.filterTo(LinkedHashSet()) { key ->
            ProgressManager.checkCanceled()
            var found = false
            index.processValues(
                @Suppress("UNCHECKED_CAST") (id as ID<String, Any>), key, null,
                FileBasedIndex.ValueProcessor { file, _ ->
                    val context = workspace.contextOf(file)
                    found = context != null && family.admits(file, context)
                    !found
                },
                family.scope,
            )
            found
        }
    }

    /** Handlers answering to [name] (a handler `name` or a `listen` topic) in [root]. */
    fun handlers(project: Project, root: AnsibleRoot, name: String): List<InContext<HandlerEntry>> =
        values(project, HandlerIndex.NAME, name, RootFamily.of(project, root))

    /** Every handler name and `listen` topic in [root] (for `notify` completion). */
    fun handlerNames(project: Project, root: AnsibleRoot): Set<String> =
        keys(project, HandlerIndex.NAME, RootFamily.of(project, root))

    /** Tasks in [root] that run the module written as [name]. */
    fun moduleUses(project: Project, root: AnsibleRoot, name: String): List<InContext<Int>> =
        values(project, ModuleUseIndex.NAME, name, RootFamily.of(project, root))

    /** Every module name used in [root], as written (for doc prefetch). */
    fun moduleNames(project: Project, root: AnsibleRoot): Set<String> =
        keys(project, ModuleUseIndex.NAME, RootFamily.of(project, root))

    /** Every play and playbook import of [root]; each play's playbook dir is `file.parent`. */
    fun plays(project: Project, root: AnsibleRoot): List<InContext<PlayEntry>> =
        values(project, PlayIndex.NAME, PlayIndex.KEY, RootFamily.of(project, root))

    /** The `tags:` scalars in [root] that carry [tag]. */
    fun tagUses(project: Project, root: AnsibleRoot, tag: String): List<InContext<Int>> =
        values(project, TagIndex.NAME, tag, RootFamily.of(project, root))

    /** Every tag written in [root]. */
    fun tagNames(project: Project, root: AnsibleRoot): Set<String> =
        keys(project, TagIndex.NAME, RootFamily.of(project, root))

    /** Render sites in [root] whose literal template name is [src]. */
    fun renders(project: Project, root: AnsibleRoot, src: String): List<InContext<RenderEntry>> =
        values(project, TemplateUseIndex.NAME, src, RootFamily.of(project, root))

    /** Every literal template name rendered in [root]. */
    fun renderedNames(project: Project, root: AnsibleRoot): Set<String> =
        keys(project, TemplateUseIndex.NAME, RootFamily.of(project, root))
}
