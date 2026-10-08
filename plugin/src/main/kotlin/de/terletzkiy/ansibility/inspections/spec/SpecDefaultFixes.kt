package de.terletzkiy.ansibility.inspections.spec

import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.modcommand.FutureVirtualFile
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.modcommand.ModCreateFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.context.TargetVersionDetector
import de.terletzkiy.ansibility.inspections.types.SpecEdits
import de.terletzkiy.ansibility.inspections.undefined.UndefinedFixes
import de.terletzkiy.ansibility.model.role.RoleDefaults
import de.terletzkiy.ansibility.model.role.RoleLayout
import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics
import de.terletzkiy.ansibility.semantics.schema.ArgSpecParser
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.validate.SpecDefaults
import de.terletzkiy.ansibility.semantics.validate.SpecValidator
import de.terletzkiy.ansibility.semantics.yaml.Resolved
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YMap
import de.terletzkiy.ansibility.semantics.yaml.YScalar
import de.terletzkiy.ansibility.semantics.yaml.YSeq
import de.terletzkiy.ansibility.semantics.yaml.YValue
import de.terletzkiy.ansibility.vars.VarLocations
import de.terletzkiy.ansibility.yaml.PsiYValueAdapter
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLAlias
import org.jetbrains.yaml.psi.YAMLAnchor
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Text edits of the argument spec and of `defaults/` for the ANS-S003–S005 fixes (plan amendment R23, D171, D172). Values
 * are copied as written: a one-line value as its source text (the spec scalar's quoting kept when it documents the same
 * value), a block collection or a multi-line scalar re-indented below its new key. A multi-line value never goes into a
 * flow mapping; a plain scalar with flow indicators goes there double-quoted. A value holding an alias or an anchor is
 * never copied ([isCopyable]): the alias would be undefined in the other file, the anchor could be a duplicate. Every
 * check that offers a fix runs the same writer, so an offered fix always edits.
 */
internal object SpecDefaultEdits {
    private const val DEFAULT = "default"
    private const val STEP = 2
    private const val PLAIN_INDICATORS = "-?:,[]{}#&*!|>'\"%@`"
    private const val FLOW_INDICATORS = ",[]{}"

    fun containsTemplate(value: YValue): Boolean = when (value) {
        is YScalar -> SpecValidator.containsJinja(value)
        is YSeq -> value.items.any(::containsTemplate)
        is YMap -> value.entries.any { containsTemplate(it.value) }
        else -> false
    }

    /** The key-value of [value] in its defaults file (top-level or nested), or null when the file changed. */
    fun sourceKeyValue(project: Project, value: RoleValue): YAMLKeyValue? = VarLocations.keyValueAt(project, SourceLocation(value.file, value.keyOffset))

    /**
     * Whether [source]'s value can be written into [mapping] by [setDefault] or [addDefault]: no alias or anchor in it,
     * a one-line form for a flow mapping ([flowText]), a re-indentable form for a block mapping.
     */
    fun canDocument(mapping: YAMLMapping?, project: Project, source: RoleValue): Boolean {
        val keyValue = sourceKeyValue(project, source) ?: return false
        val value = keyValue.value
        if (!isCopyable(value)) return false
        if (isFlow(mapping)) return flowText(keyValue) != null
        return value == null || '\n' !in value.text || block(value, STEP) != null
    }

    /** Whether [value] can be copied into another place as written: no alias (`*base`) and no anchor (`&base`) in it. */
    fun isCopyable(value: YAMLValue?): Boolean =
        value == null || PsiTreeUtil.findChildOfAnyType(value, false, YAMLAlias::class.java, YAMLAnchor::class.java) == null

    /**
     * [source]'s value as one line that keeps its meaning inside a flow mapping: its text, or a plain string scalar with
     * flow indicators (`a,b`) double-quoted; null for a multi-line value or one that cannot be written there.
     */
    private fun flowText(source: YAMLKeyValue): String? {
        val value = source.value ?: return "null"
        val text = value.text
        if ('\n' in text) return null
        if (flowSafe(text)) return text
        val scalar = PsiYValueAdapter.valueOf(source) as? YScalar ?: return null
        if (scalar.tag != null || scalar.style != ScalarStyle.PLAIN || scalar.resolved !is Resolved.Str) return null
        return SpecEdits.quoted(scalar.text)
    }

    /** A flow mapping (`{type: int, default: 80}`). */
    fun isFlow(mapping: YAMLMapping?): Boolean = mapping?.text?.startsWith("{") == true

