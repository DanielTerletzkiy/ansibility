package de.terletzkiy.ansibility.dispatch

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RenderContext
import de.terletzkiy.ansibility.api.RoleRegistry
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.TemplateContextService
import de.terletzkiy.ansibility.api.VarService
import de.terletzkiy.ansibility.context.AnsibilityCoreBundle
import de.terletzkiy.ansibility.context.AnsibleContextWidget
import de.terletzkiy.ansibility.context.ContextPresentation
import de.terletzkiy.ansibility.context.ContextPresentation.Detail
import de.terletzkiy.ansibility.context.switching.ContextReportLines

/**
 * X75: everything the plugin knows about one caret position, as labelled lines. The popup of
 * [ShowAnsibleContextAction] shows them and copies them as text for bug reports.
 *
 * The lines are those of the X02 status-bar details ([ContextPresentation.details]: root, root kind including a
 * detached worktree, role, layer, environment, group, host, molecule scenario, target ansible-core and its
 * source), plus the role entry point of a task file, for a template the render contexts of
 * [TemplateContextService] (one line per rendering task: how it names the template, the task's `path:line`, the
 * templates it is included through and the loop variable with its source), the
 * [AnsibleSite][de.terletzkiy.ansibility.api.AnsibleSite] classified at the caret with its text and classifier,
 * and, when a [VarService] is available and the site names a variable, how many definitions and spec bindings the
 * variable has in the root, or that it is not found there.
 */
class AnsibleContextReport(val lines: List<Detail>) {

    /** `Label: value` lines, for the clipboard. */
    fun asText(): String = lines.joinToString("\n") { "${it.label}: ${it.value}" }

    /** The value of the line labelled [label], or null. */
    fun valueOf(label: String): String? = lines.firstOrNull { it.label == label }?.value

