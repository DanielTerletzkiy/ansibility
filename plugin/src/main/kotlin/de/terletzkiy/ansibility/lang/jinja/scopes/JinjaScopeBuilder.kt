package de.terletzkiy.ansibility.lang.jinja.scopes

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBlockStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaBody
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallBlock
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaCallExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaElseTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaEndTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaExpression
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFilterBlock
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaForStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaForTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaFromImportStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaImportStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaMacro
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaNamespaceTarget
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaParameter
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaRawStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSetBlockStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaSetStatement
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTag
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTarget
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTargetName
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaTargetTuple
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaVariableReference
import de.terletzkiy.ansibility.lang.jinja.psi.JinjaWithStatement
import de.terletzkiy.ansibility.lang.jinja.lexer.AnsibleJinjaTokenTypes as T
import de.terletzkiy.ansibility.lang.jinja.refs.JinjaLocalKind

/**
 * Builds the [JinjaScopeModel] of one Jinja file: one walk over the PSI in source order with a stack of frames that
 * mirrors Jinja's scopes, the same rules (and the same scope boundaries) as the text-level analysis of
 * `lang.jinja.refs.JinjaRefs`, so both agree wherever both apply:
 *
 * - `for`, `macro`, `call`, `filter`, `with`, `block` and block `set` bodies open a frame; `if` does not;
 * - a name is visible from its binding point to the end of its frame: `for` targets from the loop filter (or the end
 *   of the tag) to `{% else %}`/`{% endfor %}`, `loop` in the body only, `set` targets after the tag, block `set`
 *   targets after `{% endset %}`, a macro name from its tag on, parameters and `varargs`/`kwargs`/`caller` in the body;
 * - values are read before the names they bind: `{% set x = x + 1 %}` reads the outer `x`, a `for` iterable is read
 *   outside the loop, macro and call parameter defaults outside the body;
 * - a `{% set %}` binds in the innermost frame, so one inside a `for` body does not leak, one inside an `if` does;
 * - the `{% else %}` body of a `for` starts a fresh frame (the loop targets and `loop` end there);
 * - `namespace(attr=…)` keyword arguments and `{% set ns.attr = … %}` targets are attributes of the namespace
 *   variable, in the namespace variable's frame (which is how they leave loops).
 *
 * `{% raw %}` bodies, comments and filter or test names are never references.
 */
internal class JinjaScopeBuilder(private val file: AnsibleJinjaFile) {
    private enum class FrameKind { ROOT, FOR, FOR_ELSE, MACRO, CALL, FILTER, WITH, BLOCK, SET_BLOCK }

    private class Frame(var kind: FrameKind) {
        val visible = HashMap<String, Pending>()
        val owned = ArrayList<Pending>()
    }

    private class Pending(
        val name: String,
        val kind: JinjaLocalKind,
        val element: PsiElement,
        val definitionRange: TextRange,
        val scopeStart: Int,
        val owner: String?,
        val frame: Frame,
    ) {
        var scopeEnd = -1
        lateinit var built: JinjaBinding
    }

    private val frames = ArrayList<Frame>()
    private val pending = ArrayList<Pending>()
    private val references = LinkedHashMap<PsiElement, Pending?>()
    private var steps = 0

    fun build(): JinjaScopeModel {
        frames += Frame(FrameKind.ROOT)
        children(file)
        val end = file.textLength
        while (frames.isNotEmpty()) close(frames.removeAt(frames.lastIndex), end)
        val bindings = pending.map { p ->
            JinjaBinding(p.name, p.kind, p.element, p.definitionRange, TextRange(p.scopeStart, maxOf(p.scopeStart, p.scopeEnd)), p.owner)
                .also { p.built = it }
        }
        val resolved = LinkedHashMap<PsiElement, JinjaBinding?>(references.size)
        for ((element, local) in references) resolved[element] = local?.built
        return JinjaScopeModel(bindings, resolved)
    }

