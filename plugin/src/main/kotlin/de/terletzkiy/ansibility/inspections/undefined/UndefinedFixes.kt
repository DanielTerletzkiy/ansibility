package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.FutureVirtualFile
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.modcommand.ModCreateFile
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.RoleInfo
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * The ANS-V003 quick fixes (plan amendment R7/R8, F8.12 (e)). Each is a [ModCommandQuickFix], so the preview shows the
 * exact edit (the diff of the other file for the role default) and nothing is applied on the EDT outside a command:
 * - templates: [WrapInDefinedGuardFix] (`{% if x is defined and x %}…{% endif %}` around the statement's lines) and
 *   [AppendDefaultFix];
 * - YAML tasks: [AppendDefaultFix] and [AddWhenDefinedFix] (merged with an existing `when` as a list);
 * - a role of the same root: [AddRoleDefaultFix] (`defaults/main.yml`, plus `default:` in the argument spec when the
 *   spec declares the variable).
 *
 * Edits are computed from the current text when the fix runs (the file may have changed since highlighting) and
 * returned as [ModUpdateFileText], which keeps every untouched character, quotes and escapes included.
 */
internal object UndefinedFixes {
    /** The fixes for [finding] in [file] (the host file the finding was reported in). */
    fun of(file: PsiFile, context: FileContext, finding: UndefinedFinding): Array<LocalQuickFix> {
        val use = finding.use
        val fixes = ArrayList<LocalQuickFix>()
        val scalar = use.scalar
        if (scalar == null) {
            use.statementRange?.let { fixes += WrapInDefinedGuardFix(finding.name, it) }
            if (use.appendOffset >= 0) fixes += AppendDefaultFix(use.appendOffset, defaultFilter(use.raw.iterable, quote = '\''))
        } else {
            val quote = if (scalar.text.startsWith("'")) '"' else '\''
            if (use.appendOffset >= 0) fixes += AppendDefaultFix(use.appendOffset, defaultFilter(use.raw.iterable, quote))
            if (finding.taskOffset >= 0 && context.kind in TASK_FILE_KINDS) fixes += AddWhenDefinedFix(finding.name, finding.taskOffset)
        }
        val role = finding.roles.firstOrNull { it.ref.rootDir == context.root.dir && it.ref.dir.findChild(RoleLayout.DEFAULTS)?.isDirectory == true }
        if (role != null && AnsibleWorkspace.getInstance(file.project).rootFor(role.ref.dir) == context.root) {
            val spec = role.argumentSpecs.values.any { finding.name in it.options }
            fixes += AddRoleDefaultFix(finding.name, role.ref.name, role.ref.dir, spec, finding.spec)
        }
        return fixes.toTypedArray()
    }

    /** `| default('')`, or `| default([])` for a `for` iterable; [quote] is the quote the host allows unescaped. */
    fun defaultFilter(iterable: Boolean, quote: Char): String = if (iterable) "| default([])" else "| default($quote$quote)"

    private val TASK_FILE_KINDS = setOf(FileKind.ROLE_TASKS, FileKind.ROLE_HANDLERS, FileKind.PLAYBOOK)

    /** One text replacement in a file. */
    class Edit(val start: Int, val end: Int, val text: String)

    /** [edits] (non-overlapping) applied to the current text of [file] as one [ModUpdateFileText]. */
    fun update(project: Project, file: VirtualFile, edits: List<Edit>): ModCommand {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return ModCommand.nop()
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return ModCommand.nop()
        val old = document.text
        val builder = StringBuilder(old.length + edits.sumOf { it.text.length })
        val fragments = ArrayList<ModUpdateFileText.Fragment>()
        var last = 0
        var shift = 0
        for (edit in edits.sortedBy { it.start }) {
            if (edit.start < last || edit.end > old.length) return ModCommand.nop()
            builder.append(old, last, edit.start)
            fragments += ModUpdateFileText.Fragment(edit.start + shift, edit.end - edit.start, edit.text.length)
            builder.append(edit.text)
            shift += edit.text.length - (edit.end - edit.start)
            last = edit.end
        }
        builder.append(old, last, old.length)
        return ModUpdateFileText(file, old, builder.toString(), fragments)
    }

    /** The offset of the start of the line holding [offset] in [text]. */
    fun lineStart(text: CharSequence, offset: Int): Int = StringUtil.lastIndexOf(text, '\n', 0, offset.coerceIn(0, text.length)) + 1

    /** The offset after the line break ending the line that holds [offset] (the text length on the last line). */
    fun lineEnd(text: CharSequence, offset: Int): Int {
        val newline = StringUtil.indexOf(text, '\n', offset.coerceIn(0, text.length))
        return if (newline < 0) text.length else newline + 1
    }
}

/**
 * "Wrap in `{% if x is defined and x %}…{% endif %}`" (templates): the guard of `config-base.alloy.j2:15-17`, put on
 * lines of their own around every line of the statement that uses the variable (an output tag, or a whole `{% if %}`/
 * `{% for %}` block), at column 0 so Ansible's `trim_blocks` keeps the rendered text unchanged where the variable is set.
 */
class WrapInDefinedGuardFix(private val name: String, private val statement: TextRange) : ModCommandQuickFix() {
    private val opening: String get() = "{% if $name is defined and $name %}"

    override fun getName(): String = AnsibilityUndefinedBundle.message("fix.wrap.defined", "$opening…{% endif %}")

    override fun getFamilyName(): String = AnsibilityUndefinedBundle.message("fix.wrap.defined.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val file = descriptor.psiElement?.containingFile ?: return ModCommand.nop()
        val text = file.viewProvider.contents
        if (statement.endOffset > text.length) return ModCommand.nop()
        val start = UndefinedFixes.lineStart(text, statement.startOffset)
        val end = UndefinedFixes.lineEnd(text, (statement.endOffset - 1).coerceAtLeast(statement.startOffset))
        val closing = if (end == text.length && !text.endsWith("\n")) "\n{% endif %}" else "{% endif %}\n"
        return UndefinedFixes.update(
            project,
            file.viewProvider.virtualFile,
            listOf(UndefinedFixes.Edit(start, start, "$opening\n"), UndefinedFixes.Edit(end, end, closing)),
        )
    }
}

/** "Append `| default('')`" (`| default([])` for a `for` iterable) right after the variable and its accessors. */
class AppendDefaultFix(private val offset: Int, private val filter: String) : ModCommandQuickFix() {
    override fun getName(): String = AnsibilityUndefinedBundle.message("fix.append.default", filter)

    override fun getFamilyName(): String = AnsibilityUndefinedBundle.message("fix.append.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val file = descriptor.psiElement?.containingFile?.let { it.viewProvider.virtualFile } ?: return ModCommand.nop()
        return UndefinedFixes.update(project, file, listOf(UndefinedFixes.Edit(offset, offset, " $filter")))
    }
}

/**
 * "Add `when: x is defined`" to the task whose mapping starts at [taskOffset]: a new `when:` key after the task's last
 * key, or the condition as the first item of an existing `when` (a scalar becomes a two-item list), so it is evaluated
 * before the conditions that use the variable.
 */
class AddWhenDefinedFix(private val name: String, private val taskOffset: Int) : ModCommandQuickFix() {
    private val condition: String get() = "$name is defined"

    override fun getName(): String = AnsibilityUndefinedBundle.message("fix.add.when", condition)

    override fun getFamilyName(): String = AnsibilityUndefinedBundle.message("fix.add.when.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val file = descriptor.psiElement?.containingFile as? YAMLFile ?: return ModCommand.nop()
        val item = TaskFileModels.of(file).itemAt(taskOffset)?.takeIf { it.range.startOffset == taskOffset } ?: return ModCommand.nop()
        val mapping = PsiTreeUtil.findElementOfClassAtRange(file, item.range.startOffset, item.range.endOffset, YAMLMapping::class.java)
            ?: PsiTreeUtil.getParentOfType(file.findElementAt(taskOffset), YAMLMapping::class.java, false)
            ?: return ModCommand.nop()
        val edit = edit(file.viewProvider.contents, mapping) ?: return ModCommand.nop()
        return UndefinedFixes.update(project, file.viewProvider.virtualFile, listOf(edit))
    }

    private fun edit(text: CharSequence, mapping: YAMLMapping): UndefinedFixes.Edit? {
        val keyIndent = YAMLUtil.getIndentToThisElement(mapping)
        val existing = mapping.getKeyValueByKey(WHEN)
        if (existing == null) {
            val end = mapping.textRange.endOffset
            return UndefinedFixes.Edit(end, end, "\n" + " ".repeat(keyIndent) + "$WHEN: $condition")
        }
        return when (val value = existing.value) {
            is YAMLSequence -> {
                if (value.text.startsWith("[")) {
                    val open = value.textRange.startOffset + 1
                    UndefinedFixes.Edit(open, open, if (value.items.isEmpty()) condition else "$condition, ")
                } else {
                    val first = value.items.firstOrNull() ?: return null
                    val lineStart = UndefinedFixes.lineStart(text, first.textRange.startOffset)
                    val indent = first.textRange.startOffset - lineStart
                    UndefinedFixes.Edit(lineStart, lineStart, " ".repeat(indent) + "- $condition\n")
                }
            }
            is YAMLScalar -> {
                if ('\n' in value.text) return null
                val key = existing.key ?: return null
                val itemIndent = " ".repeat(YAMLUtil.getIndentToThisElement(existing) + INDENT)
                UndefinedFixes.Edit(key.textRange.endOffset, value.textRange.endOffset, ":\n$itemIndent- $condition\n$itemIndent- ${value.text}")
            }
            else -> null
        }
    }

    private companion object {
        const val WHEN = "when"
        const val INDENT = 2
    }
}

/**
 * "Add `x` to `<role>/defaults/main.yml` and the argument spec" (a role of the analysed file's root): the key with an
 * empty value of the spec's type (`''` for strings, paths and untyped options, `[]` for lists, `{}` for dicts, `null`
 * otherwise) appended to `defaults/main.yml` (created when the role's `defaults/` directory has none), and the same value
 * as `default:` of each entry point's option that declares the variable without one, so the documentation and the
 * runtime default agree (ANS-S002).
 */
class AddRoleDefaultFix(
    private val variable: String,
    private val roleName: String,
    private val roleDir: VirtualFile,
    private val inSpec: Boolean,
    private val option: OptionSpec?,
) : ModCommandQuickFix() {
    override fun getName(): String =
        AnsibilityUndefinedBundle.message(if (inSpec) "fix.add.role.default" else "fix.add.role.default.defaults.only", variable, roleName)

    override fun getFamilyName(): String = AnsibilityUndefinedBundle.message("fix.add.role.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val value = emptyValue(option)
        val keyText = SpecEdits.keyText(variable)
        val defaultsDir = roleDir.findChild(RoleLayout.DEFAULTS)?.takeIf { it.isDirectory } ?: return ModCommand.nop()
        val defaults = RoleLayout.defaultsFiles(roleDir).firstOrNull { it.parent == defaultsDir && it.nameWithoutExtension == RoleLayout.MAIN }
        var command: ModCommand = if (defaults == null) {
            // FutureVirtualFile is @ApiStatus.Experimental (262): the only way a ModCommand names a file it creates
            ModCreateFile(FutureVirtualFile(defaultsDir, DEFAULTS_FILE, YAMLFileType.YML), ModCreateFile.Text("---\n$keyText: $value\n"))
        } else {
            val text = PsiManager.getInstance(project).findFile(defaults)?.viewProvider?.contents ?: return ModCommand.nop()
            val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
            UndefinedFixes.update(project, defaults, listOf(UndefinedFixes.Edit(text.length, text.length, "$separator$keyText: $value\n")))
        }
        if (inSpec) command = command.andThen(specEdit(project, value))
        return command
    }

    /** `default: <value>` added to every entry point's option of the variable that has none. */
    private fun specEdit(project: Project, value: String): ModCommand {
        val specFile = RoleLayout.specFile(roleDir) ?: return ModCommand.nop()
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return ModCommand.nop()
        val entryPoints = (YAMLUtil.getQualifiedKeyInFile(spec, "argument_specs")?.value as? YAMLMapping)?.keyValues.orEmpty().map(YAMLKeyValue::getKeyText)
        val edits = entryPoints.mapNotNull { entryPoint ->
            val mapping = SpecEdits.optionMapping(spec, entryPoint, listOf(variable)) ?: return@mapNotNull null
            if (mapping.getKeyValueByKey(DEFAULT) != null) return@mapNotNull null
            val end = mapping.textRange.endOffset
            UndefinedFixes.Edit(end, end, "\n" + " ".repeat(YAMLUtil.getIndentToThisElement(mapping)) + "$DEFAULT: $value")
        }
        return if (edits.isEmpty()) ModCommand.nop() else UndefinedFixes.update(project, specFile, edits)
    }

    private companion object {
        const val DEFAULT = "default"
        const val DEFAULTS_FILE = "main.yml"

        fun emptyValue(option: OptionSpec?): String = when (option?.type) {
            null, OptionType.Str, OptionType.Path, OptionType.Raw -> "''"
            OptionType.List -> "[]"
            OptionType.Dict -> "{}"
            else -> "null"
        }
    }
}