    companion object {
        private const val MAX_SITE_TEXT = 80

        /**
         * The report for [file] at [offset] (null when there is no caret), or null when the file is outside every
         * Ansible root. Needs a read lock and smart mode (classifiers and [VarService] use indexes).
         */
        fun build(project: Project, file: VirtualFile, offset: Int?): AnsibleContextReport? {
            val state = AnsibleContextWidget.computeState(project, file) ?: return null
            val lines = ContextPresentation.details(state.root, state.context, state.target, state.worktree).toMutableList()
            state.context?.let { context -> entryPointLine(project, file, context) }?.let { entryPoint ->
                val roleLabel = AnsibilityCoreBundle.message("details.role")
                val roleLine = lines.indexOfFirst { it.label == roleLabel }
                lines.add(if (roleLine >= 0) roleLine + 1 else lines.size, entryPoint)
            }
            if (isTemplate(file, state.context)) lines += renderLines(project, state.root, file)
            val psiFile = if (offset == null) null else PsiManager.getInstance(project).findFile(file)
            var variable: String? = null
            if (psiFile != null && offset != null) {
                val at = offset.coerceIn(0, psiFile.textLength)
                lines += siteLines(project, state.root, psiFile, at)
                variable = SiteDispatch.classify(HostPosition(psiFile, at))?.site?.let(SitePresentation::variableName)
            }
            // X75 / F8.1: the host context (selection, applies to, effective scope, the variable's effective value).
            lines += ContextReportLines.lines(project, file, offset, variable)
            return AnsibleContextReport(lines)
        }

        /** `main (argument_specs entry)` for `tasks/main.yml`; the entry point of a role task file is its path below `tasks/`. */
        private fun entryPointLine(project: Project, file: VirtualFile, context: FileContext): Detail? {
            if (context.kind != FileKind.ROLE_TASKS) return null
            val tasksDir = context.roleDir?.findChild(TASKS_DIR) ?: return null
            val relative = VfsUtilCore.getRelativePath(file, tasksDir) ?: return null
            val entryPoint = relative.substringBeforeLast('.')
            val specs = project.serviceOrNull<RoleRegistry>()?.roleOf(file)?.argumentSpecs
            val value = when {
                specs == null -> entryPoint
                entryPoint in specs -> AnsibilityDispatchBundle.message("report.entry.point.spec", entryPoint)
                else -> AnsibilityDispatchBundle.message("report.entry.point.no.spec", entryPoint)
            }
            return Detail(AnsibilityDispatchBundle.message("report.entry.point"), value)
        }

        /** Role templates and any `.j2` file of a root (molecule `Dockerfile.j2`, playbook-level templates). */
        private fun isTemplate(file: VirtualFile, context: FileContext?): Boolean =
            context?.kind == FileKind.ROLE_TEMPLATE || file.name.endsWith(J2_SUFFIX)

        /** `Render contexts: 2 tasks` and one line per context, at most [MAX_RENDER_CONTEXTS]. */
        private fun renderLines(project: Project, root: AnsibleRoot, file: VirtualFile): List<Detail> {
            val service = project.serviceOrNull<TemplateContextService>() ?: return emptyList()
            ProgressManager.checkCanceled()
            val contexts = service.renderContexts(file)
            if (contexts.isEmpty()) {
                return listOf(line("report.render.contexts", AnsibilityDispatchBundle.message("report.render.contexts.none", root.displayName)))
            }
            val lines = mutableListOf(line("report.render.contexts", AnsibilityDispatchBundle.message("report.render.contexts.count", contexts.size)))
            contexts.take(MAX_RENDER_CONTEXTS).forEachIndexed { index, context ->
                lines += Detail(AnsibilityDispatchBundle.message("report.render.context", index + 1), describe(root, context))
            }
            if (contexts.size > MAX_RENDER_CONTEXTS) {
                val more = AnsibilityDispatchBundle.message("report.render.contexts.more.value", contexts.size - MAX_RENDER_CONTEXTS)
                lines += line("report.render.contexts.more", more)
            }
            return lines
        }

        /**
         * `dynamic src · roles/grafana/tasks/nginx.yml:33 · role grafana · loop item over grafana_nginx_sites`, with
         * ` · via a.j2 › b.j2` for a context inherited through `{% include %}`.
         */
        internal fun describe(root: AnsibleRoot, context: RenderContext): String {
            val parts = ArrayList<String>()
            parts += SitePresentation.renderKindName(context.kind)
            parts += locationLabel(root, context.taskSite)
            context.role?.let { parts += AnsibilityDispatchBundle.message("report.render.role", it.name) }
            if (context.via.isNotEmpty()) {
                parts += AnsibilityDispatchBundle.message("report.render.via", context.via.joinToString(" › ") { relativePath(root, it) })
            }
            context.loop?.let { loop ->
                val source = loop.sourceVariable?.let { (listOf(it) + loop.sourcePath).joinToString(".") }
                parts += if (source != null) {
                    AnsibilityDispatchBundle.message("report.render.loop", loop.loopVar, source)
                } else {
                    AnsibilityDispatchBundle.message("report.render.loop.untyped", loop.loopVar)
                }
            }
            return parts.joinToString(" · ")
        }

        /** `roles/grafana/tasks/nginx.yml:33`: [location] relative to [root] with its 1-based line. */
        private fun locationLabel(root: AnsibleRoot, location: SourceLocation): String {
            val file = location.file
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
            val line = if (document != null && location.offset <= document.textLength) {
                document.getLineNumber(location.offset)
            } else {
                StringUtil.offsetToLineNumber(VfsUtilCore.loadText(file), location.offset)
            }
            return "${relativePath(root, file)}:${line + 1}"
        }

        private fun relativePath(root: AnsibleRoot, file: VirtualFile): String =
            VfsUtilCore.getRelativePath(file, root.dir) ?: file.presentableUrl

        private fun siteLines(project: Project, root: AnsibleRoot, file: PsiFile, offset: Int): List<Detail> {
            val classified = SiteDispatch.classify(HostPosition(file, offset))
                ?: return listOf(line("report.site", AnsibilityDispatchBundle.message("report.site.none")))
            val site = classified.site
            val lines = mutableListOf(line("report.site", SitePresentation.describe(site)))
            siteText(file, site.range)?.let { lines += line("report.site.text", it) }
            lines += line("report.site.classifier", classified.classifier.javaClass.simpleName)
            val name = SitePresentation.variableName(site) ?: return lines
            lines += line("report.variable", name)
            val vars = project.serviceOrNull<VarService>() ?: return lines
            ProgressManager.checkCanceled()
            val symbol = vars.symbol(root, name)
            if (symbol.definitions.isEmpty() && symbol.specBindings.isEmpty()) {
                lines += line("report.resolution", AnsibilityDispatchBundle.message("report.not.found", root.displayName))
                return lines
            }
            val definitions = if (symbol.definitions.isEmpty()) {
                AnsibilityDispatchBundle.message("report.definitions.none", root.displayName)
            } else {
                AnsibilityDispatchBundle.message(
                    "report.definitions.value",
                    symbol.definitions.size,
                    root.displayName,
                    SitePresentation.definitionKinds(symbol.definitions.map { it.kind }),
                )
            }
            lines += line("report.definitions", definitions)
            val bindings = if (symbol.specBindings.isEmpty()) {
                AnsibilityDispatchBundle.message("report.spec.bindings.none")
            } else {
                AnsibilityDispatchBundle.message(
                    "report.spec.bindings.value",
                    symbol.specBindings.size,
                    symbol.specBindings.joinToString(", ") { "${it.role.name} › ${it.entryPoint}" },
                )
            }
            lines += line("report.spec.bindings", bindings)
            return lines
        }

        private fun siteText(file: PsiFile, range: TextRange): String? {
            val text = file.viewProvider.contents
            if (range.isEmpty || range.endOffset > text.length) return null
            return StringUtil.shortenTextWithEllipsis(range.subSequence(text).toString().replace('\n', ' '), MAX_SITE_TEXT, 0)
        }

        private fun line(labelKey: String, value: String) = Detail(AnsibilityDispatchBundle.message(labelKey), value)

        private const val TASKS_DIR = "tasks"
        private const val J2_SUFFIX = ".j2"
        private const val MAX_RENDER_CONTEXTS = 10
    }
}
