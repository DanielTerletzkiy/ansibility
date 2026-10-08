package de.terletzkiy.ansibility.inspections.vault

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.inspections.modules.TaskFileChecks
import de.terletzkiy.ansibility.inspections.modules.TaskProblemInspection
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.settings.AnsibilityProjectSettings
import de.terletzkiy.ansibility.yaml.YamlPsi
import org.jetbrains.yaml.YAMLTokenTypes
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * ANS-V113: a task writes a secret (a vaulted variable, a `!vault` value, a whole-file vault, or anything under
 * `no_log: true`) into a file others can read ([SecretFileModeChecks]). Reported on the mode value (on the `mode` key
 * when the value is an alias), or on the module name when the task sets no mode or its mode is written elsewhere,
 * with the fixes "Set mode to 0600" and "Set mode to 0640". A missing mode is not reported while any file of the
 * project uses `module_defaults`, which may supply it (as for ANS-M002, [TaskFileChecks.moduleDefaultsInUse]). The
 * severity is the inspection profile's (WARNING by default, Settings › Editor › Inspections › Ansibility › Vault);
 * ignored paths are skipped. Nothing is decrypted.
 */
class AnsibleSecretFileModeInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val all = SecretFileModeChecks.of(file)
        if (all.isEmpty()) return null
        if (AnsibilityProjectSettings.getInstance(file.project).isIgnored(file.viewProvider.virtualFile)) return null
        val findings = if (all.any { it.site == ModeSite.ABSENT } && TaskFileChecks.moduleDefaultsInUse(file.project)) {
            all.filter { it.site != ModeSite.ABSENT }
        } else {
            all
        }
        return findings.mapNotNull { finding ->
            ProgressManager.checkCanceled()
            val (element, range) = TaskProblemInspection.anchor(file, finding.range) ?: return@mapNotNull null
            val wordRange = range.takeIf { finding.site == ModeSite.WORD }
            val fixes = SetFileModeFix.SAFE_MODES
                .map { mode -> SetFileModeFix(mode, finding.site, wordRange) }
                .filter { it.isApplicable(element, finding.written) }
            manager.createProblemDescriptor(
                element, range, finding.message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly, *fixes.toTypedArray<LocalQuickFix>(),
            )
        }.toTypedArray()
    }
}

/**
 * "Set mode to <mode>" (ANS-V113): the file gets a mode others cannot read, written as a quoted string the way
 * ansible-lint asks for (`mode: "0600"`). Where the finding sits ([ModeSite]) decides the edit:
 * - a mode value is replaced after its anchor and tag, which stay, so aliases of it keep loading (a single-quoted
 *   value stays single-quoted);
 * - a `mode:` without a value gets one, after its anchor (`mode: &m`);
 * - the value of a `mode=…` word of free-form arguments is replaced inside its string;
 * - an alias (`mode: *m`) is replaced, so the anchored value and its other aliases stay as they are;
 * - without a mode, or with one written elsewhere (merged in with `<<`), a `mode` line is added after the last line
 *   of the module's arguments at the column of their keys (a block mapping), or a `mode` entry before the closing
 *   brace of a flow mapping; the arguments are the module's mapping, the `action:` mapping, or the task's `args:`
 *   mapping when the module value is empty or a string. Free-form arguments without `args:` get no fix, nor does an
 *   aliased module mapping.
 *
 * Like [VaultFixes], the fix edits the text of the ModCommand copy's document, so nothing around the edit is
 * reformatted.
 */
