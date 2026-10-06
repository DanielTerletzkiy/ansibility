package de.terletzkiy.ansibility.semantics.layout

import de.terletzkiy.ansibility.semantics.CoreVersion

/**
 * `INVENTORY_IGNORE_EXTS`: the file-name endings a directory inventory source skips (plan amendment R10, D62;
 * research `flat-layouts.md` §4.1). Only directory walks apply them; a file named as a source is always read.
 *
 * The two defaults were measured with `ansible-config dump`; ansible-core 2.18.8 skips `.ini` files inside inventory
 * directories, 2.21.4 reads them. The version in between that changed it is not bisected yet (spike S-L1), so the
 * cut-over [INI_READ_SINCE] is provisional: targets below 2.19 skip `.ini`.
 */
object IgnoreExtensions {
    /** The default of ansible-core 2.21.4 (`REJECT_EXTS + ['.orig', '.cfg', '.retry']`). */
    val READS_INI: List<String> =
        listOf(".pyc", ".pyo", ".swp", ".bak", "~", ".rpm", ".md", ".txt", ".rst", ".orig", ".cfg", ".retry")

    /** The default of ansible-core 2.18.8 (`REJECT_EXTS + ('.orig', '.ini', '.cfg', '.retry')`). */
    val SKIPS_INI: List<String> =
        listOf(".pyc", ".pyo", ".swp", ".bak", "~", ".rpm", ".md", ".txt", ".rst", ".orig", ".ini", ".cfg", ".retry")

    /** Provisional (D62, spike S-L1): the first target whose default is [READS_INI]. */
    val INI_READ_SINCE: CoreVersion = CoreVersion(2, 19)

    const val DEFAULTS_KEY: String = "inventory_ignore_extensions"
    const val INVENTORY_SECTION: String = "inventory"
    const val INVENTORY_KEY: String = "ignore_extensions"

    /** The default list of target [core]. */
    fun defaultFor(core: CoreVersion): List<String> = if (core < INI_READ_SINCE) SKIPS_INI else READS_INI

    /**
     * The list in effect for target [core], given the raw values of `[defaults] inventory_ignore_extensions`
     * ([defaultsValue]) and `[inventory] ignore_extensions` ([inventoryValue]): the `[inventory]` key wins, and an
     * explicit list replaces the default on every version ([CfgValues.list]; an empty value is `[""]`, which every
     * name ends with, so the walk skips every file).
     */
    fun resolve(defaultsValue: String?, inventoryValue: String?, core: CoreVersion): List<String> =
        (inventoryValue ?: defaultsValue)?.let(CfgValues::list) ?: defaultFor(core)

    /** ansible-core's extension test on one directory entry [name] (a regex `<escaped extension>$` per entry). */
    fun isIgnored(name: String, extensions: List<String>): Boolean = extensions.any { name.endsWith(it) }
}
