package de.terletzkiy.ansibility.context

/**
 * A parsed `ansible.cfg`, read the way ansible-core reads it: Python `ConfigParser` with `#`/`;` comment lines,
 * inline `;` comments, `=` or `:` separators, lower-cased keys and indented continuation lines.
 *
 * Only the INI structure is modelled here; typed accessors exist for the keys the context layer needs.
 */
class AnsibleCfg(val sections: Map<String, Map<String, String>>) {

    /** The raw value of [key] in [section], or null when unset. */
    fun value(section: String, key: String): String? = sections[section]?.get(key.lowercase())

    /**
     * `[defaults] roles_path` entries in order, as written (relative entries are relative to the cfg dir).
     * ansible-core splits this pathspec on `os.pathsep`, which is `:` on the platforms the repo runs on.
     */
    val rolesPath: List<String>
        get() = value("defaults", "roles_path").orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        private val SECTION = Regex("""^\[([^]]+)]\s*$""")

        fun parse(text: CharSequence): AnsibleCfg {
            val sections = LinkedHashMap<String, LinkedHashMap<String, String>>()
            var current: LinkedHashMap<String, String>? = null
            var lastKey: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                val trimmed = line.trimStart()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith(';')) continue
                val section = current
                val previousKey = lastKey
                if (line.first().isWhitespace() && section != null && previousKey != null) {
                    val joined = section[previousKey].orEmpty() + "\n" + stripInlineComment(trimmed)
                    section[previousKey] = joined.trim()
                    continue
                }
                val header = SECTION.matchEntire(trimmed)
                if (header != null) {
                    current = sections.getOrPut(header.groupValues[1].trim()) { LinkedHashMap() }
                    lastKey = null
                    continue
                }
                if (section == null) continue
                val separator = trimmed.indexOfFirst { it == '=' || it == ':' }
                val key = (if (separator < 0) trimmed else trimmed.substring(0, separator)).trim().lowercase()
                if (key.isEmpty()) continue
                section[key] = if (separator < 0) "" else stripInlineComment(trimmed.substring(separator + 1)).trim()
                lastKey = key
            }
            return AnsibleCfg(sections)
        }

        /** ansible-core configures `inline_comment_prefixes=(';',)`: a `;` after whitespace starts a comment. */
        private fun stripInlineComment(value: String): String {
            var i = value.indexOf(';')
            while (i >= 0) {
                if (i == 0 || value[i - 1].isWhitespace()) return value.substring(0, i)
                i = value.indexOf(';', i + 1)
            }
            return value
        }
    }
}
