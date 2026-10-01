package de.terletzkiy.ansibility.resolve

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.index.RootFamily
import de.terletzkiy.ansibility.index.UseEntry
import de.terletzkiy.ansibility.index.VarUseIndex

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
) {
    /** The root name's range in the file. */
    val range: TextRange get() = TextRange(location.offset, location.offset + name.length)
}

/**
 * Usages of a variable name inside one root, read from `ansible.var.use` (plan A.7; Find Usages, undefined and unused
 * checks). Scoped like [VarServiceImpl] ([RootFamily]); uses in files outside any root, in tool configuration and in
 * argument specs are dropped at query time.
 *
 * Methods take a read lock when the caller holds none and need smart mode.
 */
@Service(Service.Level.PROJECT)
class VarUsageQuery(private val project: Project) {
    /** Every use of [name] in [root], in file and offset order. */
    fun usages(root: AnsibleRoot, name: String): List<VarUsage> = readLocked {
        val result = ArrayList<VarUsage>()
        process(root, name) { usage -> result += usage; true }
        result.sortWith(compareBy<VarUsage>({ it.location.file.path }, { it.location.offset }))
        result
    }

    /** True when [name] is used anywhere in [root]; stops at the first use. */
    fun hasUsages(root: AnsibleRoot, name: String): Boolean = readLocked {
        var found = false
        process(root, name) { found = true; false }
        found
    }

    private fun process(root: AnsibleRoot, name: String, consumer: (VarUsage) -> Boolean) {
        val workspace = AnsibleWorkspace.getInstance(project)
        val family = RootFamily.of(project, root, workspace)
        FileBasedIndex.getInstance().processValues(
            VarUseIndex.NAME, name, null,
            FileBasedIndex.ValueProcessor { file, entries ->
                ProgressManager.checkCanceled()
                val context = workspace.contextOf(file)
                if (context == null || !family.admits(file, context) || context.kind in NOT_TEMPLATED) return@ValueProcessor true
                entries.all { consumer(usage(name, file, it)) }
            },
            family.scope,
        )
    }

    private fun usage(name: String, file: VirtualFile, entry: UseEntry) = VarUsage(
        name, SourceLocation(file, entry.offset), entry.container.container,
        entry.guarded, entry.guardedByCondition, entry.called, entry.attrPath,
    )

    private fun <T> readLocked(action: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) action() else runReadActionBlocking(action)

    companion object {
        /** File kinds whose Jinja-looking text Ansible never templates. */
        private val NOT_TEMPLATED = setOf(FileKind.ROLE_ARGSPEC, FileKind.ANSIBLE_CFG, FileKind.LINT_CONFIG, FileKind.REQUIREMENTS)

        fun getInstance(project: Project): VarUsageQuery = project.service()
    }
}
