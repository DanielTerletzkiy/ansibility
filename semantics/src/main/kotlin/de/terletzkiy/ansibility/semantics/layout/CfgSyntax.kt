package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.inventory.Py

/**
 * Why ansible-core refuses an `ansible.cfg`. Every kind aborts **every** ansible command with rc 5 ("Error reading
 * config file …" or "Unsupported configuration file …"); measured on 2.18.8 and 2.21.4 (test resources
 * `layout-rules/`).
 */
enum class CfgErrorKind {
    /** `DuplicateOptionError`: a key twice in one section. Keys are case-insensitive, so `inventory` and `INVENTORY` collide. */
    DUPLICATE_OPTION,

    /** `DuplicateSectionError`: a section header twice. `[DEFAULT]` may repeat. */
    DUPLICATE_SECTION,

    /**
     * `MissingSectionHeaderError`: a line that is not blank, a comment or a header before the first header, indented
     * or not. A UTF-8 byte order mark before the first header counts, because ansible-core decodes without removing it.
     */
    MISSING_SECTION_HEADER,

    /**
     * `ParsingError`: a line inside a section that is neither a header nor `key = value` / `key: value`
     * (`inventory` alone, `= x`, `[]`).
     */
    PARSING_ERROR,

    /** The file name has an extension other than `.cfg`, `.ini`, `.yml` or `.yaml` (case-sensitive; none at all included). */
    UNSUPPORTED_EXTENSION,

    /** A `.yml` or `.yaml` file: "Unsupported configuration file type: yaml". */
    UNSUPPORTED_TYPE,
}

/**
 * One thing ansible-core refuses (plan amendment R10: ANS-L004). [line] is 1-based and null for the file-name kinds;
 * [previousLine] is the earlier occurrence of a duplicate key or section.
 */
data class CfgSyntaxError(
    val kind: CfgErrorKind,
    val line: Int?,
    val section: String? = null,
    val key: String? = null,
    val previousLine: Int? = null,
)

/**
 * One `key = value` as Python's `configparser` holds it after reading: [key] lower-cased, [value] with an inline `;`
 * comment removed and continuation lines joined with `\n` (then right-stripped). [line] is the key's line and
 * [lastLine] the last continuation line that added text.
 */
data class CfgEntry(val section: String, val key: String, val value: String, val line: Int, val lastLine: Int)

/**
 * The sections of an `ansible.cfg` as ansible-core reads them. Section names are case-sensitive (`[DEFAULTS]` is not
 * `[defaults]`); [CfgSyntax.DEFAULT_SECTION] holds keys that every existing section inherits.
 */
class CfgDocument internal constructor(
    private val sections: Map<String, Map<String, CfgEntry>>,
    private val defaults: Map<String, CfgEntry>,
) {
    /** The section names in file order, without [CfgSyntax.DEFAULT_SECTION]. */
    val sectionNames: List<String> get() = sections.keys.toList()

    /** Whether the case-sensitive [section] was declared in the file. */
    fun hasSection(section: String): Boolean = section in sections

    /**
     * `ConfigParser.get(section, key, raw=True)` as ansible-core calls it: the section's own key, else the
     * `[DEFAULT]` one; null when [section] does not exist (ansible-core then reads nothing, not even `[DEFAULT]`).
     * No `%(name)s` interpolation happens.
     */
    fun entry(section: String, key: String): CfgEntry? {
        val own = sections[section] ?: return null
        val name = key.lowercase()
        return own[name] ?: defaults[name]
    }

    /** The value of [entry], or null. */
    fun value(section: String, key: String): String? = entry(section, key)?.value

    /** The section's own entries in file order (inherited `[DEFAULT]` keys not included). */
    fun entries(section: String): List<CfgEntry> =
        (if (section == CfgSyntax.DEFAULT_SECTION) defaults else sections[section])?.values?.toList().orEmpty()
}

/** The result of [CfgSyntax.read]: a lenient [document] that keeps working, and every error in line order. */
class CfgRead internal constructor(val document: CfgDocument, val errors: List<CfgSyntaxError>) {
    /** True when ansible-core reads the text (the file name is checked separately, [CfgSyntax.checkFileName]). */
    val isValid: Boolean get() = errors.isEmpty()

