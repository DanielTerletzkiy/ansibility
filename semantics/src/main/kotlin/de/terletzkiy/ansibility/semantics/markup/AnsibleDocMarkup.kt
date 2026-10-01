package de.terletzkiy.ansibility.semantics.markup

/** A plugin reference inside `O()`/`RV()` (`O(community.docker.docker_container#module:mounts)`) or `P()`. */
data class PluginRef(val fqcn: String, val type: String)

/**
 * One piece of a parsed Ansible documentation string. Paragraph text is a flat list of parts
 * (the markup has no nesting: `B(C(x))` is bold text reading `C(x)`).
 */
sealed interface MarkupPart {
    /** Plain text, exactly as written. */
    data class Text(val text: String) : MarkupPart

    /** `C(text)`: code/constant. */
    data class Code(val text: String) : MarkupPart

    /** `I(text)`: italic. */
    data class Italic(val text: String) : MarkupPart

    /** `B(text)`: bold. */
    data class Bold(val text: String) : MarkupPart

    /** `U(url)`: a bare URL. */
    data class Url(val url: String) : MarkupPart

    /** `L(text, url)`: a link with a label. */
    data class Link(val text: String, val url: String) : MarkupPart

    /** `M(fqcn)`: a module reference. */
    data class Module(val fqcn: String) : MarkupPart

    /** `P(fqcn#type)`: a plugin reference, e.g. `P(ansible.builtin.file#lookup)`. */
    data class Plugin(val fqcn: String, val type: String) : MarkupPart

    /**
     * `O(name)`, `O(name=value)`, `O(a.b)`, `O(a[].b)`, `O(fqcn#type:name)`, `O(ignore:name)`.
     *
     * [name] is the option path as written (without the value), [link] its parts with array stubs removed
     * (`a[].b` → `[a, b]`), [plugin] the explicitly referenced plugin (null means the current one),
     * [entryPoint] the role entry point for role references, and [ignore] marks `ignore:` (never link it).
     */
    data class Option(
        val name: String,
        val link: List<String>,
        val value: String? = null,
        val plugin: PluginRef? = null,
        val entryPoint: String? = null,
        val ignore: Boolean = false,
    ) : MarkupPart

    /** `V(value)`: an option value. */
    data class Value(val text: String) : MarkupPart

    /** `E(NAME)`: an environment variable. */
    data class EnvVar(val name: String) : MarkupPart

    /** `RV(name)`, with the same syntax and fields as [Option]. */
    data class ReturnValue(
        val name: String,
        val link: List<String>,
        val value: String? = null,
        val plugin: PluginRef? = null,
        val entryPoint: String? = null,
        val ignore: Boolean = false,
    ) : MarkupPart

    /** `R(text, ref)` (and RST `:ref:`): a Sphinx reference; there is no URL for it outside the docsite. */
    data class Ref(val text: String, val ref: String) : MarkupPart

    /** `HORIZONTALLINE`. */
    data object HorizontalLine : MarkupPart
}

/**
 * Parser for Ansible's documentation markup, following the grammar of antsibull-docs-parser (what the docsite uses)
 * and the regexes of `ansible/cli/doc.py`:
 * - `C()`, `I()`, `B()`, `U()`, `M()`, `P()` take one argument and `L()`, `R()` two, all without escapes
 *   (the argument ends at the first `)`; `L`/`R` split at a `,` before it, ignoring spaces around it);
 * - `O()`, `V()`, `E()`, `RV()` take one argument in which `\)` and `\\` (any `\x`) are escapes;
 * - `HORIZONTALLINE` takes none;
 * - a macro name only counts at a word boundary and directly followed by `(`.
 *
 * Invalid macros (no closing parenthesis, a `P()` without `#type`) are kept as plain text rather than failing,
 * so any description can be rendered. [parseRst] additionally handles the RST found in keyword descriptions.
 */
object AnsibleDocMarkup {
    private val COMMAND = Regex("(?U)\\b(?:(HORIZONTALLINE)\\b|(RV|[CIBULRMPOVE])\\()")
    private val FQCN_TYPE_PREFIX = Regex("^([^.]+\\.[^.]+\\.[^#]+)#([a-z]+):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val ARRAY_STUB = Regex("\\[[^\\]]*]")
    private val PLUGIN_TYPE = Regex("^[a-z]+$")
    private const val IGNORE_MARKER = "ignore:"

    private val RST = Regex("(?U)``(.+?)``|:([A-Za-z][\\w+-]*):`([^`]+)`|\\.\\. ([A-Za-z][\\w-]*)::")
    private val RST_TITLED_TARGET = Regex("^(.*?)\\s*<([^<>]+)>$", RegexOption.DOT_MATCHES_ALL)

