package de.terletzkiy.ansibility.vars.usages

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileContext
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.JinjaContainer
import de.terletzkiy.ansibility.api.ValueShape
import de.terletzkiy.ansibility.index.JinjaBearing
import de.terletzkiy.ansibility.index.ValueSummary
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.vars.JinjaTextSites
import de.terletzkiy.ansibility.vars.VarKeySites
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Turns [VarOccurrence]s into the [UsageInfo]s Find Usages shows (F1.10), one search at a time: each usage sits on
 * the host-file leaf at the occurrence with the occurrence's range inside it (no injected PSI is built), and gets its
 * usage-type group ([VarUsageKind]), refined from the PSI around templated YAML reads and, for runtime names, by owner.
 *
 * **Vault safety.** A row of a vault-named file, the definition of a `vault_*` name or of a `!vault` value, and any
 * row whose line also carries a `!vault` value or a nested `vault_*` key with its value (`{user: a, vault_pw: b}`)
 * shows nothing of its line but the variable's name ([VaultSafeUsageInfo]); nothing is decrypted (the rule of
 * `ValueSummary.preview`). Other reads keep their line: it shows Jinja, never a value.
 *
 * Not thread-safe; call [create] in a read action.
 */
internal class VarUsageInfos(
    private val project: Project,
    private val symbol: VarSymbolElement,
    /** Every occurrence of the search, so a masked copy of a file can show all names written in it. */
    occurrences: List<VarOccurrence>,
    /** The owners whose occurrences of a runtime name belong to the search ([VarOwners.runtimeHome]), or null for a root variable. */
    private val runtimeHome: Set<VarOwner>?,
) {
    private val rangesByFile: Map<VirtualFile, List<TextRange>> = occurrences.groupBy({ it.file }, { it.range })
    private val maskedCopies = HashMap<VirtualFile, PsiFile>()
    private val owners = HashMap<VirtualFile, Set<VarOwner>>()

    /** The usage of [occurrence] and its group, or null when the file has no PSI any more. */
    fun create(occurrence: VarOccurrence): Pair<UsageInfo, VarUsageKind>? {
        val psi = PsiManager.getInstance(project).findFile(occurrence.file) ?: return null
        val leaf = psi.findElementAt(occurrence.range.startOffset) ?: return null
        val leafRange = leaf.textRange ?: return null
        val inLeaf = if (leafRange.contains(occurrence.range)) occurrence.range.shiftLeft(leafRange.startOffset) else TextRange(0, leafRange.length)
        val info = if (isMasked(psi, occurrence)) {
            VaultSafeUsageInfo(leaf, inLeaf.startOffset, inLeaf.endOffset, maskedCopy(psi, occurrence.file))
        } else {
            UsageInfo(leaf, inLeaf.startOffset, inLeaf.endOffset)
        }
        return info to kindOf(leaf, occurrence)
    }

    private fun kindOf(leaf: PsiElement, occurrence: VarOccurrence): VarUsageKind {
        val home = runtimeHome
        if (home != null) {
            val fileOwners = owners.getOrPut(occurrence.file) { VarOwners.of(project, occurrence.file) }
            if (fileOwners.isNotEmpty() && fileOwners.none { it in home }) return VarUsageKind.shared(VarOwners.label(symbol.root, fileOwners.first()))
        }
        occurrence.kind?.let { return it }
        val context = AnsibleWorkspace.getInstance(project).contextOf(occurrence.file)
        return VarReadKinds.of(leaf, occurrence.file, context, occurrence.container)
    }

    /** Whether the row of [occurrence] must hide its line (see the class comment). */
    private fun isMasked(psi: PsiFile, occurrence: VarOccurrence): Boolean {
        if (ValueSummary.isVaultFileName(occurrence.file.name)) return true
        if (occurrence.write && (symbol.name.startsWith(VAULT_PREFIX) || occurrence.definition?.valueShape == ValueShape.VAULT)) return true
        val line = lineAround(psi.viewProvider.contents, occurrence.range)
        return VAULT_MARKERS.any { line.contains(it) } || NESTED_VAULT_KEY.containsMatchIn(line)
    }

    /** The line(s) of [text] that [range] touches, without the line breaks: what the usage row would show. */
    private fun lineAround(text: CharSequence, range: TextRange): CharSequence {
        val start = (range.startOffset - 1 downTo 0).firstOrNull { text[it] == '\n' }?.plus(1) ?: 0
        val end = (range.endOffset until text.length).firstOrNull { text[it] == '\n' } ?: text.length
        return text.subSequence(start, end)
    }

    /** [psi]'s text with every character but the names of this search and the line breaks blanked (lines and offsets stay). */
    private fun maskedCopy(psi: PsiFile, file: VirtualFile): PsiFile = maskedCopies.getOrPut(file) {
        val text = psi.viewProvider.contents
        val shown = rangesByFile[file].orEmpty()
        val masked = CharArray(text.length) { i ->
            val c = text[i]
            if (c == '\n' || c == '\r' || shown.any { i >= it.startOffset && i < it.endOffset }) c else ' '
        }
        PsiFileFactory.getInstance(project).createFileFromText(file.name, MASK_LANGUAGE, String(masked), true, false)
    }

    private companion object {
        const val VAULT_PREFIX = "vault_"
        /** A `!vault` value (or its payload) written on the definition's line, e.g. inside a flow mapping. */
        val VAULT_MARKERS = listOf("!vault", "\$ANSIBLE_VAULT")

        /** A nested `vault_*` key with its value on the definition's line (`{user: a, vault_pw: b}`). */
        val NESTED_VAULT_KEY = Regex("""\bvault_[\w-]*['"]?\s*:""")
        val MASK_LANGUAGE: Language = PlainTextLanguage.INSTANCE
    }
}

