package de.terletzkiy.ansibility.inspections.undefined

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
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
import de.terletzkiy.ansibility.inspections.spec.AddDocumentedDefaultToDefaultsFix
import de.terletzkiy.ansibility.inspections.spec.SpecDefaultEdits
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
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
 * - a role of the same root: [AddRoleDefaultFix] (the role's defaults file, plus `default:` in the argument spec when the
 *   spec declares the variable without documenting one).
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
            // The spec gets `default:` where an entry point declares the variable without a `default` key.
            val spec = role.argumentSpecs.values.any { entry -> entry.options[finding.name]?.let { it.default == null } == true }
            // Not offered when every loaded defaults file is a vault, JSON or not a mapping: a YAML line would corrupt it.
            val target = AddDocumentedDefaultToDefaultsFix.targetLabel(file.project, role.ref.dir)
            if (target != null) fixes += AddRoleDefaultFix(finding.name, role.ref.name, role.ref.dir, spec, finding.spec, target)
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
 * "Add `x` to `<role>/defaults/main.yml` and the argument spec" (a role of the analysed file's root). The value is the
 * argument spec's documented default, copied as written, when an entry point of the role documents one (plan amendment
 * R23, D175: Ansible never applies it, so the role default should be that value); otherwise an empty value of the
 * spec's type (`''` for strings, paths and untyped options, `[]` for lists, `{}` for dicts, `null` otherwise); a
 * documented default holding an alias or an anchor is not copied ([SpecDefaultEdits.isCopyable]). It goes to the file
 * the role loads its defaults from ([RoleDefaults.appendTarget]: the main file, or the last file of a `defaults/main/`
 * directory that a YAML line can be appended to, never a vault or JSON file and never a new `defaults/main.yml` that
 * would hide that directory); `defaults/main.yml` is created only when the role loads none. [targetLabel] (the
 * role-relative file, computed when the fix is offered) names it. Each entry point's option that declares the variable
 * without a default gets the same value as `default:`, so the documentation and the role default agree (ANS-S003).
 */
class AddRoleDefaultFix(
    private val variable: String,
    private val roleName: String,
    private val roleDir: VirtualFile,
    private val inSpec: Boolean,
    private val option: OptionSpec?,
    private val targetLabel: String,
) : ModCommandQuickFix() {
    /** `web/defaults/main.yml`, or `web/defaults/main/20-web.yml` for a `defaults/main/` directory. */
    override fun getName(): String =
        AnsibilityUndefinedBundle.message(if (inSpec) "fix.add.role.default" else "fix.add.role.default.defaults.only", variable, "$roleName/$targetLabel")

    override fun getFamilyName(): String = AnsibilityUndefinedBundle.message("fix.add.role.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        if (roleDir.findChild(RoleLayout.DEFAULTS)?.isDirectory != true) return ModCommand.nop()
        val documented = documentedDefault(project)
        val empty = emptyValue(option)
        val line = documented?.let { SpecDefaultEdits.topLevelLine(variable, it) } ?: "${SpecEdits.keyText(variable)}: $empty"
        var command = AddDocumentedDefaultToDefaultsFix.appendDefault(project, roleDir, line)
        if (inSpec) command = command.andThen(specEdit(project, documented, empty))
        return command
    }

    /** The `default:` key-value of the first entry point (`main` first) that documents a default for the variable; never a secret's. */
    private fun documentedDefault(project: Project): YAMLKeyValue? {
        if (variable.startsWith(VAULT_PREFIX)) return null
        val specFile = RoleLayout.specFile(roleDir) ?: RoleLayout.metaFile(roleDir) ?: return null
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return null
        val entries = ArgSpecParser.parse(PsiYValueAdapter.documentValue(spec), roleName).entryPoints.entries.sortedBy { if (it.key == MAIN) 0 else 1 }
        for ((entryPoint, entry) in entries) {
            val declared = entry.options[variable] ?: continue
            val documented = SpecDefaults.documentedDefault(declared) ?: continue
            if (SpecDefaults.hasNoLog(declared) || SpecDefaults.containsVault(documented)) return null
            // An alias or anchor is not copied: the empty value is written instead.
            return SpecEdits.optionMapping(spec, entryPoint, listOf(variable))?.getKeyValueByKey(DEFAULT)?.takeIf { SpecDefaultEdits.isCopyable(it.value) }
        }
        return null
    }

    /** `default:` added to every entry point's option of the variable that has none: [documented] as written, else [empty]. */
    private fun specEdit(project: Project, documented: YAMLKeyValue?, empty: String): ModCommand {
        val specFile = RoleLayout.specFile(roleDir) ?: return ModCommand.nop()
        val spec = PsiManager.getInstance(project).findFile(specFile) as? YAMLFile ?: return ModCommand.nop()
        val entryPoints = (YAMLUtil.getQualifiedKeyInFile(spec, "argument_specs")?.value as? YAMLMapping)?.keyValues.orEmpty().map(YAMLKeyValue::getKeyText)
        val edits = entryPoints.mapNotNull { entryPoint ->
            val mapping = SpecEdits.optionMapping(spec, entryPoint, listOf(variable)) ?: return@mapNotNull null
            if (mapping.getKeyValueByKey(DEFAULT) != null) return@mapNotNull null
            if (documented != null) SpecDefaultEdits.addDefault(mapping, documented) else SpecDefaultEdits.addDefaultText(mapping, empty)
        }
        return if (edits.isEmpty()) ModCommand.nop() else UndefinedFixes.update(project, specFile, edits)
    }

    private companion object {
        const val DEFAULT = "default"
        const val MAIN = "main"
        const val VAULT_PREFIX = "vault_"

        fun emptyValue(option: OptionSpec?): String = when (option?.type) {
            null, OptionType.Str, OptionType.Path, OptionType.Raw -> "''"
            OptionType.List -> "[]"
            OptionType.Dict -> "{}"
            else -> "null"
        }
    }
}