    /** Parses one paragraph of Ansible markup. */
    fun parse(text: String): List<MarkupPart> {
        val parts = PartList()
        var index = 0
        while (index < text.length) {
            val match = COMMAND.find(text, index)
            if (match == null) {
                parts.text(text.substring(index))
                break
            }
            parts.text(text.substring(index, match.range.first))
            if (match.groups[1] != null) {
                parts += MarkupPart.HorizontalLine
                index = match.range.last + 1
                continue
            }
            val command = match.groupValues[2]
            val argsStart = match.range.last + 1
            val parsed = parseCommand(command, text, argsStart)
            if (parsed == null) {
                // Not a valid macro: keep "X(" as text and continue scanning after it.
                parts.text(text.substring(match.range.first, argsStart))
                index = argsStart
            } else {
                parts += parsed.first
                index = parsed.second
            }
        }
        return parts.toList()
    }

    /** Parses each paragraph of a description. */
    fun parseParagraphs(paragraphs: List<String>): List<List<MarkupPart>> = paragraphs.map(::parse)

    /**
     * Parses a keyword description (`keyword_desc.yml`), which mixes RST with Ansible markup:
     * ``` ``literal`` ``` becomes [MarkupPart.Code]; `:ref:`label <target>`` and `:doc:` become [MarkupPart.Ref];
     * `:term:`Task`` becomes plain text; other roles become code; `.. note::`, `.. warning::` and `.. seealso::`
     * become bold labels and other directives are dropped. Text between the RST constructs is parsed with [parse].
     */
    fun parseRst(text: String): List<MarkupPart> {
        val parts = PartList()
        var index = 0
        for (match in RST.findAll(text)) {
            parts.addAll(parse(text.substring(index, match.range.first)))
            index = match.range.last + 1
            val literal = match.groups[1]
            val role = match.groups[2]
            val directive = match.groups[4]
            when {
                literal != null -> parts += MarkupPart.Code(literal.value)
                role != null -> parts += rstRole(role.value, match.groupValues[3])
                directive != null -> when (directive.value) {
                    "note" -> parts += MarkupPart.Bold("Note:")
                    "warning" -> parts += MarkupPart.Bold("Warning:")
                    "seealso" -> parts += MarkupPart.Bold("See also:")
                    else -> Unit
                }
            }
        }
        parts.addAll(parse(text.substring(index)))
        return parts.toList()
    }

    /**
     * A plain-text rendering (for completion tails, tooltips and tests): macros are replaced by their visible text,
     * `O(a=b)` reads `a=b`, links read as their label and a horizontal line as a line break.
     */
    fun plainText(parts: List<MarkupPart>): String = buildString {
        for (part in parts) {
            append(
                when (part) {
                    is MarkupPart.Text -> part.text
                    is MarkupPart.Code -> part.text
                    is MarkupPart.Italic -> part.text
                    is MarkupPart.Bold -> part.text
                    is MarkupPart.Url -> part.url
                    is MarkupPart.Link -> part.text
                    is MarkupPart.Module -> part.fqcn
                    is MarkupPart.Plugin -> part.fqcn
                    is MarkupPart.Option -> if (part.value != null) "${part.name}=${part.value}" else part.name
                    is MarkupPart.Value -> part.text
                    is MarkupPart.EnvVar -> part.name
                    is MarkupPart.ReturnValue -> if (part.value != null) "${part.name}=${part.value}" else part.name
                    is MarkupPart.Ref -> part.text
                    MarkupPart.HorizontalLine -> "\n"
                },
            )
        }
    }

    /** Plain text of one markup paragraph. */
    fun plainText(text: String): String = plainText(parse(text))

    // -------------------------------------------------------------------------------------------- commands

    /** Returns the part and the index after the closing parenthesis, or null when the macro is invalid. */
    private fun parseCommand(command: String, text: String, start: Int): Pair<MarkupPart, Int>? = when (command) {
        "O", "V", "E", "RV" -> {
            val (arg, end) = escapedArgument(text, start) ?: return null
            when (command) {
                "V" -> MarkupPart.Value(arg) to end
                "E" -> MarkupPart.EnvVar(arg) to end
                "O" -> optionLike(arg).let { MarkupPart.Option(it.name, it.link, it.value, it.plugin, it.entryPoint, it.ignore) } to end
                else -> optionLike(arg).let { MarkupPart.ReturnValue(it.name, it.link, it.value, it.plugin, it.entryPoint, it.ignore) } to end
            }
        }
        "L", "R" -> {
            val (args, end) = unescapedArguments(text, start, 2) ?: return null
            if (command == "L") MarkupPart.Link(args[0], args[1]) to end else MarkupPart.Ref(args[0], args[1]) to end
        }
        else -> {
            val (args, end) = unescapedArguments(text, start, 1) ?: return null
            val arg = args[0]
            when (command) {
                "C" -> MarkupPart.Code(arg) to end
                "I" -> MarkupPart.Italic(arg) to end
                "B" -> MarkupPart.Bold(arg) to end
                "U" -> MarkupPart.Url(arg) to end
                "M" -> MarkupPart.Module(arg) to end
                "P" -> plugin(arg)?.let { it to end }
                else -> null
            }
        }
    }