class SetFileModeFix(
    private val mode: String,
    private val site: ModeSite,
    /** For [ModeSite.WORD]: the word's value inside the anchor scalar. */
    private val wordRange: TextRange?,
) : PsiUpdateModCommandQuickFix() {
    override fun getName(): String = AnsibilityVaultChecksBundle.message("fix.v113.set.mode", mode)

    override fun getFamilyName(): String = AnsibilityVaultChecksBundle.message("fix.v113.set.mode.family")

    /** Whether the fix can edit the problem's anchor [element]; [written] is the mode as written ([ModeSite.WORD]). */
    fun isApplicable(element: PsiElement, written: String?): Boolean = when (site) {
        ModeSite.VALUE -> element is YAMLScalar && element.parent is YAMLKeyValue && YamlPsi.contentStart(element) != null
        ModeSite.EMPTY_VALUE -> TaskProblemInspection.keyValueOf(element)?.let(::emptyValueStart) != null
        ModeSite.WORD -> element is YAMLScalar && wordRange != null && written != null && '\n' !in element.text &&
            wordRange.endOffset <= element.textLength && wordRange.substring(element.text) == written
        ModeSite.ALIAS -> aliasOf(element) != null
        ModeSite.SHARED, ModeSite.ABSENT -> argumentsOf(element) != null
    }

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
        val document = updater.document
        when (site) {
            ModeSite.VALUE -> {
                val scalar = element as? YAMLScalar ?: return
                // The anchor and tag before the content stay: an alias of a removed anchor would not load.
                val content = YamlPsi.contentStart(scalar) ?: return
                val quote = if (YamlPsi.styleOf(content) == ScalarStyle.SINGLE_QUOTED) SINGLE_QUOTE else DOUBLE_QUOTE
                VaultFixes.replace(project, document, content.textRange.startOffset, scalar.textRange.endOffset, quoted(quote))
            }
            ModeSite.EMPTY_VALUE -> {
                val start = TaskProblemInspection.keyValueOf(element)?.let(::emptyValueStart) ?: return
                val chars = document.charsSequence
                var blanks = start
                while (blanks < chars.length && (chars[blanks] == ' ' || chars[blanks] == '\t')) blanks++
                // Blanks up to the line end go; before a comment they stay as its separation.
                val to = if (blanks == chars.length || chars[blanks] == '\n' || chars[blanks] == '\r') blanks else start
                VaultFixes.replace(project, document, start, to, " " + quoted(DOUBLE_QUOTE))
            }
            ModeSite.WORD -> {
                val range = wordRange?.shiftRight(element.textRange.startOffset) ?: return
                VaultFixes.replace(project, document, range.startOffset, range.endOffset, mode)
            }
            ModeSite.ALIAS -> {
                val alias = aliasOf(element) ?: return
                VaultFixes.replace(project, document, alias.textRange.startOffset, alias.textRange.endOffset, quoted(DOUBLE_QUOTE))
            }
            ModeSite.SHARED, ModeSite.ABSENT -> {
                val arguments = argumentsOf(element) ?: return
                if (isFlow(arguments)) addToFlow(project, document, arguments) else addLine(project, document, arguments)
            }
        }
    }

    private fun quoted(quote: Char): String = "$quote$mode$quote"

    /** A `mode` line after the last line of the block mapping [arguments], at the column of its keys. */
    private fun addLine(project: Project, document: Document, arguments: YAMLMapping) {
        val first = arguments.keyValues.firstOrNull() ?: return
        val last = arguments.keyValues.last()
        val column = YamlPsi.columnOf(first)
        val lineEnd = document.getLineEndOffset(document.getLineNumber(last.textRange.endOffset))
        VaultFixes.replace(project, document, lineEnd, lineEnd, "\n" + " ".repeat(column) + "$MODE: " + quoted(DOUBLE_QUOTE))
    }

    /** A `mode` entry before the closing brace of the flow mapping [arguments], keeping the rest as written. */
    private fun addToFlow(project: Project, document: Document, arguments: YAMLMapping) {
        val text = arguments.text
        val close = text.lastIndexOf('}')
        if (close < 0) return
        val end = text.substring(0, close).trimEnd().length
        val separator = when {
            arguments.keyValues.isEmpty() -> ""
            text[end - 1] == ',' -> " "
            else -> ", "
        }
        val offset = arguments.textRange.startOffset + end
        VaultFixes.replace(project, document, offset, offset, separator + "$MODE: " + quoted(DOUBLE_QUOTE))
    }

    companion object {
        /** The modes the fixes offer: owner only, and owner plus group read. */
        val SAFE_MODES: List<String> = listOf("0600", "0640")

        private const val MODE = "mode"
        private const val ARGS = "args"
        private const val MODULE = "module"
        private const val SINGLE_QUOTE = '\''
        private const val DOUBLE_QUOTE = '"'

        /**
         * The argument mapping a missing `mode` goes into, from the problem's anchor: the module key (its mapping, else
         * the task's `args:` mapping) or the module name of an `action:` (the `action:` mapping of `module: copy`,
         * else the task's `args:` mapping).
         */
        private fun argumentsOf(element: PsiElement): YAMLMapping? {
            TaskProblemInspection.keyValueOf(element)?.let { module ->
                (module.value as? YAMLMapping)?.let { return it }
                return argsOf(module.parentMapping)
            }
            val keyValue = (element as? YAMLScalar)?.parent as? YAMLKeyValue ?: return null
            if (keyValue.keyText == MODULE) return keyValue.parentMapping
            return argsOf(keyValue.parentMapping)
        }

        private fun argsOf(task: YAMLMapping?): YAMLMapping? = task?.getKeyValueByKey(ARGS)?.value as? YAMLMapping

        private fun isFlow(mapping: YAMLMapping): Boolean = mapping.text.startsWith("{")

        /**
         * Where the value of a `mode:` without a value goes: after the colon, or after the anchor of the empty value,
         * which hangs off the key-value (`mode: &m`); null when it has a value.
         */
        private fun emptyValueStart(keyValue: YAMLKeyValue): Int? {
            if (keyValue.keyText != MODE || keyValue.value != null) return null
            val colon = keyValue.node.findChildByType(YAMLTokenTypes.COLON)?.psi ?: return null
            val anchor = generateSequence(colon.nextSibling) { it.nextSibling }
                .takeWhile { it is YAMLAnchor || (it.text.isBlank() && '\n' !in it.text) }
                .lastOrNull { it is YAMLAnchor }
            return (anchor ?: colon).textRange.endOffset
        }

        /** The alias that is the value of the `mode` key [element] (`mode: *m`). */
        private fun aliasOf(element: PsiElement): YAMLAlias? =
            TaskProblemInspection.keyValueOf(element)?.takeIf { it.keyText == MODE }?.value as? YAMLAlias
    }
}
