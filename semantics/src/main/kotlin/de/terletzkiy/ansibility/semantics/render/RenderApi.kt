package de.terletzkiy.ansibility.semantics.render

import de.terletzkiy.ansibility.semantics.CoreVersion
import de.terletzkiy.ansibility.semantics.yaml.YValue

/** What a name is bound to for one render (plan amendment R11, A.17 "Callbacks"); built by the plugin's binder. */
sealed interface Binding {
    /**
     * A value loaded from a file ([label] says where, for placeholders). Its Jinja-bearing strings are rendered when
     * the name is used, like ansible-core's lazy templating; [unsafe] (`!unsafe`) values are never templated.
     * [json]: the file was read as JSON, so 2.18 gives its strings the plain `str` type.
     */
    class Yaml(val value: YValue, val label: String, val unsafe: Boolean = false, val json: Boolean = false) : Binding

    /** A value the binder computed: loop items, magic variables. Never templated again. */
    class Computed(val value: RValue) : Binding

    /** Nothing defines the name. [proven] false: something may still set it (shown as a placeholder, never as an error). */
    class Undefined(val proven: Boolean, val why: String? = null) : Binding

    /** The name is defined but its value is not provable: a vault value, a registered result, a fact. */
    class Unknown(val placeholder: Placeholder) : Binding
}

/** Resolves the names a template reads (the task's variables, magic variables, loop items). */
fun interface RenderScope {
    fun lookup(name: String): Binding
}

/**
 * The lookups that read files (`fileglob`, `first_found`, `file`, `template`); the pure ones (`dict`, `items`,
 * `vars` …) are the renderer's. Returns the lookup's list result, or null when the lookup is not emulated.
 */
fun interface LookupResolver {
    fun lookup(plugin: String, terms: List<RValue>, kwargs: Map<String, RValue>): RValue?

    companion object {
        val NONE: LookupResolver = LookupResolver { _, _, _ -> null }
    }
}

/** The result of loading a template for `include`/`import`. */
sealed interface Loaded {
    /** [id] identifies the file in segments (the plugin uses the file URL). */
    class Found(val id: String, val source: String) : Loaded

    class Missing(val tried: List<String>) : Loaded

    /** The file exists but is not read (too large, a vault, a sensitive name, outside the root). */
    class Refused(val reason: String) : Loaded
}

/** Loads templates by name, searched from the template [from] (null: the top-level template). */
fun interface TemplateLoader {
    fun load(name: String, from: String?): Loaded

    companion object {
        val NONE: TemplateLoader = TemplateLoader { name, _ -> Loaded.Missing(listOf(name)) }
    }
}

/** Where a template comes from, which decides escapes and the result's type. */
enum class RenderMode {
    /** A template file (`template` module, `lookup('template')`): Jinja's string escapes apply. */
    TEMPLATE_FILE,

    /** A templated YAML value: backslashes in `{{ }}` string literals are kept, the result has a native type. */
    YAML_VALUE,

    /** `when:` and friends: the expression's truth. */
    CONDITION,
}

/** The options of one render; [core] decides the version flags (plan A.17 `RenderSemantics`). */
data class RenderOptions(
    val mode: RenderMode,
    val core: CoreVersion = CoreVersion.PINNED,
    val env: EnvOptions = EnvOptions(),
    val jinja2Native: Boolean = false,
    val maxSteps: Int = 200_000,
    val maxOutput: Int = 1 shl 20,
    val maxLoopItems: Int = 10_000,
    val maxDepth: Int = 8,
) {
    /** 2.19+ native templating: a single expression keeps its type; tuples print as lists. */
    val native: Boolean get() = core >= V2_19

    val tuplesAsLists: Boolean get() = native

    companion object {
        val V2_19 = CoreVersion(2, 19, 0)
    }
}

/** What a stretch of the output is. */
enum class SegmentKind {
    /** Outer text copied from the template. */
    TEXT,

    /** The printed value of a `{{ }}` tag. */
    VALUE,

    /** A placeholder for an unproven value. */
    PLACEHOLDER,

    /** An error ansible-core would raise here (the run would fail). */
    ERROR,

    /** Output of a branch or loop body whose condition or sequence is unknown; [RenderSegment.note] says which. */
    UNKNOWN_BRANCH,
}

/**
 * Maps output `[outStart, outEnd)` to the source `[srcStart, srcEnd)` of [file] (null: the rendered template itself).
 * [UNKNOWN_BRANCH] segments span the whole branch output and overlap the segments inside it.
 */
data class RenderSegment(
    val outStart: Int,
    val outEnd: Int,
    val kind: SegmentKind,
    val file: String?,
    val srcStart: Int,
    val srcEnd: Int,
    val note: String? = null,
)

/** An error or note of a render, at a source range of [file] (null: the rendered template). */
data class RenderProblem(val message: String, val file: String?, val srcStart: Int, val srcEnd: Int, val fatal: Boolean)

/**
 * A render's result: the output [text] (vault-free: holes print as markers), its [value] for YAML values, the
 * source map, and the problems. [complete] is false when the text contains a placeholder, an unknown branch or an
 * error, so it is not the file ansible-core would write.
 */
class Rendered(
    val text: String,
    val value: RValue?,
    val segments: List<RenderSegment>,
    val problems: List<RenderProblem>,
    val placeholders: Int,
    val unknownBranches: Int,
) {
    val errors: List<RenderProblem> get() = problems.filter { it.fatal }

    val complete: Boolean get() = placeholders == 0 && unknownBranches == 0 && errors.isEmpty()
}
