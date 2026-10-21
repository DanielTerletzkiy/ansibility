package de.terletzkiy.ansibility.lint

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.settings.RootKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.yaml.psi.YAMLScalar
import java.awt.datatransfer.StringSelection
import java.nio.file.Files

/**
 * The quick fixes of an ansible-lint finding (plan amendment R28, L3): `# noqa`, `.ansible-lint-ignore`, `skip_list`,
 * "Fix with ansible-lint" for its transform rules, the rule's documentation and copying the message. The editing fixes
 * act on the line of the caret (Alt+Enter is invoked on the finding's line) and are one undo step each.
 */
object LintFixes {
    fun of(finding: LintFinding): List<IntentionAction> = buildList {
        if (finding.autofix) add(AutofixFix(finding))
        add(NoqaFix(finding.rule))
        add(IgnoreFileFix(finding.rule))
        add(SkipListFix(finding.rule))
        finding.url?.let { add(DocsFix(finding.rule, it)) }
        add(CopyFix("${finding.rule}: ${finding.message.ifBlank { finding.description.orEmpty() }} (${finding.file.fileName}:${finding.line})"))
    }

    abstract class Fix : IntentionAction {
        override fun getFamilyName(): String = message("fix.family")
        override fun startInWriteAction(): Boolean = false
        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null
    }

