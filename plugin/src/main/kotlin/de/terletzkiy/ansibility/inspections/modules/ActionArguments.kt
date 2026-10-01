package de.terletzkiy.ansibility.inspections.modules

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.schema.ModuleDoc
import de.terletzkiy.ansibility.semantics.schema.OptionSpec
import de.terletzkiy.ansibility.semantics.schema.OptionType
import de.terletzkiy.ansibility.semantics.schema.SpecOrigin
import de.terletzkiy.ansibility.semantics.yaml.ScalarStyle
import de.terletzkiy.ansibility.semantics.yaml.YScalar

/** How ansible-core treats arguments it does not know for a module (ANS-M001). */
enum class UnknownArguments {
    /** Rejected; the documented options are what is accepted, plus [ActionArguments.extraAccepted]. */
    REJECTED,

    /** Never checked: the action plugin reads the arguments it knows and ignores the rest (`fetch`, `gather_facts`). */
    IGNORED,

    /** Passed on to another module chosen at runtime (`package`, `service`): only that module decides. */
    PASSED_ON,
}

/** Who rejects an unknown argument, which decides ansible-core's error text. */
enum class ArgumentErrors {
    /** The module's `AnsibleModule`: `Unsupported parameters for (ansible.builtin.file) module: x`. */
    MODULE,

    /** An action plugin's `_VALID_ARGS`, or the playbook loader for includes: `Invalid options for <action>: x`. */
    VALID_ARGS,

    /** An action plugin's own `validate_argument_spec`: `Unsupported parameters for (ansible_collections…action.debug) module: x`. */
    ACTION_SPEC,
}

/**
 * How ansible-core 2.18.8 and 2.21.4 handle one module's arguments where it differs from the plain rule "the
 * documented options, validated by the module's argument spec" (read in the action plugins and in the playbook
 * loader's `role_include.py` and `task_include.py` of both versions, and checked by running them).
 *
 * @property unknown what happens to undocumented arguments
 * @property extraAccepted undocumented arguments ansible-core accepts (`include_role`'s `role`, the copy options that
 *   `template` hands to the copy action)
 * @property errors who reports an unknown argument, up to [errorsChange]
 * @property errorsChange the version from which another [ArgumentErrors] style applies (`assert` uses its own argument
 *   spec in 2.21); between 2.19 and that version the wording is not known and messages leave it out
 * @property checksRequired whether a missing documented required option fails (the backends of `package`/`service`
 *   have their own rules)
 * @property checkAllValues false when the action plugin reads every argument itself (`assert`, `fetch`): no value
 *   is checked against the documentation
 * @property uncheckedOptions options the action reads itself with `boolean(strict=False)` or `int()`, whose
 *   documented type says nothing about what is rejected
 * @property runtimeSpec the argument spec an action plugin validates with instead of its documentation (`debug`
 *   validates `msg` as `raw`, the docs say `str`)
 * @property skipValuesWhen true for argument sets with which the module may not run at all (`copy` with a false
 *   `force` skips an existing destination, `unarchive` with `creates`), so no value is certainly rejected; it gets
 *   each provided option's scalar value (null for other values)
 * @property requiredAliases undocumented names that satisfy a required option (`include_role` accepts `role` for
 *   `name`)
 * @property missingMessage ansible-core's error when a required option is missing, for action plugins and includes
 *   that check it themselves (`template`: "src and dest are required"); `%s` stands for the action as written. Null
 *   for modules, which fail with `AnsibleModule`'s "missing required arguments: x"
 * @property unknownMessage ansible-core's error for an unknown argument when it follows none of the [ArgumentErrors]
 *   styles (`include_vars`); `%s` stands for the argument
 * @property executedModule the module an action plugin runs with the task's arguments, which the module-style error
 *   names (`template` runs `ansible.legacy.copy`)
 */
