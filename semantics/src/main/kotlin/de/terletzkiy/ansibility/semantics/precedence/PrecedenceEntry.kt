package de.terletzkiy.ansibility.semantics.precedence

/**
 * The entries of ansible-core's `VARIABLE_PRECEDENCE` (`precedence` in `ansible.cfg`, `ANSIBLE_PRECEDENCE`),
 * which order the group-level inventory variables (levels 3–7).
 */
enum class PrecedenceEntry(val configName: String) {
    /** Inline `vars` of the `all` group in the inventory source. */
    ALL_INVENTORY("all_inventory"),

    /** Inline `vars` of the host's other groups, in `sort_groups` order. */
    GROUPS_INVENTORY("groups_inventory"),

    /** `group_vars/all` next to the inventory source. */
    ALL_PLUGINS_INVENTORY("all_plugins_inventory"),

    /** `group_vars/all` next to the playbook. */
    ALL_PLUGINS_PLAY("all_plugins_play"),

    /** `group_vars/<group>` next to the inventory source, groups in `sort_groups` order. */
    GROUPS_PLUGINS_INVENTORY("groups_plugins_inventory"),

    /** `group_vars/<group>` next to the playbook, groups in `sort_groups` order. */
    GROUPS_PLUGINS_PLAY("groups_plugins_play"),
    ;

    /** A parsed precedence list and the entries ansible-core would ignore ("Ignoring unknown variable precedence entry"). */
    data class Parsed(val entries: List<PrecedenceEntry>, val ignored: List<String>)

    companion object {
        /** ansible-core's default order. */
        val DEFAULT: List<PrecedenceEntry> = listOf(
            ALL_INVENTORY, GROUPS_INVENTORY, ALL_PLUGINS_INVENTORY, ALL_PLUGINS_PLAY, GROUPS_PLUGINS_INVENTORY, GROUPS_PLUGINS_PLAY,
        )

        fun byConfigName(name: String): PrecedenceEntry? = entries.firstOrNull { it.configName == name }

        /**
         * Parses a config list value (comma separated, as ansible-core's `list` type). Unknown names, and
         * `plugins_by_group` (accepted by the allow-list but crashing in every release), are reported as ignored.
         * Duplicates are kept: ansible-core applies such an entry twice.
         */
        fun parse(text: String): Parsed {
            val names = text.split(',').map { it.trim().removeSurrounding("\"").removeSurrounding("'") }.filter { it.isNotEmpty() }
            return parse(names)
        }

        fun parse(names: List<String>): Parsed {
            val entries = ArrayList<PrecedenceEntry>()
            val ignored = ArrayList<String>()
            for (name in names) byConfigName(name)?.let { entries += it } ?: run { ignored += name }
            return Parsed(entries, ignored)
        }
    }
}
