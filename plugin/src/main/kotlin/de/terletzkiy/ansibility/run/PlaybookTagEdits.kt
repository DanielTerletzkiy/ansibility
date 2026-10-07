package de.terletzkiy.ansibility.run

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequence

/**
 * Adds the tags of [TagAddition]s to a playbook, in the style of the entries around them: a plain role entry becomes
 * `{ role: x, tags: ['x'] }` when its neighbours are flow mappings (else a block mapping), a mapping entry or a role
 * task gets a `tags:` key, an `include_role` also an `apply:` passing the tag on, and a play a `tags:` key after
 * `hosts:`. The tag list is quoted like the playbook's other tag lists.
 */
object PlaybookTagEdits {
    /** One text change; at equal offsets the lower [order] is applied first (so an outer key ends up after an inner one). */
    data class TextEdit(val offset: Int, val end: Int, val text: String, val order: Int = 0)

    /** The edits for [additions], or null when one of them no longer applies to [file]. Read action. */
    fun edits(file: YAMLFile, additions: List<TagAddition>): List<TextEdit>? {
        val source = file.text
        val quote = quoteOf(file)
        val result = ArrayList<TextEdit>()
        for (addition in additions) {
            val (play, role) = PlaybookParts.find(file, addition.target) ?: return null
            val list = "[$quote${addition.tag}$quote]"
            if (role == null) {
                val mapping = play.item.value as? YAMLMapping ?: return null
                result += playEdit(source, mapping, list) ?: return null
                continue
            }
            val value = role.item.value
            if (!role.fromTask) {
                result += when (value) {
                    is YAMLScalar -> scalarRoleEdit(source, role, value, list)
                    is YAMLMapping -> keyEdit(source, value, "tags", list)
                    else -> null
                } ?: return null
                continue
            }
            val task = value as? YAMLMapping ?: return null
            result += keyEdit(source, task, "tags", list) ?: return null
            val module = task.keyValues.firstOrNull { PlaybookParts.isIncludeRole(it.keyText) }
            if (module != null) {
                val args = module.value as? YAMLMapping ?: return null
                result += if (args.text.startsWith("{")) {
                    keyEdit(source, args, "apply", "{tags: $list}", order = 1) ?: return null
                } else {
                    val column = column(source, args.keyValues.first().textRange.startOffset)
                    TextEdit(args.textRange.endOffset, args.textRange.endOffset, "\n" + " ".repeat(column) + "apply:\n" + " ".repeat(column + 2) + "tags: $list", 1)
                }
            }
        }
        return result
    }

    /** Applies [additions] to [playbook] as one undoable command and saves it. EDT. False when one no longer applies. */
    fun apply(project: Project, playbook: VirtualFile, additions: List<TagAddition>): Boolean {
        if (additions.isEmpty()) return true
        val psi = PsiManager.getInstance(project).findFile(playbook) as? YAMLFile ?: return false
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(psi) ?: return false
        documents.commitDocument(document)
        val edits = edits(psi, additions) ?: return false
        WriteCommandAction.runWriteCommandAction(project, AnsibilityRunBundle.message("run.tags.command"), null, {
            for (edit in edits.sortedWith(compareByDescending<TextEdit> { it.offset }.thenBy { it.order })) {
                document.replaceString(edit.offset, edit.end, edit.text)
            }
            documents.commitDocument(document)
        }, psi)
        FileDocumentManager.getInstance().saveDocument(document)
        return true
    }

    /** A plain role name: a flow mapping like its neighbours (or inside a flow list), else a block mapping. */
    private fun scalarRoleEdit(source: String, role: PlaybookRole, value: YAMLScalar, list: String): TextEdit {
        val sequence = role.item.parent as? YAMLSequence
        val inFlowList = sequence?.text?.startsWith("[") == true
        val flowNeighbour = sequence?.items?.mapNotNull { it.value as? YAMLMapping }?.firstOrNull { it.text.startsWith("{") }
        val range = value.textRange
        if (inFlowList || flowNeighbour != null) {
            val pad = if (flowNeighbour?.text?.startsWith("{ ") == true) " " else ""
            return TextEdit(range.startOffset, range.endOffset, "{${pad}role: ${value.text}, tags: $list$pad}")
        }
        val column = column(source, range.startOffset)
        return TextEdit(range.startOffset, range.endOffset, "role: ${value.text}\n${" ".repeat(column)}tags: $list")
    }

    /** `key: value` added to [mapping]: before the `}` of a flow mapping, else as a last line at the keys' column. */
    private fun keyEdit(source: String, mapping: YAMLMapping, key: String, value: String, order: Int = 0): TextEdit? {
        val text = mapping.text
        val start = mapping.textRange.startOffset
        if (text.startsWith("{")) {
            val inner = text.substring(0, text.lastIndexOf('}'))
            val at = start + inner.trimEnd().length
            val separator = if (inner.trimEnd() == "{") "" else ", "
            return TextEdit(at, at, "$separator$key: $value", order)
        }
        val first = mapping.keyValues.firstOrNull() ?: return null
        val end = mapping.textRange.endOffset
        return TextEdit(end, end, "\n" + " ".repeat(column(source, first.textRange.startOffset)) + "$key: $value", order)
    }

    /** `tags:` of a play, after its `hosts:` (else its `name:`, else its first key). */
    private fun playEdit(source: String, play: YAMLMapping, list: String): TextEdit? {
        if (play.text.startsWith("{")) return keyEdit(source, play, "tags", list)
        val after: YAMLKeyValue = play.getKeyValueByKey("hosts") ?: play.getKeyValueByKey("name") ?: play.keyValues.firstOrNull() ?: return null
        val end = after.textRange.endOffset
        return TextEdit(end, end, "\n" + " ".repeat(column(source, after.textRange.startOffset)) + "tags: $list")
    }

    /** The quote of the playbook's first quoted tag list (`'`, `"`), else none. */
    private fun quoteOf(file: YAMLFile): String {
        val lists = ArrayList<String>()
        fun visit(element: PsiElement) {
            if (element is YAMLKeyValue && element.keyText == "tags") element.value?.text?.let { lists += it }
            element.children.forEach(::visit)
        }
        visit(file)
        val quoted = lists.firstOrNull { '\'' in it || '"' in it } ?: return ""
        return if (quoted.indexOf('\'').let { it >= 0 && (quoted.indexOf('"') < 0 || it < quoted.indexOf('"')) }) "'" else "\""
    }

    private fun column(source: String, offset: Int): Int = offset - (source.lastIndexOf('\n', offset - 1) + 1)
}
