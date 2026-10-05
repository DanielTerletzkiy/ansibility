package de.terletzkiy.ansibility.lang.jinja.refs

import com.intellij.openapi.util.TextRange
import de.terletzkiy.ansibility.lang.jinja.lexer.JinjaLexMode

/**
 * Text-level variable analysis of Jinja for indexers and locators that run without PSI (plan A.4 `TextJinjaLocator`,
 * A.7 `ansible.var.use`). It lexes with the Jinja lexer and follows Jinja's scoping rules closely enough to separate
 * free variables (which Ansible resolves) from template locals.
 *
 * Besides the references it reports the variables read by name through `hostvars` and `vars`
 * ([JinjaRefsResult.indirectReferences], FU2), in a list of their own so that consumers of [JinjaRefsResult.references]
 * keep seeing direct reads only.
 *
 * Offsets are relative to the analysed text. Comments and `{% raw %}` bodies are never analysed.
 */
object JinjaRefs {
    /** Analyses [text], a template or (in [JinjaLexMode.EXPRESSION]) one bare expression such as a `when:` value. */
    @JvmStatic
    @JvmOverloads
    fun analyze(text: CharSequence, mode: JinjaLexMode = JinjaLexMode.TEMPLATE): JinjaRefsResult =
        JinjaRefsAnalyzer(text, mode).analyze()

    /**
     * Analyses the part outside the braces of an implicit expression that contains `{{ }}` or `{% %}`
     * (`that: "'{{ item }}=' in out.stdout"`, `when: "{{ a }} == b"`). ansible-core (verified on 2.18.8) renders such a
     * value as a template first and then evaluates the result as a bare expression (`Conditional` for `when`,
     * `changed_when`, `failed_when`, `until` and `assert`'s `that`; `debug`'s `var` after the task's argument
     * templating), so the names outside the braces are variable reads too. From 2.19 on (checked on 2.21.4) only
     * braces inside a string constant still render (deprecated, off by default from 2.23); braces elsewhere and in
     * `debug`'s `var` are errors there, so recording the outside names stays right for every version.
     *
     * The text is analysed in [JinjaLexMode.EXPRESSION] mode with every braced part replaced by neutral text of the
     * same length (an output tag by a string literal standing for its value, a statement tag or comment by spaces, as
     * they render nothing, and a `{% raw %}` body by spaces too, which leaves its verbatim text out), so every range
     * stays valid for [text]. Only what lies outside the braced parts is reported: the braced parts themselves are the
     * business of `analyze(text, TEMPLATE)`. A name that touches a braced part (`prefix_{{ x }}`, `{{ x }}_suffix`,
     * also across the whitespace that `{{-`/`-}}` or `trim_blocks` remove: `prefix_ {{- x }}`) is assembled at render
     * time and left out, and so is a subscript or member written inside one (`vars['{{ n }}']`). Without braced parts
     * this is `analyze(text, EXPRESSION)`.
     */
    @JvmStatic
    fun analyzeBracedExpression(text: CharSequence): JinjaRefsResult = JinjaBracedExpressions.analyze(text)
}

/** How a variable is read by name through another object instead of being referenced directly. */
enum class JinjaIndirection {
    /**
     * A member of some host's variables: `hostvars[h].x`, `hostvars[h]['x']`, `hostvars.h.x`,
     * `hosts | map('extract', hostvars, 'x')` and `h | extract(hostvars, 'x')`. The host is usually another one, and
     * even `hostvars[inventory_hostname]` holds only inventory, fact and runtime variables (no play or role
     * variables), so such a read is never treated as a read of the current host's variable.
     */
    HOSTVARS,

    /** The current host's variable by name: `vars['x']`, `vars.x`, `lookup('vars', 'x')`, `query('vars', 'x')`. */
    VARS,
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

/**
 * A variable read by name through `hostvars` or `vars` ([via]): the member `x` of `hostvars[h].x`, `vars['x']` or
 * `lookup('vars', 'x')`. The root (`hostvars`, `vars`, `lookup`) stays an ordinary [JinjaVarRef] as well. Only constant
 * names are recorded: `hostvars[h][name]`, `vars[prefix ~ x]` and `lookup('vars', 'p_' ~ x)` read names that are known
 * only at run time. Roots that resolve to a template local (`{% set vars = … %}`, a macro parameter) are not followed.
 */
data class JinjaIndirectRef(
    /** The variable read: the member name. */
    val name: String,
    val via: JinjaIndirection,
    /**
     * Constant accessors after the member (`hostvars[h].x.y['z']` → `["y", "z"]`, `map('extract', hostvars, ['x', 'y'])`
     * → `["y"]`), with the same stopping rules as [JinjaVarRef.attrPath]. Always empty for `lookup` and `query`, whose
     * result may be a list.
     */
    val attrPath: List<String>,
    /** The member name; inside the quotes when it is written as a string (`['x']`, `'x'` arguments). */
    val nameRange: TextRange,
    /** From the member name through the last [attrPath] segment. */
    val range: TextRange,
    /** The root name the access starts from: `hostvars`, `vars`, `lookup`, `query` or `q`. */
    val rootRange: TextRange,
    /**
     * The read cannot fail on an undefined member: `| default`/`| d` or `is [not] defined|undefined` directly after
     * the access, or a `default=` argument of the `vars` lookup (a `| default` after a lookup does not help, the lookup
     * itself fails). Always false for `extract`, whose guard would apply to the whole list.
     */
    val guarded: Boolean,
    /**
     * An enclosing condition asserts the member is defined: the same member access tested with `is defined` (for
     * [JinjaIndirection.HOSTVARS] on the same host selector as written: `hostvars[h].x is defined` guards
     * `hostvars[h].x`, not `hostvars[other].x`), or, for [JinjaIndirection.VARS], the plain name
     * (`x is defined and vars['x']`). Always false for `extract`, which reads many hosts.
     */
    val guardedByCondition: Boolean,
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
    /**
     * Variables read by name through `hostvars` or `vars` (FU2). They are kept apart from [references] on purpose:
     * a consumer that resolves, types or checks references sees only direct reads unless it asks for these.
     */
    val indirectReferences: List<JinjaIndirectRef> = emptyList(),
) {
    /** The free or local reference whose [JinjaVarRef.range] contains [offset] (end inclusive, for a caret after a name). */
    fun referenceAt(offset: Int): JinjaVarRef? =
        references.firstOrNull { it.range.containsOffset(offset) }
            ?: localReferences.firstOrNull { it.range.containsOffset(offset) }

    /** The indirect read whose [JinjaIndirectRef.range] contains [offset] (end inclusive), or null. */
    fun indirectReferenceAt(offset: Int): JinjaIndirectRef? = indirectReferences.firstOrNull { it.range.containsOffset(offset) }

    /** Locals visible at [offset], innermost binding first. Namespace attributes are included. */
    fun localsVisibleAt(offset: Int): List<JinjaLocal> =
        locals.filter { offset >= it.scope.startOffset && offset < it.scope.endOffset }
            .sortedByDescending { it.scope.startOffset }
}
