package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.MoleculeView
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.index.VarUseIndex
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaIndirection

/** One reference to a variable in Jinja: a template file, a templated YAML scalar or a bare expression. */
data class VarUsage(
    val name: String,
    /** Where the root name starts. */
    val location: SourceLocation,
    val container: JinjaContainer,
    /** Directly followed by `is defined`/`is undefined` or `| default`. */
    val guarded: Boolean,
    /** Inside a condition that asserts the name is defined. */
    val guardedByCondition: Boolean,
    /** Called directly (`lookup(…)`): a Jinja global rather than a variable, in most cases. */
    val called: Boolean,
    /** Constant accessors after the root name. */
    val attrPath: List<String>,
    /**
     * Null for a direct reference; otherwise a member read by name ([UseEntry.indirect]): [JinjaIndirection.HOSTVARS]
     * (some host's variable, not the current host's) or [JinjaIndirection.VARS] (`vars['x']`, `lookup('vars', 'x')`).
     */
    val indirect: JinjaIndirection? = null,
) {
    /** The root name's range in the file. */
    val range: TextRange get() = TextRange(location.offset, location.offset + name.length)
}

/**
 * Usages of a variable name inside one root, read from `ansible.var.use` (plan A.7; Find Usages, undefined and unused
 * checks). Scoped like [VarServiceImpl] ([RootFamily]); uses in files outside any root, in tool configuration and in
 * argument specs are dropped at query time. Every method takes the request's [MoleculeView] (plan amendment R20,
 * D153): [MoleculeView.EXCLUDE] drops the uses in Molecule files; the default [MoleculeView.INCLUDE] keeps them
 * (inspections, rename).
 *
 * Methods take a read lock when the caller holds none and need smart mode.
 */
@Service(Service.Level.PROJECT)
class VarUsageQuery(private val project: Project) {
    /** Every use of [name] in [root], in file and offset order. */
    fun usages(root: AnsibleRoot, name: String, view: MoleculeView = MoleculeView.INCLUDE): List<VarUsage> = readLocked {
        val result = ArrayList<VarUsage>()
        process(root, name, null, view) { usage -> result += usage; true }
        result.sortWith(compareBy<VarUsage>({ it.location.file.path }, { it.location.offset }))
        result
    }

    /** True when [name] is used anywhere in [root]; stops at the first use. */
    fun hasUsages(root: AnsibleRoot, name: String, view: MoleculeView = MoleculeView.INCLUDE): Boolean = readLocked {
        var found = false
        process(root, name, null, view) { found = true; false }
        found
    }

    /**
     * Streams every use of [name] in [root] to [consumer], file by file in index order (not sorted), until it returns
     * false (Find Usages, F1.10). [scope] narrows the root's family further (a Find Usages dialog scope); null searches
     * the whole family. Returns false when [consumer] stopped the walk. Checks for cancellation once per file.
     */
    fun process(
        root: AnsibleRoot,
        name: String,
        scope: GlobalSearchScope?,
        view: MoleculeView = MoleculeView.INCLUDE,
        consumer: (VarUsage) -> Boolean,
    ): Boolean = readLocked {
        val workspace = AnsibleWorkspace.getInstance(project)
        val family = RootFamily.of(project, root, workspace, view)
        val searchScope = scope?.let { family.scope.intersectWith(it) } ?: family.scope
        FileBasedIndex.getInstance().processValues(
            VarUseIndex.NAME, name, null,
            FileBasedIndex.ValueProcessor { file, entries ->
                ProgressManager.checkCanceled()
                if (!counts(file, family, workspace)) return@ValueProcessor true
                entries.all { consumer(usage(name, file, it)) }
            },
            searchScope,
        )
    }

    /**
     * The uses of [name] in one [file] of [root], in offset order, from that file's own index data (no lookup across
     * the root). Empty when [file] does not count for [root].
     */
    fun usagesIn(root: AnsibleRoot, file: VirtualFile, name: String, view: MoleculeView = MoleculeView.INCLUDE): List<VarUsage> =
        usesOf(root, file, name, view) { FileBasedIndex.getInstance().getFileData(VarUseIndex.NAME, file, project)[name].orEmpty() }

    /**
     * The uses of [name] among [entries], the `ansible.var.use` entries of [file] for that name as its indexer computes
     * them (the caret highlighting reads an open file that way, never the index), in offset order; empty when [file]
     * does not count for [root].
     */
    fun usagesIn(root: AnsibleRoot, file: VirtualFile, name: String, entries: List<UseEntry>, view: MoleculeView = MoleculeView.INCLUDE): List<VarUsage> =
        usesOf(root, file, name, view) { entries }

    private fun usesOf(root: AnsibleRoot, file: VirtualFile, name: String, view: MoleculeView, entries: () -> List<UseEntry>): List<VarUsage> = readLocked {
        val workspace = AnsibleWorkspace.getInstance(project)
        if (!counts(file, RootFamily.of(project, root, workspace, view), workspace)) return@readLocked emptyList()
        entries().map { usage(name, file, it) }.sortedBy { it.location.offset }
    }

    /** True when uses in [file] belong to [family]'s results (a file of the family whose Jinja Ansible templates). */
    private fun counts(file: VirtualFile, family: RootFamily, workspace: AnsibleWorkspace): Boolean {
        val context = workspace.contextOf(file) ?: return false
        return family.admits(file, context) && context.kind !in NOT_TEMPLATED
    }

    private fun usage(name: String, file: VirtualFile, entry: UseEntry) = VarUsage(
        name, SourceLocation(file, entry.offset), entry.container.container,
        entry.guarded, entry.guardedByCondition, entry.called, entry.attrPath, entry.indirect,
    )

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        /** File kinds whose Jinja-looking text Ansible never templates. */
        private val NOT_TEMPLATED = setOf(FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS)

        fun getInstance(project: Project): VarUsageQuery = project.service()
    }
}