    /**
     * Sets the `default:` of [specKeyValue] to the value of [source]: the spec scalar's quoting style when it documents
     * the same value under [option] (checked with [comparator]), else [source]'s text. Null when it cannot be written.
     */
    fun setDefault(specKeyValue: YAMLKeyValue, option: OptionSpec?, comparator: SpecDefaults?, source: YAMLKeyValue): UndefinedFixes.Edit? {
        val key = specKeyValue.key ?: return null
        val start = key.textRange.endOffset
        val end = specKeyValue.textRange.endOffset
        val value = source.value
        if (!isCopyable(value)) return null
        val sourceText = value?.text ?: "null"
        val flow = isFlow(specKeyValue.parentMapping)
        if (flow) {
            val inline = styled(specKeyValue, option, comparator, source)?.takeIf(::flowSafe) ?: flowText(source) ?: return null
            return UndefinedFixes.Edit(start, end, ": $inline")
        }
        if ('\n' !in sourceText) {
            val inline = styled(specKeyValue, option, comparator, source) ?: sourceText
            return UndefinedFixes.Edit(start, end, ": $inline")
        }
        if (value == null) return null
        val block = block(value, YAMLUtil.getIndentToThisElement(specKeyValue) + step(specKeyValue)) ?: return null
        return UndefinedFixes.Edit(start, end, ":$block")
    }

    /** `default: <source>` added to the option [mapping] (or its empty `default:` set). */
    fun addDefault(mapping: YAMLMapping, source: YAMLKeyValue): UndefinedFixes.Edit? {
        mapping.getKeyValueByKey(DEFAULT)?.let { return setDefault(it, null, null, source) }
        val value = source.value ?: return null
        if (!isCopyable(value)) return null
        if (isFlow(mapping)) return addDefaultText(mapping, flowText(source) ?: return null)
        val text = value.text
        if ('\n' !in text) return addDefaultText(mapping, text)
        val indent = YAMLUtil.getIndentToThisElement(mapping)
        val written = block(value, indent + stepAbove(mapping, indent)) ?: return null
        val end = mapping.textRange.endOffset
        return UndefinedFixes.Edit(end, end, "\n" + " ".repeat(indent) + "$DEFAULT:$written")
    }

    /** `default: <text>` (one line) added after the last key of the option [mapping], block or flow. */
    fun addDefaultText(mapping: YAMLMapping, text: String): UndefinedFixes.Edit? {
        if (isFlow(mapping)) {
            if (!flowSafe(text)) return null
            val close = mapping.textRange.endOffset - 1
            val separator = if (mapping.keyValues.isEmpty()) "" else ", "
            return UndefinedFixes.Edit(close, close, "$separator$DEFAULT: $text")
        }
        val end = mapping.textRange.endOffset
        return UndefinedFixes.Edit(end, end, "\n" + " ".repeat(YAMLUtil.getIndentToThisElement(mapping)) + "$DEFAULT: $text")
    }

    /** Removes [keyValue] (`default: …`) from its option mapping; an option left without keys stays a mapping (`{}`). */
    fun removeDefault(text: CharSequence, keyValue: YAMLKeyValue): UndefinedFixes.Edit {
        val mapping = keyValue.parentMapping
        val range = keyValue.textRange
        if (isFlow(mapping)) {
            var start = range.startOffset
            var end = range.endOffset
            var before = start - 1
            while (before >= 0 && text[before].isWhitespace()) before--
            if (before >= 0 && text[before] == ',') {
                start = before
            } else {
                var after = end
                while (after < text.length && text[after] == ' ') after++
                if (after < text.length && text[after] == ',') {
                    end = after + 1
                    while (end < text.length && text[end] == ' ') end++
                }
            }
            return UndefinedFixes.Edit(start, end, "")
        }
        if (mapping != null && mapping.keyValues.size == 1) {
            val optionKey = (mapping.parent as? YAMLKeyValue)?.key
            return if (optionKey != null) {
                UndefinedFixes.Edit(optionKey.textRange.endOffset, mapping.textRange.endOffset, ": {}")
            } else {
                UndefinedFixes.Edit(range.startOffset, range.endOffset, "{}")
            }
        }
        val lineStart = UndefinedFixes.lineStart(text, range.startOffset)
        if (text.subSequence(lineStart, range.startOffset).isBlank()) {
            return UndefinedFixes.Edit(lineStart, UndefinedFixes.lineEnd(text, (range.endOffset - 1).coerceAtLeast(range.startOffset)), "")
        }
        return UndefinedFixes.Edit(range.startOffset, range.endOffset, "")
    }

