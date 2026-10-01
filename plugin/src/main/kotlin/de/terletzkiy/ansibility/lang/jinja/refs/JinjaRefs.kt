package de.terletzkiy.ansibility.lang.jinja.refs

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * Text-level variable analysis of Jinja for indexers and locators that run without PSI (plan A.4 `TextJinjaLocator`,
 * A.7 `ansible.var.use`). It lexes with the Jinja lexer and follows Jinja's scoping rules closely enough to separate
 * free variables (which Ansible resolves) from template locals.
 *
 * Offsets are relative to the analysed text. Comments and `{% raw %}` bodies are never analysed.
 */
object JinjaRefs {
    /** Analyses [text], a template or (in [JinjaLexMode.EXPRESSION]) one bare expression such as a `when:` value. */
    @JvmStatic
    @JvmOverloads
    fun analyze(text: CharSequence, mode: JinjaLexMode = JinjaLexMode.TEMPLATE): JinjaRefsResult =
        JinjaRefsAnalyzer(text, mode).analyze()
}

/**
 * A variable reference: a name that is not an attribute (after `.`), a filter or test name, or a keyword-argument or
 * assignment target (`name=`).
 */
data class JinjaVarRef(
    /** The root name: `item` in `item.floating.ssl['cert_file']`. */
    val name: String,
    /**
     * Constant accessors after the root, in order: `.attr`, `['key']`, `["key"]`, `[0]` and `.0` (as `"0"`). The path
     * stops at the first dynamic subscript, slice or method call, so `x.items()` has an empty path and
     * `hostvars[host].ansible_host` has none either.
     */
    val attrPath: List<String>,
    /** The root name. */
    val nameRange: TextRange,
    /** From the root name through the last [attrPath] segment (closing bracket included). */
    val range: TextRange,
    /**
     * The access is directly followed by `is defined`, `is not defined`, `is undefined`, `| default`/`| default(…)` or
     * `| d`/`| d(…)`.
     */
    val guarded: Boolean,
    /**
     * An enclosing condition asserts the root variable is defined: `x is defined and x.y`, `x.y if x is defined`,
     * `x is not defined or x.y`, or `{% if x is defined %}…{{ x.y }}…{% endif %}` (including `elif`/`else` branches).
     * Macro bodies do not inherit conditions around the macro definition.
     */
    val guardedByCondition: Boolean,
    /** The root is called directly: `lookup(…)`, `range(…)`, `namespace(…)`. */
    val called: Boolean,
    /** The template-local binding the name resolves to, or null for a free (context) variable. */
    val local: JinjaLocal?,
)

/** How a template-local name is bound. */
enum class JinjaLocalKind {
    /** A `{% for x, y in … %}` target, visible in the loop filter and the loop body (not in its `{% else %}`). */
    FOR_TARGET,

    /** The implicit `loop` variable of a `for` body. */
    LOOP,

    /** `{% set x = … %}`, visible after the tag until the end of the enclosing scope. */
    SET,

    /** `{% set x %}…{% endset %}`, visible after `{% endset %}`. */
    BLOCK_SET,

    /**
     * An attribute of a `namespace()` object: a keyword of `namespace(found=false)` or the target of
     * `{% set ns.found = … %}`. Not visible as a bare name; [JinjaLocal.owner] is the namespace variable and the scope
     * is that of the namespace variable (namespace writes leave loops, which is their purpose).
     */
    NAMESPACE_ATTRIBUTE,

    /** A macro name, visible from its `{% macro %}` tag on. */
    MACRO,

    /** A parameter of `{% macro m(a, b=1) %}`, visible in the macro body. */
    MACRO_PARAMETER,

    /** `varargs`, `kwargs` and `caller`, which every macro body has. */
    MACRO_IMPLICIT,

    /** A parameter of `{% call(a) m() %}`, visible in the call body. */
    CALL_PARAMETER,

    /** A `{% with a = … %}` assignment, visible until `{% endwith %}`. */
    WITH,

    /** A name bound by `{% import … as m %}` or `{% from … import a as b %}`. */
    IMPORT,
}

/** A name bound inside the template. */
data class JinjaLocal(
    val name: String,
    val kind: JinjaLocalKind,
    /** The name that binds it; for implicit locals (`loop`, `caller` …) the keyword of the statement that opens the scope. */
    val definitionRange: TextRange,
    /** Where the name is visible: from its binding point to the end of the enclosing scope. */
    val scope: TextRange,
    /** For [JinjaLocalKind.NAMESPACE_ATTRIBUTE]: the namespace variable (`ns` in `ns.found`); null otherwise. */
    val owner: String? = null,
)

/** A filter or test name as written: `default`, `ansible.builtin.splitext`, or a quoted name like `map('basename')`. */
data class JinjaNameSite(
    /** The name with dotted segments joined by `.`. */
    val name: String,
    /** The name (without quotes for [viaString] sites). */
    val range: TextRange,
    /**
     * The name is passed as a string: the first argument of `map` (a filter), of `select`/`reject` (a test), or the
     * second argument of `selectattr`/`rejectattr` (a test).
     */
    val viaString: Boolean = false,
)

/** The result of [JinjaRefs.analyze]. Lists are ordered by offset, except [locals], which are in binding order. */
data class JinjaRefsResult(
    /** Free variable references: everything Ansible has to resolve. */
    val references: List<JinjaVarRef>,
    /** References that resolve to a template local ([JinjaVarRef.local] is set). */
    val localReferences: List<JinjaVarRef>,
    val locals: List<JinjaLocal>,
    val filterNames: List<JinjaNameSite>,
    val testNames: List<JinjaNameSite>,
) {
    /** The free or local reference whose [JinjaVarRef.range] contains [offset] (end inclusive, for a caret after a name). */
    fun referenceAt(offset: Int): JinjaVarRef? =
        references.firstOrNull { it.range.containsOffset(offset) }
            ?: localReferences.firstOrNull { it.range.containsOffset(offset) }

    /** Locals visible at [offset], innermost binding first. Namespace attributes are included. */
    fun localsVisibleAt(offset: Int): List<JinjaLocal> =
        locals.filter { offset >= it.scope.startOffset && offset < it.scope.endOffset }
            .sortedByDescending { it.scope.startOffset }
}