    /** Arguments without escapes: all but the last end at `,` (spaces around it are dropped), the last at `)`. */
    private fun unescapedArguments(text: String, start: Int, count: Int): Pair<List<String>, Int>? {
        val result = ArrayList<String>(count)
        var index = start
        repeat(count - 1) {
            val comma = text.indexOf(',', index)
            if (comma < 0) return null
            val close = text.indexOf(')', index)
            if (close in 0 until comma) return null
            result += if (result.isEmpty()) text.substring(index, comma).trimEnd(' ') else text.substring(index, comma).trim(' ')
            index = comma + 1
            while (index < text.length && text[index] == ' ') index++
        }
        val close = text.indexOf(')', index)
        if (close < 0) return null
        result += text.substring(index, close)
        return result to close + 1
    }

    /** One argument in which a backslash escapes the next character; it ends at the first unescaped `)`. */
    private fun escapedArgument(text: String, start: Int): Pair<String, Int>? {
        val value = StringBuilder()
        var index = start
        while (index < text.length) {
            val c = text[index]
            when {
                c == '\\' && index + 1 < text.length -> {
                    value.append(text[index + 1])
                    index += 2
                }
                c == ')' -> return value.toString() to index + 1
                else -> {
                    value.append(c)
                    index++
                }
            }
        }
        return null
    }

    private fun plugin(arg: String): MarkupPart.Plugin? {
        val hash = arg.indexOf('#')
        if (hash <= 0) return null
        val type = arg.substring(hash + 1)
        if (!PLUGIN_TYPE.matches(type)) return null
        return MarkupPart.Plugin(arg.substring(0, hash), type)
    }

    private class OptionLike(
        val name: String,
        val link: List<String>,
        val value: String?,
        val plugin: PluginRef?,
        val entryPoint: String?,
        val ignore: Boolean,
    )

    /** The `O()`/`RV()` argument grammar (`ansible/cli/doc.py` `_tty_ify_sem_complex`, antsibull `_parse_option_like`). */
    private fun optionLike(arg: String): OptionLike {
        var text = arg
        var value: String? = null
        val eq = text.indexOf('=')
        if (eq >= 0) {
            value = text.substring(eq + 1)
            text = text.substring(0, eq)
        }
        var plugin: PluginRef? = null
        var ignore = false
        val prefixed = FQCN_TYPE_PREFIX.matchEntire(text)
        if (prefixed != null) {
            plugin = PluginRef(prefixed.groupValues[1], prefixed.groupValues[2])
            text = prefixed.groupValues[3]
        } else if (text.startsWith(IGNORE_MARKER)) {
            ignore = true
            text = text.substring(IGNORE_MARKER.length)
        }
        var entryPoint: String? = null
        val colon = text.indexOf(':')
        if (colon >= 0) {
            entryPoint = text.substring(0, colon)
            text = text.substring(colon + 1)
        }
        val link = text.split('.').map { ARRAY_STUB.replace(it, "") }
        return OptionLike(text, link, value, plugin, entryPoint, ignore)
    }

    private fun rstRole(role: String, content: String): MarkupPart {
        val titled = RST_TITLED_TARGET.matchEntire(content)
        val title = titled?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val target = titled?.groupValues?.get(2) ?: content
        return when (role) {
            "ref", "doc" -> MarkupPart.Ref(title ?: target, target)
            "term" -> MarkupPart.Text(title ?: content)
            else -> MarkupPart.Code(title ?: content)
        }
    }

    /** Collects parts and merges adjacent text. */
    private class PartList {
        private val parts = ArrayList<MarkupPart>()
        private val pending = StringBuilder()

        fun text(text: String) {
            pending.append(text)
        }

        operator fun plusAssign(part: MarkupPart) {
            if (part is MarkupPart.Text) {
                pending.append(part.text)
                return
            }
            flush()
            parts += part
        }

        fun addAll(more: List<MarkupPart>) = more.forEach { this += it }

        fun toList(): List<MarkupPart> {
            flush()
            return parts.toList()
        }

        private fun flush() {
            if (pending.isNotEmpty()) {
                parts += MarkupPart.Text(pending.toString())
                pending.setLength(0)
            }
        }
    }
}
