package de.terletzkiy.ansibility.vars

import de.terletzkiy.ansibility.api.AnsibleRoot
import de.terletzkiy.ansibility.api.SourceLocation
import de.terletzkiy.ansibility.api.SpecBinding
import de.terletzkiy.ansibility.api.VarDefinition
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.yaml.YValue

/**
 * Everything the variable documentation card shows (plan F1.2, F4.3, F4.8; X06, X07, X84), computed in one read
 * action by [VarCards] and rendered by [VarCardHtml]. Texts are plain (the renderer escapes them); descriptions are
 * Ansible doc markup unless [descriptionFromComment] is set. No value of a vault file, a `vault_*` variable or a
 * `!vault` scalar is ever stored here.
 */
internal class VarCard(
    val root: AnsibleRoot,
    val subject: VarSubject,
    /** `haproxy_servers.port`: the variable and the resolved option names. */
    val title: String,
    /** The role the card documents: the primary spec's role, else the role whose default is shown. */
    val role: String?,
    /** The spec that documents the variable (own role first, plan F1.5). */
    val binding: SpecBinding?,
    /** The documented option: [binding]'s option, or the nested option [optionNames] below it. */
    val option: OptionSpec?,
    /** Option names from [binding]'s option down to [option] (empty for a top-level option). */
    val optionNames: List<String>,
    val description: List<String>,
    /** The description is the comment block above the defaults key (X07), plain text rather than doc markup. */
    val descriptionFromComment: Boolean,
    val runtimeDefault: RuntimeDefault?,
    /** Why there is no runtime default, when the card documents a top-level option without one. */
    val missingDefault: MissingDefault?,
    /** ⚠ reasons (X06): the spec and runtime defaults disagree, or the runtime default is a lossy YAML type. */
    val badges: List<String>,
    val subOptions: List<SubOptionRow>,
    val subOptionsTruncated: Int,
    val declaredBy: List<Declaration>,
    val setIn: List<SetInGroup>,
    /** The top-level variable whose definitions [setIn] lists, when the card documents a nested option. */
    val setInOf: String?,
    val thisDefinition: ThisDefinition?,
    val note: Note?,
    /** The loop variable the reference was written through, when the card documents an element of the iterated variable. */
    val loop: VarSubject.LoopVia? = null,
) {
    /** `str`, `list[dict]`, or null when nothing documents the type. */
    val typeText: String? get() = option?.let(::typeText)

    /** Options below [option] come from `options`, for dicts and lists of dicts alike. */
    val hasSubOptions: Boolean get() = subOptions.isNotEmpty()

    companion object {
        fun typeText(option: OptionSpec): String {
            val elements = option.elements
            return if (option.type == OptionType.List && elements != null) "list[${elements.name}]" else option.type.name
        }
    }
}

/** The runtime default of the documented role (`defaults/main.yml`), as written. */
internal class RuntimeDefault(
    val definition: VarDefinition,
    /** `roles/haproxy/defaults/main.yml:15`. */
    val label: String,
    /** The value as written in the file; null for secrets. */
    val text: String?,
    /** The loaded value (null for secrets and for nested values that are not written there). */
    val value: YValue?,
    /** The value holds Jinja, which ansible-core evaluates at runtime. */
    val isJinja: Boolean,
    /** The vault-safe replacement text, when the value must not be shown. */
    val secret: String?,
    /** The comment block above the key, when it is not already the description. */
    val comment: String?,
    /** Where a bare `{{ name }}` default leads through the same role's defaults. */
    val chain: DefaultChain?,
)

/** `→ int 65535 via haproxy_settings_maximum_connections (roles/haproxy/defaults/main.yml:12)`. */
internal class DefaultChain(val typeName: String, val valueText: String, val value: YValue, val via: String, val location: SourceLocation, val label: String)

internal enum class MissingDefault { NONE, DOCUMENTED, REQUIRED }

/** One row of the nested options table: [names] from the card's option down to this sub-option. */
internal class SubOptionRow(val depth: Int, val names: List<String>, val option: OptionSpec)

/** Another role in the root whose spec declares the same (nested) option (X84). */
internal class Declaration(
    val role: String,
    val required: Boolean,
    /** `required` differs from the card's option. */
    val differs: Boolean,
    val location: SourceLocation,
    val label: String,
)

/** Definitions of one environment (or another group: playbook vars, roles and plays, molecule). */
internal class SetInGroup(val title: String, val entries: List<SetInEntry>)

internal class SetInEntry(
    val location: SourceLocation,
    /** `environments/prod/group_vars/all/vars.yml:471`. */
    val label: String,
    /** `inventory group_vars/all · level 4`. */
    val layer: String,
    /** A vault-safe value preview. */
    val preview: String?,
    /** The vault-safe replacement text for secrets. */
    val secret: String?,
)

/** "This definition" (plan F4.3): layer, environment, level, and the role default it overrides. */
internal class ThisDefinition(
    val definition: VarDefinition,
    /** `inventory group_vars/all`, `env prod`, `level 4`, … in display order. */
    val parts: List<String>,
    /** `overrides postfix default "" (roles/postfix/defaults/main.yml:2)`. */
    val overrides: String?,
    val overridesLocation: SourceLocation?,
    val secret: String?,
)

/** Cards without a documented variable behind them. */
internal sealed interface Note {
    /** A Jinja local bound in the template ([kind] is its binding form). */
    data class Local(val kind: String, val label: String) : Note

    /** The loop variable of the task at [label]. */
    data class Loop(val label: String) : Note

    /** Nothing in the root defines, declares or sets the name. */
    data class Undefined(val rootName: String) : Note
}