    /** `name: <value>` for a top-level key of a defaults file, from the spec's `default:` key-value; null when it cannot be copied. */
    fun topLevelLine(name: String, specDefault: YAMLKeyValue): String? {
        val value = specDefault.value ?: return null
        if (!isCopyable(value)) return null
        val text = value.text
        val keyText = SpecEdits.keyText(name)
        return if ('\n' !in text) "$keyText: $text" else block(value, STEP)?.let { "$keyText:$it" }
    }

    /**
     * The spec scalar's quoting applied to [source]'s scalar content, when that documents the same value as [source]
     * (the comparator says equal); null to copy [source]'s text instead.
     */
    private fun styled(specKeyValue: YAMLKeyValue, option: OptionSpec?, comparator: SpecDefaults?, source: YAMLKeyValue): String? {
        if (comparator == null || option == null) return null
        val specValue = specKeyValue.value ?: return null
        if ('\n' in specValue.text) return null
        val spec = PsiYValueAdapter.valueOf(specKeyValue) as? YScalar ?: return null
        val written = PsiYValueAdapter.valueOf(source) as? YScalar ?: return null
        if (spec.tag != null || written.tag != null) return null
        val content = written.text
        if ('\n' in content) return null
        val candidate = when (spec.style) {
            ScalarStyle.DOUBLE_QUOTED -> SpecEdits.quoted(content)
            ScalarStyle.SINGLE_QUOTED -> "'" + content.replace("'", "''") + "'"
            ScalarStyle.PLAIN -> content.takeIf(::plainWritable) ?: return null
            else -> return null
        }
        if (candidate == source.value?.text) return candidate
        val documented = option.copy(default = YScalar(content, spec.style, null, candidate), required = false)
        return candidate.takeIf { comparator.compare(documented, written) == SpecDefaults.Verdict.Equal }
    }

    private fun plainWritable(text: String): Boolean =
        text.isNotEmpty() && text == text.trim() && text.first() !in PLAIN_INDICATORS && ": " !in text && " #" !in text && !text.endsWith(":")

    /** One-line text that keeps its meaning inside a flow mapping. */
    private fun flowSafe(text: String): Boolean = text.isNotEmpty() && (text.first() in "\"'[{" || text.none { it in FLOW_INDICATORS })

    /**
     * [value] (a multi-line collection or scalar) as the text after `key:`, its content at column [indent]: a newline
     * and the re-indented lines for a collection; the first line (a block scalar's `|` header, or a flow scalar's first
     * line of content) and the re-indented continuation lines for a scalar. Null for a block scalar's explicit
     * indentation indicator (`|2`), which would no longer fit.
     */
    private fun block(value: YAMLValue, indent: Int): String? {
        val text = value.text
        val lines = text.split('\n')
        val pad = " ".repeat(indent)
        if (value is YAMLScalar) {
            val header = lines.first().trim()
            val blockScalar = header.startsWith("|") || header.startsWith(">")
            if (blockScalar && header.any { it.isDigit() }) return null
            val content = lines.drop(1)
            val strip = content.filter { it.isNotBlank() }.minOfOrNull { line -> line.length - line.trimStart(' ').length } ?: 0
            return " $header" + content.joinToString("") { line -> "\n" + if (line.isBlank()) "" else pad + line.drop(strip) }
        }
        val documentText = value.containingFile.viewProvider.contents
        val column = value.textRange.startOffset - UndefinedFixes.lineStart(documentText, value.textRange.startOffset)
        return lines.withIndex().joinToString("") { (index, line) ->
            "\n" + when {
                index == 0 -> pad + line
                line.isBlank() -> ""
                else -> pad + line.drop(minOf(column, line.length - line.trimStart(' ').length))
            }
        }
    }

    /** The indentation step below [keyValue]'s key (its column minus its parent key's), 2 when it cannot be told. */
    private fun step(keyValue: YAMLKeyValue): Int {
        val mapping = keyValue.parentMapping ?: return STEP
        return stepAbove(mapping, YAMLUtil.getIndentToThisElement(keyValue))
    }

    private fun stepAbove(mapping: YAMLMapping, indent: Int): Int {
        val parent = (mapping.parent as? YAMLKeyValue) ?: return STEP
        return (indent - YAMLUtil.getIndentToThisElement(parent)).takeIf { it > 0 } ?: STEP
    }

