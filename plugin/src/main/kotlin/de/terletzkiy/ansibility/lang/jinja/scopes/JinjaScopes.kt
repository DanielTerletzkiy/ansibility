package de.terletzkiy.ansibility.lang.jinja.scopes

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaElementTypes
import de.terletzkiy.ansibility.lang.jinja.psi.AnsibleJinjaFile
import de.terletzkiy.ansibility.semantics.schema.OptionType

/**
 * Jinja's scoping rules on the Ansible Jinja PSI (plan A.5 `JinjaScopes`, WU C5), for template files and for fragments
 * injected into YAML alike:
 *
 * - `for`, `macro`, `call`, `filter`, `with`, `block` and block `set` open a scope ([SCOPE_STATEMENTS]); `if` does not;
 * - a `{% set %}` inside a `for` body does not leak out of the loop (one inside an `if` does);
 * - `include` passes the context (the locals visible at the tag, [JinjaScopeModel.contextPassedTo]), `import` and
 *   `from … import` do not unless written `with context`;
 * - `loop` ([LOOP_ATTRIBUTES]) exists inside a `for` body only;
 * - `namespace()` attributes, from `namespace(found=false)` or `{% set ns.found = … %}`, belong to the namespace
 *   variable and are visible wherever it is ([JinjaScopeModel.resolveMember]).
 *
 * The model is computed once per file and cached until the file changes. Call in a read action. Needs no index.
 */
object JinjaScopes {
    private val MODEL = Key.create<CachedValue<JinjaScopeModel>>("ansibility.jinja.scopes")

    /** Statements that open a scope (`if` does not). */
    val SCOPE_STATEMENTS: TokenSet = AnsibleJinjaElementTypes.SCOPE_STATEMENTS

    /**
     * The attributes of the implicit `loop` variable inside a `for` body, with their Python types (Jinja 3.1
     * `LoopContext`): `cycle` and `changed` are callables, `previtem`/`nextitem` the neighbouring items.
     */
    val LOOP_ATTRIBUTES: Map<String, OptionType> = linkedMapOf(
        "index" to OptionType.Int,
        "index0" to OptionType.Int,
        "revindex" to OptionType.Int,
        "revindex0" to OptionType.Int,
        "first" to OptionType.Bool,
        "last" to OptionType.Bool,
        "length" to OptionType.Int,
        "depth" to OptionType.Int,
        "depth0" to OptionType.Int,
        "previtem" to OptionType.Raw,
        "nextitem" to OptionType.Raw,
        "cycle" to OptionType.Raw,
        "changed" to OptionType.Raw,
    )

    /** The scopes of [file], cached until it changes. */
    fun of(file: AnsibleJinjaFile): JinjaScopeModel = CachedValuesManager.getCachedValue(file, MODEL) {
        CachedValueProvider.Result.create(JinjaScopeBuilder(file).build(), file)
    }

    /** The scopes of the Jinja file containing [element], or null outside Ansible Jinja. */
    fun of(element: PsiElement): JinjaScopeModel? = (element.containingFile as? AnsibleJinjaFile)?.let(::of)
}