    /**
     * The error ansible-core reports: duplicates and a missing header raise at their line, so the first of those;
     * otherwise the first [CfgErrorKind.PARSING_ERROR] (configparser raises one `ParsingError` listing every bad line).
     */
    val abortsWith: CfgSyntaxError?
        get() = errors.firstOrNull { it.kind != CfgErrorKind.PARSING_ERROR } ?: errors.firstOrNull()
}

/**
 * Port of how ansible-core's `ConfigManager` reads an `ansible.cfg`: Python 3.13/3.14 `configparser.ConfigParser`
 * with `inline_comment_prefixes=(';',)`, fed by `read_string` after a strict UTF-8 decode, queried with
 * `get(…, raw=True)`.
 *
 * - lines split at `\n` only; a full-line comment starts with `#` or `;` after any indentation; an inline comment is
 *   a `;` at the start or after whitespace, so `hosts.ini # local` keeps its `#` and `hosts.ini;x` its `;`;
 * - a header is `[` up to the last `]` of the line, with at least one character between them (`[defaults] x` is
 *   `[defaults]`, `[ defaults ]` is a section named ` defaults `, `[]` is a parse error);
 * - a key line splits at its first `=` or `:`; keys are right-stripped and lower-cased, values stripped;
 * - a line indented deeper than the current key line continues its value; blank lines inside a value become empty
 *   lines, comment lines are skipped, and the joined value is right-stripped;
 * - strict mode: a duplicate key (per section) and a duplicate section (except `[DEFAULT]`) are errors.
 *
 * ansible-core aborts at the first error. [read] reports all of them and still builds a document: it keeps the later
 * value of a duplicate key, merges a repeated section and drops the lines it cannot place, so the IDE keeps working
 * on a broken file while ANS-L004 says that every ansible command fails.
 */
object CfgSyntax {
    /** configparser's default section: its keys apply to every other section. */
    const val DEFAULT_SECTION: String = "DEFAULT"

    /** `get_config_type`: the extensions ansible-core reads as INI, and the ones it names as unsupported YAML. */
    private val INI_EXTENSIONS = setOf(".cfg", ".ini")
    private val YAML_EXTENSIONS = setOf(".yml", ".yaml")

    /**
     * The rc-5 error for a configuration file named [fileName] (a cfg chosen in settings may have any name), or null
     * for `.cfg` and `.ini`. The extension is compared as written, so `ansible.CFG` is refused.
     */
    fun checkFileName(fileName: String): CfgSyntaxError? = when (Py.extension(fileName.substringAfterLast('/'))) {
        in INI_EXTENSIONS -> null
        in YAML_EXTENSIONS -> CfgSyntaxError(CfgErrorKind.UNSUPPORTED_TYPE, null)
        else -> CfgSyntaxError(CfgErrorKind.UNSUPPORTED_EXTENSION, null)
    }

    /**
     * Reads [text], the decoded file content. Keep a leading U+FEFF when the file starts with a byte order mark:
     * ansible-core keeps it too, and then finds no section header.
     */
    fun read(text: CharSequence): CfgRead {
        val reader = Reader()
        val lines = text.toString().split('\n')
        // Iterating a StringIO yields no line after a final newline.
        val count = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size
        for (i in 0 until count) reader.line(i + 1, lines[i])
        return reader.result()
    }

    /** One option while its value is collected: configparser keeps a list of lines per option. */
    private class Pending(val section: String, val key: String, val line: Int, first: String) {
        val lines = arrayListOf(first)
        var lastLine = line

        fun entry() = CfgEntry(section, key, PyStrings.rstrip(lines.joinToString("\n")), line, lastLine)
    }

