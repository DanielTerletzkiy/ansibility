package de.terletzkiy.ansibility.lang.jinja.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.OuterLanguageElementType
import com.intellij.psi.tree.TokenSet
import de.terletzkiy.ansibility.lang.jinja.AnsibleJinjaLanguage

/**
 * A composite element of the Ansible Jinja PSI. [toString] is the debug name that PSI dumps print; [createPsi] builds
 * the PSI class of the node (called by the parser definition).
 */
class AnsibleJinjaElementType(debugName: String, private val factory: (ASTNode) -> PsiElement) :
    IElementType(debugName, AnsibleJinjaLanguage) {
    /** The PSI element for [node], a node of this type. */
    fun createPsi(node: ASTNode): PsiElement = factory(node)
}

/**
 * The composite element types the parser builds (plan A.5, WU C2), from template structure down to expressions.
 *
 * The tree mirrors `jinja2/parser.py` 3.1:
 * - a template is a sequence of outer text (`TEXT`), comments, [OUTPUT_TAG]s and statements;
 * - a block statement holds its opening tag, a [BODY] per branch, its middle tags (`elif`, `else`) and its [END_TAG];
 *   single-tag statements (`set x = …`, `include`, `import`, `from … import`, `extends`, `do`, `break`, `continue` and
 *   unknown tags) are the tag itself;
 * - expressions follow Jinja's precedence: conditional, `or`, `and`, `not`, comparison, `+`/`-`, `~`, `*`/`/`/`//`/`%`,
 *   `**`, unary, then postfix (`.attr`, `[…]`, calls), filters and tests.
 */
object AnsibleJinjaElementTypes {
    /** The root of every Ansible Jinja tree: a template file or an injected fragment. */
    @JvmField val FILE: IFileElementType = IFileElementType("ANSIBLE_JINJA_FILE", AnsibleJinjaLanguage)

    /**
     * The Jinja ranges as they appear in the outer-language tree of a template file (one leaf per tag run), created by
     * the template data element type.
     */
    @JvmField val OUTER_FRAGMENT: IElementType = OuterLanguageElementType("ANSIBLE_JINJA_FRAGMENT", AnsibleJinjaLanguage)

    private fun type(name: String, factory: (ASTNode) -> PsiElement) = AnsibleJinjaElementType(name, factory)

    // ---------------------------------------------------------------- template structure

    /** `{{ … }}`. */
    @JvmField val OUTPUT_TAG = type("OUTPUT_TAG", ::JinjaOutputTag)

    /** The content of one branch of a block statement, between two of its tags. */
    @JvmField val BODY = type("BODY", ::JinjaBody)

    // ---------------------------------------------------------------- block statements

    @JvmField val IF_STATEMENT = type("IF_STATEMENT", ::JinjaIfStatement)
    @JvmField val FOR_STATEMENT = type("FOR_STATEMENT", ::JinjaForStatement)

    /** `{% set x %}…{% endset %}` (with optional filters). */
    @JvmField val SET_BLOCK_STATEMENT = type("SET_BLOCK_STATEMENT", ::JinjaSetBlockStatement)
    @JvmField val MACRO_STATEMENT = type("MACRO_STATEMENT", ::JinjaMacro)
    @JvmField val CALL_STATEMENT = type("CALL_STATEMENT", ::JinjaCallBlock)
    @JvmField val FILTER_STATEMENT = type("FILTER_STATEMENT", ::JinjaFilterBlock)
    @JvmField val WITH_STATEMENT = type("WITH_STATEMENT", ::JinjaWithStatement)
    @JvmField val BLOCK_STATEMENT = type("BLOCK_STATEMENT", ::JinjaBlockStatement)

    /** `{% raw %}…{% endraw %}`; the body is one opaque `RAW_TEXT` token. */
    @JvmField val RAW_STATEMENT = type("RAW_STATEMENT", ::JinjaRawStatement)

    // ---------------------------------------------------------------- tags of block statements

