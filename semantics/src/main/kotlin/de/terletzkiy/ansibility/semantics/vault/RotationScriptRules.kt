package de.terletzkiy.ansibility.semantics.vault

/**
 * What [RotationScriptRules.parse] read from a rotation shell script (plan amendment R29, X131): the rules of the arms
 * it understood, in the script's order, and the patterns of the arms it did not.
 */
class ScriptImport(val rules: List<GenerationRule>, val skipped: List<String>) {
    override fun toString(): String = "ScriptImport(${rules.size} rules, skipped=$skipped)"
}

/**
 * Reads the generation rules of a secret-rotation shell script (plan amendment R29, X131) without running anything:
 * the arms of its `case "$key" in … esac` blocks, where each arm's pattern (`a|b)`, `*)` for the default) names keys
 * and its body makes the value.
 *
 * Bodies it understands (the first one found in the arm):
 * - `openssl rand -hex N` → hex of 2N characters;
 * - `openssl rand -base64 N` → base64 of N bytes;
 * - a call of a function of the script whose body keeps alphanumeric characters (`tr -dc 'A-Za-z0-9'` or
 *   `[:alnum:]`) with an argument N → N alphanumeric; an argument `"$PASSWORD_LENGTH"` (any variable set as
 *   `NAME="${NAME:-N}"` or `NAME=N`), or no argument, takes the function's own default (`${1:-…}`) or the script's
 *   `PASSWORD_LENGTH` default (32 when the script sets none);
 * - the same `tr -dc` pipeline inline with `head -c N`.
 *
 * Arms it does not understand are returned as skipped, with their pattern. Only innermost `case` blocks are read, and
 * only those with at least one understood arm (a command dispatcher such as `case "$1" in generate) … esac` is not a
 * rule table). Comments are ignored. Pure text work: nothing is executed, nothing is looked up.
 */
object RotationScriptRules {
    /** The default length of the alphanumeric generator when the script names none (the rotation script's own default). */
    const val DEFAULT_LENGTH: Int = 32

    private const val LENGTH_VARIABLE = "PASSWORD_LENGTH"

    /** `case WORD in` at a command position (line start, after `;`, `&&`, `||`, `{`, `(`, `do`, `then`, `else`). */
    private val CASE = Regex("""(?m)(?:^|[;&|{(]|\bdo\b|\bthen\b|\belse\b)[ \t]*(case)[ \t]+\S+[ \t]+in\b""")

    /** `esac` as a word of its own. */
    private val ESAC = Regex("""(?m)(?<=^|[\s;])esac(?=[\s;)]|$)""")
    private val HEX = Regex("""openssl\s+rand\s+-hex\s+(\d+)""")
    private val BASE64 = Regex("""openssl\s+rand\s+-base64\s+(\d+)""")
    private val ALNUM_FILTER = Regex("""tr\s+-dc\s+(?:'(?:A-Za-z0-9|a-zA-Z0-9|\[:alnum:])'|"(?:A-Za-z0-9|a-zA-Z0-9|\[:alnum:])"|A-Za-z0-9|\[:alnum:])""")
    private val INLINE_HEAD = Regex("""head\s+-c\s*"?(\d+|\$\{?\w+}?)"?""")
    private val FUNCTION = Regex("""(?m)^[ \t]*(?:function[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)[ \t]*\(\)[ \t]*\{""")
    private val DEFAULTED = Regex("""(?m)^[ \t]*(?:export[ \t]+|readonly[ \t]+|local[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)="?\$\{\1:-(\d+)}"?""")
    private val ASSIGNED = Regex("""(?m)^[ \t]*(?:export[ \t]+|readonly[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)="?(\d+)"?[ \t]*(?:#.*)?$""")
    private val FIRST_ARGUMENT_DEFAULT = Regex("""\$\{1:-("?)(\d+|\$\{?\w+}?)\1}""")

    /** Reads [script] (the text of a shell script). */
    fun parse(script: String): ScriptImport {
        val text = stripComments(script)
        val variables = variables(text)
        val defaultLength = variables[LENGTH_VARIABLE] ?: DEFAULT_LENGTH
        val functions = alphanumericFunctions(text, variables, defaultLength)
        val rules = ArrayList<GenerationRule>()
        val skipped = ArrayList<String>()
        for (block in innermostCaseBodies(text)) {
            val arms = arms(block)
            val understood = arms.map { (patterns, body) -> patterns to generatorOf(body, functions, variables, defaultLength) }
            if (understood.none { it.second != null }) continue
            for ((patterns, generator) in understood) {
                if (generator == null) {
                    skipped += patterns.joinToString("|")
                    continue
                }
                for (pattern in patterns) rules += GenerationRule(pattern, generator.kind, generator.length)
            }
        }
        return ScriptImport(rules, skipped)
    }

