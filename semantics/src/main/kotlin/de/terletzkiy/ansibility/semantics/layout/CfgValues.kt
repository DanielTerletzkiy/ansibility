package de.terletzkiy.ansibility.semantics.layout

/** ansible-core's configuration value types (`config/manager.py` `ensure_type`) for ini values, beyond the path types. */
object CfgValues {
    /**
     * Type `list` from an ini value (`enable_plugins`, `vars_plugins_enabled`, `inventory_ignore_extensions`, …):
     * split at `,`, each entry stripped and then unquoted. An empty value is the one-entry list `[""]`, which is how
     * `vars_plugins_enabled =` turns every vars plugin off and `inventory_ignore_extensions =` skips every file.
     */
    fun list(value: String): List<String> = value.split(',').map { PyStrings.unquote(PyStrings.strip(it)) }
}