    /** Appends `# noqa: <rule>` to the line (or adds the rule to the line's `# noqa:`); never inside a multi-line scalar. */
    class NoqaFix(private val rule: String) : Fix() {
        override fun getText(): String = message("fix.noqa", rule)

        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
            if (editor == null || file == null) return false
            val document = editor.document
            val line = document.getLineNumber(editor.caretModel.offset)
            val end = document.getLineEndOffset(line)
            val element = file.findElementAt((end - 1).coerceAtLeast(document.getLineStartOffset(line))) ?: return true
            val scalar = PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false) ?: return true
            return !scalar.isMultiline || document.getLineNumber(scalar.textRange.endOffset) == line && document.getLineNumber(scalar.textRange.startOffset) == line
        }

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            editor ?: return
            val document = editor.document
            val line = document.getLineNumber(editor.caretModel.offset)
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val text = document.getText(com.intellij.openapi.util.TextRange(start, end))
            val updated = withNoqa(text, rule) ?: return
            WriteCommandAction.runWriteCommandAction(project, getText(), null, { document.replaceString(start, end, updated) }, file)
        }
    }

    /** Adds `<path> <rule>` to `.ansible-lint-ignore` next to the config (or the lint directory). */
    class IgnoreFileFix(private val rule: String) : Fix(), LowPriorityAction {
        override fun getText(): String = message("fix.ignore", rule)

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            val target = file?.virtualFile ?: return
            val root = AnsibleWorkspace.getInstance(project).rootFor(target) ?: return
            val base = configDir(project, root)
            val relative = VfsUtil.getRelativePath(target, base, '/') ?: return
            val entry = "$relative $rule"
            WriteCommandAction.runWriteCommandAction(project, getText(), null, {
                val ignore = base.findChild(IGNORE_FILE) ?: base.createChildData(this, IGNORE_FILE)
                val document = FileDocumentManager.getInstance().getDocument(ignore) ?: return@runWriteCommandAction
                if (document.text.lines().any { it.trim() == entry }) return@runWriteCommandAction
                val prefix = if (document.textLength > 0 && !document.text.endsWith("\n")) "\n" else ""
                document.insertString(document.textLength, "$prefix$entry\n")
            })
        }
    }

    /** Adds the rule to `skip_list` of the root's config (creating the list when there is none). */
    class SkipListFix(private val rule: String) : Fix(), LowPriorityAction {
        override fun getText(): String = message("fix.skip", rule)

        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = configOf(project, file) != null

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            val config = configOf(project, file) ?: return
            val document = FileDocumentManager.getInstance().getDocument(config) ?: return
            val updated = withSkipped(document.text, rule) ?: return
            WriteCommandAction.runWriteCommandAction(project, getText(), null, { document.setText(updated) })
        }
    }

    /** Runs `ansible-lint --fix=<rule>` on the file and applies the result as one undoable edit. */
    class AutofixFix(private val finding: LintFinding) : Fix() {
        override fun getText(): String = message("fix.autofix", finding.rule)

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            val target = file?.virtualFile ?: return
            val document = FileDocumentManager.getInstance().getDocument(target) ?: return
            FileDocumentManager.getInstance().saveDocument(document)
            val rule = finding.baseRule
            LintService.getInstance(project).scope().launch {
                withBackgroundProgress(project, message("fix.autofix.progress", finding.rule)) {
                    val root = readAction { AnsibleWorkspace.getInstance(project).rootFor(target) } ?: return@withBackgroundProgress
                    val path = target.toNioPath()
                    val before = withContext(Dispatchers.IO) { Files.readAllBytes(path) }
                    val outcome = LintRunner.run(project, root, listOf(target), fix = rule)
                    val after = withContext(Dispatchers.IO) { Files.readAllBytes(path) }
                    if (outcome is LintOutcome.Failed && after.contentEquals(before)) {
                        notify(project, message("fix.autofix.failed", finding.rule, outcome.reason), NotificationType.WARNING)
                        return@withBackgroundProgress
                    }
                    if (after.contentEquals(before)) {
                        notify(project, message("fix.autofix.unchanged", finding.rule), NotificationType.INFORMATION)
                        return@withBackgroundProgress
                    }
                    // Put the file back and apply ansible-lint's result through the document: one undo step.
                    withContext(Dispatchers.IO) { Files.write(path, before) }
                    val text = String(after, target.charset)
                    withContext(Dispatchers.EDT) {
                        target.refresh(false, false)
                        WriteCommandAction.runWriteCommandAction(project, message("fix.autofix", finding.rule), null, {
                            document.setText(com.intellij.openapi.util.text.StringUtil.convertLineSeparators(text))
                        })
                        FileDocumentManager.getInstance().saveDocument(document)
                    }
                }
            }
        }
    }

    class DocsFix(private val rule: String, private val url: String) : Fix(), LowPriorityAction {
        override fun getText(): String = message("fix.docs", rule)
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = BrowserUtil.browse(url)
    }

    class CopyFix(private val text: String) : Fix(), LowPriorityAction {
        override fun getText(): String = message("fix.copy")
        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    /** [line] with `# noqa: <rule>` (merged into an existing `# noqa:`), or null when it already has the rule. */
    fun withNoqa(line: String, rule: String): String? {
        val match = NOQA.find(line)
        if (match == null) return line.trimEnd() + "  # noqa: " + rule
        val rules = match.groupValues[1].split(' ', ',').filter { it.isNotBlank() }
        if (rule in rules) return null
        val upTo = line.substring(0, match.range.last + 1)
        val gap = upTo.substring(upTo.trimEnd().length)
        return upTo.trimEnd() + " " + rule + gap + line.substring(match.range.last + 1)
    }

    /** [config] with [rule] added to its top-level `skip_list` (a new list at the end when there is none), or null when it is there. */
    fun withSkipped(config: String, rule: String): String? {
        val lines = config.lines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("skip_list:") }
        if (index < 0) {
            val body = config.trimEnd('\n')
            return "$body\nskip_list:\n  - $rule\n"
        }
        if (lines[index].substringAfter(':').trim().startsWith("[")) {
            val inline = lines[index].substringAfter('[').substringBefore(']').split(',').map { it.trim().trim('"', '\'') }
            if (rule in inline) return null
            lines[index] = lines[index].replace("]", if (inline.all { it.isEmpty() }) "$rule]" else ", $rule]")
            return lines.joinToString("\n")
        }
        var last = index
        var indent = "  "
        for (i in index + 1 until lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!trimmed.startsWith("- ") && trimmed != "-") break
            indent = line.substring(0, line.length - trimmed.length)
            if (trimmed.removePrefix("-").substringBefore('#').trim().trim('"', '\'') == rule) return null
            last = i
        }
        lines.add(last + 1, "$indent- $rule")
        return lines.joinToString("\n")
    }

    private fun configOf(project: Project, file: PsiFile?): VirtualFile? {
        val target = file?.virtualFile ?: return null
        val root = AnsibleWorkspace.getInstance(project).rootFor(target) ?: return null
        val settings = LintSettings.getInstance(project).rootSettings(RootKeys.keyOf(project, root.dir))
        return LintRunner.configOf(project, root, settings)
    }

    private fun configDir(project: Project, root: AnsibleRoot): VirtualFile {
        val settings = LintSettings.getInstance(project).rootSettings(RootKeys.keyOf(project, root.dir))
        val config = LintRunner.configOf(project, root, settings)
        return config?.parent?.let { if (it.name == ".config") it.parent else it } ?: LintRunner.lintDir(root)
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(LintService.NOTIFICATION_GROUP).createNotification(text, type).notify(project)
    }

    private val NOQA = Regex("""#\s*noqa:\s*([^#]*)""")
    const val IGNORE_FILE = ".ansible-lint-ignore"
}
