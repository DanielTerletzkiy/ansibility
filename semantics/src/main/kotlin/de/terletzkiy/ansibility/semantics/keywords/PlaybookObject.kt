package de.terletzkiy.ansibility.semantics.keywords

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.coerce.CoreSemantics

/**
 * The playbook objects ansible-core loads keywords into (the classes of its `playbook` package), named as the keyword docs'
 * `applies_to` ([docName]) and as ansible-core's error messages name them ([className]).
 */
enum class PlaybookObject(val docName: String, val className: String) {
    PLAY("Play", "Play"),

    /** An entry of a play's `roles:` list (`RoleInclude`); keys that are not keywords are role parameters. */
    ROLE("Role", "RoleInclude"),
    BLOCK("Block", "Block"),
    TASK("Task", "Task"),
    HANDLER("Handler", "Handler"),

    /** An `import_playbook` entry of a playbook. */
    PLAYBOOK_INCLUDE("PlaybookInclude", "PlaybookInclude"),

    /** A task's `loop_control:` mapping. */
    LOOP_CONTROL("LoopControl", "LoopControl"),
    ;

    /** Tasks and handlers: the objects `TaskExecutor` post-validates before running them. */
    val isTask: Boolean get() = this == TASK || this == HANDLER
}

/**
 * The version-dependent parts of keyword validation, from the sources of 2.18.8 and 2.21.4 (the two versions the
 * oracle tables record). Behaviour that changed with the 2.19 templating rewrite is switched at 2.19.0; where the
 * two recorded versions differ and the change cannot be dated (variable-name rules, the `register` mapping form),
 * versions between them are treated conservatively: only what both versions reject is reported.
 */
internal class KeywordSemantics(val version: CoreVersion) {
    /** 2.19+ words post-validation errors as `Error processing keyword 'x': The value … could not be converted to 'int'.`. */
    val newWording: Boolean get() = version >= V2_19

    /** 2.19+ post-validates `import_playbook` entries (`_post_validate_object = True`); 2.18 ignores most of their keywords. */
    val postValidatesPlaybookImports: Boolean get() = version >= V2_19

    /** 2.19+ catches every exception of a keyword conversion; 2.18 lets `OverflowError` escape as a traceback. */
    val wrapsConversionCrashes: Boolean get() = version >= V2_19

    /** ≤ 2.18 rejects Python keywords as variable names (`isidentifier`). */
    val rejectsPythonKeywords: Boolean get() = version < V2_19

    /** 2.21 rejects Jinja's reserved names (`true`, `none`, `not` …) as variable names (`validate_variable_name`). */
    val rejectsJinjaKeywords: Boolean get() = version >= V2_21

    /** 2.21 validates `loop_control.loop_var`/`index_var` as variable names. */
    val validatesLoopVariableNames: Boolean get() = version >= V2_21

    /** 2.19+ may accept a mapping for `register` (register projections); only ≤ 2.18 is known to reject it. */
    val rejectsRegisterMapping: Boolean get() = version < V2_19

    /** 2.18 requires `vars` of an `import_playbook` entry to be a mapping (`None` included). */
    val importVarsMustBeMapping: Boolean get() = version < V2_19

    companion object {
        private val V2_19 = CoreVersion(2, 19, 0)
        private val V2_21 = CoreVersion(2, 21, 0)

        fun of(semantics: CoreSemantics): KeywordSemantics = KeywordSemantics(semantics.version)
    }
}