    // ------------------------------------------------------------------------------------------------ walk

    private fun children(element: PsiElement) {
        var child = element.firstChild
        while (child != null) {
            visit(child)
            child = child.nextSibling
        }
    }

    private fun visit(element: PsiElement?) {
        if (element == null) return
        if (++steps and 0xFF == 0) ProgressManager.checkCanceled()
        when (element) {
            is JinjaVariableReference -> references[element] = lookup(element.name)
            is JinjaForStatement -> forStatement(element)
            is JinjaSetStatement -> setStatement(element)
            is JinjaSetBlockStatement -> setBlock(element)
            is JinjaMacro -> macro(element)
            is JinjaCallBlock -> callBlock(element)
            is JinjaFilterBlock -> scopedBlock(element, FrameKind.FILTER) { tag -> children(tag) }
            is JinjaWithStatement -> withStatement(element)
            is JinjaBlockStatement -> scopedBlock(element, FrameKind.BLOCK) {}
            is JinjaImportStatement -> {
                element.template?.let(::visit)
                element.alias?.let { define(nearestScope(), it, JinjaLocalKind.IMPORT, element.textRange.endOffset) }
            }
            is JinjaFromImportStatement -> {
                element.template?.let(::visit)
                val scope = nearestScope()
                for (imported in element.importedNames) {
                    val bound = imported.alias ?: imported.importedNameElement
                    define(scope, bound.text, JinjaLocalKind.IMPORT, bound, bound.textRange, element.textRange.endOffset)
                }
            }
            is JinjaRawStatement, is JinjaTargetName, is JinjaNamespaceTarget, is JinjaTargetTuple -> Unit
            else -> children(element)
        }
    }

    /** `{% for targets in iterable [if filter] %}…[{% else %}…]{% endfor %}`. */
    private fun forStatement(statement: JinjaForStatement) {
        var frame: Frame? = null
        var child = statement.firstChild
        while (child != null) {
            when {
                child is JinjaForTag -> frame = forHeader(child)
                child is JinjaElseTag && frame != null -> {
                    // the loop's `else` body runs without the loop targets, `loop` and the body's sets
                    close(frame, child.textRange.startOffset)
                    frame.visible.clear()
                    frame.kind = FrameKind.FOR_ELSE
                }
                child is JinjaEndTag -> Unit
                else -> visit(child)
            }
            child = child.nextSibling
        }
        frame?.let { pop(it, endOf(statement)) }
    }

    private fun forHeader(tag: JinjaForTag): Frame {
        // the iterable is evaluated in the outer scope
        tag.iterable?.let(::visit)
        val frame = push(FrameKind.FOR)
        val filterKeyword = tag.node.findChildByType(T.IF_KEYWORD)?.psi
        val targetsFrom = filterKeyword?.textRange?.startOffset ?: tag.textRange.endOffset
        for (target in tag.targetNames) define(frame, target, JinjaLocalKind.FOR_TARGET, targetsFrom)
        tag.filterCondition?.let(::visit)
        val keyword = tag.tagKeyword ?: tag
        define(frame, LOOP, JinjaLocalKind.LOOP, keyword, keyword.textRange, tag.textRange.endOffset)
        return frame
    }