/**
 * A usage whose row shows only the variable's name (vault safety, F1.10). The row text and the preview come from
 * [getFile]'s document, which is a copy of the real file with every other character blanked, so lines and offsets
 * are the real ones and the value never reaches the Find tool window or the Show Usages popup; navigation, grouping
 * and the element (usage type, read/write access) use the real file ([getVirtualFile] and [getElement] are not
 * overridden).
 */
internal class VaultSafeUsageInfo(element: PsiElement, start: Int, end: Int, private val masked: PsiFile) : UsageInfo(element, start, end) {
    override fun getFile(): PsiFile = masked
}

/**
 * The `Read: …` group of a Jinja use from the PSI around it: a template file, a condition or `debug var` (bare
 * expressions), the value of another variable, a loop source, a play's keywords, or a task. Call in a read action.
 */
internal object VarReadKinds {
    /** Kinds whose YAML values are variable values (Jinja there templates another variable). */
    private val VALUE_KINDS = setOf(
        FileKind.ROLE_DEFAULTS, FileKind.ROLE_VARS, FileKind.GROUP_VARS, FileKind.HOST_VARS, FileKind.MOLECULE_VARS,
        FileKind.INVENTORY, FileKind.MOLECULE_CONFIG,
    )
    private val PLAYBOOK_KINDS = setOf(FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK)
    private val CONDITION_KEYS = JinjaBearing.IMPLICIT_EXPRESSION_KEYS + "that"
    private const val DEBUG_VAR = "var"
    private const val LOOP = "loop"
    private const val WITH_PREFIX = "with_"

    fun of(leaf: PsiElement, file: VirtualFile, context: FileContext?, container: JinjaContainer?): VarUsageKind {
        if (container == JinjaContainer.TEMPLATE_FILE || context != null && JinjaTextSites.isTemplateFile(file, context)) return VarUsageKind.READ_TEMPLATE
        val scalar = PsiTreeUtil.getParentOfType(leaf, YAMLScalar::class.java, false)
        val key = scalar?.let(::keyOf)?.keyText
        if (container == JinjaContainer.YAML_EXPRESSION) return if (key == DEBUG_VAR) VarUsageKind.READ_DEBUG_VAR else VarUsageKind.READ_CONDITION
        if (context == null || context.kind in VALUE_KINDS) return VarUsageKind.READ_VALUE
        if (scalar == null) return VarUsageKind.READ_TASK
        var ancestor = PsiTreeUtil.getParentOfType(scalar, YAMLKeyValue::class.java, true)
        while (ancestor != null) {
            if (VarKeySites.of(ancestor, context, file) != null) return VarUsageKind.READ_VALUE
            ancestor = PsiTreeUtil.getParentOfType(ancestor, YAMLKeyValue::class.java, true)
        }
        if (key != null && (key == LOOP || key.startsWith(WITH_PREFIX))) return VarUsageKind.READ_LOOP_SOURCE
        if (key in CONDITION_KEYS) return VarUsageKind.READ_CONDITION
        val yaml = scalar.containingFile as? YAMLFile
        if (context.kind in PLAYBOOK_KINDS && yaml != null && TaskFileModels.of(yaml).itemAt(scalar.textRange.startOffset) == null) {
            return VarUsageKind.READ_PLAYBOOK
        }
        return VarUsageKind.READ_TASK
    }

    /** The key whose value (or list item) [scalar] is. */
    private fun keyOf(scalar: YAMLScalar): YAMLKeyValue? = when (val parent = scalar.parent) {
        is YAMLKeyValue -> parent
        is YAMLSequenceItem -> (parent.parent as? YAMLSequence)?.parent as? YAMLKeyValue
        else -> null
    }
}