    @JvmField val IF_TAG = type("IF_TAG", ::JinjaIfTag)
    @JvmField val ELIF_TAG = type("ELIF_TAG", ::JinjaIfTag)
    @JvmField val ELSE_TAG = type("ELSE_TAG", ::JinjaElseTag)
    @JvmField val FOR_TAG = type("FOR_TAG", ::JinjaForTag)
    @JvmField val SET_TAG = type("SET_TAG", ::JinjaSetTag)
    @JvmField val MACRO_TAG = type("MACRO_TAG", ::JinjaMacroTag)
    @JvmField val CALL_TAG = type("CALL_TAG", ::JinjaCallTag)
    @JvmField val FILTER_TAG = type("FILTER_TAG", ::JinjaFilterTag)
    @JvmField val WITH_TAG = type("WITH_TAG", ::JinjaWithTag)
    @JvmField val BLOCK_TAG = type("BLOCK_TAG", ::JinjaBlockTag)
    @JvmField val RAW_TAG = type("RAW_TAG", ::JinjaRawTag)

    /** Any `{% end… %}` tag that closes a block statement. */
    @JvmField val END_TAG = type("END_TAG", ::JinjaEndTag)

    // ---------------------------------------------------------------- single-tag statements

    /** `{% set x = … %}`, `{% set a, b = … %}`, `{% set ns.attr = … %}`. */
    @JvmField val SET_STATEMENT = type("SET_STATEMENT", ::JinjaSetStatement)
    @JvmField val INCLUDE_STATEMENT = type("INCLUDE_STATEMENT", ::JinjaIncludeStatement)
    @JvmField val IMPORT_STATEMENT = type("IMPORT_STATEMENT", ::JinjaImportStatement)
    @JvmField val FROM_IMPORT_STATEMENT = type("FROM_IMPORT_STATEMENT", ::JinjaFromImportStatement)
    @JvmField val EXTENDS_STATEMENT = type("EXTENDS_STATEMENT", ::JinjaExtendsStatement)
    @JvmField val DO_STATEMENT = type("DO_STATEMENT", ::JinjaDoStatement)
    @JvmField val BREAK_STATEMENT = type("BREAK_STATEMENT", ::JinjaLoopControlStatement)
    @JvmField val CONTINUE_STATEMENT = type("CONTINUE_STATEMENT", ::JinjaLoopControlStatement)

    /** A tag Jinja core does not define (`{% print %}`, extension tags …): kept as a generic statement. */
    @JvmField val GENERIC_STATEMENT = type("GENERIC_STATEMENT", ::JinjaGenericStatement)

    // ---------------------------------------------------------------- statement parts

    /** `a = expr` inside `{% with … %}`. */
    @JvmField val WITH_ASSIGNMENT = type("WITH_ASSIGNMENT", ::JinjaWithAssignment)

    /** `name` or `name as alias` in `{% from … import … %}`. */
    @JvmField val IMPORTED_NAME = type("IMPORTED_NAME", ::JinjaImportedName)

    /** `(a, b=1)` of a macro or a call block. */
    @JvmField val PARAMETER_LIST = type("PARAMETER_LIST", ::JinjaParameterList)
    @JvmField val PARAMETER = type("PARAMETER", ::JinjaParameter)

    // ---------------------------------------------------------------- assignment targets

    /** A name being bound: loop variable, `set` target, macro or parameter name, import alias. */
    @JvmField val TARGET_NAME = type("TARGET_NAME", ::JinjaTargetName)

    /** `ns.attr` as a `set` target. */
    @JvmField val NAMESPACE_TARGET = type("NAMESPACE_TARGET", ::JinjaNamespaceTarget)

    /** `k, v` or `(k, v)` as a target. */
    @JvmField val TARGET_TUPLE = type("TARGET_TUPLE", ::JinjaTargetTuple)

    // ---------------------------------------------------------------- expressions

    @JvmField val VARIABLE_REFERENCE = type("VARIABLE_REFERENCE", ::JinjaVariableReference)

    /** Strings (adjacent strings concatenate), numbers, `true`/`false`, `none`. */
    @JvmField val LITERAL = type("LITERAL", ::JinjaLiteral)
    @JvmField val LIST_EXPRESSION = type("LIST_EXPRESSION", ::JinjaListLiteral)
    @JvmField val TUPLE_EXPRESSION = type("TUPLE_EXPRESSION", ::JinjaTupleExpression)
    @JvmField val DICT_EXPRESSION = type("DICT_EXPRESSION", ::JinjaDictLiteral)
    @JvmField val DICT_ENTRY = type("DICT_ENTRY", ::JinjaDictEntry)
    @JvmField val PARENTHESIZED_EXPRESSION = type("PARENTHESIZED_EXPRESSION", ::JinjaParenthesizedExpression)