    /** `{% set x = v %}`, `{% set a, b = … %}`, `{% set ns.attr = v %}`. */
    private fun setStatement(statement: JinjaSetStatement) {
        val scope = nearestScope()
        val end = statement.textRange.endOffset
        // the value is evaluated before the names are bound
        statement.value?.let(::visit)
        val names = ArrayList<PsiElement>()
        for (part in targetParts(statement.target)) {
            when (part) {
                is JinjaNamespaceTarget -> {
                    val attribute = part.attributeElement
                    if (attribute == null) {
                        names += part.namespaceElement
                        continue
                    }
                    val namespace = lookup(part.namespaceName)
                    references[part] = namespace
                    define(
                        namespace?.frame ?: frames.first(), attribute.text, JinjaLocalKind.NAMESPACE_ATTRIBUTE, attribute,
                        attribute.textRange, end, owner = part.namespaceName, visible = false,
                    )
                }
                is JinjaTargetName -> names += part
            }
        }
        for (name in names) define(scope, name.text, JinjaLocalKind.SET, name, name.textRange, end)
        val single = names.singleOrNull() ?: return
        val call = namespaceCall(statement.value) ?: return
        for (argument in call.argumentList?.keywordArguments.orEmpty()) {
            val element = argument.nameElement
            define(
                scope, element.text, JinjaLocalKind.NAMESPACE_ATTRIBUTE, element, element.textRange, end,
                owner = single.text, visible = false,
            )
        }
    }

    /** `{% set x | filters %}…{% endset %}`: the body is its own scope, `x` is bound after `{% endset %}`. */
    private fun setBlock(statement: JinjaSetBlockStatement) {
        val tag = statement.setTag
        tag?.filter?.let(::visit)
        val frame = push(FrameKind.SET_BLOCK)
        bodies(statement)
        pop(frame, endOf(statement))
        val endTag = statement.endTag ?: return
        val scope = nearestScope()
        for (target in targetParts(tag?.target)) {
            val names = when (target) {
                is JinjaTargetName -> listOf(target.nameIdentifier)
                is JinjaNamespaceTarget -> listOfNotNull(target.namespaceElement, target.attributeElement)
                else -> emptyList()
            }
            for (name in names) define(scope, name.text, JinjaLocalKind.BLOCK_SET, name, name.textRange, endTag.textRange.endOffset)
        }
    }

    /** `{% macro name(params) %}…{% endmacro %}`. */
    private fun macro(statement: JinjaMacro) {
        val tag = statement.macroTag
        tag?.nameElement?.let { define(nearestScope(), it, JinjaLocalKind.MACRO, tag.textRange.startOffset) }
        val parameters = tag?.parameterList?.parameters.orEmpty()
        defaults(parameters)
        val frame = push(FrameKind.MACRO)
        val bodyStart = tag?.textRange?.endOffset ?: statement.textRange.startOffset
        for (parameter in parameters) parameter.nameElement?.let { define(frame, it, JinjaLocalKind.MACRO_PARAMETER, bodyStart) }
        val keyword = tag?.tagKeyword ?: statement
        for (implicit in MACRO_IMPLICITS) define(frame, implicit, JinjaLocalKind.MACRO_IMPLICIT, keyword, keyword.textRange, bodyStart)
        bodies(statement)
        pop(frame, endOf(statement))
    }

    /** `{% call(params) macro(args) %}…{% endcall %}`. */
    private fun callBlock(statement: JinjaCallBlock) {
        val tag = statement.callTag
        val parameters = tag?.parameterList?.parameters.orEmpty()
        defaults(parameters)
        tag?.let { callTag ->
            var child = callTag.firstChild
            while (child != null) {
                if (child is JinjaExpression) visit(child)
                child = child.nextSibling
            }
        }
        val frame = push(FrameKind.CALL)
        val bodyStart = tag?.textRange?.endOffset ?: statement.textRange.startOffset
        for (parameter in parameters) parameter.nameElement?.let { define(frame, it, JinjaLocalKind.CALL_PARAMETER, bodyStart) }
        bodies(statement)
        pop(frame, endOf(statement))
    }

    /** `{% with a = 1, b = x %}…{% endwith %}`: the values are read outside, the names bound inside. */
    private fun withStatement(statement: JinjaWithStatement) {
        val tag = statement.withTag
        val assignments = tag?.assignments.orEmpty()
        for (assignment in assignments) assignment.value?.let(::visit)
        val frame = push(FrameKind.WITH)
        val bodyStart = tag?.textRange?.endOffset ?: statement.textRange.startOffset
        for (assignment in assignments) (assignment.target as? JinjaTargetName)?.let { define(frame, it, JinjaLocalKind.WITH, bodyStart) }
        bodies(statement)
        pop(frame, endOf(statement))
    }