    /** The comparator for [specFile]'s root target, for [styled]. */
    fun comparator(project: Project, specFile: VirtualFile): SpecDefaults {
        val root = AnsibleWorkspace.getInstance(project).contextOf(specFile)?.root
        val version = root?.let { TargetVersionDetector.getInstance(project).targetVersion(it).version } ?: CoreVersion.PINNED
        return SpecDefaults(CoreSemantics(version), SpecValidator::containsJinja)
    }

    /** The option at [target] in [spec], parsed as ansible-core reads it. */
    fun optionAt(spec: YAMLFile, roleName: String, target: SpecTarget): OptionSpec? {
        val entry = ArgSpecParser.parse(PsiYValueAdapter.documentValue(spec), roleName).entryPoints[target.entryPoint] ?: return null
        var option = entry.options[target.path.first()] ?: return null
        for (name in target.path.drop(1)) option = option.options?.get(name) ?: return null
        return option
    }

    /** [edits] of [file]'s current text as one command; nop when there are none. */
    fun update(project: Project, file: VirtualFile, edits: List<UndefinedFixes.Edit>): ModCommand =
        if (edits.isEmpty()) ModCommand.nop() else UndefinedFixes.update(project, file, edits)

    fun specFile(project: Project, file: VirtualFile): YAMLFile? = PsiManager.getInstance(project).findFile(file) as? YAMLFile

    /** The current text of [file] (its document when open). */
    fun textOf(project: Project, file: VirtualFile): CharSequence? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        return PsiDocumentManager.getInstance(project).getDocument(psi)?.immutableCharSequence ?: psi.viewProvider.contents
    }
}

/**
 * ANS-S003's primary fix (D171): "Set the documented default to 1.2", the role default's value written into the
 * `default:` of every [targets] option of [specFile] (one from the spec, each differing entry point from the defaults
 * twin). Offered first; it never changes `defaults/`, so what Ansible does stays the same.
 */
class SetDocumentedDefaultFix internal constructor(
    private val specFile: VirtualFile,
    private val roleName: String,
    private val targets: List<SpecTarget>,
    private val source: RoleValue,
    private val value: String,
) : ModCommandQuickFix(), HighPriorityAction {
    override fun getName(): String = AnsibilitySpecBundle.message("fix.set.documented.default", value)

    override fun getFamilyName(): String = AnsibilitySpecBundle.message("fix.set.documented.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = SpecDefaultEdits.specFile(project, specFile) ?: return ModCommand.nop()
        val sourceKeyValue = SpecDefaultEdits.sourceKeyValue(project, source) ?: return ModCommand.nop()
        val comparator = SpecDefaultEdits.comparator(project, specFile)
        val edits = targets.mapNotNull { target ->
            val keyValue = SpecEdits.optionMapping(spec, target.entryPoint, target.path)?.getKeyValueByKey("default") ?: return@mapNotNull null
            SpecDefaultEdits.setDefault(keyValue, SpecDefaultEdits.optionAt(spec, roleName, target), comparator, sourceKeyValue)
        }
        return SpecDefaultEdits.update(project, specFile, edits)
    }
}

/** ANS-S003/S004's second fix: "Remove the documented default" from every [targets] option of [specFile]. */
class RemoveDocumentedDefaultFix internal constructor(
    private val specFile: VirtualFile,
    private val targets: List<SpecTarget>,
) : ModCommandQuickFix() {
    override fun getName(): String = AnsibilitySpecBundle.message("fix.remove.documented.default")

    override fun getFamilyName(): String = name

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = SpecDefaultEdits.specFile(project, specFile) ?: return ModCommand.nop()
        val text = SpecDefaultEdits.textOf(project, specFile) ?: return ModCommand.nop()
        val edits = targets.mapNotNull { target ->
            SpecEdits.optionMapping(spec, target.entryPoint, target.path)?.getKeyValueByKey("default")?.let { SpecDefaultEdits.removeDefault(text, it) }
        }
        return SpecDefaultEdits.update(project, specFile, edits)
    }
}

/**
 * ANS-S004's fix (D172, D175 rule): "Add 'version: 1.5' to defaults/main.yml", the documented default copied as written
 * into the file the role loads its defaults from ([RoleDefaults.appendTarget]: the main file, or the last file of a
 * `defaults/main/` directory that a YAML line can be appended to); `defaults/main.yml` is created only when the role
 * loads no defaults file. [targetLabel] (computed with the finding) names that file.
 */
