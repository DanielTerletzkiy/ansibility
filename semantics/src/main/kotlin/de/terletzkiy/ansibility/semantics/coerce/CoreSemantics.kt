package de.terletzkiy.ansibility.semantics.coerce

import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * Version-dependent behaviour of ansible-core that the type checks depend on (plan A.5).
 *
 * Boundaries come from the sources of 2.18.8 (the target repo's Docker pin), 2.19.0, 2.19.1, 2.20.0 and 2.21.4,
 * and every flag is pinned by the golden tables recorded with 2.18.8 and 2.21.4. `check_type_int` is
 * Decimal-based in all of them (`42.0`, `'42.0'` and `'1e3'` are accepted), so there is no flag for it.
 */
data class CoreSemantics(val version: CoreVersion) {
    /**
     * `check_type_str(None)` raises `TypeError` (measured in 2.18.8; the 2.19.0 source still raises, 2.19.1 added
     * `return ''`). Affects `str` and `path` options and `str`/`path` elements.
     */
    val strRejectsNone: Boolean get() = version < V2_19_1

    /**
     * Classic templating (≤ 2.18): only a bare `{{ name }}` keeps its native type; every other template renders to
     * a string, which is then `literal_eval`'d only if it looks like a list, dict or bool.
     */
    val stringifyNonBareTemplates: Boolean get() = version < V2_19

    /** 2.19+ refuses non-bool results in conditionals (`when: "yes"` fails), which makes str-for-bool coercion unsafe. */
    val conditionalsMustBeBool: Boolean get() = version >= V2_19

    /**
     * ≤ 2.18 checks `elements` against the *declared* type (`wanted_type != 'list'`), so `type: raw` with
     * `elements` always fails; 2.19+ checks that the converted value is a list.
     */
    val elementsRequireListType: Boolean get() = version < V2_19

    /** 2.19+ serialises `jsonarg` values with the legacy profile encoder (dates → ISO strings); 2.18 rejects dates. */
    val jsonargEncodesDates: Boolean get() = version >= V2_19

    /** 2.19+ `convert_bool.boolean()` maps unhashable values to None instead of raising (visible with `strict=False`). */
    val booleanToleratesUnhashable: Boolean get() = version >= V2_19

    /** Error messages name types as `str` (2.19+, `native_type_name`) rather than `<class 'str'>` (≤ 2.18). */
    val nativeTypeNamesInMessages: Boolean get() = version >= V2_19

    companion object {
        private val V2_19 = CoreVersion(2, 19, 0)
        private val V2_19_1 = CoreVersion(2, 19, 1)

        /** The semantics of the target repo's pinned ansible-core (2.18.8). */
        val PINNED: CoreSemantics = CoreSemantics(CoreVersion.PINNED)
    }
}
