package de.terletzkiy.ansibility.lint

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.RootKeys

/**
 * Shows ansible-lint's findings in the editor (plan amendment R28). It runs no process: [LintService] lints on open
 * and save; a file without a result for its saved content gets a [LintService.request] here (opening it), and the
 * service restarts highlighting when the findings arrive. Findings follow edits through range markers.
 */
class LintAnnotator : ExternalAnnotator<LintAnnotator.Info, LintAnnotator.Result>() {
    class Info(val project: com.intellij.openapi.project.Project, val file: VirtualFile, val severity: LintSeverity)

    class Result(val findings: List<Pair<LintFinding, TextRange>>, val severity: LintSeverity)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Info? = collectInformation(file)

    override fun collectInformation(file: PsiFile): Info? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        if (!virtualFile.isInLocalFileSystem) return null
        val project = file.project
        val root = AnsibleWorkspace.getInstance(project).rootFor(virtualFile) ?: return null
        val settings = LintSettings.getInstance(project).rootSettings(RootKeys.keyOf(project, root.dir))
        if (!settings.enabled) return null
        return Info(project, virtualFile, settings.severity)
    }

    override fun doAnnotate(info: Info?): Result? {
        info ?: return null
        if (info.project.isDisposed) return null
        val service = LintService.getInstance(info.project)
        val result = service.result(info.file)
        if (result == null) {
            if (service.needsRun(info.file)) service.request(info.file)
            return null
        }
        return Result(result.findings.map { it.finding to it.range }, info.severity)
    }

    override fun apply(file: PsiFile, result: Result?, holder: AnnotationHolder) {
        result ?: return
        val length = file.textLength
        for ((finding, raw) in result.findings) {
            val range = if (raw.endOffset <= length) raw else continue
            val text = message("annotation.text", finding.rule, finding.message.ifBlank { finding.description.orEmpty() })
            val help = finding.help?.takeIf { it.isNotBlank() }?.let { message("annotation.tooltip.help", StringUtil.escapeXmlEntities(it)) }.orEmpty()
            val link = finding.url?.let { message("annotation.tooltip.link", it) }.orEmpty()
            val description = listOfNotNull(finding.message.takeIf { it.isNotBlank() }, finding.description).distinct().joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
            var builder = holder.newAnnotation(severityOf(finding.level, result.severity), text)
                .range(range)
                .tooltip(message("annotation.tooltip", finding.rule, description, help, link))
            for (fix in LintFixes.of(finding)) builder = builder.withFix(fix)
            builder.create()
        }
    }

    companion object {
        /** D-L2: the editor severity of an ansible-lint [level] under [mode]. */
        fun severityOf(level: LintLevel, mode: LintSeverity): HighlightSeverity = when (mode) {
            LintSeverity.YELLOW -> if (level == LintLevel.ERROR) HighlightSeverity.WARNING else HighlightSeverity.WEAK_WARNING
            LintSeverity.MIRROR -> when (level) {
                LintLevel.ERROR -> HighlightSeverity.ERROR
                LintLevel.WARNING -> HighlightSeverity.WARNING
                LintLevel.NOTE -> HighlightSeverity.WEAK_WARNING
            }
            LintSeverity.WEAK -> HighlightSeverity.WEAK_WARNING
        }
    }
}