data class ActionArguments(
    val unknown: UnknownArguments = UnknownArguments.REJECTED,
    val extraAccepted: Set<String> = emptySet(),
    val errors: ArgumentErrors = ArgumentErrors.MODULE,
    val errorsChange: Pair<CoreVersion, ArgumentErrors>? = null,
    val checksRequired: Boolean = true,
    val checkAllValues: Boolean = true,
    val uncheckedOptions: Set<String> = emptySet(),
    val runtimeSpec: Map<String, OptionSpec>? = null,
    val skipValuesWhen: (Map<String, YScalar?>) -> Boolean = { false },
    val requiredAliases: Map<String, Set<String>> = emptyMap(),
    val missingMessage: String? = null,
    val unknownMessage: String? = null,
    val executedModule: String? = null,
) {
    /** The options whose values are checked against [doc] (or [runtimeSpec]); null when none are. */
    fun valueSpec(doc: ModuleDoc, pseudo: Set<String>): Map<String, OptionSpec>? {
        if (!checkAllValues) return null
        val base = runtimeSpec ?: doc.options
        return base.filterKeys { it !in pseudo && it !in uncheckedOptions }
    }

    /**
     * ansible-core's error for the unknown argument [key] of module [written] (as the task names it, [canonical]
     * after routing) under [version], or null when that version's wording is not known.
     */
    fun unknownError(version: CoreVersion, written: String, canonical: String, key: String): String? {
        unknownMessage?.let { return it.replace("%s", key) }
        val change = errorsChange
        val style = when {
            change == null -> errors
            version >= change.first -> change.second
            version >= V2_19 -> return null
            else -> errors
        }
        return when (style) {
            ArgumentErrors.MODULE -> AnsibilityModuleChecksBundle.message("m001.runtime.module", executedModule ?: written, key)
            ArgumentErrors.VALID_ARGS -> AnsibilityModuleChecksBundle.message("m001.runtime.action", written, key)
            ArgumentErrors.ACTION_SPEC -> AnsibilityModuleChecksBundle.message(
                "m001.runtime.module",
                "ansible_collections.ansible.builtin.plugins.action.${canonical.substringAfterLast('.')}",
                key,
            )
        }
    }

    /** ansible-core's error for the missing required [option] of module [written], or null when it has no fixed text. */
    fun missingError(written: String, option: String): String? = when {
        missingMessage != null -> missingMessage.replace("%s", written)
        errors == ArgumentErrors.MODULE && executedModule == null -> AnsibilityModuleChecksBundle.message("m002.runtime.module", option)
        else -> null
    }

    companion object {
        /** The plain rule: documented options, rejected otherwise, all values checked. */
        val DEFAULT = ActionArguments()

        private val V2_19 = CoreVersion(2, 19, 0)
        private val V2_21 = CoreVersion(2, 21, 0)
        private const val BUILTIN = "ansible.builtin."
        private const val SRC_AND_DEST = "src and dest are required"

        /** Arguments the copy action takes and the template action hands on to it. */
        private val COPY_ONLY = setOf("checksum", "content", "decrypt", "directory_mode", "local_follow", "remote_src")

        /** A value that is certainly not false for `boolean(strict=False)` is literally one of the true spellings. */
        private fun isLiterallyTrue(value: YScalar?): Boolean =
            value != null && value.text.trim().lowercase() in setOf("yes", "on", "1", "true", "y", "t")

        /** `copy` and `template` skip an existing destination when `force` is false, without running a module. */
        private val FORCE_NOT_TRUE: (Map<String, YScalar?>) -> Boolean = { args -> "force" in args && !isLiterallyTrue(args["force"]) }

        /** `debug`'s own `validate_argument_spec` (identical in 2.18.8 and 2.21.4). */
        private val DEBUG_SPEC: Map<String, OptionSpec> = listOf(
            OptionSpec("msg", OptionType.Raw, default = YScalar("Hello world!", ScalarStyle.DOUBLE_QUOTED)),
            OptionSpec("var", OptionType.Raw),
            OptionSpec("verbosity", OptionType.Int, default = YScalar("0", ScalarStyle.PLAIN)),
        ).associate { it.name to it.copy(origin = SpecOrigin.ModuleDoc("${BUILTIN}debug", "runtime")) }

        /** An action plugin that reads its arguments itself after checking `_VALID_ARGS`. */
        private val VALID_ARGS_ONLY = ActionArguments(errors = ArgumentErrors.VALID_ARGS, checkAllValues = false)

        /** `IncludeRole.VALID_ARGS` accepts `role` for `name` (`'name' is a required field for include_role.`). */
        private val ROLE_INCLUDE = ActionArguments(
            extraAccepted = setOf("role"),
            errors = ArgumentErrors.VALID_ARGS,
            requiredAliases = mapOf("name" to setOf("role")),
            missingMessage = "'name' is a required field for %s.",
        )

        /** `TaskInclude.VALID_ARGS` (`file`, `_raw_params`, `apply`). */
        private val TASK_INCLUDE = ActionArguments(extraAccepted = setOf("_raw_params"), errors = ArgumentErrors.VALID_ARGS)

        private val TABLE: Map<String, ActionArguments> = mapOf(
            // Action plugins that never run a module and read their arguments themselves.
            "assert" to VALID_ARGS_ONLY.copy(
                errorsChange = V2_21 to ArgumentErrors.ACTION_SPEC,
                missingMessage = "conditional required in \"that\" string",
            ),
            "fail" to VALID_ARGS_ONLY,
            "group_by" to VALID_ARGS_ONLY.copy(missingMessage = "the 'key' param is required when using group_by"),
            "set_stats" to VALID_ARGS_ONLY,
            "wait_for_connection" to VALID_ARGS_ONLY, // int() on its own
            "reboot" to VALID_ARGS_ONLY, // int() on its own
            "add_host" to ActionArguments(checkAllValues = false, missingMessage = "name, host or hostname needs to be provided"),
            "include_vars" to ActionArguments(
                extraAccepted = setOf("_raw_params"),
                checkAllValues = false,
                unknownMessage = "%s is not a valid option in include_vars",
            ),
            "validate_argument_spec" to ActionArguments(
                unknown = UnknownArguments.IGNORED,
                checkAllValues = false,
                missingMessage = "\"argument_spec\" arg is required in args",
            ),
            "fetch" to ActionArguments(unknown = UnknownArguments.IGNORED, checkAllValues = false, missingMessage = SRC_AND_DEST),
            "gather_facts" to ActionArguments(unknown = UnknownArguments.IGNORED, checkAllValues = false),
            "debug" to ActionArguments(errors = ArgumentErrors.ACTION_SPEC, runtimeSpec = DEBUG_SPEC),
            "pause" to ActionArguments(errors = ArgumentErrors.ACTION_SPEC, checkAllValues = false), // int() callables
            "async_status" to ActionArguments(errors = ArgumentErrors.ACTION_SPEC),
            // Backends chosen at runtime decide about arguments and requirements.
            "package" to ActionArguments(unknown = UnknownArguments.PASSED_ON, checksRequired = false, checkAllValues = false),
            "service" to ActionArguments(unknown = UnknownArguments.PASSED_ON, checksRequired = false, checkAllValues = false),
            // Action plugins that preprocess some arguments and then run a module with the rest.
            "copy" to ActionArguments(
                uncheckedOptions = setOf("decrypt", "force", "raw", "remote_src", "local_follow", "follow"),
                skipValuesWhen = FORCE_NOT_TRUE,
                missingMessage = "dest is required",
                executedModule = "ansible.legacy.copy",
            ),
            "template" to ActionArguments(
                extraAccepted = COPY_ONLY,
                uncheckedOptions = setOf("follow", "trim_blocks", "lstrip_blocks", "force", "decrypt", "remote_src", "local_follow"),
                skipValuesWhen = FORCE_NOT_TRUE,
                missingMessage = SRC_AND_DEST,
                executedModule = "ansible.legacy.copy",
            ),
            "assemble" to ActionArguments(
                uncheckedOptions = setOf("remote_src", "decrypt", "follow", "ignore_hidden"),
                missingMessage = SRC_AND_DEST,
                executedModule = "ansible.legacy.assemble",
            ),
            "unarchive" to ActionArguments(
                uncheckedOptions = setOf("copy", "remote_src", "decrypt"),
                skipValuesWhen = { args -> "creates" in args },
                missingMessage = "src (or content) and dest are required",
                executedModule = "ansible.legacy.unarchive",
            ),
            "uri" to ActionArguments(uncheckedOptions = setOf("remote_src"), executedModule = "ansible.legacy.uri"),
            // Includes, validated by the playbook loader (TaskInclude.VALID_ARGS, IncludeRole.VALID_ARGS).
            "include_tasks" to TASK_INCLUDE,
            "import_tasks" to TASK_INCLUDE,
            "include_role" to ROLE_INCLUDE,
            "import_role" to ROLE_INCLUDE,
        ).mapKeys { BUILTIN + it.key }

        /** The rules for canonical module [fqcn]; [DEFAULT] for modules without action-plugin quirks. */
        fun of(fqcn: String): ActionArguments = TABLE[fqcn] ?: DEFAULT
    }
}