class AddDocumentedDefaultToDefaultsFix internal constructor(
    private val specFile: VirtualFile,
    private val roleDir: VirtualFile,
    private val target: SpecTarget,
    private val value: String,
    private val targetLabel: String,
) : ModCommandQuickFix() {
    private val variable: String get() = target.path.single()

    override fun getName(): String = AnsibilitySpecBundle.message("fix.add.documented.default", "$variable: $value", targetLabel)

    override fun getFamilyName(): String = AnsibilitySpecBundle.message("fix.add.documented.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = SpecDefaultEdits.specFile(project, specFile) ?: return ModCommand.nop()
        val specDefault = SpecEdits.optionMapping(spec, target.entryPoint, target.path)?.getKeyValueByKey("default") ?: return ModCommand.nop()
        val line = SpecDefaultEdits.topLevelLine(variable, specDefault) ?: return ModCommand.nop()
        return appendDefault(project, roleDir, line)
    }

    companion object {
        private const val MAIN_FILE = "main.yml"

        /** The role-relative name of the file [appendDefault] writes, or null when no fix may write (vault, JSON). */
        internal fun targetLabel(project: Project, roleDir: VirtualFile): String? = when (val target = RoleDefaults.appendTarget(project, roleDir)) {
            is RoleDefaults.AppendTarget.Existing -> VfsUtilCore.getRelativePath(target.file, roleDir) ?: target.file.name
            RoleDefaults.AppendTarget.Create -> "${RoleLayout.DEFAULTS}/$MAIN_FILE"
            RoleDefaults.AppendTarget.None -> null
        }

        /**
         * Appends [line] (`name: value`, block lines included) to the defaults file [roleDir] loads; creates
         * `defaults/main.yml` (and `defaults/`) when there is none, never next to a `defaults/main/` directory; nothing
         * when every loaded file is a vault, JSON or not a mapping ([RoleDefaults.AppendTarget.None]).
         */
        internal fun appendDefault(project: Project, roleDir: VirtualFile, line: String): ModCommand {
            val target = RoleDefaults.appendTarget(project, roleDir)
            if (target is RoleDefaults.AppendTarget.None) return ModCommand.nop()
            if (target is RoleDefaults.AppendTarget.Existing) {
                val existing = target.file
                val text = SpecDefaultEdits.textOf(project, existing) ?: return ModCommand.nop()
                val separator = if (text.isEmpty() || text.endsWith("\n")) "" else "\n"
                return UndefinedFixes.update(project, existing, listOf(UndefinedFixes.Edit(text.length, text.length, "$separator$line\n")))
            }
            // FutureVirtualFile is @ApiStatus.Experimental (262): the only way a ModCommand names a file it creates
            val content = ModCreateFile.Text("---\n$line\n")
            val dir = roleDir.findChild(RoleLayout.DEFAULTS)
            if (dir != null) {
                if (!dir.isDirectory) return ModCommand.nop()
                return ModCreateFile(FutureVirtualFile(dir, MAIN_FILE, YAMLFileType.YML), content)
            }
            val newDir = FutureVirtualFile(roleDir, RoleLayout.DEFAULTS, null)
            return ModCreateFile(newDir, ModCreateFile.Directory()).andThen(ModCreateFile(FutureVirtualFile(newDir, MAIN_FILE, YAMLFileType.YML), content))
        }
    }
}

/** ANS-S005's fix (D172): "Document the default in argument_specs (default: 80)", the role default copied as written. */
class DocumentRoleDefaultFix internal constructor(
    private val specFile: VirtualFile,
    private val targets: List<SpecTarget>,
    private val source: RoleValue,
    private val value: String,
) : ModCommandQuickFix() {
    override fun getName(): String = AnsibilitySpecBundle.message("fix.document.default", value)

    override fun getFamilyName(): String = AnsibilitySpecBundle.message("fix.document.default.family")

    override fun perform(project: Project, descriptor: ProblemDescriptor): ModCommand {
        val spec = SpecDefaultEdits.specFile(project, specFile) ?: return ModCommand.nop()
        val sourceKeyValue = SpecDefaultEdits.sourceKeyValue(project, source) ?: return ModCommand.nop()
        val edits = targets.mapNotNull { target ->
            SpecEdits.optionMapping(spec, target.entryPoint, target.path)?.let { SpecDefaultEdits.addDefault(it, sourceKeyValue) }
        }
        return SpecDefaultEdits.update(project, specFile, edits)
    }
}
