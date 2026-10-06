package de.terletzkiy.ansibility.context.layout

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.ProjectLayoutService
import de.terletzkiy.ansibility.api.RootKind
import de.terletzkiy.ansibility.context.AnsibleLayout
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.model.inventory.InventoryModels
import de.terletzkiy.ansibility.model.inventory.SourceProblem
import de.terletzkiy.ansibility.model.inventory.VarsConfig
import de.terletzkiy.ansibility.model.inventory.VarsDirectories
import de.terletzkiy.ansibility.semantics.layout.CfgErrorKind
import de.terletzkiy.ansibility.semantics.layout.CfgPathContext
import de.terletzkiy.ansibility.semantics.layout.CfgPathList
import de.terletzkiy.ansibility.semantics.layout.CfgSyntax
import de.terletzkiy.ansibility.semantics.layout.CfgSyntaxError
import de.terletzkiy.ansibility.semantics.layout.EntryHint
import org.jetbrains.annotations.Nls
import java.io.IOException

/**
 * The layout diagnostics ANS-L001–L008 (plan amendment R10, X108) for one file. Pure reads of the layout, the
 * inventory models and the VFS; the banner ([LayoutProblemBannerProvider]) and the vars inspection
 * ([LayoutVarsInspection]) present them. Call inside a read action, in smart mode.
 */
object LayoutDiagnostics {
    private val LOG = logger<LayoutDiagnostics>()

    enum class Code { L001, L002, L003, L004, L005, L006, L007, L008 }

    /** One problem: [offset] is where in the file it is, when one place is to blame. */
    data class Problem(val code: Code, val severity: HighlightSeverity, @Nls val message: String, val offset: Int? = null)

    /** Every layout problem the banner of [file] shows: `ansible.cfg` (L002–L004) and inventory sources (L001, L007, L008). */
    fun bannerProblems(project: Project, file: VirtualFile): List<Problem> {
        val context = AnsibleWorkspace.getInstance(project).contextOf(file) ?: return emptyList()
        return when (context.kind) {
            FileKind.ANSIBLE_CFG -> cfgProblems(context.root, file)
            FileKind.INVENTORY, FileKind.INVENTORY_INI -> sourceProblems(project, context.root, file)
            else -> emptyList()
        }
    }

    /** L002–L004 of the `ansible.cfg` [file] of [root]. */
    fun cfgProblems(root: AnsibleRoot, file: VirtualFile): List<Problem> {
        val text = load(file) ?: return emptyList()
        val problems = ArrayList<Problem>()
        val read = CfgSyntax.read(text)
        (listOfNotNull(CfgSyntax.checkFileName(file.name)) + read.errors).forEach { error ->
            problems += Problem(Code.L004, HighlightSeverity.ERROR, message("L004", cfgErrorText(error)))
        }
        val value = read.document.value("defaults", "inventory") ?: return problems
        val cfgDir = file.parent ?: return problems
        val base = if (root.kind == RootKind.NESTED_PLAYBOOK) root.parentDir ?: root.dir else root.dir
        for (entry in CfgPathList.inventory(value, CfgPathContext(cfgDir.path, base.path))) {
            val shown = entry.text
            when {
                EntryHint.CFG_DIR in entry.hints -> problems += Problem(Code.L002, HighlightSeverity.WARNING, message("L002", value))
                EntryHint.HASH_COMMENT in entry.hints -> problems += Problem(Code.L003, HighlightSeverity.WARNING, message("L003.hash", shown))
                EntryHint.QUOTED in entry.hints -> problems += Problem(Code.L003, HighlightSeverity.WARNING, message("L003.quoted", shown))
                entry.path == null || !entry.isFollowed ->
                    problems += Problem(Code.L003, HighlightSeverity.WEAK_WARNING, message("L003.notFollowed", shown))
                cfgDir.fileSystem.findFileByPath(entry.path!!) == null -> problems += if (EntryHint.HOST_LIST in entry.hints) {
                    Problem(Code.L003, HighlightSeverity.WARNING, message("L003.hostlist", shown))
                } else {
                    Problem(Code.L003, HighlightSeverity.WEAK_WARNING, message("L003.missing", shown))
                }
            }
        }
        return problems
    }

