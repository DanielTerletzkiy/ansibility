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
 * `!vault` scalar is ever stored here, and no value of a variable a spec keeps secret (`no_log`: [runtimeDefault],
 * [documented], [setIn], [thisDefinition]); the renderer never shows [option]'s own `default`.
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
    /** The role default Ansible uses (plan amendment R23, D173): the "Default" row and the hint's `= value`. */
    val runtimeDefault: RuntimeDefault?,
    /** Why there is no role default, when the card documents an option without one. */
    val missingDefault: MissingDefault?,
    /** ⚠ reasons (X06): the role default is a lossy YAML type for the documented type. */
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
    /** How the include tasks that run the reference's file give it the name, when some do ([IncludeProvision]). */
    val provision: IncludeProvision? = null,
    /** For a loop card ([Note.Loop]): the type of the loop variable (or of its member) where the loop binds it. */
    val loopItem: OptionSpec? = null,
    /**
     * For a loop card: the reads of the loop variable per file, in file order (the loop's own scope, [LoopUse]), computed
     * only when the full card renders its "Used in" row (never for the Ctrl-hover hint); null inside for `item` and
     * `ansible_loop`, which are never counted across the root (the row keeps its "Show usages" link).
     */
    val loopUses: Lazy<List<LoopUse>?>? = null,
    /**
     * The argument_specs `default:` of [option] where it is not the role default (plan amendment R23, D173): never
     * applied by Ansible, so it is shown only as documentation, flagged by the same findings as the spec's underline.
     */
    val documented: DocumentedDefault? = null,
    /**
     * The role whose tasks see [runtimeDefault] when that is not what [role]'s tasks see (plan amendment R23, D174: the
     * own role's default over another role's spec): the hint names it next to the value, and the "Documented" row says
     * whose documentation it is.
     */
    val defaultRole: String? = null,
) {
    /** `str`, `list[dict]`, or null when nothing documents the type. */
    val typeText: String? get() = (option ?: loopItem)?.let(::typeText)

    /** The card's ⚠ (header and hint): a lossy role default, or a documented default ANS-S003/S004 flags. */
    val warns: Boolean get() = badges.isNotEmpty() || documented?.warns == true

    /** The ⚠ is only the ANS-S004 state: a documented default that nothing applies (no role default to contradict). */
    val warnsNotApplied: Boolean get() = badges.isEmpty() && documented?.state == DocumentedDefault.State.NOT_APPLIED

    /** Options below [option] come from `options`, for dicts and lists of dicts alike. */
    val hasSubOptions: Boolean get() = subOptions.isNotEmpty()

    companion object {
        fun typeText(option: OptionSpec): String {
            val elements = option.elements
            return if (option.type == OptionType.List && elements != null) "list[${elements.name}]" else option.type.name
        }
    }
}

/**
 * The include tasks that run the card's file give the name (their `vars:`, a looping include's loop variable): on
 * [everywhere] include path, and all of them through their loop ([byLoop]). The header then calls it an include or loop
 * variable instead of an inventory variable.
 */
internal class IncludeProvision(val everywhere: Boolean, val byLoop: Boolean)

/** One file of a loop card's "Used in" row: [count] reads of the loop variable in the file named [file]. */
internal class LoopUse(val file: String, val count: Int)

/**
 * The role default a task of the card's role sees (`model.role.RoleDefaults.lookup`: the last value of the defaults
 * files Ansible loads, else that of a `meta/main.yml` dependency), as written.
 */
internal class RuntimeDefault(
    /** Where the winning top-level key is written. */
    val location: SourceLocation,
    /** The index definition at [location] (its doc comment; excluded from "Set in"), when the index knows it. */
    val definition: VarDefinition?,
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
    /** Where a bare `{{ name }}` default leads through the role defaults the same tasks see. */
    val chain: DefaultChain?,
    /** The dependency (`meta/main.yml`) whose defaults set it, when the role itself sets none. */
    val dependency: String? = null,
    /** A defaults file Ansible loads after it cannot be read (a whole-file vault) and may override it. */
    val uncertain: Boolean = false,
    /** `hash_behaviour = merge`: Ansible merges this dictionary with those of the earlier defaults files. */
    val merged: Boolean = false,
)

/** `→ int 65535 via haproxy_settings_maximum_connections (roles/haproxy/defaults/main.yml:12)`. */
internal class DefaultChain(val typeName: String, val valueText: String, val value: YValue, val via: String, val location: SourceLocation, val label: String)

/**
 * Why there is no role default: [DOCUMENTED] only with an ANS-S004 finding for the option (the spec documents a
 * default that nothing applies), [OPAQUE] when a defaults file Ansible loads cannot be read (a whole-file vault may set it).
 */
internal enum class MissingDefault { NONE, DOCUMENTED, REQUIRED, OPAQUE }

/**
 * The argument_specs `default:` of the card's option where it is not the role default (plan amendment R23, D173).
 * [text] is null for secrets (never shown); [location] is the spec's `default:` value.
 */
internal class DocumentedDefault(val state: State, val text: String?, val location: SourceLocation, val label: String) {
    enum class State {
        /** ANS-S003 reports it: it differs from the role default (struck, ⚠). */
        DIFFERS,

        /** ANS-S004 reports it: nothing sets the variable, and Ansible never applies the documented value (⚠). */
        NOT_APPLIED,

        /**
         * Neither finding, but not the role default either: another role's, a templated, merged or possibly overridden
         * role default, or no role default where the role's `vars/` or tasks may set it (grey, no ⚠).
         */
        DOCUMENTED,
    }

    /** The state the spec's underline flags too. */
    val warns: Boolean get() = state != State.DOCUMENTED
}

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

    /**
     * A loop variable (`item`, a `loop_var`, an `index_var`, `ansible_loop`) of the tasks [lines] describe, one line per
     * task: `loop variable of 'Apply rulesets' (include_tasks in roles/fw/tasks/rules.yml:8), iterates a list of 2 items`.
     */
    data class Loop(val lines: List<String>) : Note

    /** Nothing in the root defines, declares or sets the name. */
    data class Undefined(val rootName: String) : Note

    /**
     * Only Molecule files of the root define or set the name, and the card's request does not see them (plan amendment
     * R20, D153: shown outside Molecule while "Show Molecule in navigation and search" is off).
     */
    data class MoleculeOnly(val rootName: String) : Note
}