    /** The generator an arm's [body] calls, or null when it is none of the understood forms. */
    private fun generatorOf(body: String, functions: Map<String, Int>, variables: Map<String, Int>, defaultLength: Int): SecretGenerator? {
        HEX.find(body)?.let { return SecretGenerator(GeneratorKind.HEX, (it.groupValues[1].toInt() * 2).coerceIn(SecretGenerator.lengths(GeneratorKind.HEX))) }
        BASE64.find(body)?.let { return SecretGenerator(GeneratorKind.BASE64, it.groupValues[1].toInt().coerceIn(SecretGenerator.lengths(GeneratorKind.BASE64))) }
        for ((name, ownDefault) in functions) {
            val call = Regex("""(?<![\w-])${Regex.escape(name)}(?![\w-])(?:[ \t]+("?)(\d+|\$\{?\w+}?)\1)?""").find(body) ?: continue
            val argument = call.groups[2]?.value
            val length = when {
                argument == null -> ownDefault
                argument.all(Char::isDigit) -> argument.toInt()
                else -> variables[variableName(argument)] ?: defaultLength
            }
            return SecretGenerator(GeneratorKind.ALPHANUMERIC, length.coerceIn(SecretGenerator.lengths(GeneratorKind.ALPHANUMERIC)))
        }
        if (ALNUM_FILTER.containsMatchIn(body)) {
            val head = INLINE_HEAD.find(body)?.groupValues?.get(1) ?: return null
            val length = if (head.all(Char::isDigit)) head.toInt() else variables[variableName(head)] ?: defaultLength
            return SecretGenerator(GeneratorKind.ALPHANUMERIC, length.coerceIn(SecretGenerator.lengths(GeneratorKind.ALPHANUMERIC)))
        }
        return null
    }

    /** The functions of [text] that make alphanumeric values, with the length each uses without an argument. */
    private fun alphanumericFunctions(text: String, variables: Map<String, Int>, defaultLength: Int): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (match in FUNCTION.findAll(text)) {
            val body = braceBody(text, match.range.last) ?: continue
            if (!ALNUM_FILTER.containsMatchIn(body)) continue
            val default = FIRST_ARGUMENT_DEFAULT.find(body)?.groupValues?.get(2)?.let { value ->
                if (value.all(Char::isDigit)) value.toInt() else variables[variableName(value)]
            }
            result[match.groupValues[1]] = default ?: defaultLength
        }
        return result
    }

    /** The text between the `{` at [open] and its matching `}`, or null when it is not closed. */
    private fun braceBody(text: String, open: Int): String? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        return null
    }

    /** Numeric variables: `NAME="${NAME:-N}"` gives N, `NAME=N` gives N (the first assignment wins). */
    private fun variables(text: String): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (match in DEFAULTED.findAll(text)) result.putIfAbsent(match.groupValues[1], match.groupValues[2].toInt())
        for (match in ASSIGNED.findAll(text)) result.putIfAbsent(match.groupValues[1], match.groupValues[2].toInt())
        return result
    }

    /** `PASSWORD_LENGTH` from `$PASSWORD_LENGTH` or `${PASSWORD_LENGTH}`. */
    private fun variableName(reference: String): String = reference.removePrefix("$").removePrefix("{").removeSuffix("}")

    /** The bodies (between `in` and `esac`) of the `case` blocks that contain no other `case` block. */
    private fun innermostCaseBodies(text: String): List<String> {
        // (offset, is case, end of the case head)
        val marks = CASE.findAll(text).map { Triple(it.groups[1]!!.range.first, true, it.range.last + 1) } +
            ESAC.findAll(text).map { Triple(it.range.first, false, it.range.last + 1) }
        val bodies = ArrayList<Pair<Int, String>>()
        val stack = ArrayList<Pair<Int, Boolean>>()
        for ((offset, isCase, headEnd) in marks.sortedBy { it.first }) {
            if (isCase) {
                if (stack.isNotEmpty()) stack[stack.size - 1] = stack.last().first to true
                stack += headEnd to false
            } else if (stack.isNotEmpty()) {
                val (bodyStart, nested) = stack.removeAt(stack.size - 1)
                if (!nested && bodyStart <= offset) bodies += bodyStart to text.substring(bodyStart, offset)
            }
        }
        return bodies.sortedBy { it.first }.map { it.second }
    }

    /** The arms of a case body: their patterns (quotes and a leading `(` removed) and their command text. */
    private fun arms(body: String): List<Pair<List<String>, String>> {
        val arms = ArrayList<Pair<List<String>, String>>()
        for (segment in body.split(Regex(""";;&?|;&"""))) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val close = patternEnd(trimmed) ?: continue
            val patterns = trimmed.substring(0, close).trim().removePrefix("(").split('|')
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() }
            if (patterns.isEmpty()) continue
            arms += patterns to trimmed.substring(close + 1)
        }
        return arms
    }

    /** The index of the `)` that ends an arm's pattern list (outside quotes), or null. */
    private fun patternEnd(segment: String): Int? {
        var quote: Char? = null
        for ((i, ch) in segment.withIndex()) {
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == ')' -> return i
                ch == '\n' -> return null
            }
        }
        return null
    }

    /** [script] without comments: `#` at the start of a word, outside quotes, to the end of its line. */
    private fun stripComments(script: String): String = buildString(script.length) {
        var quote: Char? = null
        var comment = false
        for ((i, ch) in script.withIndex()) {
            if (comment) {
                if (ch == '\n') {
                    comment = false
                    append(ch)
                }
                continue
            }
            when {
                quote != null -> if (ch == quote) quote = null
                ch == '"' || ch == '\'' -> quote = ch
                ch == '#' && (i == 0 || script[i - 1].isWhitespace()) -> {
                    comment = true
                    continue
                }
                ch == '\n' -> quote = null
            }
            append(ch)
        }
    }
}