    /** A block statement whose bodies form one [kind] frame; [header] walks the opening tag first. */
    private inline fun scopedBlock(statement: PsiElement, kind: FrameKind, header: (JinjaTag) -> Unit) {
        val tag = PsiTreeUtil.getChildOfType(statement, JinjaTag::class.java)?.takeIf { it !is JinjaEndTag }
        tag?.let(header)
        val frame = push(kind)
        bodies(statement)
        pop(frame, endOf(statement))
    }

    /** Walks the bodies (and any recovered content) of a block statement, never its tags. */
    private fun bodies(statement: PsiElement) {
        var child = statement.firstChild
        while (child != null) {
            if (child is JinjaBody || (child !is JinjaTag && child.firstChild != null)) visit(child)
            child = child.nextSibling
        }
    }

    private fun defaults(parameters: List<JinjaParameter>) {
        for (parameter in parameters) parameter.defaultValue?.let(::visit)
    }

    // ------------------------------------------------------------------------------------------------ frames

    private fun push(kind: FrameKind): Frame = Frame(kind).also { frames += it }

    /** The innermost frame (every frame is a scope; `if` has none). */
    private fun nearestScope(): Frame = frames.last()

    /** Pops [frame] and every frame above it, ending their locals at [offset]. */
    private fun pop(frame: Frame, offset: Int) {
        val index = frames.indexOfLast { it === frame }
        if (index <= 0) return
        while (frames.size > index) close(frames.removeAt(frames.lastIndex), offset)
    }

    private fun close(frame: Frame, offset: Int) {
        for (local in frame.owned) if (local.scopeEnd < 0) local.scopeEnd = offset
        frame.owned.clear()
    }

    private fun define(frame: Frame, target: JinjaTargetName, kind: JinjaLocalKind, scopeStart: Int) =
        define(frame, target.name, kind, target, target.nameIdentifier.textRange, scopeStart)

    private fun define(
        frame: Frame,
        name: String,
        kind: JinjaLocalKind,
        element: PsiElement,
        definitionRange: TextRange,
        scopeStart: Int,
        owner: String? = null,
        visible: Boolean = true,
    ) {
        val local = Pending(name, kind, element, definitionRange, scopeStart, owner, frame)
        frame.owned += local
        if (visible) frame.visible[name] = local
        pending += local
    }

    private fun lookup(name: String): Pending? {
        for (index in frames.indices.reversed()) frames[index].visible[name]?.let { return it }
        return null
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Where the frame of a block statement ends: at its end tag, else at the statement's end. */
    private fun endOf(statement: PsiElement): Int =
        PsiTreeUtil.getChildOfType(statement, JinjaEndTag::class.java)?.textRange?.startOffset ?: statement.textRange.endOffset

    /** The plain names and namespace targets of an assignment target, in source order (tuples flattened). */
    private fun targetParts(target: JinjaTarget?): List<JinjaTarget> = when (target) {
        null -> emptyList()
        is JinjaTargetTuple -> target.targets.flatMap(::targetParts)
        else -> listOf(target)
    }

    /** The `namespace(…)` call a `set` value starts with (`namespace(found=false)`), or null. */
    private fun namespaceCall(value: JinjaExpression?): JinjaCallExpression? {
        var current: PsiElement? = value ?: return null
        val start = value.textRange.startOffset
        while (current != null && current.textRange.startOffset == start) {
            if (current is JinjaCallExpression) {
                val callee = current.callee as? JinjaVariableReference
                if (callee?.name == NAMESPACE && current.argumentList != null) return current
            }
            current = current.firstChild
        }
        return null
    }

    private companion object {
        const val LOOP = "loop"
        const val NAMESPACE = "namespace"
        val MACRO_IMPLICITS = listOf("varargs", "kwargs", "caller")
    }
}
