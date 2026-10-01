package de.terletzkiy.ansibility.semantics.schema

import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * One option schema for role argument specs, module options and keywords
 * (the three sources use the same shape in ansible-core).
 */
data class OptionSpec(
    val name: String,
    val type: OptionType = OptionType.Str,
    val elements: OptionType? = null,
    val required: Boolean = false,
    /** Spec default (documentation only for role specs; the runtime default lives in defaults/main.yml). */
    val default: YValue? = null,
    val choices: Choices? = null,
    val aliases: List<String> = emptyList(),
    /** Paragraphs of Ansible doc markup (strings and lists are both normalised to a list). */
    val description: List<String> = emptyList(),
    /** Nested options (`options` in role specs, `suboptions` in module docs), or null when free-form. */
    val options: Map<String, OptionSpec>? = null,
    val versionAdded: String? = null,
    val deprecated: Deprecation? = null,
    val noLog: Boolean = false,
    /** Only for module docs: where the default value comes from (e.g. env var); informational. */
    val origin: SpecOrigin = SpecOrigin.Unknown,
)

/** The type names accepted by `ArgumentSpecValidator` (DEFAULT_TYPE_VALIDATORS), plus the invalid case. */
sealed interface OptionType {
    val name: String

    data object Str : OptionType { override val name = "str" }
    data object Bool : OptionType { override val name = "bool" }
    data object Int : OptionType { override val name = "int" }
    data object Float : OptionType { override val name = "float" }
    data object List : OptionType { override val name = "list" }
    data object Dict : OptionType { override val name = "dict" }
    data object Path : OptionType { override val name = "path" }
    data object Raw : OptionType { override val name = "raw" }
    data object JsonArg : OptionType { override val name = "jsonarg" }
    data object Json : OptionType { override val name = "json" }
    data object Bytes : OptionType { override val name = "bytes" }
    data object Bits : OptionType { override val name = "bits" }

    /** An unknown type string such as `string`: ansible-core fails at runtime ("'NoneType' object is not callable"). */
    data class Invalid(override val name: String) : OptionType

    companion object {
        private val KNOWN = listOf(Str, Bool, Int, Float, List, Dict, Path, Raw, JsonArg, Json, Bytes, Bits).associateBy { it.name }

        /** A missing type means `str`, as in ansible-core. */
        fun parse(text: String?): OptionType = if (text == null) Str else KNOWN[text] ?: Invalid(text)
    }
}

sealed interface Choices {
    val values: kotlin.collections.List<YValue>

    data class Values(override val values: kotlin.collections.List<YValue>) : Choices

    /** ansible-doc may give choices as a mapping value → description; validation uses the keys. */
    data class Described(val described: kotlin.collections.List<Pair<YValue, kotlin.collections.List<String>>>) : Choices {
        override val values: kotlin.collections.List<YValue> get() = described.map { it.first }
    }
}

data class Deprecation(
    val why: String? = null,
    val alternative: String? = null,
    val removedIn: String? = null,
    val removedFromCollection: String? = null,
)

sealed interface SpecOrigin {
    data object Unknown : SpecOrigin
    /** A role's `meta/argument_specs.yml`; [pointer] is an opaque locator filled in by :plugin. */
    data class RoleSpec(val roleName: String, val entryPoint: String, val pointer: String? = null) : SpecOrigin
    data class ModuleDoc(val fqcn: String, val source: String) : SpecOrigin
    data class Keyword(val keyword: String) : SpecOrigin
}

/** One entry point of a role's `meta/argument_specs.yml`. */
data class ArgumentSpec(
    val entryPoint: String,
    val shortDescription: String? = null,
    val description: List<String> = emptyList(),
    val options: Map<String, OptionSpec> = emptyMap(),
)

/** A module's documentation (from `ansible-doc --json` / the bundled snapshot), normalised. */
data class ModuleDoc(
    val fqcn: String,
    val canonicalFqcn: String = fqcn,
    val shortDescription: String? = null,
    val description: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val options: Map<String, OptionSpec> = emptyMap(),
    val returns: Map<String, ReturnSpec> = emptyMap(),
    /** Attribute name → its `support` value (`full`, `partial`, `none`, `N/A`), or the platforms for `platform`. */
    val attributes: Map<String, String> = emptyMap(),
    val requirements: List<String> = emptyList(),
    val deprecated: Deprecation? = null,
    /** Ansible-markup strings, e.g. `M(ansible.builtin.copy)`, `P(x#lookup)`, `L(label,url) – description`. */
    val seeAlso: List<String> = emptyList(),
    val examples: String? = null,
    /** Name of the free-form pseudo-option (`free_form` for command/shell/raw/meta), if any. */
    val freeForm: String? = null,
    /** Modules such as `set_fact` accept arbitrary keys; unknown options are never flagged for them. */
    val acceptsArbitraryKeys: Boolean = false,
    val filename: String? = null,
    val versionAdded: String? = null,
    /** Where this doc came from, e.g. "bundled 2.18.8", "local 2.21.4". */
    val source: String = "unknown",
)

data class ReturnSpec(
    val name: String,
    /** The doc type `complex` maps to [OptionType.Dict]. */
    val type: OptionType = OptionType.Str,
    val elements: OptionType? = null,
    val description: List<String> = emptyList(),
    val returned: String? = null,
    val sample: String? = null,
    val contains: Map<String, ReturnSpec>? = null,
)

enum class TemplateMode { EXPLICIT, IMPLICIT, STATIC }

/** A play/block/task/handler keyword (keyword_desc.yml + FieldAttribute metadata). */
data class KeywordDoc(
    val name: String,
    val appliesTo: Set<String>,
    val type: OptionType?,
    /** The raw `isa` of the FieldAttribute (list, bool, int, percent, class, string, dict …). */
    val isa: String?,
    /**
     * The FieldAttribute's `listof` as Python type names (`["str"]` for `hosts`, `["str", "int"]` for `tags`): the
     * types every item of a `list` value must have after post-validation; null when items are not checked.
     */
    val listOf: List<String>? = null,
    val template: TemplateMode = TemplateMode.EXPLICIT,
    val description: List<String> = emptyList(),
    val default: String? = null,
)