    /** L001, L007 and L008 of the inventory source [file]. */
    fun sourceProblems(project: Project, root: AnsibleRoot, file: VirtualFile): List<Problem> {
        val problems = ArrayList<Problem>()
        val core = TargetVersionDetector.getInstance(project).targetVersion(root).version
        for (env in InventoryModels.getInstance(project).environments(root)) {
            for (problem in env.problems) {
                if (problem.file != file) continue
                problems += when (problem.kind) {
                    SourceProblem.Kind.FAILED -> Problem(
                        Code.L001, HighlightSeverity.WARNING,
                        problem.message?.let { message("L001", it) } ?: message("L001.unknown"), problem.offset,
                    )
                    SourceProblem.Kind.SKIPPED_BY_EXTENSION -> Problem(
                        Code.L007, HighlightSeverity.WARNING,
                        if (core != null) message("L007", core.toString(), problem.message.orEmpty()) else message("L007.unknown"),
                    )
                    SourceProblem.Kind.DYNAMIC -> Problem(
                        Code.L008, HighlightSeverity.INFORMATION, message("L008", problem.message?.lowercase() ?: "plugin"),
                    )
                }
            }
        }
        return problems.distinct()
    }

    /** L005 and L006 of a `group_vars`/`host_vars` file: hidden by first-match, or in a directory nothing loads. */
    fun varsProblems(project: Project, file: VirtualFile, context: FileContext): List<Problem> {
        if (context.kind != FileKind.GROUP_VARS && context.kind != FileKind.HOST_VARS && context.kind != FileKind.OTHER) return emptyList()
        if (context.roleDir != null || context.moleculeScenarioDir != null) return emptyList()
        val varsDir = varsDirOf(file) ?: return emptyList()
        val owner = varsDir.parent ?: return emptyList()
        val problems = ArrayList<Problem>()
        hidden(context, file, varsDir)?.let(problems::add)
        if (isOrphan(project, context.root, owner)) {
            problems += Problem(Code.L006, HighlightSeverity.WEAK_WARNING, message("L006"))
        }
        return problems
    }

    private fun hidden(context: FileContext, file: VirtualFile, varsDir: VirtualFile): Problem? {
        if (file.parent != varsDir) return null
        val entity = context.group ?: context.host ?: return null
        val loaded = VarsDirectories.files(varsDir, entity, VarsConfig.of(context.root).extensions)
        if (loaded.isEmpty() || file in loaded) return null
        val winner = loaded.first()
        val shown = VfsUtilCore.getRelativePath(winner, varsDir.parent ?: varsDir) ?: winner.name
        return Problem(Code.L005, HighlightSeverity.WARNING, message("L005", shown, entity))
    }

    /**
     * A vars dir next to no inventory source and no playbook. Convention env dirs (`environments/<env>`,
     * `inventories/<env>`) and root dirs keep today's treatment and are never orphans.
     */
    private fun isOrphan(project: Project, root: AnsibleRoot, owner: VirtualFile): Boolean {
        if (owner.parent?.name in AnsibleLayout.ENVIRONMENT_PARENTS) return false
        if (AnsibleWorkspace.getInstance(project).roots().any { it.dir == owner }) return false
        val layout = ProjectLayoutService.getInstance(project).layout(root)
        if (layout.inventories.any { owner in it.varsDirs }) return false
        val workspace = AnsibleWorkspace.getInstance(project)
        return owner.children.orEmpty().none { child ->
            !child.isDirectory && AnsibleLayout.isYamlName(child.name) && workspace.contextOf(child)?.kind == FileKind.PLAYBOOK
        }
    }

    private fun varsDirOf(file: VirtualFile): VirtualFile? {
        var current = file.parent
        while (current != null) {
            if (current.name == AnsibleLayout.GROUP_VARS || current.name == AnsibleLayout.HOST_VARS) return current
            current = current.parent
        }
        return null
    }

    @Nls
    private fun cfgErrorText(error: CfgSyntaxError): String = when (error.kind) {
        CfgErrorKind.DUPLICATE_OPTION ->
            message("L004.DUPLICATE_OPTION", error.line ?: 0, error.key.orEmpty(), error.section.orEmpty(), error.previousLine ?: 0)
        CfgErrorKind.DUPLICATE_SECTION -> message("L004.DUPLICATE_SECTION", error.line ?: 0, error.section.orEmpty(), error.previousLine ?: 0)
        CfgErrorKind.MISSING_SECTION_HEADER -> message("L004.MISSING_SECTION_HEADER", error.line ?: 0)
        CfgErrorKind.PARSING_ERROR -> message("L004.PARSING_ERROR", error.line ?: 0)
        CfgErrorKind.UNSUPPORTED_EXTENSION -> message("L004.UNSUPPORTED_EXTENSION")
        CfgErrorKind.UNSUPPORTED_TYPE -> message("L004.UNSUPPORTED_TYPE")
    }

    private fun load(file: VirtualFile): String? = try {
        VfsUtilCore.loadText(file)
    } catch (e: IOException) {
        LOG.debug("Cannot read ${file.path}", e)
        null
    }

    @Nls
    private fun message(key: String, vararg params: Any): String = AnsibilityLayoutBundle.message(key, *params)
}
