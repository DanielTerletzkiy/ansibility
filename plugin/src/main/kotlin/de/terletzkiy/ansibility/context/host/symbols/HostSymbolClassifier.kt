package de.terletzkiy.ansibility.context.host.symbols

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.api.AnsibleSite
import de.terletzkiy.ansibility.api.AnsibleWorkspace
import de.terletzkiy.ansibility.api.FileKind
import de.terletzkiy.ansibility.api.HostConstruct
import de.terletzkiy.ansibility.api.HostPatternSite
import de.terletzkiy.ansibility.api.InventoryNameSite
import de.terletzkiy.ansibility.api.SiteClassifier
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Hosts and groups as symbols (plan amendment R7/R8, F8.8): classifies play `hosts:` patterns, literal
 * `delegate_to:` values and the name literals of `groups['g']`, `groups.g`, `'g' in group_names` and
 * `hostvars['h']`. Registered first, it answers only with the caret on one of those names; everything else
 * (the `groups` or `hostvars` word itself, member access after `hostvars['h']`) falls through to the vars area.
 */
class HostSymbolClassifier : SiteClassifier, DumbAware {
    override fun classify(file: PsiFile, offset: Int): AnsibleSite? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        if (file !is YAMLFile) {
            val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
            return if (context.kind == FileKind.INVENTORY_INI) iniNameAt(file.viewProvider.contents, offset) else null
        }
        val context = AnsibleWorkspace.getInstance(file.project).contextOf(virtualFile) ?: return null
        // At the end of a value (where completion runs) the element at the caret is the line break after it.
        val scalar = PsiTreeUtil.getParentOfType(file.findElementAt(offset), YAMLScalar::class.java, false)
            ?: offset.takeIf { it > 0 }?.let { PsiTreeUtil.getParentOfType(file.findElementAt(it - 1), YAMLScalar::class.java, false) }
            ?: return null
        val start = scalar.textRange.startOffset
        nameAt(scalar.text, offset - start)?.let { return it.copy(range = it.range.shiftRight(start)) }
        val key = keyOf(scalar) ?: return null
        val value = scalar.textValue
        return when (key.keyText) {
            HOSTS -> if (context.kind in PLAYBOOK_KINDS && isPlay(key)) HostPatternSite(value, HostConstruct.PLAY_HOSTS, contentRange(scalar)) else null
            DELEGATE_TO -> if (!isTemplated(value)) HostPatternSite(value.trim(), HostConstruct.DELEGATE_TO, contentRange(scalar)) else null
            else -> null
        }
    }

    companion object {
        const val HOSTS = "hosts"
        const val DELEGATE_TO = "delegate_to"

        internal val PLAYBOOK_KINDS = setOf(FileKind.PLAYBOOK, FileKind.MOLECULE_PLAYBOOK)

        private val GROUPS_SUBSCRIPT = Regex("""\bgroups\s*\[\s*(['"])([^'"\]]+)\1\s*]""")
        private val GROUPS_ATTRIBUTE = Regex("""\bgroups\.([A-Za-z_][A-Za-z0-9_]*)""")
        private val HOSTVARS_SUBSCRIPT = Regex("""\bhostvars\s*\[\s*(['"])([^'"\]]+)\1\s*]""")
        private val GROUP_NAMES_TEST = Regex("""(['"])([^'"]+)\1\s+(?:not\s+)?in\s+group_names\b""")

        /** The name literal of an expression construct that covers [offset] in [text] (range relative to [text]). */
        internal fun nameAt(text: String, offset: Int): InventoryNameSite? {
            fun find(regex: Regex, group: Int, isGroup: Boolean, construct: HostConstruct): InventoryNameSite? {
                for (match in regex.findAll(text)) {
                    val name = match.groups[group] ?: continue
                    if (offset in name.range.first..name.range.last + 1) {
                        return InventoryNameSite(name.value, isGroup, construct, TextRange(name.range.first, name.range.last + 1))
                    }
                }
                return null
            }
            if (!text.contains("group") && !text.contains("hostvars")) return null
            return find(GROUPS_SUBSCRIPT, 2, true, HostConstruct.GROUPS)
                ?: find(GROUPS_ATTRIBUTE, 1, true, HostConstruct.GROUPS)
                ?: find(HOSTVARS_SUBSCRIPT, 2, false, HostConstruct.HOSTVARS)
                ?: find(GROUP_NAMES_TEST, 2, true, HostConstruct.GROUP_NAMES)
        }

        private val INI_HEADER = Regex("""^\s*\[([^\]:\s]+)(?::(\w+))?]""")
        private val INI_NAME = Regex("""^\s*([^\s#;=\[]+)""")

        /**
         * The group or host name at [offset] of an INI inventory: the group of a section header, a child group of a
         * `:children` section, the host of a host line (not host ranges). Keys of `:vars` sections are variables.
         */
        internal fun iniNameAt(text: CharSequence, offset: Int): InventoryNameSite? {
            if (offset < 0 || offset > text.length) return null
            val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0 || offset == 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
            val line = text.subSequence(lineStart, lineEnd).toString()
            val column = offset - lineStart
            INI_HEADER.find(line)?.let { header ->
                val name = header.groups[1]!!
                if (column !in name.range.first..name.range.last + 1) return null
                return InventoryNameSite(name.value, true, HostConstruct.INVENTORY_FILE, TextRange(lineStart + name.range.first, lineStart + name.range.last + 1))
            }
            val section = sectionAbove(text, lineStart) ?: return null
            if (section == "vars") return null
            val name = INI_NAME.find(line)?.groups?.get(1) ?: return null
            if (column !in name.range.first..name.range.last + 1 || name.value.startsWith("#") || name.value.startsWith(";")) return null
            val range = TextRange(lineStart + name.range.first, lineStart + name.range.last + 1)
            return InventoryNameSite(name.value, section == "children", HostConstruct.INVENTORY_FILE, range)
        }

        /** The suffix of the section header above [lineStart] (`children`, `vars`, or "" for a host section). */
        private fun sectionAbove(text: CharSequence, lineStart: Int): String? {
            var end = lineStart - 1
            while (end > 0) {
                val start = text.lastIndexOf('\n', end - 1).let { if (it < 0) 0 else it + 1 }
                INI_HEADER.find(text.subSequence(start, end))?.let { return it.groups[2]?.value.orEmpty() }
                end = start - 1
            }
            return ""
        }

        internal fun isTemplated(text: String): Boolean = "{{" in text || "{%" in text

        /** The key whose value [scalar] is, directly or as an item of a sequence value (`hosts: [web, db]`). */
        internal fun keyOf(scalar: YAMLScalar): YAMLKeyValue? {
            (scalar.parent as? YAMLKeyValue)?.takeIf { it.value == scalar }?.let { return it }
            val item = scalar.parent as? YAMLSequenceItem ?: return null
            return (item.parent as? YAMLSequence)?.parent as? YAMLKeyValue
        }

        /** Whether [key] is a key of a play: an item of the playbook's top-level sequence. */
        internal fun isPlay(key: YAMLKeyValue): Boolean {
            val mapping = key.parent as? YAMLMapping ?: return false
            val item = mapping.parent as? YAMLSequenceItem ?: return false
            return (item.parent as? YAMLSequence)?.parent is YAMLDocument
        }

        /** The range of [scalar]'s text without its quotes. */
        internal fun contentRange(scalar: YAMLScalar): TextRange {
            val range = scalar.textRange
            return if (scalar is YAMLQuotedText && range.length >= 2) TextRange(range.startOffset + 1, range.endOffset - 1) else range
        }
    }
}
