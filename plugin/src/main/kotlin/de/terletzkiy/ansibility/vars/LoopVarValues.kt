package de.terletzkiy.ansibility.vars

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import de.terletzkiy.ansibility.api.LoopVarKind
import de.terletzkiy.ansibility.api.LoopVarSite
import de.terletzkiy.ansibility.model.task.NameRef
import de.terletzkiy.ansibility.model.task.TaskFileModels
import de.terletzkiy.ansibility.model.task.TaskNode
import de.terletzkiy.ansibility.model.task.YamlFiles
import de.terletzkiy.ansibility.yaml.YamlPaths
import org.jetbrains.yaml.psi.YAMLFile

/**
 * The values of `loop_control.loop_var` and `index_var` in a YAML task list (role tasks, handlers, playbooks): the
 * name a task's loop binds, found structurally in the file's task model (no index, so it works while indexing).
 */
internal object LoopVarValues {
    /** The `loop_var`/`index_var` value of a task, with its kind. */
    class Value(val task: TaskNode, val ref: NameRef, val kind: LoopVarKind)

    /** The [LoopVarSite] at [offset] of the host [file], or null when the offset is in no `loop_var`/`index_var` value. */
    fun at(file: PsiFile, offset: Int): LoopVarSite? {
        val yaml = file as? YAMLFile ?: YamlFiles.yamlFile(file.project, file.originalFile.viewProvider.virtualFile) ?: return null
        val value = valueAt(yaml, offset) ?: return null
        return LoopVarSite(value.ref.text, value.kind, nameRange(yaml.viewProvider.contents, value.ref))
    }

    /** The `loop_var`/`index_var` value whose scalar contains [offset] of [yaml], or null. */
    fun valueAt(yaml: YAMLFile, offset: Int): Value? {
        if (!YamlPaths.isTopLevelSequence(yaml)) return null
        val task = TaskFileModels.of(yaml).itemAt(offset) as? TaskNode ?: return null
        return valueOf(task, offset)
    }

    /** The `loop_var`/`index_var` value of [task] whose scalar contains [offset], or null. */
    fun valueOf(task: TaskNode, offset: Int): Value? {
        val control = task.loopControl ?: return null
        control.loopVar?.takeIf { it.range.containsOffset(offset) && it.text.isNotBlank() }?.let { return Value(task, it, LoopVarKind.LOOP_VAR) }
        control.indexVar?.takeIf { it.range.containsOffset(offset) && it.text.isNotBlank() }?.let { return Value(task, it, LoopVarKind.INDEX_VAR) }
        return null
    }

    /** The value of [task] that names [name] (`loop_var` first, then `index_var`), or null. */
    fun valueNaming(task: TaskNode, name: String): Value? {
        val control = task.loopControl ?: return null
        control.loopVar?.takeIf { it.text == name }?.let { return Value(task, it, LoopVarKind.LOOP_VAR) }
        control.indexVar?.takeIf { it.text == name }?.let { return Value(task, it, LoopVarKind.INDEX_VAR) }
        return null
    }

    /** The range of the name inside the scalar of [ref] (quotes excluded), in [text], the file's text. */
    fun nameRange(text: CharSequence, ref: NameRef): TextRange {
        val end = minOf(ref.range.endOffset, text.length)
        if (ref.range.startOffset >= end) return ref.range
        val inScalar = text.subSequence(ref.range.startOffset, end).indexOf(ref.text)
        return if (inScalar >= 0) TextRange.from(ref.range.startOffset + inScalar, ref.text.length) else ref.range
    }
}
