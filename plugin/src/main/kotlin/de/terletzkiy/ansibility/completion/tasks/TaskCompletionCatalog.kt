package de.terletzkiy.ansibility.completion.tasks

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleDocService
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.index.AnsibleIndexQueries
import de.terletzkiy.ansibility.index.ModuleUseIndex
import de.terletzkiy.ansibility.semantics.markup.AnsibleDocMarkup
import java.util.concurrent.ConcurrentHashMap

/** One module name offered at a task key position, with what its lookup item shows. */
internal data class ModuleEntry(
    /** The name as offered (an FQCN; a documentation alias or a collection redirect included). */
    val name: String,
    /** The canonical module after routing (`ansible.builtin.systemd` → `ansible.builtin.systemd_service`). */
    val canonical: String,
    /** The collection of [name] (`ansible.builtin`, `community.docker`), or null for a name without one. */
    val collection: String?,
    /** The short description as plain text (markup removed), or null when nothing documents the module. */
    val description: String?,
    /** The first redirect target when [name] is a redirect (`community.mysql.mysql_user` → `ansible.mysql.mysql_user`). */
    val redirect: String?,
    /** Deprecated by routing or by its own documentation on the root's target line. */
    val deprecated: Boolean,
    /** Takes a free-form string (`command`, `shell`), so its value usually follows on the key's line. */
    val freeForm: Boolean,
    /** Documents at least one option besides a free-form pseudo-option. */
    val hasOptions: Boolean,
) {
    val isBuiltin: Boolean get() = collection == BUILTIN

    companion object {
        const val BUILTIN: String = "ansible.builtin"
    }
}

/**
 * Project service: the per-root data task completion needs on every invocation, cached so an invocation stays far
 * below the plan's 100 ms budget.
 * - [modules]: every module name of the root's documentation sources ([AnsibleDocService.moduleSummaries], which
 *   never queues a local doc refresh) with its short description and deprecation state. Rebuilt when the docs change
 *   ([AnsibleDocService.docsTracker]), the target version changes ([TargetVersionDetector.modificationTracker]) or
 *   the roots change.
 * - [usedModules]: the module names already used in the root ([AnsibleIndexQueries.moduleNames]), for ranking.
 *   Rebuilt when the module-use index changed and the result is a few seconds old; empty while indexing.
 *
 * Every call needs a read action.
 */
@Service(Service.Level.PROJECT)
internal class TaskCompletionCatalog(private val project: Project) {
    private data class Stamp(val docs: Long, val target: Long, val structure: Long)

    private data class UsedStamp(val index: Long, val structure: Long, val computedAt: Long)

    private class Cached<T>(val root: AnsibleRoot, val stamp: Any, val value: T)

    private val modules = ConcurrentHashMap<String, Cached<List<ModuleEntry>>>()
    private val used = ConcurrentHashMap<String, Cached<Set<String>>>()

    /** The module entries of [root], sorted by name. */
    fun modules(root: AnsibleRoot): List<ModuleEntry> {
        val docs = AnsibleDocService.getInstance(project)
        val stamp = Stamp(
            docs.docsTracker.modificationCount,
            TargetVersionDetector.getInstance(project).modificationTracker.modificationCount,
            AnsibleWorkspace.getInstance(project).structureTracker.modificationCount,
        )
        modules[root.dir.url]?.takeIf { it.root == root && it.stamp == stamp }?.let { return it.value }
        val computed = buildModules(docs, root)
        modules[root.dir.url] = Cached(root, stamp, computed)
        return computed
    }

    /**
     * The module names written in task files of [root] (as written: FQCNs or short names); empty while indexing.
     *
     * Only a ranking signal, so a result stays valid for [USED_MODULES_TTL_MS] after the module-use index changed
     * (typing in a task file changes it on every keystroke); a change of the roots invalidates it at once.
     */
    fun usedModules(root: AnsibleRoot): Set<String> {
        if (DumbService.isDumb(project)) return emptySet()
        val indexStamp = FileBasedIndex.getInstance().getIndexModificationStamp(ModuleUseIndex.NAME, project)
        val structure = AnsibleWorkspace.getInstance(project).structureTracker.modificationCount
        val now = System.currentTimeMillis()
        used[root.dir.url]?.takeIf { cached ->
            val stamp = cached.stamp as UsedStamp
            cached.root == root && stamp.structure == structure && (stamp.index == indexStamp || now - stamp.computedAt < USED_MODULES_TTL_MS)
        }?.let { return it.value }
        val computed = AnsibleIndexQueries.moduleNames(project, root)
        used[root.dir.url] = Cached(root, UsedStamp(indexStamp, structure, now), computed)
        return computed
    }

    private fun buildModules(docs: AnsibleDocService, root: AnsibleRoot): List<ModuleEntry> =
        docs.moduleSummaries(root).map { (name, resolved) ->
            ProgressManager.checkCanceled()
            val doc = resolved?.doc
            val description = doc?.shortDescription?.takeIf { it.isNotBlank() }?.let { text ->
                StringUtil.collapseWhiteSpace(AnsibleDocMarkup.plainText(text))
            }
            ModuleEntry(
                name = name,
                canonical = resolved?.canonical ?: name,
                collection = collectionOf(name),
                description = description,
                redirect = resolved?.takeIf { it.isRedirected }?.redirectChain?.getOrNull(1),
                deprecated = resolved?.isDeprecated == true,
                freeForm = doc?.freeForm != null,
                hasOptions = doc?.options?.keys?.any { it != doc.freeForm } == true,
            )
        }.sortedBy { it.name }

    companion object {
        /** How long [usedModules] may lag behind edits of task files. */
        const val USED_MODULES_TTL_MS: Long = 5_000

        fun getInstance(project: Project): TaskCompletionCatalog = project.service()

        /** `community.docker` for `community.docker.docker_container`; null for a name with fewer than three parts. */
        fun collectionOf(fqcn: String): String? {
            val first = fqcn.indexOf('.')
            val second = if (first < 0) -1 else fqcn.indexOf('.', first + 1)
            return if (second < 0) null else fqcn.substring(0, second)
        }
    }
}