    /** `x.attr`, `x.0`. */
    @JvmField val MEMBER_ACCESS = type("MEMBER_ACCESS", ::JinjaMemberAccess)

    /** `x[key]`, `x[1:2]`. */
    @JvmField val SUBSCRIPTION = type("SUBSCRIPTION", ::JinjaSubscription)
    @JvmField val SLICE = type("SLICE", ::JinjaSlice)
    @JvmField val CALL_EXPRESSION = type("CALL_EXPRESSION", ::JinjaCallExpression)
    @JvmField val ARGUMENT_LIST = type("ARGUMENT_LIST", ::JinjaArgumentList)
    @JvmField val KEYWORD_ARGUMENT = type("KEYWORD_ARGUMENT", ::JinjaKeywordArgument)
    @JvmField val STAR_ARGUMENT = type("STAR_ARGUMENT", ::JinjaStarArgument)
    @JvmField val DOUBLE_STAR_ARGUMENT = type("DOUBLE_STAR_ARGUMENT", ::JinjaStarArgument)

    /** `x | name(args)`, also the operand-less filters of `{% filter %}` and block `set`. */
    @JvmField val FILTER_CALL = type("FILTER_CALL", ::JinjaFilterCall)

    /** The (possibly dotted) name of a filter: `default`, `ansible.builtin.splitext`. */
    @JvmField val FILTER_REFERENCE = type("FILTER_REFERENCE", ::JinjaFilterName)

    /** `x is [not] name args`. */
    @JvmField val TEST_EXPRESSION = type("TEST_EXPRESSION", ::JinjaTestExpr)

    /** The (possibly dotted) name of a test: `defined`, `ansible.builtin.version`. */
    @JvmField val TEST_REFERENCE = type("TEST_REFERENCE", ::JinjaTestName)

    /** `not x`, `-x`, `+x`. */
    @JvmField val UNARY_EXPRESSION = type("UNARY_EXPRESSION", ::JinjaUnaryExpression)

    /** `and`, `or`, `+`, `-`, `~`, `*`, `/`, `//`, `%`, `**` (left-associative, as in Jinja). */
    @JvmField val BINARY_EXPRESSION = type("BINARY_EXPRESSION", ::JinjaBinaryExpression)

    /** A comparison chain: `a == b`, `a < b <= c`, `x in y`, `x not in y`. */
    @JvmField val COMPARE_EXPRESSION = type("COMPARE_EXPRESSION", ::JinjaCompareExpression)

    /** `a if cond else b` (the `else` part is optional in Jinja). */
    @JvmField val CONDITIONAL_EXPRESSION = type("CONDITIONAL_EXPRESSION", ::JinjaConditionalExpression)

    // ---------------------------------------------------------------- token sets

    /** Every tag that opens or continues a block statement, and the end tags. */
    @JvmField
    val BLOCK_TAGS: TokenSet = TokenSet.create(
        IF_TAG, ELIF_TAG, ELSE_TAG, FOR_TAG, SET_TAG, MACRO_TAG, CALL_TAG, FILTER_TAG, WITH_TAG, BLOCK_TAG, RAW_TAG, END_TAG,
    )

    /** Statements made of one tag. */
    @JvmField
    val SINGLE_TAG_STATEMENTS: TokenSet = TokenSet.create(
        SET_STATEMENT, INCLUDE_STATEMENT, IMPORT_STATEMENT, FROM_IMPORT_STATEMENT, EXTENDS_STATEMENT, DO_STATEMENT,
        BREAK_STATEMENT, CONTINUE_STATEMENT, GENERIC_STATEMENT,
    )

    /** Statements with a body. */
    @JvmField
    val BLOCK_STATEMENTS: TokenSet = TokenSet.create(
        IF_STATEMENT, FOR_STATEMENT, SET_BLOCK_STATEMENT, MACRO_STATEMENT, CALL_STATEMENT, FILTER_STATEMENT, WITH_STATEMENT,
        BLOCK_STATEMENT, RAW_STATEMENT,
    )

    /** Statements that open a Jinja scope (`if` does not; plan A.5 `JinjaScopes`). */
    @JvmField
    val SCOPE_STATEMENTS: TokenSet = TokenSet.create(
        FOR_STATEMENT, MACRO_STATEMENT, CALL_STATEMENT, FILTER_STATEMENT, WITH_STATEMENT, BLOCK_STATEMENT, SET_BLOCK_STATEMENT,
    )
}