    /** configparser's `_ReadState`, plus what the lenient document needs. */
    private class Reader {
        private val sections = LinkedHashMap<String, LinkedHashMap<String, Pending>>()
        private val defaults = LinkedHashMap<String, Pending>()
        private val sectionLines = HashMap<String, Int>()
        private val errors = ArrayList<CfgSyntaxError>()
        private var current: LinkedHashMap<String, Pending>? = null
        private var sectionName: String? = null
        private var option: Pending? = null
        private var indentLevel = 0
        private var missingHeaderReported = false

        fun line(lineNo: Int, raw: String) {
            val trimmed = PyStrings.strip(raw)
            val clean = clean(trimmed)
            val open = option?.takeIf { current != null && it.key.isNotEmpty() }
            if (clean.isEmpty()) {
                // empty_lines_in_values: a blank line (not a comment line) inside a value adds an empty value line.
                if (trimmed.isEmpty() && open != null) open.lines += ""
                return
            }
            val indent = raw.indexOfFirst { !PyStrings.isSpace(it) }
            if (open != null && indent > indentLevel) {
                open.lines += clean
                open.lastLine = lineNo
                return
            }
            indentLevel = indent
            val header = headerName(clean)
            when {
                header != null -> header(lineNo, header)
                current == null -> {
                    // configparser raises at the first such line; the lenient reader drops it and the ones that follow.
                    if (!missingHeaderReported) errors += CfgSyntaxError(CfgErrorKind.MISSING_SECTION_HEADER, lineNo)
                    missingHeaderReported = true
                }
                else -> option(lineNo, clean)
            }
        }

        private fun header(lineNo: Int, name: String) {
            sectionName = name
            option = null
            if (name == DEFAULT_SECTION) {
                current = defaults
                return
            }
            val existing = sections[name]
            if (existing != null) {
                errors += CfgSyntaxError(CfgErrorKind.DUPLICATE_SECTION, lineNo, section = name, previousLine = sectionLines[name])
                current = existing
            } else {
                current = LinkedHashMap<String, Pending>().also { sections[name] = it }
                sectionLines[name] = lineNo
            }
        }

        private fun option(lineNo: Int, clean: String) {
            val section = checkNotNull(sectionName)
            val target = checkNotNull(current)
            val delimiter = clean.indexOfFirst { it == '=' || it == ':' }
            if (delimiter < 0) {
                // The open option stays open: a deeper-indented line after this one still continues it.
                errors += CfgSyntaxError(CfgErrorKind.PARSING_ERROR, lineNo, section = section)
                return
            }
            val key = PyStrings.rstrip(clean.substring(0, delimiter)).lowercase()
            val value = PyStrings.strip(clean.substring(delimiter + 1))
            if (key.isEmpty()) errors += CfgSyntaxError(CfgErrorKind.PARSING_ERROR, lineNo, section = section)
            val previous = target.remove(key)
            if (previous != null) {
                // Without strict mode configparser keeps the later value, and so does the lenient document.
                errors += CfgSyntaxError(CfgErrorKind.DUPLICATE_OPTION, lineNo, section, key, previous.line)
            }
            option = Pending(section, key, lineNo, value).also { target[key] = it }
        }

        fun result(): CfgRead = CfgRead(
            CfgDocument(
                sections.mapValues { (_, options) -> options.mapValues { it.value.entry() } },
                defaults.mapValues { it.value.entry() },
            ),
            errors.toList(),
        )

        /** `_Line.clean`: empty for a full-line comment, else the text before an inline `;` comment, stripped. */
        private fun clean(trimmed: String): String {
            if (trimmed.startsWith('#') || trimmed.startsWith(';')) return ""
            var i = trimmed.indexOf(';')
            while (i >= 0) {
                if (i == 0 || PyStrings.isSpace(trimmed[i - 1])) return PyStrings.strip(trimmed.substring(0, i))
                i = trimmed.indexOf(';', i + 1)
            }
            return trimmed
        }

        /** `SECTCRE.match`: `\[(?P<header>.+)\]` from the start; `.+` is greedy, so the name ends at the last `]`. */
        private fun headerName(clean: String): String? {
            if (!clean.startsWith('[')) return null
            val close = clean.lastIndexOf(']')
            return if (close >= 2) clean.substring(1, close) else null
        }
    }
}
